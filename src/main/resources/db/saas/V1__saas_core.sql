-- VPS Autopilot SaaS — schéma initial (ADR-048, ADR-049, ADR-050, ADR-051, ADR-052, ADR-053).
-- Exécuté par Flyway avec le rôle propriétaire. L'application se connecte avec ${app_role}
-- (NOSUPERUSER, NOBYPASSRLS, non propriétaire) : la sécurité au niveau des lignes s'applique à elle.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${app_role}') THEN
        CREATE ROLE ${app_role} LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
    END IF;
END
$$;

-- Contexte de requête posé par l'application à chaque transaction (SET LOCAL via set_config(..., true)).
CREATE OR REPLACE FUNCTION app_current_org() RETURNS uuid
    LANGUAGE sql STABLE AS
$$ SELECT nullif(current_setting('app.org_id', true), '')::uuid $$;

CREATE OR REPLACE FUNCTION app_bypass_rls() RETURNS boolean
    LANGUAGE sql STABLE AS
$$ SELECT coalesce(current_setting('app.bypass_rls', true), '') = 'on' $$;

-- ───────────────────────────── Identité (global, sans RLS) ─────────────────────────────

CREATE TABLE app_user (
    id                uuid PRIMARY KEY,
    email             text        NOT NULL,
    password_hash     text        NOT NULL,
    display_name      text        NOT NULL DEFAULT '',
    locale            text        NOT NULL DEFAULT 'en',
    time_zone         text        NOT NULL DEFAULT 'UTC',
    status            text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    email_verified_at timestamptz,
    mfa_secret_enc    bytea,
    mfa_enabled       boolean     NOT NULL DEFAULT false,
    failed_logins     int         NOT NULL DEFAULT 0,
    locked_until      timestamptz,
    last_login_at     timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    version           bigint      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_app_user_email ON app_user (lower(email));

-- Personnel INOVNE : distinct des membres des organisations clientes (ADR-052).
CREATE TABLE platform_staff (
    user_id    uuid PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    role       text        NOT NULL CHECK (role IN ('PLATFORM_ADMIN', 'PLATFORM_OPERATOR')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE auth_session (
    id           uuid PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash   text        NOT NULL UNIQUE,
    created_at   timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    last_seen_at timestamptz,
    ip           text,
    user_agent   text,
    revoked_at   timestamptz
);
CREATE INDEX ix_auth_session_user ON auth_session (user_id);

CREATE TABLE one_time_token (
    id         uuid PRIMARY KEY,
    purpose    text        NOT NULL CHECK (purpose IN ('PASSWORD_RESET', 'EMAIL_VERIFY', 'INVITATION')),
    user_id    uuid REFERENCES app_user (id) ON DELETE CASCADE,
    email      text,
    organization_id uuid,
    role_code  text,
    token_hash text        NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE login_attempt (
    id         bigserial PRIMARY KEY,
    bucket     text        NOT NULL,
    at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_login_attempt_bucket ON login_attempt (bucket, at);

-- ───────────────────────────── Organisations ─────────────────────────────

CREATE TABLE organization (
    id                uuid PRIMARY KEY,
    name              text        NOT NULL,
    slug              text        NOT NULL UNIQUE,
    customer_type     text        NOT NULL DEFAULT 'SAAS' CHECK (customer_type IN ('SAAS', 'SAAS_ASSISTED', 'MANAGED')),
    default_locale    text        NOT NULL DEFAULT 'en',
    time_zone         text        NOT NULL DEFAULT 'UTC',
    billing_currency  text        NOT NULL DEFAULT 'USD',
    require_four_eyes boolean     NOT NULL DEFAULT false,
    status            text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    version           bigint      NOT NULL DEFAULT 0
);

-- Rôles : système (organization_id NULL) ou propres à une organisation. Permissions = codes stables.
CREATE TABLE org_role (
    id              uuid PRIMARY KEY,
    organization_id uuid REFERENCES organization (id) ON DELETE CASCADE,
    code            text    NOT NULL,
    label           text    NOT NULL,
    permissions     text[]  NOT NULL DEFAULT '{}',
    system          boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX ux_org_role_code ON org_role (coalesce(organization_id, '00000000-0000-0000-0000-000000000000'::uuid), code);

CREATE TABLE membership (
    id              uuid PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    user_id         uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role_code       text        NOT NULL,
    status          text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, user_id)
);
CREATE INDEX ix_membership_user ON membership (user_id);

CREATE TABLE api_token (
    id              uuid PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    name            text        NOT NULL,
    token_prefix    text        NOT NULL,
    token_hash      text        NOT NULL UNIQUE,
    scopes          text[]      NOT NULL DEFAULT '{}',
    created_by      uuid        NOT NULL REFERENCES app_user (id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    revoked_at      timestamptz,
    last_used_at    timestamptz
);
CREATE INDEX ix_api_token_org ON api_token (organization_id);

-- ───────────────────────────── Serveurs et identifiants (RLS) ─────────────────────────────

CREATE TABLE org_data_key (
    organization_id uuid PRIMARY KEY REFERENCES organization (id) ON DELETE CASCADE,
    kek_id          text        NOT NULL,
    wrapped_dek     bytea       NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE server (
    id                uuid PRIMARY KEY,
    organization_id   uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    name              text        NOT NULL,
    host              text        NOT NULL,
    ssh_port          int         NOT NULL DEFAULT 22 CHECK (ssh_port BETWEEN 1 AND 65535),
    ssh_user          text        NOT NULL,
    os_family         text,
    os_version        text,
    connection_state  text        NOT NULL DEFAULT 'UNKNOWN' CHECK (connection_state IN ('UNKNOWN', 'REACHABLE', 'UNREACHABLE', 'AUTH_FAILED')),
    last_checked_at   timestamptz,
    engine_version    text,
    capabilities      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    maintenance_state text        NOT NULL DEFAULT 'NONE' CHECK (maintenance_state IN ('NONE', 'SCHEDULED', 'IN_PROGRESS')),
    protection        text        NOT NULL DEFAULT 'NONE' CHECK (protection IN ('NONE', 'READ_ONLY', 'BLOCKED')),
    host_fingerprint  text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    version           bigint      NOT NULL DEFAULT 0,
    UNIQUE (organization_id, host, ssh_port, ssh_user)
);

CREATE TABLE server_credential (
    id              uuid PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    server_id       uuid        NOT NULL REFERENCES server (id) ON DELETE CASCADE,
    kind            text        NOT NULL DEFAULT 'SSH_KEY' CHECK (kind IN ('SSH_KEY')),
    public_key      text        NOT NULL,
    private_key_enc bytea       NOT NULL,
    fingerprint     text        NOT NULL,
    status          text        NOT NULL CHECK (status IN ('ACTIVE', 'PENDING', 'REVOKED')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    activated_at    timestamptz,
    revoked_at      timestamptz
);
CREATE UNIQUE INDEX ux_credential_active ON server_credential (server_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX ux_credential_pending ON server_credential (server_id) WHERE status = 'PENDING';

CREATE TABLE org_secret (
    id              uuid PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    name            text        NOT NULL,
    value_enc       bytea       NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, name)
);

CREATE TABLE server_snapshot (
    id              uuid PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    server_id       uuid        NOT NULL REFERENCES server (id) ON DELETE CASCADE,
    taken_at        timestamptz NOT NULL DEFAULT now(),
    payload         jsonb       NOT NULL,
    audit_score     int,
    audit_grade     text
);
CREATE INDEX ix_snapshot_server ON server_snapshot (server_id, taken_at DESC);

-- ───────────────────────────── Opérations (ADR-050) ─────────────────────────────

CREATE TABLE operation (
    id              uuid PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    server_id       uuid        NOT NULL REFERENCES server (id) ON DELETE CASCADE,
    type            text        NOT NULL,
    risk_level      text        NOT NULL CHECK (risk_level IN ('READ', 'CHANGE', 'DESTRUCTIVE')),
    status          text        NOT NULL CHECK (status IN ('DRAFT', 'PROPOSED', 'APPROVED', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    origin          text        NOT NULL DEFAULT 'USER' CHECK (origin IN ('USER', 'API', 'SCHEDULE', 'AI_SUGGESTION', 'MANAGED')),
    initiator_id    uuid        REFERENCES app_user (id),
    reason          text        NOT NULL DEFAULT '',
    parameters      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    plan            jsonb,
    plan_hash       text,
    idempotency_key text,
    expires_at      timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint      NOT NULL DEFAULT 0
);
CREATE INDEX ix_operation_org_created ON operation (organization_id, created_at DESC);
CREATE UNIQUE INDEX ux_operation_idem ON operation (organization_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE TABLE operation_approval (
    id              uuid PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    operation_id    uuid        NOT NULL REFERENCES operation (id) ON DELETE CASCADE,
    approver_id     uuid        NOT NULL REFERENCES app_user (id),
    plan_hash       text        NOT NULL,
    decision        text        NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    comment         text        NOT NULL DEFAULT '',
    decided_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL
);
CREATE INDEX ix_approval_operation ON operation_approval (operation_id);

CREATE TABLE operation_execution (
    id              uuid PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    operation_id    uuid        NOT NULL REFERENCES operation (id) ON DELETE CASCADE,
    server_id       uuid        NOT NULL REFERENCES server (id) ON DELETE CASCADE,
    approval_id     uuid REFERENCES operation_approval (id),
    idempotency_key text        NOT NULL UNIQUE,
    status          text        NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    runner_id       text,
    attempt         int         NOT NULL DEFAULT 1,
    queued_at       timestamptz NOT NULL DEFAULT now(),
    started_at      timestamptz,
    heartbeat_at    timestamptz,
    ended_at        timestamptz,
    exit_code       int,
    output_excerpt  text,
    output_sha256   text,
    error           text,
    verification    jsonb
);
-- Une seule exécution active par serveur (protection contre les opérations concurrentes).
CREATE UNIQUE INDEX ux_execution_active_server ON operation_execution (server_id) WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX ix_execution_status ON operation_execution (status, queued_at);

-- ───────────────────────────── Audit (ADR-053) ─────────────────────────────

CREATE TABLE audit_chain_head (
    chain_key  uuid PRIMARY KEY,
    last_seq   bigint NOT NULL,
    last_hash  text   NOT NULL
);

CREATE TABLE audit_event (
    id              uuid PRIMARY KEY,
    organization_id uuid,
    seq             bigint      NOT NULL,
    actor_type      text        NOT NULL CHECK (actor_type IN ('USER', 'STAFF', 'API_TOKEN', 'SYSTEM', 'RUNNER')),
    actor_id        text,
    action          text        NOT NULL,
    target_type     text,
    target_id       text,
    outcome         text        NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED')),
    details         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    ip              text,
    correlation_id  text,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    prev_hash       text        NOT NULL,
    hash            text        NOT NULL,
    UNIQUE (organization_id, seq)
);
CREATE INDEX ix_audit_org_time ON audit_event (organization_id, occurred_at DESC);

CREATE OR REPLACE FUNCTION audit_event_immutable() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only';
END
$$;
CREATE TRIGGER trg_audit_event_immutable
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_immutable();

-- ───────────────────────────── Notifications ─────────────────────────────

CREATE TABLE notification (
    id              uuid PRIMARY KEY,
    organization_id uuid,
    user_id         uuid REFERENCES app_user (id) ON DELETE SET NULL,
    channel         text        NOT NULL DEFAULT 'EMAIL',
    recipient       text        NOT NULL,
    template_key    text        NOT NULL,
    locale          text        NOT NULL,
    subject         text        NOT NULL,
    body            text        NOT NULL,
    status          text        NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'LOGGED')),
    error           text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    sent_at         timestamptz
);

-- ───────────────────────────── Facturation (fondations) ─────────────────────────────

CREATE TABLE plan (
    code         text PRIMARY KEY,
    name         text    NOT NULL,
    server_quota int     NOT NULL,
    member_quota int     NOT NULL,
    features     text[]  NOT NULL DEFAULT '{}',
    trial_days   int     NOT NULL DEFAULT 0,
    public       boolean NOT NULL DEFAULT true,
    active       boolean NOT NULL DEFAULT true,
    sort_order   int     NOT NULL DEFAULT 0
);

CREATE TABLE plan_price (
    plan_code    text   NOT NULL REFERENCES plan (code),
    currency     text   NOT NULL,
    period       text   NOT NULL CHECK (period IN ('MONTH', 'YEAR')),
    amount_minor bigint NOT NULL CHECK (amount_minor >= 0),
    PRIMARY KEY (plan_code, currency, period)
);

CREATE TABLE subscription (
    id                   uuid PRIMARY KEY,
    organization_id      uuid        NOT NULL UNIQUE REFERENCES organization (id) ON DELETE CASCADE,
    plan_code            text        NOT NULL REFERENCES plan (code),
    status               text        NOT NULL CHECK (status IN ('TRIALING', 'ACTIVE', 'PAST_DUE', 'CANCELLED')),
    currency             text        NOT NULL,
    period               text        NOT NULL DEFAULT 'MONTH',
    trial_ends_at        timestamptz,
    current_period_start timestamptz NOT NULL,
    current_period_end   timestamptz NOT NULL,
    cancel_at_period_end boolean     NOT NULL DEFAULT false,
    provider             text        NOT NULL DEFAULT 'manual',
    provider_ref         text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    version              bigint      NOT NULL DEFAULT 0
);

CREATE TABLE invoice (
    id              uuid PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    subscription_id uuid REFERENCES subscription (id),
    number          text        NOT NULL UNIQUE,
    currency        text        NOT NULL,
    amount_minor    bigint      NOT NULL,
    status          text        NOT NULL CHECK (status IN ('DRAFT', 'OPEN', 'PAID', 'VOID', 'UNCOLLECTIBLE')),
    description     text        NOT NULL DEFAULT '',
    period_start    timestamptz,
    period_end      timestamptz,
    issued_at       timestamptz NOT NULL DEFAULT now(),
    due_at          timestamptz,
    paid_at         timestamptz,
    provider        text        NOT NULL DEFAULT 'manual',
    provider_ref    text,
    UNIQUE (provider, provider_ref)
);

-- Événements des prestataires de paiement : idempotence par (provider, provider_event_id).
CREATE TABLE payment_event (
    id                uuid PRIMARY KEY,
    provider          text        NOT NULL,
    provider_event_id text        NOT NULL,
    type              text        NOT NULL,
    payload           jsonb       NOT NULL,
    received_at       timestamptz NOT NULL DEFAULT now(),
    processed_at      timestamptz,
    status            text        NOT NULL CHECK (status IN ('RECEIVED', 'PROCESSED', 'IGNORED', 'FAILED')),
    error             text,
    UNIQUE (provider, provider_event_id)
);

CREATE SEQUENCE invoice_number_seq START 1000;

-- ───────────────────────────── Services managés (ADR-052) ─────────────────────────────

CREATE TABLE managed_contract (
    id                     uuid PRIMARY KEY,
    organization_id        uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    service_level          text        NOT NULL CHECK (service_level IN ('ESSENTIAL', 'BUSINESS', 'CRITICAL')),
    status                 text        NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'ENDED')),
    starts_on              date        NOT NULL,
    ends_on                date,
    response_time_minutes  int         NOT NULL,
    scope                  text        NOT NULL DEFAULT '',
    created_by             uuid REFERENCES app_user (id),
    created_at             timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE managed_contract_server (
    contract_id     uuid NOT NULL REFERENCES managed_contract (id) ON DELETE CASCADE,
    server_id       uuid NOT NULL REFERENCES server (id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    PRIMARY KEY (contract_id, server_id)
);

-- Seul moyen pour un technicien INOVNE d'agir chez un client : borné, révocable, audité.
CREATE TABLE support_access_grant (
    id              uuid PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    staff_user_id   uuid        NOT NULL REFERENCES app_user (id),
    access_level    text        NOT NULL CHECK (access_level IN ('READ', 'OPERATE')),
    reason          text        NOT NULL,
    granted_by      uuid        NOT NULL REFERENCES app_user (id),
    starts_at       timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    revoked_at      timestamptz,
    revoked_by      uuid REFERENCES app_user (id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    CHECK (expires_at > starts_at)
);
CREATE INDEX ix_support_grant_staff ON support_access_grant (staff_user_id, expires_at);

CREATE TABLE intervention (
    id              uuid PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    contract_id     uuid REFERENCES managed_contract (id),
    server_id       uuid REFERENCES server (id) ON DELETE SET NULL,
    title           text        NOT NULL,
    description     text        NOT NULL DEFAULT '',
    priority        text        NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    status          text        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'PLANNED', 'IN_PROGRESS', 'DONE', 'CANCELLED')),
    requested_by    uuid REFERENCES app_user (id),
    assigned_to     uuid REFERENCES app_user (id),
    planned_for     timestamptz,
    closed_at       timestamptz,
    report          text        NOT NULL DEFAULT '',
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint      NOT NULL DEFAULT 0
);

CREATE TABLE intervention_operation (
    intervention_id uuid NOT NULL REFERENCES intervention (id) ON DELETE CASCADE,
    operation_id    uuid NOT NULL REFERENCES operation (id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    PRIMARY KEY (intervention_id, operation_id)
);

-- ───────────────────────────── IA (ADR-054) ─────────────────────────────

CREATE TABLE ai_interaction (
    id              uuid PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    server_id       uuid REFERENCES server (id) ON DELETE SET NULL,
    provider        text        NOT NULL,
    model           text        NOT NULL,
    prompt_key      text        NOT NULL,
    prompt_version  text        NOT NULL,
    input_digest    text        NOT NULL,
    output          jsonb,
    schema_valid    boolean     NOT NULL,
    error           text,
    created_by      uuid REFERENCES app_user (id),
    created_at      timestamptz NOT NULL DEFAULT now()
);

-- ───────────────────────────── Sécurité au niveau des lignes (ADR-049) ─────────────────────────────

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'org_data_key', 'server', 'server_credential', 'org_secret', 'server_snapshot',
        'operation', 'operation_approval', 'operation_execution',
        'subscription', 'invoice',
        'managed_contract', 'managed_contract_server', 'support_access_grant',
        'intervention', 'intervention_operation', 'ai_interaction'
    ]
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (app_bypass_rls() OR organization_id = app_current_org()) '
            'WITH CHECK (app_bypass_rls() OR organization_id = app_current_org())', t);
    END LOOP;
END
$$;

-- Audit : événements plateforme (organization_id NULL) visibles en mode plateforme seulement.
ALTER TABLE audit_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_event
    USING (app_bypass_rls() OR organization_id = app_current_org())
    WITH CHECK (app_bypass_rls() OR organization_id = app_current_org());
ALTER TABLE notification ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification
    USING (app_bypass_rls() OR organization_id = app_current_org() OR organization_id IS NULL)
    WITH CHECK (app_bypass_rls() OR organization_id = app_current_org() OR organization_id IS NULL);

-- ───────────────────────────── Droits du rôle applicatif ─────────────────────────────

GRANT USAGE ON SCHEMA public TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${app_role};
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ${app_role};
-- Journal d'audit : ni modification ni suppression, même pour l'application.
REVOKE UPDATE, DELETE, TRUNCATE ON audit_event FROM ${app_role};
REVOKE TRUNCATE ON ALL TABLES IN SCHEMA public FROM ${app_role};
GRANT EXECUTE ON FUNCTION app_current_org(), app_bypass_rls() TO ${app_role};
REVOKE ALL ON TABLE flyway_schema_history FROM ${app_role};
