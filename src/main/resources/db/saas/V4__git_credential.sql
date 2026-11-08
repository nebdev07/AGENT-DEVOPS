-- Clés Deploy Git (lecture seule) par organisation — distinctes des clés SSH des serveurs.

CREATE TABLE git_credential (
    id                uuid PRIMARY KEY,
    organization_id   uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    key_id            text        NOT NULL,
    git_url           text        NOT NULL,
    public_key        text        NOT NULL,
    private_key_enc   bytea       NOT NULL,
    fingerprint       text        NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_git_credential_key UNIQUE (organization_id, key_id)
);
CREATE INDEX ix_git_credential_org ON git_credential (organization_id);

ALTER TABLE git_credential ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON git_credential
    USING (app_bypass_rls() OR organization_id = app_current_org())
    WITH CHECK (app_bypass_rls() OR organization_id = app_current_org());

GRANT SELECT, INSERT, UPDATE, DELETE ON git_credential TO ${app_role};
REVOKE TRUNCATE ON git_credential FROM ${app_role};
