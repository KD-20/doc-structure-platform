# Architecture Decisions

What was chosen, what was considered instead, and why — for every significant decision made building this platform. Format: **Decision → Alternatives considered → Why chosen → Why declined → Revisit trigger.**

---

## 1. Backend: Java + Spring Boot 3, not Python + FastAPI

**Alternatives:** Python/FastAPI (richer document-parsing ecosystem: `unstructured`, `pypdf`, native OCR bindings); Node/TypeScript (single language across stack).

**Why chosen:** Spring Security + Spring Data JPA give mature, well-trodden primitives for exactly the hard parts here — multi-tenant RBAC, method-level authorization, JPA entity mapping onto a Postgres schema with row-level security. Apache Tika covers document parsing (PDF/DOCX/HTML/images+OCR) uniformly, closing most of the gap with Python's ecosystem.

**Why declined:** Python's document-parsing libraries are somewhat richer, but not enough to outweigh Spring Security's maturity for the RBAC/multi-tenancy core of this system.

**Revisit trigger:** If the extraction pipeline needs a Python-only library (e.g. a specific ML model) with no JVM equivalent.

---

## 2. Multi-tenancy: shared schema + `tenant_id` + Postgres Row-Level Security

**Alternatives:** Schema-per-tenant; database-per-tenant; app-level filtering only (no RLS).

**Why chosen:** RLS is defense-in-depth on top of app-level `tenant_id` filtering — a service method that forgets a `WHERE tenant_id = ?` predicate is still blocked at the database. Shared schema avoids migration fan-out and connection-pool complexity that schema/DB-per-tenant would introduce at this scale.

**Why declined:** Schema/DB-per-tenant give stronger physical isolation but cost real operational complexity (N migrations to run, N connection pools or dynamic routing) that isn't justified for v1.

**Revisit trigger:** A compliance requirement mandating physical data isolation per tenant.

### 2a. A dedicated non-superuser `app_user` role is required — this was not optional

**What happened:** During implementation, RLS policies were verified against a live Postgres and found to be completely ineffective — every tenant's rows were visible regardless of `SET app.current_tenant_id`. Root cause: the Docker Postgres image's `POSTGRES_USER` is a **superuser**, and Postgres superusers unconditionally bypass row-level security, even with `FORCE ROW LEVEL SECURITY` on the table. This is documented Postgres behavior, not a bug, but it's easy to build a system that "passes" manual testing anyway if that testing is also done as the superuser (which is exactly what happened here first).

**Fix:** `V1__init_schema.sql` creates a separate `app_user` role (`NOSUPERUSER NOBYPASSRLS`) with grants on every table. Flyway migrations still run as the superuser (`spring.flyway.*` credentials — needed for `CREATE EXTENSION`/`CREATE ROLE`), but Hibernate/JPA's runtime connection (`spring.datasource.*`) uses `app_user`. This split is the actual mechanism that makes every RLS policy in this codebase real rather than decorative.

**Revisit trigger:** Never — this is load-bearing. If someone "simplifies" the datasource config back to a single superuser role, RLS silently stops working with no error.

### 2b. Two Postgres functions deliberately bypass RLS (`SECURITY DEFINER`)

Login needs to list which tenants a user belongs to *before* any tenant is selected (a JWT is single-tenant-scoped — see §4), and guest-link authentication needs to find a link by an opaque token *before* its tenant is known. Both are legitimately cross-tenant lookups that RLS's per-tenant policy would otherwise block entirely (0 rows, always).

`list_tenant_memberships_for_user(uuid)` and `find_guest_share_link_by_token_hash(text)` are `SECURITY DEFINER` functions (owned by the migration superuser, so they run with RLS bypassed) that are deliberately hard-scoped — the first to a single caller-supplied `user_id`, the second to a single unique unguessable token hash — so neither can be used to enumerate another tenant's data. `GRANT EXECUTE` is given to `app_user` explicitly, nothing else.

