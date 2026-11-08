set -eu
# VPS Autopilot — plateforme N services. Compose généré par l’agent. Pas de port public.
# Variables : PLATFORM_ID PLATFORM_COMPOSE_B64 PLATFORM_MANIFEST_B64 PLATFORM_NEED_DB PLATFORM_NEED_REDIS
#             PLATFORM_RETAIN_SOURCES (1=garder src/ après build, 0=purger — défaut 1)
#             PLATFORM_ONLY_SERVICE (optionnel : id d’un seul service à cloner/builder — V7-07)
#             PLATFORM_FILES_B64 (Dockerfile de modèle, env) GIT_KEYS_B64 (une clé lecture seule par dépôt)
# Manifeste : sid<TAB>git<TAB>ref<TAB>context<TAB>template<TAB>keyId — « - » = vide.
PLATFORM_ID="${PLATFORM_ID:-}"
PLATFORM_COMPOSE_B64="${PLATFORM_COMPOSE_B64:-}"
PLATFORM_MANIFEST_B64="${PLATFORM_MANIFEST_B64:-}"
PLATFORM_NEED_DB="${PLATFORM_NEED_DB:-0}"
PLATFORM_NEED_REDIS="${PLATFORM_NEED_REDIS:-0}"
PLATFORM_RETAIN_SOURCES="${PLATFORM_RETAIN_SOURCES:-1}"
PLATFORM_ONLY_SERVICE="${PLATFORM_ONLY_SERVICE:-}"
umask 022
# true si on traite ce service (plein déploiement ou cible unique)
want_service() {
  if [ -z "$PLATFORM_ONLY_SERVICE" ]; then
    return 0
  fi
  [ "$1" = "$PLATFORM_ONLY_SERVICE" ]
}
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

KEYDIR=/home/deploy/.ssh
# Une clé par dépôt (GitHub refuse une même Deploy key sur deux dépôts) : lignes « id<TAB>base64 ».
setup_git_keys() {
  [ -z "${GIT_KEYS_B64:-}" ] && return 0
  $SUDO mkdir -p "$KEYDIR"
  printf '%s' "$GIT_KEYS_B64" | base64 -d | while IFS="$(printf '\t')" read -r KID KB64 || [ -n "${KID:-}" ]; do
    [ -z "${KID:-}" ] && continue
    case "$KID" in *[!a-z0-9.-]*) echo "BAD_KEY_ID=$KID"; exit 1 ;; esac
    printf '%s' "$KB64" | base64 -d | $SUDO tee "$KEYDIR/git-$KID" >/dev/null
    $SUDO chmod 600 "$KEYDIR/git-$KID"
  done
  $SUDO chmod 700 "$KEYDIR"
  $SUDO chown -R deploy:deploy "$KEYDIR"
}

# git_as_deploy <keyId|-> args…
git_as_deploy() {
  KID="$1"
  shift
  if [ -n "$KID" ] && [ "$KID" != "-" ]; then
    $SUDO -u deploy env GIT_SSH_COMMAND="ssh -i $KEYDIR/git-$KID -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new" git "$@"
  else
    $SUDO -u deploy git "$@"
  fi
}

require_git_key_if_needed() {
  case "$2" in
    git@*|ssh://git@*)
      if [ -z "$3" ] || [ "$3" = "-" ] || ! $SUDO test -s "$KEYDIR/git-$3"; then
        echo NEED_GIT_DEPLOY_KEY
        echo "SERVICE=$1"
        exit 1
      fi
      ;;
  esac
}

undash() {
  [ "$1" = "-" ] && printf '' || printf '%s' "$1"
}

setup_git_keys

$SUDO docker network inspect proxy >/dev/null 2>&1 || { echo NEED_TRAEFIK; exit 1; }
if [ "$PLATFORM_NEED_DB" = "1" ] || [ "$PLATFORM_NEED_REDIS" = "1" ]; then
  $SUDO docker network inspect data >/dev/null 2>&1 || { echo NEED_DATA_NETWORK; exit 1; }
fi

