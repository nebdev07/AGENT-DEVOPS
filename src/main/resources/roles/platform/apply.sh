set -eu
# VPS Autopilot — plateforme N services. Compose généré par l’agent. Pas de port public.
# Variables : PLATFORM_ID PLATFORM_COMPOSE_B64 PLATFORM_MANIFEST_B64 PLATFORM_NEED_DB PLATFORM_NEED_REDIS
PLATFORM_ID="${PLATFORM_ID:-}"
PLATFORM_COMPOSE_B64="${PLATFORM_COMPOSE_B64:-}"
PLATFORM_MANIFEST_B64="${PLATFORM_MANIFEST_B64:-}"
PLATFORM_NEED_DB="${PLATFORM_NEED_DB:-0}"
PLATFORM_NEED_REDIS="${PLATFORM_NEED_REDIS:-0}"
umask 022
if [ "$(id -u)" -eq 0 ]; then
  SUDO=""
else
  SUDO="sudo -n"
  $SUDO true || { echo NEED_PASSWORDLESS_SUDO; exit 1; }
fi

if [ -z "$PLATFORM_ID" ] || [ -z "$PLATFORM_COMPOSE_B64" ] || [ -z "$PLATFORM_MANIFEST_B64" ]; then
  echo NEED_PLATFORM_SPEC
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
if [ "$PLATFORM_NEED_REDIS" = "1" ]; then
  if ! $SUDO docker ps --format '{{.Names}}' 2>/dev/null | grep -qx redis; then
    echo NEED_REDIS
    exit 1
  fi
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
if [ "$PLATFORM_NEED_DB" = "1" ] || [ "$PLATFORM_NEED_REDIS" = "1" ]; then
  $SUDO docker network inspect data >/dev/null 2>&1 || { echo NEED_DATA_NETWORK; exit 1; }
fi

ROOT=/opt/vps-autopilot/platforms/$PLATFORM_ID
$SUDO mkdir -p "$ROOT/src"
$SUDO chown -R deploy:deploy "$ROOT"

clone_one() {
  SID="$1"
  SGIT="$2"
  SREF="${3:-main}"
  SRC="$ROOT/src/$SID"
  if [ -d "$SRC/.git" ]; then
    $SUDO -u deploy git -C "$SRC" fetch --depth 1 origin "$SREF" || $SUDO -u deploy git -C "$SRC" fetch --depth 1 origin
    $SUDO -u deploy git -C "$SRC" checkout -f "$SREF" || $SUDO -u deploy git -C "$SRC" checkout -f FETCH_HEAD
  else
    $SUDO rm -rf "$SRC"
    CLONE_LOG="$(mktemp)"
    if ! $SUDO -u deploy git clone --depth 1 --branch "$SREF" "$SGIT" "$SRC" >"$CLONE_LOG" 2>&1; then
      if ! $SUDO -u deploy git clone --depth 1 "$SGIT" "$SRC" >>"$CLONE_LOG" 2>&1; then
        echo GIT_CLONE_FAILED
        echo "SERVICE=$SID"
        tail -n 20 "$CLONE_LOG" || true
        rm -f "$CLONE_LOG"
        exit 1
      fi
      $SUDO -u deploy git -C "$SRC" checkout "$SREF" || true
    fi
    rm -f "$CLONE_LOG"
  fi
}

MANIFEST="$(printf '%s' "$PLATFORM_MANIFEST_B64" | base64 -d)"
while IFS="$(printf '\t')" read -r SID SGIT SREF SCTX || [ -n "${SID:-}" ]; do
  [ -z "${SID:-}" ] && continue
  clone_one "$SID" "$SGIT" "$SREF"
  DF="$ROOT/src/$SID/Dockerfile"
  if [ -n "${SCTX:-}" ]; then
    DF="$ROOT/src/$SID/$SCTX/Dockerfile"
  fi
  if [ ! -f "$DF" ]; then
    echo NEED_DOCKERFILE
    echo "SERVICE=$SID"
    echo "HINT: Dockerfile manquant ($DF)"
    exit 1
  fi
done <<EOF
$MANIFEST
EOF

if [ "$PLATFORM_NEED_DB" = "1" ]; then
  if ! $SUDO docker ps --format '{{.Names}}' 2>/dev/null | grep -qx mariadb; then
    echo NEED_MARIADB
    exit 1
  fi
  DB_ID="$(printf '%s' "$PLATFORM_ID" | tr '-' '_')"
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
  fi
  $SUDO chown deploy:deploy "$DB_FILE"
  ROOT_PW="$($SUDO cat /opt/vps-autopilot/mariadb/root.pw)"
  DB_PW="$($SUDO grep '^DB_PASSWORD=' "$DB_FILE" | cut -d= -f2-)"
  printf '%s\n' \
    "CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\`;" \
    "CREATE USER IF NOT EXISTS '${DB_USER}'@'%' IDENTIFIED BY '${DB_PW}';" \
    "GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'%';" \
    "FLUSH PRIVILEGES;" \
    | $SUDO docker exec -i mariadb mariadb -uroot -p"$ROOT_PW"
  unset ROOT_PW DB_PW
  echo "DB_GRANT=${DB_NAME}"
fi

REDIS_LINE=""
if [ "$PLATFORM_NEED_REDIS" = "1" ]; then
  REDIS_PW="$($SUDO cat /opt/vps-autopilot/redis/redis.pw)"
  REDIS_LINE="REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=${REDIS_PW}"
  unset REDIS_PW
fi

while IFS="$(printf '\t')" read -r SID SGIT SREF SCTX || [ -n "${SID:-}" ]; do
  [ -z "${SID:-}" ] && continue
  ENVF="$ROOT/svc-${SID}.env"
  if ! $SUDO test -s "$ENVF"; then
    $SUDO tee "$ENVF" >/dev/null <<ENV
NODE_ENV=production
${REDIS_LINE}
ENV
    $SUDO chmod 600 "$ENVF"
  elif [ -n "$REDIS_LINE" ] && ! $SUDO grep -q '^REDIS_HOST=' "$ENVF"; then
    printf '%s\n' "$REDIS_LINE" | $SUDO tee -a "$ENVF" >/dev/null
  fi
  $SUDO chown deploy:deploy "$ENVF"
done <<EOF
$MANIFEST
EOF

printf '%s' "$PLATFORM_COMPOSE_B64" | base64 -d | $SUDO -u deploy tee "$ROOT/docker-compose.yml" >/dev/null
$SUDO chown deploy:deploy "$ROOT/docker-compose.yml"

BUILD_LOG="$(mktemp)"
if $SUDO docker compose version >/dev/null 2>&1; then
  if ! $SUDO docker compose --project-directory "$ROOT" -f "$ROOT/docker-compose.yml" up -d --build >"$BUILD_LOG" 2>&1; then
    echo BUILD_FAILED
    tail -n 80 "$BUILD_LOG" || true
    rm -f "$BUILD_LOG"
    exit 1
  fi
elif command -v docker-compose >/dev/null 2>&1; then
  if ! $SUDO docker-compose --project-directory "$ROOT" -f "$ROOT/docker-compose.yml" up -d --build >"$BUILD_LOG" 2>&1; then
    echo BUILD_FAILED
    tail -n 80 "$BUILD_LOG" || true
    rm -f "$BUILD_LOG"
    exit 1
  fi
else
  echo COMPOSE_MISSING
  rm -f "$BUILD_LOG"
  exit 1
fi
rm -f "$BUILD_LOG"
echo PLATFORM_DEPLOYED
