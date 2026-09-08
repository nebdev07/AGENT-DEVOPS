set -eu
# VPS Autopilot — MariaDB en conteneur. Pas de port public. N’applique que APPLY_*=1.
APPLY_INSTANCE="${APPLY_INSTANCE:-1}"
APPLY_PER_PROJECT="${APPLY_PER_PROJECT:-1}"
umask 077
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

container_running() {
  $SUDO docker ps --format '{{.Names}}' 2>/dev/null | grep -qx mariadb
}

if [ "$APPLY_INSTANCE" != "1" ]; then
  if container_running; then
    echo MARIADB_APPLIED
    exit 0
  fi
  echo NEED_INSTANCE
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
if ! $SUDO docker compose version >/dev/null 2>&1 && ! command -v docker-compose >/dev/null 2>&1; then
  $SUDO apt-get update -y
  $SUDO apt-get install -y docker-compose-v2 || $SUDO apt-get install -y docker-compose
fi

DIR=/opt/vps-autopilot/mariadb
$SUDO mkdir -p "$DIR"
if ! $SUDO test -s "$DIR/root.pw"; then
  PW="$($SUDO openssl rand -hex 16)"
  printf '%s' "$PW" | $SUDO tee "$DIR/root.pw" >/dev/null
  $SUDO chmod 600 "$DIR/root.pw"
fi
$SUDO chmod 600 "$DIR/root.pw"
ROOT_PW="$($SUDO cat "$DIR/root.pw")"
printf 'MARIADB_ROOT_PASSWORD=%s\n' "$ROOT_PW" | $SUDO tee "$DIR/.env" >/dev/null
$SUDO chmod 600 "$DIR/.env"
unset ROOT_PW

$SUDO docker network inspect data >/dev/null 2>&1 || $SUDO docker network create data

$SUDO tee "$DIR/docker-compose.yml" >/dev/null <<'EOF'
services:
  mariadb:
    image: mariadb:11.4
    container_name: mariadb
    restart: unless-stopped
    env_file: .env
    volumes:
      - mariadb_data:/var/lib/mysql
    networks:
      - data
    healthcheck:
      test: ["CMD", "healthcheck.sh", "--connect", "--innodb_initialized"]
      interval: 5s
      timeout: 5s
      retries: 12
networks:
  data:
    external: true
volumes:
  mariadb_data:
EOF

compose_up() {
  if $SUDO docker compose version >/dev/null 2>&1; then
    $SUDO docker compose --project-directory "$DIR" --env-file "$DIR/.env" -f "$DIR/docker-compose.yml" up -d
  elif command -v docker-compose >/dev/null 2>&1; then
    $SUDO docker-compose --project-directory "$DIR" --env-file "$DIR/.env" -f "$DIR/docker-compose.yml" up -d
  else
    echo COMPOSE_MISSING
    exit 1
  fi
}

compose_up

n=0
until $SUDO docker exec mariadb healthcheck.sh --connect --innodb_initialized >/dev/null 2>&1; do
  n=$((n + 1))
  if [ "$n" -gt 36 ]; then
    echo MARIADB_NOT_READY
    exit 1
  fi
  sleep 5
done

if [ "$APPLY_PER_PROJECT" = "1" ]; then
  echo GRANTS_AT_DEPLOY
fi
echo MARIADB_SECRET="$DIR/root.pw"
echo MARIADB_APPLIED
