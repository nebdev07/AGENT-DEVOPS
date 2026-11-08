-- Spécifications de déploiement (apps 1:1 et plateformes N services) par organisation / serveur.
-- Remplace progressivement platforms-intent.json / apps-intent.json pour le profil saas.

CREATE TABLE deployment_spec (
    id                uuid PRIMARY KEY,
    organization_id   uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    server_id         uuid        NOT NULL REFERENCES server (id) ON DELETE CASCADE,
    kind              text        NOT NULL CHECK (kind IN ('APP', 'PLATFORM')),
    slug              text        NOT NULL,
    spec              jsonb       NOT NULL,
    spec_version      int         NOT NULL DEFAULT 1,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    version           bigint      NOT NULL DEFAULT 0,
    CONSTRAINT ux_deployment_spec_slug UNIQUE (organization_id, server_id, kind, slug)
);
CREATE INDEX ix_deployment_spec_server ON deployment_spec (organization_id, server_id);

ALTER TABLE deployment_spec ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON deployment_spec
    USING (app_bypass_rls() OR organization_id = app_current_org())
    WITH CHECK (app_bypass_rls() OR organization_id = app_current_org());

GRANT SELECT, INSERT, UPDATE, DELETE ON deployment_spec TO ${app_role};
REVOKE TRUNCATE ON deployment_spec FROM ${app_role};