ROOT=/opt/vps-autopilot/platforms/$PLATFORM_ID
# platforms doit être traversable par deploy (sinon git clone → « Permission denied » sur …/src/<svc>).
$SUDO mkdir -p /opt/vps-autopilot/platforms "$ROOT/src"
$SUDO chown deploy:deploy /opt/vps-autopilot/platforms
$SUDO chmod 755 /opt/vps-autopilot/platforms
$SUDO chown -R deploy:deploy "$ROOT"

# clone_one <sid> <git> <ref> <keyId|->
clone_one() {
  SID="$1"
  SGIT="$2"
  SREF="${3:-main}"
  SKEY="${4:--}"
  require_git_key_if_needed "$SID" "$SGIT" "$SKEY"
  SRC="$ROOT/src/$SID"
  CLONE_LOG="$(mktemp)"
  if [ -d "$SRC/.git" ]; then
    if ! git_as_deploy "$SKEY" -C "$SRC" fetch --depth 1 origin "$SREF" >"$CLONE_LOG" 2>&1; then
      echo GIT_CLONE_FAILED
      echo "SERVICE=$SID"
      echo "GIT_URL=$SGIT"
      echo "GIT_REF=$SREF"
      tail -n 20 "$CLONE_LOG" || true
      rm -f "$CLONE_LOG"
      exit 1
    fi
    git_as_deploy "$SKEY" -C "$SRC" checkout -f FETCH_HEAD >>"$CLONE_LOG" 2>&1
  else
    $SUDO rm -rf "$SRC"
    $SUDO mkdir -p "$ROOT/src"
    $SUDO chown -R deploy:deploy "$ROOT"
    if ! git_as_deploy "$SKEY" clone --depth 1 --branch "$SREF" "$SGIT" "$SRC" >"$CLONE_LOG" 2>&1; then
      if ! git_as_deploy "$SKEY" clone --depth 1 "$SGIT" "$SRC" >>"$CLONE_LOG" 2>&1; then
        echo GIT_CLONE_FAILED
        echo "SERVICE=$SID"
        echo "GIT_URL=$SGIT"
        echo "GIT_REF=$SREF"
        tail -n 20 "$CLONE_LOG" || true
        rm -f "$CLONE_LOG"
        exit 1
      fi
      git_as_deploy "$SKEY" -C "$SRC" checkout "$SREF" || true
    fi
  fi
  rm -f "$CLONE_LOG"
  echo "CLONED=$SID@$(git_as_deploy - -C "$SRC" rev-parse --short HEAD 2>/dev/null || echo '?')"
}

MANIFEST="$(printf '%s' "$PLATFORM_MANIFEST_B64" | base64 -d)"
if [ -n "$PLATFORM_ONLY_SERVICE" ]; then
  FOUND_ONLY=0
  while IFS="$(printf '\t')" read -r SID SGIT SREF SCTX STPL SKEY || [ -n "${SID:-}" ]; do
    [ -z "${SID:-}" ] && continue
    if [ "$SID" = "$PLATFORM_ONLY_SERVICE" ]; then
      FOUND_ONLY=1
      break
    fi
  done <<EOF
$MANIFEST
EOF
  if [ "$FOUND_ONLY" != "1" ]; then
    echo NEED_SERVICE
    echo "SERVICE=$PLATFORM_ONLY_SERVICE"
    exit 1
  fi
  echo "ONLY_SERVICE=$PLATFORM_ONLY_SERVICE"
fi
while IFS="$(printf '\t')" read -r SID SGIT SREF SCTX STPL SKEY || [ -n "${SID:-}" ]; do
  [ -z "${SID:-}" ] && continue
  want_service "$SID" || continue
  clone_one "$SID" "$SGIT" "$SREF" "${SKEY:--}"
done <<EOF
$MANIFEST
EOF

