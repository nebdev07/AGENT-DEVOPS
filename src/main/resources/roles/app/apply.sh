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

setup_git_ssh() {
  GIT_SSH_COMMAND=""
  if [ -n "${GIT_DEPLOY_KEY_B64:-}" ]; then
    KEYDIR=/home/deploy/.ssh
    $SUDO mkdir -p "$KEYDIR"
    printf '%s' "$GIT_DEPLOY_KEY_B64" | base64 -d | $SUDO tee "$KEYDIR/git-deploy" >/dev/null
    $SUDO chmod 700 "$KEYDIR"
    $SUDO chmod 600 "$KEYDIR/git-deploy"
    $SUDO chown -R deploy:deploy "$KEYDIR"
    GIT_SSH_COMMAND="ssh -i /home/deploy/.ssh/git-deploy -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new"
  fi
}

git_as_deploy() {
  if [ -n "${GIT_SSH_COMMAND:-}" ]; then
    $SUDO -u deploy env GIT_SSH_COMMAND="$GIT_SSH_COMMAND" git "$@"
  else
    $SUDO -u deploy git "$@"
  fi
}

require_git_ssh_if_needed() {
  case "$1" in
    git@*|ssh://git@*)
      if [ -z "${GIT_DEPLOY_KEY_B64:-}" ]; then
        echo NEED_GIT_DEPLOY_KEY
        exit 1
      fi
      ;;
  esac
}

setup_git_ssh
require_git_ssh_if_needed "$APP_GIT"

$SUDO docker network inspect proxy >/dev/null 2>&1 || { echo NEED_TRAEFIK; exit 1; }

ROOT=/opt/vps-autopilot/apps/$APP_SLUG
SRC="$ROOT/src"
$SUDO mkdir -p "$ROOT"
$SUDO chown deploy:deploy "$ROOT"

if [ -d "$SRC/.git" ]; then
  # Mise à jour : la branche locale n’avance pas avec fetch, seul FETCH_HEAD porte le dernier commit de la référence.
  FETCH_LOG="$(mktemp)"
  if ! git_as_deploy -C "$SRC" fetch --depth 1 origin "$APP_REF" >"$FETCH_LOG" 2>&1; then
    echo GIT_CLONE_FAILED
    tail -n 20 "$FETCH_LOG" || true
    rm -f "$FETCH_LOG"
    exit 1
  fi
  git_as_deploy -C "$SRC" checkout -f FETCH_HEAD >>"$FETCH_LOG" 2>&1
  rm -f "$FETCH_LOG"
  echo "APP_COMMIT=$(git_as_deploy -C "$SRC" rev-parse --short HEAD)"
else
  $SUDO rm -rf "$SRC"
  CLONE_LOG="$(mktemp)"
  if ! git_as_deploy clone --depth 1 --branch "$APP_REF" "$APP_GIT" "$SRC" >"$CLONE_LOG" 2>&1; then
    if ! git_as_deploy clone --depth 1 "$APP_GIT" "$SRC" >>"$CLONE_LOG" 2>&1; then
      echo GIT_CLONE_FAILED
      tail -n 20 "$CLONE_LOG" || true
      rm -f "$CLONE_LOG"
      exit 1
    fi
    git_as_deploy -C "$SRC" checkout "$APP_REF" || true
  fi
  rm -f "$CLONE_LOG"
  echo "APP_COMMIT=$(git_as_deploy -C "$SRC" rev-parse --short HEAD)"
fi

if [ -n "${APP_SCAFFOLD_B64:-}" ]; then
  printf '%s' "$APP_SCAFFOLD_B64" | base64 -d | $SUDO -u deploy tee "$SRC/Dockerfile" >/dev/null
  $SUDO chown deploy:deploy "$SRC/Dockerfile"
  echo SCAFFOLD_DOCKERFILE_APPLIED
fi

if [ ! -f "$SRC/Dockerfile" ]; then
  echo NEED_DOCKERFILE
  echo "HINT: racine du dépôt sans Dockerfile (un docker-compose.yml ne suffit pas)."
  exit 1
fi

HAS_DATA=0
if $SUDO docker network inspect data >/dev/null 2>&1; then
  HAS_DATA=1
fi

HAS_DB=0
DB_ENV_LINE=""
if [ "$HAS_DATA" = "1" ] && $SUDO docker ps --format '{{.Names}}' 2>/dev/null | grep -qx mariadb; then
  DB_ID="$(printf '%s' "$APP_SLUG" | tr '-' '_')"
  DB_NAME="app_${DB_ID}"
  DB_USER="app_${DB_ID}"
  DB_FILE="$ROOT/db.env"
  if ! $SUDO test -s "$DB_FILE"; then
    DB_PW="$($SUDO openssl rand -hex 16)"
    $SUDO tee "$DB_FILE" >/dev/null <<ENV
