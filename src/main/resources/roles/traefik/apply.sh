set -eu
# VPS Autopilot — Traefik dans Docker. N’applique que APPLY_*=1.
# Aucune app exposée (exposedByDefault=false). Pas de dashboard public.
APPLY_PROXY="${APPLY_PROXY:-1}"
APPLY_HTTPS="${APPLY_HTTPS:-1}"
umask 022
if [ "$(id -u)" -eq 0 ]; then
  SUDO=""
else
  SUDO="sudo -n"
  $SUDO true || { echo NEED_PASSWORDLESS_SUDO; exit 1; }
fi

if [ ! -f /etc/os-release ]; then
  echo UNSUPPORTED_OS
  exit 1
fi
# shellcheck disable=SC1091
. /etc/os-release
case "${ID_LIKE:-$ID}" in
  *debian*|*ubuntu*) ;;
  *) echo UNSUPPORTED_OS; exit 1 ;;
esac

if ! command -v docker >/dev/null 2>&1; then
  echo DOCKER_MISSING
  exit 1
fi
if ! $SUDO docker info >/dev/null 2>&1; then
  echo DOCKER_MISSING
  exit 1
fi

if [ "$APPLY_PROXY" != "1" ] && [ "$APPLY_HTTPS" != "1" ]; then
  echo TRAEFIK_APPLIED
  exit 0
fi

export DEBIAN_FRONTEND=noninteractive
if ! $SUDO docker compose version >/dev/null 2>&1 && ! command -v docker-compose >/dev/null 2>&1; then
  $SUDO apt-get update -y
  $SUDO apt-get install -y docker-compose-v2 || $SUDO apt-get install -y docker-compose
fi

compose_up() {
  if $SUDO docker compose version >/dev/null 2>&1; then
    $SUDO docker compose --project-directory "$DIR" -f "$DIR/docker-compose.yml" up -d --force-recreate --remove-orphans
  elif command -v docker-compose >/dev/null 2>&1; then
    $SUDO docker-compose --project-directory "$DIR" -f "$DIR/docker-compose.yml" up -d --force-recreate --remove-orphans
  else
    echo COMPOSE_MISSING
    exit 1
  fi
}

DIR=/opt/vps-autopilot/traefik
$SUDO mkdir -p "$DIR/letsencrypt"
$SUDO touch "$DIR/letsencrypt/acme.json"
$SUDO chmod 600 "$DIR/letsencrypt/acme.json"
$SUDO docker network inspect proxy >/dev/null 2>&1 || $SUDO docker network create proxy

HOST_NAME="$(hostname -f 2>/dev/null || hostname)"
ACME_EMAIL="${ACME_EMAIL:-vps-autopilot@${HOST_NAME}}"

HTTPS_PORTS=""
HTTPS_CMD=""
HTTPS_VOL=""
if [ "$APPLY_HTTPS" = "1" ]; then
  HTTPS_PORTS='      - "443:443"'
  HTTPS_CMD=$(printf '%s\n' \
    '      - --entrypoints.web.http.redirections.entrypoint.to=websecure' \
    '      - --entrypoints.web.http.redirections.entrypoint.scheme=https' \
    '      - --entrypoints.websecure.address=:443' \
    '      - --certificatesresolvers.le.acme.httpchallenge=true' \
    '      - --certificatesresolvers.le.acme.httpchallenge.entrypoint=web' \
    "      - --certificatesresolvers.le.acme.email=${ACME_EMAIL}" \
    '      - --certificatesresolvers.le.acme.storage=/letsencrypt/acme.json')
  HTTPS_VOL='      - ./letsencrypt:/letsencrypt'
fi

# Ubuntu récent (Docker Engine 29+) impose MinAPI 1.44. Traefik ≤ 3.5 parle encore en API 1.24
# via le socket → provider Docker mort, 404 sur toutes les apps. Workaround : min-api-version 1.24.
merge_docker_min_api() {
  local conf=/etc/docker/daemon.json
  $SUDO mkdir -p /etc/docker
  if [ -f "$conf" ]; then
    if grep -q 'min-api-version' "$conf" 2>/dev/null; then
      return 0
    fi
    # Fichier existant sans la clé : backup + écriture minimale (lab).
    $SUDO cp "$conf" "${conf}.bak.vps-autopilot" || true
  fi
  $SUDO tee "$conf" >/dev/null <<'JSON'
{
  "min-api-version": "1.24"
}
JSON
  if command -v systemctl >/dev/null 2>&1; then
    $SUDO systemctl restart docker
  else
    $SUDO service docker restart
  fi
  # Attendre que le daemon réponde.
  i=0
  while [ "$i" -lt 30 ]; do
    if $SUDO docker info >/dev/null 2>&1; then
      break
    fi
    i=$((i + 1))
    sleep 1
  done
  $SUDO docker info >/dev/null 2>&1 || { echo DOCKER_RESTART_FAILED; exit 1; }
}

merge_docker_min_api

# Image v3.5 + DOCKER_API_VERSION (défense en profondeur) ; le min-api-version débloque le socket.
# shellcheck disable=SC2086
$SUDO tee "$DIR/docker-compose.yml" >/dev/null <<EOF
services:
  traefik:
    image: traefik:v3.5
    container_name: traefik
    restart: unless-stopped
    environment:
      - DOCKER_API_VERSION=1.44
    command:
      - --providers.docker=true
      - --providers.docker.exposedbydefault=false
      - --providers.docker.network=proxy
      - --entrypoints.web.address=:80
      - --api.dashboard=false
      - --log.level=WARN
${HTTPS_CMD}
    ports:
      - "80:80"
${HTTPS_PORTS}
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
${HTTPS_VOL}
    networks:
      - proxy
networks:
  proxy:
    external: true
EOF

if [ "$APPLY_PROXY" = "1" ]; then
  $SUDO ufw allow 80/tcp comment 'vps-autopilot traefik' || $SUDO ufw allow 80/tcp
fi
if [ "$APPLY_HTTPS" = "1" ]; then
  $SUDO ufw allow 443/tcp comment 'vps-autopilot traefik' || $SUDO ufw allow 443/tcp
fi

compose_up
echo TRAEFIK_APPLIED
