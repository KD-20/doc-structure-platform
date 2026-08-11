# DocStructure

A multi-tenant platform that turns unstructured/semi-structured documents (PDF, Word, images,
HTML, plain text) into clean, structured, searchable data — via a **config-driven rule engine**
(no code changes to add a new document type), with per-tenant RBAC, revocable guest share
links, full-text + structured search, and a complete audit trail.

See [`docs/DECISIONS.md`](docs/DECISIONS.md) for the full architecture decision log — what was
chosen, what was considered instead, and why, including a few real bugs found (and fixed)
along the way that are worth reading if you're extending this.

## Prerequisites

- Docker + Docker Compose. That's it for the quickstart below.
- For local (non-Docker) backend development: Java 21, Maven, Node 20+, and Tesseract OCR
  (`brew install tesseract` on macOS) if you want OCR on scanned/image documents to actually
  produce text — without it, Tika degrades gracefully rather than failing (see
  [`docs/DECISIONS.md`](docs/DECISIONS.md)).

## Quickstart

```
docker compose up --build
```

That's the entire deployment procedure. This one command builds the React frontend, embeds it
into the Spring Boot jar as static resources, builds the backend, and starts both the app and a
Postgres (with the pgvector extension) container. On first boot, the app automatically:

- Runs all database migrations (schema, row-level security policies, the append-only audit
  trigger).
- Seeds one demo tenant and an admin user (see credentials below) — skipped automatically on
  every subsequent start once any user exists.

Once healthy, open **http://localhost:8081** — the backend serves the UI directly (no separate
frontend server/port). Port 8081 (not 8080) and Postgres on 5433 (not 5432) are the compose
defaults specifically so this doesn't collide with anything else you might already have running
locally; override with `HOST_APP_PORT`/`HOST_DB_PORT` if 8081/5433 are also taken.

```
curl http://localhost:8081/actuator/health
```

### Default credentials (dev only)

| | |
|---|---|
| Email | `admin@example.com` |
| Password | `ChangeMe123!` |
| Tenant | "Demo Tenant" |

**These are insecure by design and only meant for local evaluation.** Override them via
`BOOTSTRAP_ADMIN_EMAIL`/`BOOTSTRAP_ADMIN_PASSWORD` env vars, or just change the password after
first login in any real deployment. There is no password-reset flow in v1 — see
[`docs/DECISIONS.md §15`](docs/DECISIONS.md).

## First-steps walkthrough

1. **Log in** at http://localhost:8081 with the credentials above (or register your own account
   and create a tenant — the "Create a new tenant" form on the tenant-picker page).