# Fichiers posés par l’agent : Dockerfile de modèle, entrypoint, svc-<id>.env (régénéré à chaque déploiement).
if [ -n "${PLATFORM_FILES_B64:-}" ]; then
  FILES="$(printf '%s' "$PLATFORM_FILES_B64" | base64 -d)"
  while IFS="$(printf '\t')" read -r FSID FPATH FB64 || [ -n "${FSID:-}" ]; do
    [ -z "${FSID:-}" ] && continue
    want_service "$FSID" || continue
    case "$FSID$FPATH" in *..*) echo "BAD_FILE_PATH=$FSID/$FPATH"; exit 1 ;; esac
    if [ "$FPATH" = "@env" ]; then
      DEST="$ROOT/svc-${FSID}.env"
      printf '%s' "$FB64" | base64 -d | $SUDO tee "$DEST" >/dev/null
      $SUDO chmod 600 "$DEST"
    else
      DEST="$ROOT/src/$FSID/$FPATH"
      $SUDO mkdir -p "$(dirname "$DEST")"
      printf '%s' "$FB64" | base64 -d | $SUDO tee "$DEST" >/dev/null
      case "$DEST" in *.sh) $SUDO chmod 755 "$DEST" ;; esac
    fi
    $SUDO chown deploy:deploy "$DEST"
  done <<EOF
$FILES
EOF
fi

while IFS="$(printf '\t')" read -r SID SGIT SREF SCTX STPL SKEY || [ -n "${SID:-}" ]; do
  [ -z "${SID:-}" ] && continue
  want_service "$SID" || continue
  SCTX="$(undash "${SCTX:--}")"
  STPL="$(undash "${STPL:--}")"
  DIR="$ROOT/src/$SID"
  [ -n "$SCTX" ] && DIR="$DIR/$SCTX"
  DF="$DIR/Dockerfile"
  [ -n "$STPL" ] && DF="$DIR/Dockerfile.autopilot"
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
    "CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" \
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

while IFS="$(printf '\t')" read -r SID SGIT SREF SCTX STPL SKEY || [ -n "${SID:-}" ]; do
  [ -z "${SID:-}" ] && continue
  want_service "$SID" || continue
  ENVF="$ROOT/svc-${SID}.env"
  if ! $SUDO test -s "$ENVF"; then
    printf 'NODE_ENV=production\n' | $SUDO tee "$ENVF" >/dev/null
    $SUDO chmod 600 "$ENVF"
  fi
  # Redis partagé seulement si le service n’a pas déjà un REDIS_HOST (ex. Redis privé platform-cache).
  if [ -n "$REDIS_LINE" ] && ! $SUDO grep -q '^REDIS_HOST=' "$ENVF"; then
    printf '%s\n' "$REDIS_LINE" | $SUDO tee -a "$ENVF" >/dev/null
  fi
  LOCALF="$ROOT/svc-${SID}.local.env"
  $SUDO test -f "$LOCALF" || { $SUDO touch "$LOCALF"; $SUDO chmod 600 "$LOCALF"; }
  $SUDO chown deploy:deploy "$ENVF" "$LOCALF"
done <<EOF
$MANIFEST
EOF

printf '%s' "$PLATFORM_COMPOSE_B64" | base64 -d | $SUDO -u deploy tee "$ROOT/docker-compose.yml" >/dev/null
$SUDO chown deploy:deploy "$ROOT/docker-compose.yml"

BUILD_ARGS=""
if [ -n "$PLATFORM_ONLY_SERVICE" ]; then
  BUILD_ARGS="$PLATFORM_ONLY_SERVICE"
fi
BUILD_LOG="$(mktemp)"
# Marqueur pour le suivi cockpit (sinon JOB_TAIL reste bloqué sur DB_GRANT pendant tout le build).
echo BUILD_START
if $SUDO docker compose version >/dev/null 2>&1; then
  # shellcheck disable=SC2086
  if ! $SUDO docker compose --project-directory "$ROOT" -f "$ROOT/docker-compose.yml" up -d --build $BUILD_ARGS >"$BUILD_LOG" 2>&1; then
    echo BUILD_FAILED
    if [ -n "$PLATFORM_ONLY_SERVICE" ]; then
      echo "BUILD_FAILED_SERVICE=$PLATFORM_ONLY_SERVICE"
    else
      # Dernière cible BuildKit « > [svc …] » = service en échec le plus probable.
      FAILED_SVC="$(grep -Eo '> \[[a-z][a-z0-9-]{0,31} ' "$BUILD_LOG" 2>/dev/null | tail -n 1 | sed -E 's/^> \[//;s/ $//' || true)"
      if [ -n "$FAILED_SVC" ]; then
        echo "BUILD_FAILED_SERVICE=$FAILED_SVC"
      fi
    fi
    tail -n 80 "$BUILD_LOG" || true
    rm -f "$BUILD_LOG"
    exit 1
  fi
