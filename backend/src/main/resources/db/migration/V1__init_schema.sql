-- Extensions
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================================
-- Dedicated non-superuser application role.
--
-- Postgres superusers ALWAYS bypass row-level security, regardless of
-- FORCE ROW LEVEL SECURITY on the table. The docker-image default
-- POSTGRES_USER is a superuser (needed to create extensions), so the
-- application's runtime JDBC connection must use a *different*,
-- non-superuser role or every tenant-isolation policy in this file is a
-- no-op. Flyway itself still runs as the superuser (spring.flyway.*),
-- since migrations need to create extensions/roles; Hibernate/JPA connects
-- as app_user (spring.datasource.*) instead. See docs/DECISIONS.md.
-- ============================================================
CREATE ROLE app_user LOGIN PASSWORD '${appDbPassword}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

-- Helper used by every RLS policy below. Postgres custom GUCs return NULL from
-- current_setting(name, true) only if the name has never been touched in the current
-- session; once SET LOCAL has run once on a pooled connection (HikariCP reuses
-- connections across requests/tenants), the value reverts to '' rather than NULL after
-- the transaction ends, which would make a raw ::uuid cast throw instead of cleanly
-- deny-by-default. NULLIF converts that '' back to NULL first.
CREATE OR REPLACE FUNCTION current_tenant_id() RETURNS uuid AS $$
    SELECT NULLIF(current_setting('app.current_tenant_id', true), '')::uuid;
$$ LANGUAGE sql STABLE;

-- ============================================================
-- tenants
-- ============================================================
CREATE TABLE tenants (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT NOT NULL,
    slug        TEXT NOT NULL UNIQUE,
    settings    JSONB NOT NULL DEFAULT '{}'::jsonb,
    status      TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================
-- users (global identity; tenant-scoped access via tenant_memberships)
-- ============================================================
CREATE TABLE users (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email          CITEXT NOT NULL UNIQUE,
    password_hash  TEXT NOT NULL,
    full_name      TEXT NOT NULL,
    status         TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================
-- tenant_memberships
-- ============================================================
CREATE TABLE tenant_memberships (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role        TEXT NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'EDITOR', 'VIEWER')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, user_id)
);
CREATE INDEX ix_tenant_memberships_user ON tenant_memberships(user_id);

ALTER TABLE tenant_memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_memberships FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_tenant_memberships ON tenant_memberships
    USING (tenant_id = current_tenant_id());

-- A freshly authenticated user has no tenant selected yet (a JWT is scoped to exactly one
-- tenant, minted only after this call), so login must enumerate which tenants a user
-- belongs to before any tenant_id context exists for RLS to key off. SECURITY DEFINER runs
-- as this function's owner (the migration superuser), which bypasses RLS same as any
-- superuser; safe because the result is hard-scoped to the caller-supplied user_id only.
CREATE OR REPLACE FUNCTION list_tenant_memberships_for_user(p_user_id uuid)
RETURNS TABLE(tenant_id uuid, role text) AS $$
    SELECT tenant_id, role FROM tenant_memberships WHERE user_id = p_user_id;
$$ LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public;
GRANT EXECUTE ON FUNCTION list_tenant_memberships_for_user(uuid) TO app_user;