**Revisit trigger:** Any new "must look up X before the tenant is known" requirement should extend this pattern, not add a general RLS bypass.

### 2c. Empty-string GUC edge case

A second real bug, also only caught by writing an actual integration test: Postgres custom GUCs (like `app.current_tenant_id`) return `NULL` from `current_setting(name, true)` only the *first* time a session touches that name. Once `SET LOCAL` has run once on a pooled connection — and HikariCP *will* reuse connections across different tenants' requests — the value reverts to `''` (empty string) rather than `NULL` after each transaction, which crashes a raw `::uuid` cast instead of cleanly denying access. Fixed with a `current_tenant_id()` SQL helper function using `NULLIF(..., '')` before the cast, used by every RLS policy instead of the raw expression.

---

## 3. Extraction: rules-first for v1, LLM as a wired-but-inert seam

**Alternatives:** LLM-first extraction (Claude API) for every document; a hybrid that tries rules then falls back to an LLM.

**Why chosen:** The user explicitly asked for rules-first with a real, working seam to add an LLM strategy later purely via configuration — deterministic, zero external dependency/cost, fully auditable field-by-field output for v1, with the extension point built so a future LLM strategy requires zero changes to `ExtractionService`, `ExtractionStrategyFactory`, or the API.

**Mechanism:** `ExtractionStrategy` interface; `RuleBasedExtractionStrategy` is the only bean registered by default. `LlmExtractionStrategy` implements the same interface, is annotated `@ConditionalOnProperty(platform.extraction.llm.enabled)`, and is genuinely not registered as a Spring bean until that flag flips — this isn't a stub that's silently selected, it's absent from the `Map<String, ExtractionStrategy>` `ExtractionStrategyFactory` resolves against. A tenant configured for `LLM` while the bean is absent gets a loud `ExtractionStrategyUnavailableException` (503), never a silent fallback to rules.

**Why declined (hybrid):** Adds meaningful complexity (when does a rule "fail" enough to justify an LLM call? per-field or per-document?) that isn't needed until there's a real LLM implementation to hybridize with.

**Revisit trigger:** Implementing `LlmExtractionStrategy` for real.

### 3a. Doc type is auto-classified, not typed by the uploader

**What changed:** v1 originally required the uploader to type a free-text `docType` string that had to exactly match an existing rule set's `docType` — a silent trap for anyone who didn't already know the rule-set vocabulary (typo, wrong case, or a type nobody had created a rule set for yet all failed identically, and only at extraction time). Reworked so `docType` is optional on upload; when omitted, `DocTypeClassifier` (`extraction` package) scores the document's extracted text against every one of the tenant's *active* rule sets by literally running `RuleInterpreter` against each and counting how many fields matched (with a bonus for satisfying every required field, so a rule set that fully matches beats one that partially over-matches on raw count). The best-scoring doc type wins if it clears a minimum threshold (`MIN_SCORE = 0.34`); otherwise the document is tagged `unclassified` rather than guessing. If classification succeeds, `DocumentService.upload` immediately also calls `ExtractionService.triggerExtraction` in the same request — upload, classify, and structure in one step, zero extra clicks.

**Why chosen:** The whole point of a config-driven rule engine is that non-technical users shouldn't need to understand its internals. A classifier reusing the *exact same* `RuleInterpreter` that performs real extraction (no separate model, no new dependency) is a natural fit for a rules-first system — it improves in lockstep with extraction accuracy, for free, as rule sets get better anchor text.

**Why declined (ML/LLM-based classification):** Would need training data or an LLM call for a decision this system can already make deterministically from what it already has (the rule sets themselves are the taxonomy). Also would have been inconsistent with §3's rules-first stance for the exact same reason extraction itself is rules-first.

**Known limitation:** Classification runs once, at upload time, against whatever rule sets are active *then*. A rule set created afterward doesn't retroactively reclassify existing `unclassified` documents — they'd need re-upload or a manual extraction trigger once their content happens to match. An explicitly-provided `docType` (still accepted by the API) always overrides classification.

