-- Demandes d'inscription en attente de validation INOVNE (mot de passe envoyé par e-mail après approbation).
CREATE TABLE signup_request (
    id                  uuid PRIMARY KEY,
    email               text        NOT NULL,
    display_name        text        NOT NULL DEFAULT '',
    organization_name   text        NOT NULL,
    locale              text        NOT NULL DEFAULT 'en',
    time_zone           text        NOT NULL DEFAULT 'UTC',
    currency            text        NOT NULL DEFAULT 'USD',
    message             text,
    status              text        NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    decided_at          timestamptz,
    decided_by          uuid REFERENCES app_user (id),
    created_user_id     uuid REFERENCES app_user (id),
    created_org_id      uuid REFERENCES organization (id),
    ip_address          text,
    user_agent          text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX signup_request_pending_email
    ON signup_request (lower(email))
    WHERE status = 'PENDING';

CREATE INDEX signup_request_status_created ON signup_request (status, created_at DESC);
