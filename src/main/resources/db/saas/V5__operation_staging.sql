-- Dépôt temporaire chiffré (dumps restore, archives, valeurs de secrets one-shot).
-- Consommé à l'exécution d'une opération ; jamais renvoyé en clair par l'API.

CREATE TABLE operation_staging (
    id                uuid PRIMARY KEY,
    organization_id   uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    server_id         uuid        NOT NULL REFERENCES server (id) ON DELETE CASCADE,
    kind              text        NOT NULL CHECK (kind IN ('SQL_DUMP', 'UPLOADS_ARCHIVE', 'PLATFORM_SECRET')),
    original_name     text,
    content_sha256    text        NOT NULL,
    content_enc       bytea       NOT NULL,
    byte_size         int         NOT NULL,
    meta              jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_by        uuid,
    created_at        timestamptz NOT NULL DEFAULT now(),
    expires_at        timestamptz NOT NULL,
    consumed_at       timestamptz
);
CREATE INDEX ix_operation_staging_org_server ON operation_staging (organization_id, server_id);
CREATE INDEX ix_operation_staging_expires ON operation_staging (expires_at);

ALTER TABLE operation_staging ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON operation_staging
    USING (app_bypass_rls() OR organization_id = app_current_org())
    WITH CHECK (app_bypass_rls() OR organization_id = app_current_org());

GRANT SELECT, INSERT, UPDATE, DELETE ON operation_staging TO ${app_role};
REVOKE TRUNCATE ON operation_staging FROM ${app_role};