**Revisit trigger:** If false classifications become common enough to need a confidence display/override step in the UI before extraction runs automatically, rather than trusting the auto-run.

---

## 4. Custom JSON rule DSL, not Drools

**Alternatives:** Drools (mature Java rule engine); a full "if/then" expression language.

**Why chosen:** The extraction rules needed here are narrow and declarative — "find field X near anchor text Y using regex Z, then normalize" — not general inference chains. A ~30-line JSON schema (`RuleSetDefinition` → `FieldRule[]`, each with a `strategy` + `params` map) is directly authorable/editable from a UI textarea or form builder, versioned as plain rows in Postgres, and dispatched to `FieldExtractor` beans via Spring's `Map<String, T>`-of-beans-by-name autowiring (bean name = strategy string, e.g. `"ANCHOR_REGEX"`). Adding a new extraction strategy is a new `@Component("NAME")` class, zero changes elsewhere.

**Why declined:** Drools' inference-engine semantics (working memory, rule salience, forward chaining) solve a different problem than "extract these five fields from this text" and would add a real learning-curve/operational cost for no corresponding benefit here.

**Revisit trigger:** A genuine need for rules that reference/depend on each other's outputs (not just independent per-field extraction).

---

## 5. Postgres full-text + pgvector, not a dedicated vector database

**Alternatives:** Pinecone/Weaviate/Milvus/Elasticsearch alongside Postgres.

**Why chosen:** Avoids a second stateful service to deploy, back up, and keep in sync with the source of truth. `tsvector`/`ts_rank` cover full-text search; the `extracted_data.embedding VECTOR(1536)` column + `ivfflat` index give semantic search a real destination once it's needed, all in the one database this platform already runs.

**Why declined:** A dedicated vector DB has better ANN performance at large scale, which this platform doesn't need yet.

**Revisit trigger:** Query-per-second or corpus-size thresholds where `ivfflat` recall/latency becomes the bottleneck.

### 5a. Semantic search is a real, wired, currently-inert seam — not a TODO

`EmbeddingProvider` interface ships with `NoOpEmbeddingProvider` (`@Primary`, `isEnabled() = false`). The `embedding` column, its index, and `SearchService`'s query path all exist and are exercised by the same code that will run once a real provider is added — but nothing in v1 ever calls an embeddings API, so `extracted_data.embedding` stays `NULL` for every row. A `semanticQuery` search param returns `semanticSearchAvailable: false` and silently falls back to full-text/structured results rather than erroring. Enabling it later is one new `EmbeddingProvider` bean gated by `platform.embeddings.enabled=true` — zero changes to `SearchService` or the API contract. This is stated explicitly in the README so it doesn't read as a broken feature.

### 5a-i. Every document with usable text gets embedded, not just ones matching a rule set

Originally, `extracted_data` (and therefore `embedding`) only existed for a document once *structured* extraction succeeded — a document with no matching rule set (custom or platform default) just never got a row at all, and was invisible to semantic search regardless of whether embeddings were enabled. `RuleBasedExtractionStrategy` now returns an `UNSTRUCTURED` result (empty fields, `ExtractedDataStatus.UNSTRUCTURED`) instead of failing when no rule set applies — the run still succeeds, `ExtractionService#writeEmbedding` still runs unconditionally on the result either way, and the document stays findable via semantic/fuzzy search even with zero structured fields. The document itself stays at `TEXT_EXTRACTED`, not `STRUCTURED` — that status specifically means fields were extracted, which didn't happen here. `DocumentService#upload` auto-triggers extraction whenever docType was left for classification to decide (confident rule match or not), so the common "no doc type, no rule set" case is embedded automatically; an explicitly-typed upload with no matching rule set does not auto-trigger (manual retrigger, or a doc-type change, still work) — see its own javadoc for the full reasoning.

