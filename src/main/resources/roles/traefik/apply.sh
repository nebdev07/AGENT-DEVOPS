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
    $SUDO docker compose --project-directory "$DIR" -f "$DIR/docker-compose.yml" up -d
  elif command -v docker-compose >/dev/null 2>&1; then
    $SUDO docker-compose --project-directory "$DIR" -f "$DIR/docker-compose.yml" up -d
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
    '      - --entrypoints.websecure.address=:443' \
    '      - --certificatesresolvers.le.acme.httpchallenge=true' \
    '      - --certificatesresolvers.le.acme.httpchallenge.entrypoint=web' \
    "      - --certificatesresolvers.le.acme.email=${ACME_EMAIL}" \
    '      - --certificatesresolvers.le.acme.storage=/letsencrypt/acme.json')
  HTTPS_VOL='      - ./letsencrypt:/letsencrypt'
fi

# shellcheck disable=SC2086
$SUDO tee "$DIR/docker-compose.yml" >/dev/null <<EOF
services:
  traefik:
    image: traefik:v3.3
    container_name: traefik
    restart: unless-stopped
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