DB_HOST=mariadb
DB_PORT=3306
DB_NAME=${DB_NAME}
DB_USER=${DB_USER}
DB_PASSWORD=${DB_PW}
PERSISTENCE_DRIVER=mysql
DATABASE_URL=mysql://${DB_USER}:${DB_PW}@mariadb:3306/${DB_NAME}
ENV
    $SUDO chmod 600 "$DB_FILE"
    unset DB_PW
  elif ! $SUDO grep -q '^DATABASE_URL=' "$DB_FILE"; then
    DB_PW="$($SUDO grep '^DB_PASSWORD=' "$DB_FILE" | cut -d= -f2-)"
    $SUDO tee -a "$DB_FILE" >/dev/null <<ENV
PERSISTENCE_DRIVER=mysql
DATABASE_URL=mysql://${DB_USER}:${DB_PW}@mariadb:3306/${DB_NAME}
ENV
    unset DB_PW
  fi
  $SUDO chown deploy:deploy "$DB_FILE"
  ROOT_PW="$($SUDO cat /opt/vps-autopilot/mariadb/root.pw)"
  DB_PW="$($SUDO grep '^DB_PASSWORD=' "$DB_FILE" | cut -d= -f2-)"
  # Mot de passe = hex openssl uniquement (pas de quote).
  printf '%s\n' \
    "CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\`;" \
    "CREATE USER IF NOT EXISTS '${DB_USER}'@'%' IDENTIFIED BY '${DB_PW}';" \
    "GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'%';" \
    "FLUSH PRIVILEGES;" \
    | $SUDO docker exec -i mariadb mariadb -uroot -p"$ROOT_PW"
  unset ROOT_PW DB_PW
  HAS_DB=1
  DB_ENV_LINE='    env_file:
      - ./db.env'
  echo "DB_GRANT=${DB_NAME}"
fi

APP_ENV_FILE="$ROOT/app.env"
if ! $SUDO test -s "$APP_ENV_FILE"; then
  JOB_SECRET="$($SUDO openssl rand -hex 32)"
  $SUDO tee "$APP_ENV_FILE" >/dev/null <<ENV
NODE_ENV=production
PERSISTENCE_DRIVER=mysql
ENABLE_ADMIN_PORTFOLIO_EXAMPLE=0
JOB_SECRET=${JOB_SECRET}
ENV
  $SUDO chmod 600 "$APP_ENV_FILE"
  unset JOB_SECRET
fi
$SUDO chown deploy:deploy "$APP_ENV_FILE"

APP_ENV_LINES='    env_file:
      - ./app.env'
if [ -n "$DB_ENV_LINE" ]; then
  APP_ENV_LINES='    env_file:
      - ./app.env
      - ./db.env'
fi

if [ "$HAS_DATA" = "1" ]; then
  $SUDO tee "$ROOT/docker-compose.yml" >/dev/null <<EOF
services:
  app:
    build: ./src
    container_name: app-${APP_SLUG}
    restart: unless-stopped
${APP_ENV_LINES}
    volumes:
      - app_data_${APP_SLUG}:/app/data
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
volumes:
  app_data_${APP_SLUG}:
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
${APP_ENV_LINES}
    volumes:
      - app_data_${APP_SLUG}:/app/data
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
volumes:
  app_data_${APP_SLUG}:
networks:
  proxy:
    external: true
EOF
fi

$SUDO chown deploy:deploy "$ROOT/docker-compose.yml"

BUILD_LOG="$(mktemp)"
if $SUDO docker compose version >/dev/null 2>&1; then
  if ! $SUDO docker compose --project-directory "$ROOT" -f "$ROOT/docker-compose.yml" up -d --build >"$BUILD_LOG" 2>&1; then
    echo BUILD_FAILED
    tail -n 50 "$BUILD_LOG" || true
    rm -f "$BUILD_LOG"
    exit 1
  fi
elif command -v docker-compose >/dev/null 2>&1; then
  if ! $SUDO docker-compose --project-directory "$ROOT" -f "$ROOT/docker-compose.yml" up -d --build >"$BUILD_LOG" 2>&1; then
    echo BUILD_FAILED
    tail -n 50 "$BUILD_LOG" || true
    rm -f "$BUILD_LOG"
    exit 1
  fi
else
  echo COMPOSE_MISSING
  rm -f "$BUILD_LOG"
  exit 1
fi
rm -f "$BUILD_LOG"

if [ "$HAS_DB" = "1" ]; then
  echo DB_ATTACHED
fi
echo APP_DEPLOYED
