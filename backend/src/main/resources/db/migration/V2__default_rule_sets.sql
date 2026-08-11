-- Global, tenant-independent baseline rule sets for common document types, shipped with the
-- platform so a tenant gets working structured extraction out of the box without defining any
-- rules themselves first. Not RLS-protected: this is shared reference data, not tenant data,
-- so every tenant reads the same rows. A tenant's own active extraction_rule_sets row for a
-- doc type always takes precedence over the matching default when one exists — see
-- RuleSetService#resolveDefinition. Rows are seeded by DefaultRuleSetSeeder on boot, not here,
-- so new built-in templates can be added later without a new migration.
CREATE TABLE default_rule_sets (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_type    TEXT NOT NULL UNIQUE,
    definition  JSONB NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- INSERT/UPDATE (not DELETE) so the boot-time seeder can add/refresh built-in templates;
-- there's no user-facing mutation endpoint for this table.
GRANT SELECT, INSERT, UPDATE ON default_rule_sets TO app_user;