Semantic search itself changed from "rank everything by cosine distance" to a genuine similarity floor: `SearchQueryBuilder.SEMANTIC_SIMILARITY_THRESHOLD` (0.70) is a hard `WHERE` filter whenever a real query embedding is used, not just a sort key — a semantic query with nothing sufficiently similar now returns an honestly empty result instead of the closest-available-but-unrelated documents. Not currently exposed as a request parameter; one fixed threshold for every semantic query.

**Revisit trigger:** If 0.70 proves too strict/loose in practice, or callers want to tune it per-query — expose it as a `minSimilarity` search param instead of a constant.

### 5b. Native SQL query results are fetched as typed entities, not raw `Object[]` columns

`SearchService` runs a native query that returns only `(document_id, rank)` — safe, unambiguous JDBC types — then fetches `Document`/`ExtractedData` as proper JPA entities rather than trying to map `jsonb`/`timestamptz` columns out of a raw multi-column native query result. This trades a couple of extra queries (irrelevant at v1 scale) for not having to guess/verify Hibernate's native-query type mapping for those column types.

---

## 6. Document storage: local disk volume + Postgres metadata, not `bytea` in Postgres

**Alternatives:** Store raw file bytes as a `bytea` column in `documents`.

**Why chosen:** Explicitly discussed with the user. Keeps the database lean and backup/restore fast; `documents.storage_path` is the only pointer, behind a `StorageService` interface with `LocalDiskStorageService` as the only v1 implementation — an `S3StorageService` can be added later with zero callers changing. In Docker, the storage path is a named volume (`documents_storage`), independent of the Postgres volume.

**Why declined:** Storing bytes in Postgres means one thing to back up, but bloats the database and routes all file I/O through the DB connection pool.

**Revisit trigger:** Needing multi-instance horizontal scaling of the `app` container (local disk doesn't share across replicas — that's when `S3StorageService` gets built).

---

## 7. Auth: JWT, single-tenant-scoped per token, no refresh-token rotation in v1

**Alternatives:** Server-side sessions; a JWT carrying all of a user's tenant memberships; full refresh-token rotation with revocation.

**Why chosen:** Stateless JWT fits a horizontally-scalable container without a shared session store. A token is scoped to **at most one** tenant (`tenantId`/`role` claims, both absent on the identity-only token minted at login) — every downstream `@PreAuthorize` check is then a simple `hasRole('X')` plus `@tenantAccess.isCurrentTenant(#tenantId)` (a bean checking the JWT's tenant against the URL's `{tenantId}` path variable), rather than parsing a multi-tenant membership list per request. Switching tenants calls `POST /api/auth/select-tenant/{id}`, which re-verifies membership (via RLS, with `TenantContext` set to the *target* tenant first) and issues a fresh token.

**Why declined (refresh rotation):** A `refresh_tokens` table with rotation/revocation is real additional scope; given the size of the rest of this build, v1 ships a single access token with a moderate TTL (`platform.jwt.access-token-minutes`, default 60) and no server-side revocation — logging out is client-side token discard only. This is a genuine v1 gap, not a security design position; call it out explicitly before any non-local deployment.

**Revisit trigger:** Before any production/non-local deployment.

### 7a-storage. Token persisted to `sessionStorage`, not held purely in memory

v1 originally kept the access token in a JS variable only, on the reasoning that this limits what an XSS payload can exfiltrate. In practice that meant **every page refresh silently logged the user out** — there was no refresh-token flow (see above) to silently re-authenticate, so the only path back in was the login form. That's a worse day-to-day tradeoff than the risk it was avoiding, especially since the token already has no server-side revocation regardless of where it's stored.