elif command -v docker-compose >/dev/null 2>&1; then
  # shellcheck disable=SC2086
  if ! $SUDO docker-compose --project-directory "$ROOT" -f "$ROOT/docker-compose.yml" up -d --build $BUILD_ARGS >"$BUILD_LOG" 2>&1; then
    echo BUILD_FAILED
    if [ -n "$PLATFORM_ONLY_SERVICE" ]; then
      echo "BUILD_FAILED_SERVICE=$PLATFORM_ONLY_SERVICE"
    fi
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

# Sources Git : option plateforme. 1 = copie locale de secours ; 0 = plus de .java/.tsx ni .git sur le disque.
# Les images Docker et svc-*.env / docker-compose.yml restent. Au prochain déploiement, re-clone.
# Jamais de purge globale si redeploy d’un seul service (PLATFORM_ONLY_SERVICE).
if [ -n "$PLATFORM_ONLY_SERVICE" ]; then
  echo SOURCES_RETAINED
  echo "ONLY_BUILT=$PLATFORM_ONLY_SERVICE"
elif [ "$PLATFORM_RETAIN_SOURCES" = "0" ]; then
  $SUDO rm -rf "$ROOT/src"
  $SUDO mkdir -p "$ROOT/src"
  $SUDO chown deploy:deploy "$ROOT/src"
  echo SOURCES_PURGED
else
  echo SOURCES_RETAINED
fi

# Garde en ligne : toutes les 2 min (et au démarrage), relance les conteneurs de plateforme arrêtés.
# restart: unless-stopped ne relance pas un conteneur arrêté par « docker stop » ou un script interrompu.
# Un fichier .maintenance de moins de 2 h dans le dossier d’une plateforme la met en pause (restauration…).
if command -v systemctl >/dev/null 2>&1; then
  $SUDO tee /usr/local/bin/vps-autopilot-keepalive >/dev/null <<'KEEP'
#!/bin/sh
# VPS Autopilot — garde en ligne des plateformes (installé par le rôle platform).
for f in /opt/vps-autopilot/platforms/*/docker-compose.yml; do
  [ -f "$f" ] || continue
  d="$(dirname "$f")"
  if [ -n "$(find "$d" -maxdepth 1 -name .maintenance -mmin -120 2>/dev/null)" ]; then
    continue
  fi
  down="$(docker compose --project-directory "$d" -f "$f" ps -a --status exited --status created -q 2>/dev/null)"
  [ -n "$down" ] || continue
  if docker compose --project-directory "$d" -f "$f" up -d --no-build --no-recreate >/dev/null 2>&1; then
    logger -t vps-autopilot-keepalive "services relancés : $d"
  else
    logger -t vps-autopilot-keepalive "échec de relance : $d"
  fi
done
KEEP
  $SUDO chmod 755 /usr/local/bin/vps-autopilot-keepalive
  printf '%s\n' '[Unit]' 'Description=VPS Autopilot - relance les plateformes arretees' 'After=docker.service' \
    '[Service]' 'Type=oneshot' 'ExecStart=/usr/local/bin/vps-autopilot-keepalive' \
    | $SUDO tee /etc/systemd/system/vps-autopilot-keepalive.service >/dev/null
  printf '%s\n' '[Unit]' 'Description=VPS Autopilot - garde en ligne toutes les 2 min' \
    '[Timer]' 'OnBootSec=2min' 'OnUnitActiveSec=2min' '[Install]' 'WantedBy=timers.target' \
    | $SUDO tee /etc/systemd/system/vps-autopilot-keepalive.timer >/dev/null
  $SUDO systemctl daemon-reload
  if $SUDO systemctl enable --now vps-autopilot-keepalive.timer >/dev/null 2>&1; then
    echo KEEPALIVE=on
  else
    echo KEEPALIVE=failed
  fi
fi
echo PLATFORM_DEPLOYED