-- ============================================================
-- guest_share_links
-- ============================================================
CREATE TABLE guest_share_links (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    token_hash          TEXT NOT NULL UNIQUE,
    scope               JSONB NOT NULL,
    created_by_user_id  UUID NOT NULL REFERENCES users(id),
    expires_at          TIMESTAMPTZ NOT NULL,
    max_uses            INT,
    use_count           INT NOT NULL DEFAULT 0,
    revoked_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_guest_share_links_tenant ON guest_share_links(tenant_id);

ALTER TABLE guest_share_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE guest_share_links FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_guest_share_links ON guest_share_links
    USING (tenant_id = current_tenant_id());

-- GuestAuthFilter authenticates a request from an opaque bearer token alone, so it does not
-- know which tenant to set as context until AFTER it finds the matching row. Same bypass
-- pattern and same safety argument as list_tenant_memberships_for_user: hard-scoped to a
-- single unique, unguessable token_hash (equivalent to looking up a row by password hash).
CREATE OR REPLACE FUNCTION find_guest_share_link_by_token_hash(p_token_hash text)
RETURNS SETOF guest_share_links AS $$
    SELECT * FROM guest_share_links WHERE token_hash = p_token_hash;
$$ LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public;
GRANT EXECUTE ON FUNCTION find_guest_share_link_by_token_hash(text) TO app_user;

-- ============================================================
-- documents
-- ============================================================
CREATE TABLE documents (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    filename            TEXT NOT NULL,
    content_type        TEXT,
    doc_type            TEXT NOT NULL,
    storage_path        TEXT NOT NULL,
    file_size_bytes     BIGINT NOT NULL,
    raw_text            TEXT,
    raw_text_tsv        TSVECTOR GENERATED ALWAYS AS (to_tsvector('english', coalesce(raw_text, ''))) STORED,
    status              TEXT NOT NULL DEFAULT 'UPLOADED'
                            CHECK (status IN ('UPLOADED', 'TEXT_EXTRACTED', 'TEXT_EXTRACTION_FAILED', 'STRUCTURED', 'STRUCTURING_FAILED')),
    uploaded_by_user_id UUID NOT NULL REFERENCES users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_documents_tenant_status ON documents(tenant_id, status);
CREATE INDEX ix_documents_tenant_doc_type ON documents(tenant_id, doc_type);
CREATE INDEX ix_documents_raw_text_tsv ON documents USING GIN (raw_text_tsv);

ALTER TABLE documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE documents FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_documents ON documents
    USING (tenant_id = current_tenant_id());

-- ============================================================
-- extraction_rule_sets (versioned, immutable, config-driven rule definitions)
-- ============================================================
CREATE TABLE extraction_rule_sets (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    doc_type            TEXT NOT NULL,
    version             INT NOT NULL,
    definition          JSONB NOT NULL,
    is_active           BOOLEAN NOT NULL DEFAULT false,
    created_by_user_id  UUID NOT NULL REFERENCES users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, doc_type, version)
);
-- exactly one active version per (tenant_id, doc_type)
CREATE UNIQUE INDEX ux_rule_sets_active ON extraction_rule_sets(tenant_id, doc_type) WHERE is_active;

ALTER TABLE extraction_rule_sets ENABLE ROW LEVEL SECURITY;
ALTER TABLE extraction_rule_sets FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_extraction_rule_sets ON extraction_rule_sets
    USING (tenant_id = current_tenant_id());

-- ============================================================
-- extraction_runs
-- ============================================================
CREATE TABLE extraction_runs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    document_id     UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    rule_set_id     UUID REFERENCES extraction_rule_sets(id),
    strategy        TEXT NOT NULL CHECK (strategy IN ('RULE_BASED', 'LLM')),
    status          TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_extraction_runs_document ON extraction_runs(document_id);
CREATE INDEX ix_extraction_runs_tenant ON extraction_runs(tenant_id);

ALTER TABLE extraction_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE extraction_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_extraction_runs ON extraction_runs
    USING (tenant_id = current_tenant_id());

-- ============================================================
-- extracted_data (structured output + semantic search seam)
-- ============================================================
CREATE TABLE extracted_data (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    document_id         UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    extraction_run_id   UUID NOT NULL REFERENCES extraction_runs(id) ON DELETE CASCADE,
    doc_type            TEXT NOT NULL,
    fields              JSONB NOT NULL,
    overall_confidence  NUMERIC(4,3),
    status              TEXT NOT NULL CHECK (status IN ('COMPLETE', 'PARTIAL', 'NEEDS_REVIEW')),
    embedding           VECTOR(1536),
    embedding_model     TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_extracted_data_document ON extracted_data(document_id);
CREATE INDEX ix_extracted_data_fields ON extracted_data USING GIN (fields jsonb_path_ops);
-- Semantic search seam: index exists now, stays empty until an EmbeddingProvider populates embeddings.
CREATE INDEX ix_extracted_data_embedding ON extracted_data USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

ALTER TABLE extracted_data ENABLE ROW LEVEL SECURITY;
ALTER TABLE extracted_data FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_extracted_data ON extracted_data
    USING (tenant_id = current_tenant_id());

-- ============================================================
-- audit_log (append-only)
-- ============================================================
CREATE TABLE audit_log (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID REFERENCES tenants(id) ON DELETE CASCADE,
    actor_type              TEXT NOT NULL CHECK (actor_type IN ('USER', 'GUEST', 'SYSTEM')),
    actor_user_id           UUID REFERENCES users(id),
    actor_guest_link_id     UUID REFERENCES guest_share_links(id),
    action                  TEXT NOT NULL,
    entity_type             TEXT NOT NULL,
    entity_id               UUID,
    metadata                JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_log_tenant_created ON audit_log(tenant_id, created_at DESC);
CREATE INDEX ix_audit_log_entity ON audit_log(entity_type, entity_id);

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log FORCE ROW LEVEL SECURITY;
-- tenant_id IS NULL represents platform-level/SYSTEM events not tied to a single tenant
-- (e.g. bootstrap) and is always visible/insertable; tenant-scoped rows stay isolated.
-- Without the IS NULL branch, `tenant_id = current_setting(...)` is NULL (never true) for
-- these rows since SQL NULL never equals NULL, silently blocking every SYSTEM event.
CREATE POLICY tenant_isolation_audit_log ON audit_log
    USING (tenant_id = current_tenant_id() OR tenant_id IS NULL);

-- Append-only enforcement: a single DB role owns these tables in this deployment (the
-- Flyway/app role are the same for a minimal single-container setup), so REVOKE against
-- that role would have no effect (table owners bypass GRANT/REVOKE). A trigger enforces
-- immutability regardless of role, including the owner.
CREATE OR REPLACE FUNCTION reject_audit_log_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only: % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_no_update
    BEFORE UPDATE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_audit_log_mutation();

CREATE TRIGGER trg_audit_log_no_delete
    BEFORE DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_audit_log_mutation();

-- ============================================================
-- Grants for app_user (the runtime role RLS actually applies to).
-- Any future migration that adds a table must add its own grants here.
-- ============================================================
GRANT USAGE ON SCHEMA public TO app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON
    tenants, users, tenant_memberships, guest_share_links, documents,
    extraction_rule_sets, extraction_runs, extracted_data, audit_log
    TO app_user;
-- Defense-in-depth on top of the append-only trigger: app_user can't even attempt
-- UPDATE/DELETE on audit_log at the privilege level.
REVOKE UPDATE, DELETE ON audit_log FROM app_user;
