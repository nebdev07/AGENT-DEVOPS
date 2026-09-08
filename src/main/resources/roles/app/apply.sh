set -eu
# VPS Autopilot — une app cargo. Compose généré par l’agent. Pas de port public.
# Variables : APP_SLUG APP_GIT APP_REF APP_HOST APP_PORT (préfixées par Java).
APP_SLUG="${APP_SLUG:-}"
APP_GIT="${APP_GIT:-}"
APP_REF="${APP_REF:-main}"
APP_HOST="${APP_HOST:-}"
APP_PORT="${APP_PORT:-8080}"
umask 022
if [ "$(id -u)" -eq 0 ]; then
  SUDO=""
else
  SUDO="sudo -n"
  $SUDO true || { echo NEED_PASSWORDLESS_SUDO; exit 1; }
fi

if [ -z "$APP_SLUG" ] || [ -z "$APP_GIT" ] || [ -z "$APP_HOST" ]; then
  echo NEED_APP_SPEC
  exit 1
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
if ! $SUDO docker ps --format '{{.Names}}' 2>/dev/null | grep -qi traefik \
    && ! docker ps --format '{{.Names}}' 2>/dev/null | grep -qi traefik; then
  echo NEED_TRAEFIK
  exit 1
fi
if ! id deploy >/dev/null 2>&1; then
  echo NEED_DEPLOY_USER
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
if ! command -v git >/dev/null 2>&1; then
  $SUDO apt-get update -y
  $SUDO apt-get install -y git
fi
if ! $SUDO docker compose version >/dev/null 2>&1 && ! command -v docker-compose >/dev/null 2>&1; then
  $SUDO apt-get update -y
  $SUDO apt-get install -y docker-compose-v2 || $SUDO apt-get install -y docker-compose
fi

$SUDO docker network inspect proxy >/dev/null 2>&1 || { echo NEED_TRAEFIK; exit 1; }

ROOT=/opt/vps-autopilot/apps/$APP_SLUG
SRC="$ROOT/src"
$SUDO mkdir -p "$ROOT"
$SUDO chown deploy:deploy "$ROOT"

if [ -d "$SRC/.git" ]; then
  $SUDO -u deploy git -C "$SRC" fetch --depth 1 origin "$APP_REF" || $SUDO -u deploy git -C "$SRC" fetch --depth 1 origin
  $SUDO -u deploy git -C "$SRC" checkout -f "$APP_REF" || $SUDO -u deploy git -C "$SRC" checkout -f FETCH_HEAD
else
  $SUDO rm -rf "$SRC"
  if ! $SUDO -u deploy git clone --depth 1 --branch "$APP_REF" "$APP_GIT" "$SRC"; then
    if ! $SUDO -u deploy git clone --depth 1 "$APP_GIT" "$SRC"; then
      echo GIT_CLONE_FAILED
      exit 1
    fi
    $SUDO -u deploy git -C "$SRC" checkout "$APP_REF" || true
  fi
fi

if [ ! -f "$SRC/Dockerfile" ]; then
  echo NEED_DOCKERFILE
  exit 1
fi

HAS_DATA=0
if $SUDO docker network inspect data >/dev/null 2>&1; then
  HAS_DATA=1
fi

if [ "$HAS_DATA" = "1" ]; then
  $SUDO tee "$ROOT/docker-compose.yml" >/dev/null <<EOF
services:
  app:
    build: ./src
    container_name: app-${APP_SLUG}
    restart: unless-stopped
    networks:
      - proxy
      - data
    labels:
      - traefik.enable=true
      - traefik.docker.network=proxy
      - vps.autopilot.app=${APP_SLUG}
      - traefik.http.routers.${APP_SLUG}.rule=Host(\`${APP_HOST}\`)
      - traefik.http.routers.${APP_SLUG}.entrypoints=web
      - traefik.http.routers.${APP_SLUG}-secure.rule=Host(\`${APP_HOST}\`)
      - traefik.http.routers.${APP_SLUG}-secure.entrypoints=websecure
      - traefik.http.routers.${APP_SLUG}-secure.tls=true
      - traefik.http.routers.${APP_SLUG}-secure.tls.certresolver=le
      - traefik.http.services.${APP_SLUG}.loadbalancer.server.port=${APP_PORT}
networks:
  proxy:
    external: true
  data:
    external: true
EOF
else
  $SUDO tee "$ROOT/docker-compose.yml" >/dev/null <<EOF
services:
  app:
    build: ./src
    container_name: app-${APP_SLUG}
    restart: unless-stopped
    networks:
      - proxy
    labels:
      - traefik.enable=true
      - traefik.docker.network=proxy
      - vps.autopilot.app=${APP_SLUG}
      - traefik.http.routers.${APP_SLUG}.rule=Host(\`${APP_HOST}\`)
      - traefik.http.routers.${APP_SLUG}.entrypoints=web
      - traefik.http.routers.${APP_SLUG}-secure.rule=Host(\`${APP_HOST}\`)
      - traefik.http.routers.${APP_SLUG}-secure.entrypoints=websecure
      - traefik.http.routers.${APP_SLUG}-secure.tls=true
      - traefik.http.routers.${APP_SLUG}-secure.tls.certresolver=le
      - traefik.http.services.${APP_SLUG}.loadbalancer.server.port=${APP_PORT}
networks:
  proxy:
    external: true
EOF
fi

$SUDO chown deploy:deploy "$ROOT/docker-compose.yml"

if $SUDO docker compose version >/dev/null 2>&1; then
  $SUDO docker compose --project-directory "$ROOT" -f "$ROOT/docker-compose.yml" up -d --build
elif command -v docker-compose >/dev/null 2>&1; then
  $SUDO docker-compose --project-directory "$ROOT" -f "$ROOT/docker-compose.yml" up -d --build
else
  echo COMPOSE_MISSING
  exit 1
fi

echo APP_DEPLOYED
