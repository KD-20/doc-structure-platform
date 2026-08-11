-- Backs the anonymous "try before you subscribe" upload flow (see PublicDemoService):
-- documents uploaded through /api/public/documents live in the shared "Public Demo" tenant
-- (seeded by PublicDemoInitializer), tagged with the uploader's client-generated device id
-- (sent as the X-Device-Id header, no login/cookie involved) so each device only ever sees
-- its own trial uploads and is capped at a small number of them. NULL for every row created
-- through the normal authenticated upload path.
ALTER TABLE documents ADD COLUMN device_id TEXT;

CREATE INDEX ix_documents_device_id ON documents(tenant_id, device_id) WHERE device_id IS NOT NULL;
