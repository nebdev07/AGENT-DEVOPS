#!/usr/bin/env bash
set -eu
# One-shot prod deploy my-bourse on VPS Autopilot host
APP_SLUG=my-bourse
APP_GIT=https://github.com/nebdev07/BOURSE.git
APP_REF=main
APP_HOST=my-bourse.apps.nebdev.org
APP_PORT=3000
ROOT=/opt/vps-autopilot/apps/$APP_SLUG
SRC="$ROOT/src"

if [ "$(id -u)" -eq 0 ]; then SUDO=""; else SUDO="sudo -n"; fi
$SUDO true
# Toujours via sudo -u deploy (même en root) pour éviter « -u: command not found ».
as_deploy() { sudo -u deploy "$@"; }

# MariaDB must be present
if ! $SUDO docker ps --format '{{.Names}}' | grep -qx mariadb; then
  echo "INSTALL_MARIADB"
  DIR=/opt/vps-autopilot/mariadb
  $SUDO mkdir -p "$DIR"
  if ! $SUDO test -s "$DIR/root.pw"; then
    ROOT_PW="$($SUDO openssl rand -hex 24)"
    printf '%s' "$ROOT_PW" | $SUDO tee "$DIR/root.pw" >/dev/null
    $SUDO chmod 600 "$DIR/root.pw"
  else
    ROOT_PW="$($SUDO cat "$DIR/root.pw")"
  fi
  printf 'MARIADB_ROOT_PASSWORD=%s\n' "$ROOT_PW" | $SUDO tee "$DIR/.env" >/dev/null
  $SUDO tee "$DIR/docker-compose.yml" >/dev/null <<'EOF'
services:
  mariadb:
    image: mariadb:11.4
    container_name: mariadb
    restart: unless-stopped
    environment:
      MARIADB_ROOT_PASSWORD: ${MARIADB_ROOT_PASSWORD}
    volumes:
      - mariadb_data:/var/lib/mysql
    networks:
      - data
    healthcheck:
      test: ["CMD", "healthcheck.sh", "--connect", "--innodb_initialized"]
      interval: 5s
      timeout: 5s
      retries: 30
volumes:
  mariadb_data:
networks:
  data:
    external: true
EOF
  $SUDO docker network inspect data >/dev/null 2>&1 || $SUDO docker network create data
  if $SUDO docker compose version >/dev/null 2>&1; then
    $SUDO docker compose --project-directory "$DIR" --env-file "$DIR/.env" -f "$DIR/docker-compose.yml" up -d
  else
    $SUDO docker-compose --project-directory "$DIR" --env-file "$DIR/.env" -f "$DIR/docker-compose.yml" up -d
  fi
  for i in $(seq 1 60); do
    if $SUDO docker exec mariadb healthcheck.sh --connect --innodb_initialized >/dev/null 2>&1; then
      break
    fi
    sleep 2
  done
fi

$SUDO mkdir -p "$ROOT"
$SUDO chown deploy:deploy "$ROOT"

if [ -d "$SRC/.git" ]; then
  as_deploy git -C "$SRC" fetch --depth 1 origin "$APP_REF"
  as_deploy git -C "$SRC" checkout -f "$APP_REF" || as_deploy git -C "$SRC" reset --hard "origin/$APP_REF"
  as_deploy git -C "$SRC" pull --ff-only origin "$APP_REF" || true
else
  $SUDO rm -rf "$SRC"
  as_deploy git clone --depth 1 --branch "$APP_REF" "$APP_GIT" "$SRC"
fi

if [ ! -f "$SRC/Dockerfile" ]; then
  echo NEED_DOCKERFILE
  exit 1
fi

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
else
  DB_PW="$($SUDO grep '^DB_PASSWORD=' "$DB_FILE" | cut -d= -f2-)"
  if ! $SUDO grep -q '^DATABASE_URL=' "$DB_FILE"; then
    $SUDO tee -a "$DB_FILE" >/dev/null <<ENV
PERSISTENCE_DRIVER=mysql
DATABASE_URL=mysql://${DB_USER}:${DB_PW}@mariadb:3306/${DB_NAME}
ENV
  fi
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

APP_ENV_FILE="$ROOT/app.env"
if ! $SUDO test -s "$APP_ENV_FILE"; then
  JOB_SECRET="$($SUDO openssl rand -hex 32)"
  $SUDO tee "$APP_ENV_FILE" >/dev/null <<ENV
NODE_ENV=production
PERSISTENCE_DRIVER=mysql
ENABLE_ADMIN_PORTFOLIO_EXAMPLE=0
JOB_SECRET=${JOB_SECRET}
ADMIN_EMAIL=admin@nebdev.org
ENV
  $SUDO chmod 600 "$APP_ENV_FILE"
  unset JOB_SECRET
fi
$SUDO chown deploy:deploy "$APP_ENV_FILE"

$SUDO docker network inspect proxy >/dev/null 2>&1 || { echo NEED_TRAEFIK; exit 1; }
$SUDO docker network inspect data >/dev/null 2>&1 || $SUDO docker network create data

$SUDO tee "$ROOT/docker-compose.yml" >/dev/null <<EOF
services:
  app:
    build: ./src
    container_name: app-${APP_SLUG}
    restart: unless-stopped
    env_file:
      - ./app.env
      - ./db.env
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
$SUDO chown deploy:deploy "$ROOT/docker-compose.yml"

BUILD_LOG="$(mktemp)"
set +e
if $SUDO docker compose version >/dev/null 2>&1; then
  $SUDO docker compose --project-directory "$ROOT" -f "$ROOT/docker-compose.yml" up -d --build >"$BUILD_LOG" 2>&1
  RC=$?
else
  $SUDO docker-compose --project-directory "$ROOT" -f "$ROOT/docker-compose.yml" up -d --build >"$BUILD_LOG" 2>&1
  RC=$?
fi
set -e
if [ "$RC" -ne 0 ]; then
  echo BUILD_FAILED
  tail -n 80 "$BUILD_LOG" || true
  rm -f "$BUILD_LOG"
  exit 1
fi
rm -f "$BUILD_LOG"
echo APP_DEPLOYED
echo "URL=https://${APP_HOST}/"
