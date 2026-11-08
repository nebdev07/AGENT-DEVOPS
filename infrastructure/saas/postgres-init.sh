#!/bin/sh
# Premier démarrage de PostgreSQL : rôle applicatif avec mot de passe, AVANT les migrations Flyway.
# NOSUPERUSER + NOBYPASSRLS : la sécurité au niveau des lignes s'applique à l'application (ADR-049).
# Flyway (rôle propriétaire POSTGRES_USER) crée ensuite le schéma et accorde les droits à ce rôle.
set -eu
: "${AUTOPILOT_DB_PASSWORD:?AUTOPILOT_DB_PASSWORD manquant}"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v app_password="$AUTOPILOT_DB_PASSWORD" <<'SQL'
CREATE ROLE autopilot_app LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD :'app_password';
REVOKE ALL ON DATABASE autopilot FROM PUBLIC;
GRANT CONNECT ON DATABASE autopilot TO autopilot_app;
SQL