`setAuthToken`/`getAuthToken` (`frontend/src/api/client.ts`) now persist the token to `sessionStorage`. `AuthContext` rehydrates identity/tenant state from it on app load (decoding the JWT's `sub`/`email`/`tenantId`/`role` claims, then calling `GET /api/tenants` both to refill the tenant-switcher list and as a liveness check — an expired/rejected token is discarded there rather than surfacing as a raw error). `RequireAuth` waits on `initializing` instead of redirecting to `/login` before that check runs.

**Why `sessionStorage` and not `localStorage`:** still vulnerable to XSS exfiltration like `localStorage` (this isn't a security improvement over the original design, it's an explicit tradeoff), but it clears when the tab/browser closes rather than persisting indefinitely — a page refresh survives, walking away from an open browser for a week doesn't leave a live credential sitting there.

**Revisit trigger:** Same as above — real refresh-token rotation with httpOnly cookies removes the need to hold the access token in browser-accessible storage at all.

### 7a. `@tenantAccess.isCurrentTenant(#tenantId)` is required on every tenant-scoped endpoint, not just `hasRole(...)`

`hasRole('ADMIN')` alone only proves the caller holds that role for *whatever tenant their token is scoped to* — it says nothing about whether that matches the `{tenantId}` in the URL. Every controller method touching a `{tenantId}` path variable combines both checks; a token scoped to tenant A cannot be replayed against tenant B's URLs.

### 7b. Spring AOP self-invocation would silently skip `@TenantScoped`/`@Transactional` — this shaped the whole service layer

Spring's proxy-based AOP only intercepts calls that go through the bean proxy; a method calling another method on `this` bypasses the proxy entirely, silently skipping any `@Transactional`/`@TenantScoped` annotations on the callee. Caught in practice in `AuthService.selectTenant`, `TenantService`'s tenant-creation flow, and — the most consequential instance — `BulkReextractionDispatcher` (§10a): the first version of its `@Async` entry point called `reextractByDocType(...)` on its *own* bean, which silently skipped `@TenantScoped`'s `set_config` call. There was no error; the RLS-filtered query underneath just legitimately returned zero rows, so the bug read as "re-extraction quietly does nothing" rather than a crash — the quietest, hardest-to-notice failure mode this gotcha can produce. The fix, applied consistently: annotations always sit on the externally-invoked entry-point method itself; a method needing to call another transactional operation calls a **different bean** (or has the controller make two separate calls), never `this.otherAnnotatedMethod()`.

### 7c. A nested `@Transactional` method throwing marks the whole transaction rollback-only — even if the caller catches it

Also caught in practice: `ExtractionService.triggerExtraction` calling `RuleSetService.getActive()` (which throws when no active rule set exists) inside a `try/catch` still failed with `UnexpectedRollbackException`, because Spring's transaction interceptor for the *inner* proxied call marks the transaction rollback-only the moment the exception crosses that call's own boundary — regardless of what the caller does with it afterward. Fixed by adding non-throwing `Optional`-returning counterparts (`RuleSetService.findActive`) for every case where a caller needs to handle "not found" as one normal outcome among several, reserving the throwing variant for genuine request-level 404s.

---

## 8. Guest access: token-based share links, not guest user accounts

**Alternatives:** A lightweight "guest" user account per share; session-based temporary access.

**Why chosen:** Keeps the RBAC model (`tenant_memberships` = real accounts with roles) uncontaminated by ephemeral access. A `guest_share_links` row is a bearer-style opaque token (only its SHA-256 hash is ever stored), scoped to a specific `documentIds` list, with `expiresAt`/`maxUses`/`revokedAt` — independently revocable and auditable without touching the user/membership tables at all. `GuestAuthFilter` authenticates a request from the token alone (via the `SECURITY DEFINER` lookup in §2b) and resolves to a `GuestPrincipal` with `ROLE_GUEST`, checked against the link's scope via `@guestAccessEvaluator.canAccess(#documentId)` on top of the role check — same two-layer pattern as §7a.

**Why declined:** Guest user accounts would need password/no-password handling, membership rows with a synthetic role, and cleanup logic — more moving parts for weaker isolation than a purpose-built token table.

### 8a. Guest link *usage* is not audited per-access — by design, not omission

Every access with a valid guest token increments `guest_share_links.use_count`; it does **not** write an `audit_log` row. One row per guest page-view would flood the log for a feature whose whole point is repeated lightweight access; `use_count` is the intended usage signal. Link *creation* and *revocation* are audited (`GUEST_LINK_CREATED`/`GUEST_LINK_REVOKED`) since those are meaningful, low-frequency admin actions.

---

## 9. Audit trail: Spring AOP by default, explicit `AuditService.record(...)` as the documented escape hatch

**Alternatives:** Explicit `auditService.record(...)` calls scattered through every service method; a fully declarative approach with no escape hatch.

**Why chosen:** `@Audited(action, entityType)` + `AuditAspect` (`@AfterReturning`) covers the common case — success implies "record this" — consistently and reviewably (the annotation sits next to the method signature). But a method needing to audit *either* of two different outcomes with different action names (an extraction run that can `SUCCEED` or `FAIL`, both legitimate, both worth recording under different actions) doesn't fit a single static annotation; `ExtractionService.triggerExtraction` calls `AuditService.record(...)` directly in each branch instead. Both paths funnel through the same `AuditService`, so entity-id resolution, actor resolution, and the append-only guarantee are identical either way.

**Mechanism note:** `AuditAspect` resolves the audited entity's id via reflection on the method's return value, trying `getId()` (JPA entities) then `id()` (Java records) — and calls `setAccessible(true)` before invoking either, since most response DTOs in this codebase are deliberately package-private ("only this package's controller needs it"), and plain reflection across packages throws `IllegalAccessException` rather than finding the method (caught in practice: `DOCUMENT_UPLOADED` audit rows had `entityId: null` until this was added).

### 9a. Not audited: extraction-run `STARTED`

`STARTED` carries no decision-relevant information beyond what `COMPLETED`/`FAILED` already record and would double the row volume for no benefit.

**Reversed for search queries:** v1 originally excluded search from the audit log as the highest-volume, lowest-compliance-value action in the system. Explicitly requested afterward — every Search-page query (`SearchService.search`, not the guest-scoped `searchWithinDocuments`, which stays unaudited) is now recorded as `SEARCH_PERFORMED` with `q`/`docType`/`semanticQuery`/`filters`/`page`/`size`/`resultCount` in metadata and no `entityId` (a search isn't "about" one row). The volume tradeoff this section originally warned about is real and now accepted rather than avoided — revisit if `audit_log` growth becomes a problem.

### 9b. Append-only enforcement is a trigger, not `REVOKE`

The original plan assumed a separate limited-privilege app role could have `UPDATE`/`DELETE` revoked on `audit_log`. In practice, `app_user` is the table's owner (it created the table via the same migration), and Postgres table owners bypass `GRANT`/`REVOKE` entirely regardless of what's revoked from them — the same category of surprise as the RLS-superuser issue in §2a. Real enforcement is a `BEFORE UPDATE OR DELETE` trigger (`reject_audit_log_mutation()`) that raises an exception unconditionally, which works regardless of role/ownership. `REVOKE UPDATE, DELETE ... FROM app_user` is still present for defense-in-depth/documentation clarity, it just isn't the actual enforcement mechanism.

---

## 10. Extraction pipeline: async job queue, not synchronous inline

**Alternatives:** Run structured extraction inline within the HTTP request (v1's original design — see below); a full external queue (SQS/RabbitMQ/etc.) instead of an in-process one.

**Why chosen:** Text extraction (Tika) still runs inline at upload time — it's fast and the raw text is needed immediately for the response. Structured extraction does not: `ExtractionService.enqueueExtraction` does only the fast part (validate, resolve strategy, create a `PENDING` run row, publish `ExtractionRequestedEvent`) and returns; `ExtractionWorker`, `@Async("extractionExecutor") @TransactionalEventListener(phase = AFTER_COMMIT)`, picks the event up on a background thread and does the actual work via `ExtractionService.performExtraction`. `AFTER_COMMIT` matters specifically: it guarantees the worker never races the enqueueing transaction's own `PENDING` row INSERT. The `PENDING/RUNNING/SUCCEEDED/FAILED` status model already anticipated this from v1's initial schema design — switching to it needed no API or schema changes, only the request handler's internals changing from "do the work" to "enqueue the work." The frontend surfaces the in-flight state live via SSE (`DocumentEventService`) rather than requiring a manual refresh.

**Why declined (a full external queue):** An in-process `ThreadPoolTaskExecutor` (`AsyncConfig`, bounded: core 2 / max 8 / queue 200, caller-runs on saturation rather than dropping work) is sufficient at single-instance scale and adds no new infrastructure dependency. Genuinely necessary once horizontally scaled — an in-process queue's state (and the events it publishes) doesn't survive a restart or exist on other instances.

**Revisit trigger:** Horizontal scaling (an in-process executor's queue and `DocumentEventService`'s in-memory SSE subscriber list are both single-instance-only), or extraction volume outgrowing what one instance's bounded pool can absorb even with `caller-runs` degradation.

### 10a. Bulk re-extraction dispatch on a rule set change is also async — and batching is a known gap, not yet built

Saving a new rule set version or activating an older one re-syncs every existing document of that doc type (`BulkReextractionService.reextractByDocType` — otherwise a rule set change only affects documents uploaded/extracted afterward, and an existing document silently keeps whatever its last run produced). That re-sync is dispatched via `BulkReextractionDispatcher.reextractByDocTypeAsync` (`@Async`, its own dedicated executor — see below) rather than run inline in the `PUT`/`activate` request, so saving a rule set returns as soon as the version itself is written, not after every matching document has been re-enqueued.

**Own dedicated executor, not `extractionExecutor`:** tried sharing the extraction pipeline's own pool first; under concurrent load a busy extraction queue could delay this "should be near-instant" administrative dispatch well past what an admin saving a rule set would expect (confirmed live as test flakiness before the pools were split). `AsyncConfig.bulkReextractionExecutor` is intentionally small (core 1 / max 4 / queue 50) — this dispatch step is lightweight (find matching documents, enqueue each), not the heavy work itself.

**Known gap — no batching for very large document sets:** `reextractByDocType` loads every matching document for the tenant+docType in one `findByTenantIdAndDocType` query and loops over all of them synchronously within its own transaction before returning. Fine at realistic v1 volumes (each iteration is one small `enqueueExtraction` call); for a tenant with a very large number of documents of one type, this becomes a single long-running transaction holding a growing set of row locks, and a large `List<Document>` held in memory at once. The fix, not yet built: page through matching documents in fixed-size batches (e.g. 200 at a time) across multiple shorter transactions rather than one unbounded one — same shape as `DocumentRepository`'s existing paged list queries, just applied here too.

**Revisit trigger:** A tenant's per-doc-type document count reaches a size where this loop's single transaction becomes a measurable lock-contention or memory concern — batch it before that, not after it's already been observed as a problem.

---

## 11. Deployment: Spring Boot serves the built React SPA, single container — not a separate nginx/static container

**Alternatives:** Separate `nginx`-served frontend container; a CDN/edge-hosted SPA calling a separately-deployed API.

**Why chosen:** Same-origin serving eliminates CORS configuration entirely. Drops the deployment from 3 containers (db + api + web) to 2 (db + app), directly serving the explicit "fewest possible commands" requirement — `docker compose up --build` is the entire deployment procedure. The Dockerfile's first stage builds the React app; the second stage copies its `dist/` into `src/main/resources/static` before `mvn package`, so it ships inside the same jar. `SpaFallbackController` forwards client-side route paths (`/t/**`, `/guest/**`, etc.) to `index.html` so React Router's routes work on direct navigation/refresh, not just client-side `pushState`.

**Why declined:** A dedicated frontend container allows independent scaling/deployment/caching of static assets — genuinely useful at larger scale, not needed for v1's "one command, one deployable" goal. Reversing this is a compose-file-only change later (the frontend build is already a separate Dockerfile stage).

**Revisit trigger:** Needing to scale/deploy the frontend independently of the API.

### 11a. Host ports default away from 8080/5432

The `db` service's host port defaults to `5433` and `app`'s to `8081` (both overridable via `HOST_DB_PORT`/`HOST_APP_PORT`), not the "expected" 5432/8080. This was forced by discovering a native Homebrew Postgres already on 5432 and an unrelated project already on 8080 during development — a useful reminder that "standard" ports are exactly the ones likely to collide on a real dev machine. Container-to-container traffic (`app` → `db`) always uses the internal Docker network (`db:5432`) regardless of the host mapping, so this only affects host-side tooling and browser access.

---

## 12. Migrations: Flyway, not Liquibase

**Why chosen:** Plain versioned SQL is easiest to review line-by-line for a schema this concrete (RLS policies, triggers, and `SECURITY DEFINER` functions read naturally as SQL, awkwardly as an XML/YAML DSL); zero-config Spring Boot auto-run on startup; Postgres is the only target, so Liquibase's cross-database abstraction and rollback DSL buy nothing here.

**Revisit trigger:** Needing to support a second database engine.

---

## 13. Bootstrap data: a `CommandLineRunner`, not a baked SQL seed migration

**Why chosen:** `BootstrapDataInitializer` seeds one demo tenant + admin user on first boot (skipped if any user already exists) using the real `PasswordEncoder` bean at runtime, so the seeded password is hashed the same way any real signup would be — never a precomputed bcrypt hash checked into a SQL file (which would either go stale against encoder parameter changes or require careful hand-verification). Explicitly logged as dev-only credentials to change before any non-local use.

---

## 14. Project structure: single Maven module, package-by-domain

**Alternatives:** Multi-module Maven (separate artifacts per domain).

**Why chosen:** Everything ships as one deployable jar; multi-module adds build/dependency-graph overhead with no v1 benefit. Package-by-domain (`auth`, `tenancy`, `documents`, `rules`, `extraction`, `search`, `audit`, `guestaccess`, `storage`, `common`, `security`, `config`) gives the same logical separation without it. DTOs are deliberately package-private by convention ("only this package's controller needs this response shape") except where a different package genuinely needs the type (e.g. `SearchResponse`, used by both `SearchController` and the cross-package `GuestAccessController`) — those are the explicit exceptions, not the default.

---

## 15. Known v1 gaps (stated plainly, not discovered later)

- **No refresh-token rotation / server-side logout** (§7) — single access token, client-side discard only.
- **Semantic search is wired but inert** (§5a) — `NoOpEmbeddingProvider` until a real provider is added.
- **LLM extraction is wired but inert** (§3) — `LlmExtractionStrategy` throws `UnsupportedOperationException` until implemented.
- **Bulk re-extraction on a rule set change is not batched** (§10a) — one query loads every matching document at once, one transaction enqueues all of them; fine at v1 volumes, a real limit for a tenant with a very large number of documents of one doc type.
- **Table extraction (`TABLE_UNDER_HEADING`) is heuristic** — splits on runs of 2+ whitespace characters; irregular whitespace or wrapped cells will misparse.
- **OCR quality depends on Tesseract being present** — bundled in the Docker image, must be installed separately for non-Docker local dev (see README).
- **This session's automated tests run against `docker compose up -d db` directly, not Testcontainers** — the dev sandbox's Docker Desktop `/info` API wasn't compatible with the Testcontainers 1.20.3 client library (unrelated to this codebase); `TenantContextAspectIT` documents this and the manual alternative in its own javadoc.