2. **Define a rule set** (Rule Sets → New rule set) for a document type, e.g. `invoice`. A
   minimal example:
   ```json
   {
     "docType": "invoice",
     "fields": [
       {
         "name": "invoice_number",
         "type": "string",
         "required": true,
         "strategy": "ANCHOR_REGEX",
         "params": { "anchorText": "Invoice Number:", "searchWindowChars": 50, "pattern": "([A-Z]{2,4}-\\d{4}-\\d{4,8})" },
         "normalizer": null
       }
     ]
   }
   ```
   Use "Test against sample text" to dry-run the rules against pasted text before saving —
   nothing is persisted until you click "Save as new version." A ready-to-use example matching
   the sample document below is at
   [`docs/samples/invoice-rule-set-request.json`](docs/samples/invoice-rule-set-request.json)
   (its `definition` value is exactly what goes in the editor's JSON textarea), or apply it
   directly: `curl -X PUT localhost:8081/api/tenants/<id>/rule-sets/invoice -H "Authorization:
   Bearer <token>" -H "Content-Type: application/json" -d
   @docs/samples/invoice-rule-set-request.json`.
3. **Upload a document** (Documents → Upload) —
   [`docs/samples/sample-invoice.txt`](docs/samples/sample-invoice.txt) is a ready-to-use
   example. You don't pick a document type: text is extracted immediately (Tika), then
   `DocTypeClassifier` scores it against every active rule set and assigns the best match
   automatically. If it finds a confident one, structured extraction runs immediately too — no
   extra click. A document that doesn't match anything well is tagged `unclassified` and just
   sits at "text extracted" until a matching rule set exists (or you trigger extraction
   manually later, once one does).
4. Open the document to see its structured fields (already populated if it auto-matched) or
   click **"Run structured extraction"** to try again — e.g. after adding a rule set that now
   covers it.
5. **Search** — full text (`Search` page, plain query box) and structured field filters (field +
   operator + value rows) both work against everything you've uploaded and extracted.
6. **Audit Log** (admin only) shows every upload, extraction outcome, rule-set change, and
   membership change, each attributed to the user who did it.
7. **Guest Links** (admin only) — pick one or more documents, set an expiry, and create a link.
   The raw token is shown exactly once; share the resulting `/guest/<token>/documents/<id>` URL
   with anyone — no account needed, and access is limited to exactly the documents you selected.

## Anonymous trial mode ("try it before you subscribe")

Separate from Guest Links (which share *specific* documents an admin already uploaded): anyone
can go to **`/try`** (linked from the login page) and upload their own documents with no
account at all. Uploads land in one shared "Public Demo" tenant seeded automatically on first
boot (`PublicDemoInitializer`), scoped per-browser by a random id the frontend generates into
`localStorage` and sends as `X-Device-Id` — no cookies, no login. Deliberately limited:

- **5 uploads per device** by default (`PLATFORM_PUBLIC_DEMO_MAX_UPLOADS`) — the 6th attempt is
  rejected with a message pointing at registering instead.
- Only upload, status, and structured-field viewing are exposed — no rule-set editing, search,
  sharing, or doc-type override. That gap from the real app is the point: it's a taste, not the
  product.
- One device can never see another device's trial uploads (enforced server-side, not just
  hidden in the UI — a lookup for someone else's document 404s the same as a nonexistent one).

Turn it off entirely with `PLATFORM_PUBLIC_DEMO_ENABLED=false` if you don't want an
unauthenticated upload endpoint at all — worth doing before any real public deployment, since
this endpoint has no rate-limiting/captcha of its own beyond the per-device cap (fine for local
use; a genuinely public instance would want more).

## Environment variables

All have working defaults for local/demo use baked into `docker-compose.yml`; override any of
them via a `.env` file next to `docker-compose.yml` or your shell environment.

| Variable | Purpose | Default |
|---|---|---|
| `HOST_APP_PORT` | Host port for the app | `8081` |
| `HOST_DB_PORT` | Host port for Postgres | `5433` |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | Postgres superuser (migrations only — see `docs/DECISIONS.md §2a`) | `docstructure` / `docstructure` / `docstructure` |
| `APP_DB_PASSWORD` | Password for the non-superuser `app_user` role the app actually connects as | `app_user_change_me` |
| `JWT_SECRET` | Signs access tokens — **override for any non-local use** | insecure dev default |
| `BOOTSTRAP_ENABLED` | Seed the demo tenant/admin on first boot | `true` |
| `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` | Seeded admin credentials | `admin@example.com` / `ChangeMe123!` |
| `MAIL_ENABLED` | Turn on emailing guest links (see below) | `false` |
| `MAIL_FROM` | "From" address on those emails | `no-reply@example.com` |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USERNAME` / `SMTP_PASSWORD` | SMTP relay credentials — required if `MAIL_ENABLED=true` | unset |
| `LLM_ENABLED` | Register the LLM extraction strategy bean (see "Free LLM extraction" below) | `false` |
| `GROQ_API_KEY` | Groq API key — required if `LLM_ENABLED=true` | unset |
| `GROQ_MODEL` / `GROQ_BASE_URL` | Override the model or point at an OpenAI-compatible endpoint other than Groq | `llama-3.3-70b-versatile` / Groq's API |

Backend-only variables (set directly, not typically needed for the Docker path since the
Dockerfile/compose file already wire them): `PLATFORM_EXTRACTION_DEFAULT_STRATEGY` (`RULE_BASED`),
`PLATFORM_EXTRACTION_LLM_ENABLED` (`false`), `PLATFORM_EMBEDDINGS_ENABLED` (`false`),
`PLATFORM_STORAGE_BASE_PATH` (`/data/documents` in Docker), `PLATFORM_PUBLIC_DEMO_ENABLED`
(`true` — see "Anonymous trial mode" below), `PLATFORM_PUBLIC_DEMO_MAX_UPLOADS` (`5`).

### Emailing guest links

Guest links (see the walkthrough above) always work without this — the raw link is shown once
and can be copied/shared manually regardless. Setting `MAIL_ENABLED=true` plus real `SMTP_*`
credentials in a `.env` file next to `docker-compose.yml` adds an optional "email this link"
step to guest-link creation, so the platform sends it directly instead. Any standard SMTP relay
works (a Gmail account with an [app password](https://myaccount.google.com/apppasswords),
SendGrid/SES/Mailgun's SMTP relay, etc.):

```
MAIL_ENABLED=true
MAIL_FROM=you@example.com
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=you@example.com
SMTP_PASSWORD=your-app-password
```

Then `docker compose up -d --build`. Without these set, the email option in the UI still
appears but reports "email sending isn't configured" and falls back to the link — creation
never fails just because the email couldn't be sent.

## Adding a new document type

No code changes — just a new rule set. Go to Rule Sets → New rule set, set `docType` to
whatever you want (e.g. `receipt`, `contract`), define its `fields` (see the walkthrough above
and [the rule DSL reference in `docs/DECISIONS.md §4`](docs/DECISIONS.md)), save, then upload
matching documents — nothing to configure on the upload side, `DocTypeClassifier` picks it up
automatically (see [`docs/DECISIONS.md §3a`](docs/DECISIONS.md)).

Available field strategies: `ANCHOR_REGEX` (find anchor text, regex-match a window after it),
`REGEX_GLOBAL` (regex the whole document, no anchor), `TABLE_UNDER_HEADING` (parse rows under a
heading, splitting on 2+ whitespace runs). Available normalizers: `DATE`, `CURRENCY`, or omit
for no normalization.

## Free LLM extraction (zero-config field detection)

For document types with no rule set at all: `LlmExtractionStrategy` sends the document's raw
text to an LLM and lets it decide for itself which fields matter and what their values are —
no one has to define field names, anchor text, or patterns first. It calls
[Groq](https://console.groq.com/keys) (free tier, OpenAI-compatible API) directly over HTTP, no
SDK dependency.

**Off by default everywhere**, and enabling it doesn't change any existing tenant's behavior —
the platform-wide default extraction strategy stays `RULE_BASED` regardless:

1. Get a free API key at [console.groq.com/keys](https://console.groq.com/keys).
2. In a `.env` file next to `docker-compose.yml`:
   ```
   LLM_ENABLED=true
   GROQ_API_KEY=gsk_...your key...
   ```
   Then `docker compose up -d --build`.
3. **Per tenant**, opt in: `PATCH /api/tenants/{id}/settings` with body
   `{"settings": {"extractionStrategy": "LLM"}}`. Only that tenant's documents use it; every
   other tenant keeps using its own rule sets exactly as before. Uploads auto-trigger
   extraction immediately, same as a confident rule-based match does.

To go back to rule sets for a tenant: PATCH its settings back to `{}` (or omit
`extractionStrategy`). See `com.docstructure.platform.extraction.LlmExtractionStrategy` and
[`docs/DECISIONS.md §3`](docs/DECISIONS.md) for how the seam is structured if you want to swap
in a different provider (OpenAI, Anthropic, Gemini, or a local Ollama model all work the same
way — it's a plain HTTP call, not tied to Groq specifically).

## Semantic search: today vs. future

The `Search` page has a semantic-query field today — it's a real, wired code path, not fake UI.
Right now it always returns `semanticSearchAvailable: false` and falls back to full-text/
structured results, because nothing generates embeddings yet (`NoOpEmbeddingProvider`). The
`extracted_data.embedding` column and its `pgvector` index already exist. Enabling real semantic
search later is one new `EmbeddingProvider` bean gated by `platform.embeddings.enabled=true` —
zero API changes. See [`docs/DECISIONS.md §5a`](docs/DECISIONS.md).

## Local development without Docker

Backend:
```
docker compose up -d db          # just Postgres, still via Docker
cd backend
SERVER_PORT=8081 mvn spring-boot:run
```

Frontend (separate terminal, proxies `/api` to `localhost:8081` — see `frontend/vite.config.ts`):
```
cd frontend
npm install
npm run dev
```
Open http://localhost:5173.

Run backend tests: `cd backend && mvn test` (requires `docker compose up -d db` running first —
see the note on Testcontainers in `docs/DECISIONS.md §15`).

## Troubleshooting

- **Port already in use**: something else is already on 8081 or 5433 — set `HOST_APP_PORT`/
  `HOST_DB_PORT` to something free, e.g. `HOST_APP_PORT=8090 docker compose up --build`.
- **`app` container unhealthy / won't start**: check `docker compose logs app`. The most common
  cause locally is a stale Postgres volume from a previous schema version — `docker compose down
  -v` (⚠️ deletes all data) and start fresh.
- **pgvector extension errors**: make sure the `db` service is genuinely using the
  `pgvector/pgvector:pg16` image (check `docker compose config`) — a plain `postgres` image
  doesn't have the extension available at all.
- **OCR produces no text for scanned PDFs/images**: confirm Tesseract is present — it's baked
  into the Docker image already; for local non-Docker dev, `brew install tesseract` (macOS) or
  your platform's equivalent.
- **Rows look "missing" when inspecting Postgres directly** (psql/pgAdmin/DBeaver/TablePlus)
  **even though the app shows the data fine**: you're almost certainly connected as `app_user`
  (the credentials in the env var table above) with no tenant context set. Every tenant-scoped
  table has row-level security keyed off the Postgres session variable
  `app.current_tenant_id` — the app sets this per-request automatically
  (`TenantContextAspect`), but a raw DB client has no reason to, so `app_user` sees zero rows
  everywhere by design (deny-by-default — see `docs/DECISIONS.md §2a`). Two ways to actually see
  your data:
  - Connect as the Postgres **superuser** instead (`docker exec -it <db-container>
    psql -U docstructure -d docstructure` — superusers bypass RLS entirely), or
  - Stay as `app_user` but set the tenant first in the same session/transaction:
    ```sql
    BEGIN;
    SELECT set_config('app.current_tenant_id', '<your-tenant-uuid>', true);
    SELECT * FROM documents;
    COMMIT;
    ```
    (Find your tenant's UUID via `SELECT id, name FROM tenants;` as the superuser — `tenants`
    itself has no RLS.)
- **`extracted_data` is empty after uploading a document**: upload auto-classifies the doc type
  and auto-runs extraction when it finds a confident match (see "Adding a new document type"
  above) — so this means either no rule set matched well enough (check the document's `docType`
  in the UI; `unclassified` means exactly this) or a matching rule set didn't exist *yet* at
  upload time. A rule set created after the fact doesn't retroactively reclassify already
  uploaded documents — re-upload, or trigger extraction manually once the document's `docType`
  happens to match an active rule set. Check `extraction_runs.error_message` (or the document's
  extraction-runs list in the UI) for exactly why a run failed, e.g. `No active rule set for doc
  type 'X'`.
