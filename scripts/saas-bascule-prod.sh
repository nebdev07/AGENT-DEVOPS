#!/usr/bin/env bash
# Bascule autopilot.inovne.com : platform → saas (ADR-057).
# Sur le VPS :
#   sudo bash saas-bascule-prod.sh            # sauvegarde + dry-run
#   sudo bash saas-bascule-prod.sh --apply    # migration réelle + bascule Traefik
set -euo pipefail

APPLY=false
for a in "$@"; do
  [[ "$a" == "--apply" ]] && APPLY=true
done

REPO_URL=${REPO_URL:-https://github.com/nebdev07/AGENT-DEVOPS.git}
REPO_REF=${REPO_REF:-main}
SAAS=/opt/vps-autopilot/saas
SRC_CACHE=/opt/vps-autopilot/saas-src
OLD=app-vps-autopilot
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
BACKUP=/opt/vps-autopilot/backups/platform-bascule-$STAMP

echo "==> 1. Prérequis"
command -v docker >/dev/null
command -v curl >/dev/null
command -v openssl >/dev/null
docker network inspect proxy >/dev/null
docker inspect "$OLD" >/dev/null

echo "==> 2. Sauvegarde state platform → $BACKUP"
mkdir -p "$BACKUP/platform" /opt/vps-autopilot/backups
docker cp "$OLD:/app/data/state/platform/." "$BACKUP/platform/"
tar -C "$BACKUP" -czf "$BACKUP.tgz" platform
echo "    $BACKUP.tgz"

echo "==> 3. Sources SaaS ($REPO_REF)"
if [[ -d "$SRC_CACHE/.git" ]]; then
  git -C "$SRC_CACHE" fetch --depth 1 origin "$REPO_REF"
  git -C "$SRC_CACHE" checkout -f "FETCH_HEAD"
else
  rm -rf "$SRC_CACHE"
  git clone --depth 1 --branch "$REPO_REF" "$REPO_URL" "$SRC_CACHE"
fi
mkdir -p "$SAAS/migration/platform"
rsync -a --delete \
  --exclude .env --exclude runner.key --exclude runner.pub --exclude migration \
  "$SRC_CACHE/infrastructure/saas/" "$SAAS/"
cp -a "$BACKUP/platform/." "$SAAS/migration/platform/"
cp "$SRC_CACHE/scripts/saas-bascule-prod.sh" "$SAAS/saas-bascule-prod.sh"
chmod +x "$SAAS/saas-bascule-prod.sh" "$SAAS/postgres-init.sh" 2>/dev/null || true

if [[ ! -f "$SAAS/runner.key" ]]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$SAAS/runner.key"
  openssl pkey -in "$SAAS/runner.key" -pubout -out "$SAAS/runner.pub"
  chmod 600 "$SAAS/runner.key"
fi

if [[ ! -f "$SAAS/.env" ]]; then
  PUB=$(cat "$SAAS/runner.pub")
  cat > "$SAAS/.env" <<EOF
AUTOPILOT_PUBLIC_URL=https://autopilot.inovne.com
AUTOPILOT_TRAEFIK_HOST=autopilot.inovne.com
AUTOPILOT_BOOTSTRAP_ADMIN_EMAIL=nebdev07@gmail.com
AUTOPILOT_BUILD_CONTEXT=$SRC_CACHE
AUTOPILOT_DB_OWNER_PASSWORD=$(openssl rand -hex 24)
AUTOPILOT_DB_PASSWORD=$(openssl rand -hex 24)
AUTOPILOT_MASTER_KEY=$(openssl rand -base64 32)
AUTOPILOT_RUNNER_MODE=remote
AUTOPILOT_RUNNER_TOKEN=$(openssl rand -hex 32)
AUTOPILOT_RUNNER_PRIVATE_KEY_PATH=./runner.key
AUTOPILOT_RUNNER_PUBLIC_KEY="$PUB"
AUTOPILOT_AI_PROVIDER=none
AUTOPILOT_STAFF_MFA_REQUIRED=true
AUTOPILOT_MIGRATION_HOST_DIR=$SAAS/migration
AUTOPILOT_MIGRATION_PLATFORM_STATE_DIR=
AUTOPILOT_MIGRATION_ORGANIZATION_NAME=INOVNE
AUTOPILOT_MIGRATION_APPLY=false
EOF
  chmod 600 "$SAAS/.env"
  echo "    Secrets prod générés dans $SAAS/.env"
else
  # Met à jour le contexte de build si l'ancien .env n'en a pas.
  grep -q '^AUTOPILOT_BUILD_CONTEXT=' "$SAAS/.env" || echo "AUTOPILOT_BUILD_CONTEXT=$SRC_CACHE" >> "$SAAS/.env"
  sed -i "s|^AUTOPILOT_BUILD_CONTEXT=.*|AUTOPILOT_BUILD_CONTEXT=$SRC_CACHE|" "$SAAS/.env"
fi

# Compose depuis le clone (context Dockerfile correct) ; secrets/clés depuis $SAAS.
cd "$SRC_CACHE/infrastructure/saas"
ln -sfn "$SAAS/.env" .env
ln -sfn "$SAAS/runner.key" runner.key
ln -sfn "$SAAS/runner.pub" runner.pub
COMPOSE=(docker compose -f docker-compose.yml -f compose.prod.yml --env-file "$SAAS/.env")

echo "==> 4. Build"
"${COMPOSE[@]}" build control-plane

echo "==> 5. Base de données"
"${COMPOSE[@]}" up -d db
for i in $(seq 1 40); do
  "${COMPOSE[@]}" exec -T db pg_isready -U autopilot_owner -d autopilot && break
  sleep 2
done

run_cp_once() {
  local apply_flag=$1
  local name=$2
  docker rm -f "$name" >/dev/null 2>&1 || true
  # Réseau interne seul + traefik.enable=false : ne pas voler autopilot.inovne.com pendant la migration.
  docker compose -f docker-compose.yml -f compose.prod.yml --env-file "$SAAS/.env" run -d \
    --name "$name" \
    --network vps-autopilot-saas_internal \
    -p 127.0.0.1:18080:8080 \
    -l traefik.enable=false \
    -e AUTOPILOT_MIGRATION_PLATFORM_STATE_DIR=/migration/platform \
    -e AUTOPILOT_MIGRATION_APPLY="$apply_flag" \
    -e AUTOPILOT_MIGRATION_REPORT_FILE=/app/data/saas-import-report.json \
    -e AUTOPILOT_MIGRATION_ORGANIZATION_NAME=INOVNE \
    -v "$SAAS/migration:/migration:ro" \
    -v vps-autopilot-saas_control-data:/app/data \
    --no-deps \
    control-plane
  for i in $(seq 1 90); do
    if curl -sf http://127.0.0.1:18080/actuator/health 2>/dev/null | grep -q UP; then
      return 0
    fi
    sleep 2
  done
  echo "Timeout santé $name"
  docker logs "$name" 2>&1 | tail -100
  return 1
}

echo "==> 6. Migration essai à blanc"
run_cp_once false saas-migrate-cp
sleep 3
docker cp saas-migrate-cp:/app/data/saas-import-report.json "$BACKUP/saas-import-report-dry.json" 2>/dev/null || \
  echo "    (rapport dry-run absent — vérifier les logs)"
if [[ -f "$BACKUP/saas-import-report-dry.json" ]]; then
  echo "    Rapport : $BACKUP/saas-import-report-dry.json"
  head -c 2000 "$BACKUP/saas-import-report-dry.json"; echo
fi

if [[ "$APPLY" != "true" ]]; then
  echo ""
  echo "DRY-RUN OK. Relancer avec --apply pour migrer et basculer Traefik."
  echo "Inspection : curl -s http://127.0.0.1:18080/actuator/health"
  exit 0
fi

echo "==> 7. Migration APPLY"
docker rm -f saas-migrate-cp >/dev/null 2>&1 || true
run_cp_once true saas-migrate-cp
sleep 3
docker cp saas-migrate-cp:/app/data/saas-import-report.json "$BACKUP/saas-import-report-apply.json" 2>/dev/null || true
docker rm -f saas-migrate-cp >/dev/null 2>&1 || true

echo "==> 8. Stop ancienne instance platform (conservée, restart désactivé)"
docker update --restart=no "$OLD" >/dev/null
docker stop "$OLD"

echo "==> 9. Démarre SaaS derrière Traefik"
# Pas de variables migration sur le service long-running
sed -i 's/^AUTOPILOT_MIGRATION_APPLY=.*/AUTOPILOT_MIGRATION_APPLY=false/' .env
sed -i 's|^AUTOPILOT_MIGRATION_PLATFORM_STATE_DIR=.*|AUTOPILOT_MIGRATION_PLATFORM_STATE_DIR=|' .env
"${COMPOSE[@]}" up -d

echo "    Attente https://autopilot.inovne.com …"
for i in $(seq 1 60); do
  if curl -sf https://autopilot.inovne.com/actuator/health 2>/dev/null | grep -q UP; then break; fi
  sleep 3
done

echo "==> 10. Vérifications"
echo -n "health: "; curl -s https://autopilot.inovne.com/actuator/health || true; echo
curl -s -o /dev/null -w "app:%{http_code}\n" https://autopilot.inovne.com/app/
curl -s -o /dev/null -w "openapi:%{http_code}\n" https://autopilot.inovne.com/api/v2/openapi
docker ps --format 'table {{.Names}}\t{{.Status}}' | head -20
echo "Archive : $BACKUP.tgz"
echo "Activer la MFA du compte personnel (Sécurité) si bootstrap PLATFORM_ADMIN."
