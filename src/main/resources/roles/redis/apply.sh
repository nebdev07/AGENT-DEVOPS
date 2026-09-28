set -eu
# VPS Autopilot — Redis en conteneur. Réseau data uniquement. Pas de port public.
APPLY_INTERNAL="${APPLY_INTERNAL:-1}"
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
  $SUDO docker ps --format '{{.Names}}' 2>/dev/null | grep -qx redis
}

if [ "$APPLY_INTERNAL" != "1" ]; then
  if container_running; then
    echo REDIS_APPLIED
    exit 0
  fi
  echo NEED_INTERNAL
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
if ! $SUDO docker compose version >/dev/null 2>&1 && ! command -v docker-compose >/dev/null 2>&1; then
  $SUDO apt-get update -y
  $SUDO apt-get install -y docker-compose-v2 || $SUDO apt-get install -y docker-compose
fi

DIR=/opt/vps-autopilot/redis
$SUDO mkdir -p "$DIR"
if ! $SUDO test -s "$DIR/redis.pw"; then
  PW="$($SUDO openssl rand -hex 16)"
  printf '%s' "$PW" | $SUDO tee "$DIR/redis.pw" >/dev/null
  $SUDO chmod 600 "$DIR/redis.pw"
fi
$SUDO chmod 600 "$DIR/redis.pw"
REDIS_PW="$($SUDO cat "$DIR/redis.pw")"

$SUDO docker network inspect data >/dev/null 2>&1 || $SUDO docker network create data

$SUDO tee "$DIR/redis.conf" >/dev/null <<EOF
bind 0.0.0.0
protected-mode yes
port 6379
requirepass ${REDIS_PW}
appendonly yes
EOF
$SUDO chmod 644 "$DIR/redis.conf"
unset REDIS_PW

$SUDO tee "$DIR/docker-compose.yml" >/dev/null <<'EOF'
services:
  redis:
    image: redis:7.4-alpine
    container_name: redis
    restart: unless-stopped
    command: ["redis-server", "/etc/redis/redis.conf"]
    volumes:
      - ./redis.conf:/etc/redis/redis.conf:ro
      - redis_data:/data
    networks:
      - data
networks:
  data:
    external: true
volumes:
  redis_data:
EOF

if $SUDO docker compose version >/dev/null 2>&1; then
  $SUDO docker compose --project-directory "$DIR" -f "$DIR/docker-compose.yml" up -d
elif command -v docker-compose >/dev/null 2>&1; then
  $SUDO docker-compose --project-directory "$DIR" -f "$DIR/docker-compose.yml" up -d
else
  echo COMPOSE_MISSING
  exit 1
fi

PW="$($SUDO cat "$DIR/redis.pw")"
n=0
until $SUDO docker exec redis redis-cli -a "$PW" --no-auth-warning ping 2>/dev/null | grep -qx PONG; do
  n=$((n + 1))
  if [ "$n" -gt 36 ]; then
    echo REDIS_NOT_READY
    exit 1
  fi
  sleep 2
done
unset PW

echo REDIS_SECRET="$DIR/redis.pw"
echo REDIS_APPLIED
