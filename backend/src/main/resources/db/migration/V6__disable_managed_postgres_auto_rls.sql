-- Some managed Postgres hosts (Supabase, notably) automatically enable Row Level Security on
-- every new table created in the public schema, regardless of what this migration does, since
-- their platform exposes public-schema tables via an auto-generated API by default and treats
-- "no RLS" as a footgun to guard against. That overrides this project's intentional design for
-- the three tables below, which were never meant to be RLS-protected in the first place:
-- users/tenants (login and tenant lookup must work before any tenant context exists — RLS on
-- these would break login itself), and default_rule_sets (shared, tenant-independent reference
-- data every tenant reads the same rows from — see its own comment in V2).
--
-- Safe to run against Postgres hosts that never auto-enabled RLS on these tables in the first
-- place (local Docker, plain postgres/pgvector images): disabling RLS on a table where it was
-- never enabled is a harmless no-op.
ALTER TABLE users DISABLE ROW LEVEL SECURITY;
ALTER TABLE tenants DISABLE ROW LEVEL SECURITY;
ALTER TABLE default_rule_sets DISABLE ROW LEVEL SECURITY;
