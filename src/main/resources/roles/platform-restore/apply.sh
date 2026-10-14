set -eu
# VPS Autopilot — restauration des données d’une plateforme (dump SQL + archive de fichiers, ou archive
# files-*.tar.gz.enc des sauvegardes de l’agent, déchiffrée avec la phrase du VPS).
# Variables : PLATFORM_ID RESTORE_DIR RESTORE_SQL RESTORE_UPLOADS UPLOADS_VOLUME UPLOADS_SUBDIR PLATFORM_SERVICES
#             RESTORE_FK_CHECKS (1 par défaut, 0 = clés étrangères non vérifiées pendant l’import)
# Les fichiers ont été envoyés par SFTP dans RESTORE_DIR (supprimé en fin de script).
# Avant d’écraser la base : sauvegarde de l’existant dans $ROOT/backups (jamais de perte silencieuse).
PLATFORM_ID="${PLATFORM_ID:-}"
RESTORE_DIR="${RESTORE_DIR:-}"
RESTORE_SQL="${RESTORE_SQL:-}"
RESTORE_UPLOADS="${RESTORE_UPLOADS:-}"
UPLOADS_VOLUME="${UPLOADS_VOLUME:-}"
UPLOADS_SUBDIR="${UPLOADS_SUBDIR:-}"
PLATFORM_SERVICES="${PLATFORM_SERVICES:-}"
RESTORE_FK_CHECKS="${RESTORE_FK_CHECKS:-1}"
umask 077
if [ "$(id -u)" -eq 0 ]; then
  SUDO=""
else
  SUDO="sudo -n"
  $SUDO true || { echo NEED_PASSWORDLESS_SUDO; exit 1; }
fi

cleanup() {
  case "$RESTORE_DIR" in
    /tmp/autopilot-restore-*) rm -rf "$RESTORE_DIR" ;;
  esac
}
trap cleanup EXIT

case "$RESTORE_DIR" in
  /tmp/autopilot-restore-*) ;;
  *) echo NEED_RESTORE_SPEC; exit 1 ;;
esac
if [ -z "$PLATFORM_ID" ] || { [ -z "$RESTORE_SQL" ] && [ -z "$RESTORE_UPLOADS" ]; }; then
  echo NEED_RESTORE_SPEC
  exit 1
fi
case "$RESTORE_SQL$RESTORE_UPLOADS$UPLOADS_SUBDIR" in
  *..*|*/*) echo BAD_RESTORE_PATH; exit 1 ;;
esac

if ! $SUDO docker info >/dev/null 2>&1; then
  echo DOCKER_MISSING
  exit 1
fi

ROOT=/opt/vps-autopilot/platforms/$PLATFORM_ID
$SUDO mkdir -p "$ROOT/backups"
TS="$(date +%Y%m%d-%H%M%S)"

compose() {
  if [ -f "$ROOT/docker-compose.yml" ]; then
    $SUDO docker compose --project-directory "$ROOT" -f "$ROOT/docker-compose.yml" "$@" >/dev/null 2>&1 || true
  fi
}

# Arrête les services applicatifs (pas le cache) pendant l’import. Ils sont relancés à la sortie, même en cas
# d’échec. .maintenance suspend le garde en ligne (vps-autopilot-keepalive) pendant l’opération.
STOPPED=""
finish() {
  if [ -n "$STOPPED" ]; then
    # shellcheck disable=SC2086
    compose start $PLATFORM_SERVICES
    echo "SERVICES_STARTED=$PLATFORM_SERVICES"
  fi
  $SUDO rm -f "$ROOT/.maintenance"
  cleanup
}
trap finish EXIT
$SUDO touch "$ROOT/.maintenance"
if [ -f "$ROOT/docker-compose.yml" ] && [ -n "$PLATFORM_SERVICES" ]; then
  # shellcheck disable=SC2086
  compose stop $PLATFORM_SERVICES
  STOPPED=1
  echo "SERVICES_STOPPED=$PLATFORM_SERVICES"
fi

if [ -n "$RESTORE_SQL" ]; then
  SQL_FILE="$RESTORE_DIR/$RESTORE_SQL"
  [ -s "$SQL_FILE" ] || { echo RESTORE_FILE_MISSING; echo "FILE=$RESTORE_SQL"; exit 1; }
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
  if id deploy >/dev/null 2>&1; then
    $SUDO chown deploy:deploy "$DB_FILE"
  fi
  ROOT_PW="$($SUDO cat /opt/vps-autopilot/mariadb/root.pw)"
  DB_PW="$($SUDO grep '^DB_PASSWORD=' "$DB_FILE" | cut -d= -f2-)"

  recreate_db() {
    printf '%s\n' \
      "DROP DATABASE IF EXISTS \`${DB_NAME}\`;" \
      "CREATE DATABASE \`${DB_NAME}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" \
      "CREATE USER IF NOT EXISTS '${DB_USER}'@'%' IDENTIFIED BY '${DB_PW}';" \
      "ALTER USER '${DB_USER}'@'%' IDENTIFIED BY '${DB_PW}';" \
      "GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'%';" \
      "FLUSH PRIVILEGES;" \
      | $SUDO docker exec -i mariadb mariadb -uroot -p"$ROOT_PW"
  }

  BK=""
  if printf 'SHOW DATABASES LIKE "%s";' "$DB_NAME" \
      | $SUDO docker exec -i mariadb mariadb -uroot -p"$ROOT_PW" -N 2>/dev/null | grep -qx "$DB_NAME"; then
    BK="$ROOT/backups/${DB_NAME}-before-restore-${TS}.sql.gz"
    set -o pipefail
    if ! $SUDO docker exec mariadb mariadb-dump -uroot -p"$ROOT_PW" --single-transaction --routines --triggers "$DB_NAME" \
        | gzip | $SUDO tee "$BK" >/dev/null; then
      set +o pipefail
      $SUDO rm -f "$BK"
      unset ROOT_PW DB_PW
      echo DB_BACKUP_FAILED
      exit 1
    fi
    set +o pipefail
    $SUDO chmod 600 "$BK"
    echo "DB_BACKUP=$BK"
  fi

  recreate_db

  # MySQL 8 → MariaDB : collations 0900, DEFINER, USE/CREATE DATABASE d’une autre base.
  # RESTORE_FK_CHECKS=0 : clés étrangères non vérifiées pendant l’import (dump où les enfants précèdent les parents,
  # ou données orphelines). Les contraintes sont créées, les lignes existantes ne sont pas contrôlées.
  FK_RULE='s/^//'
  if [ "$RESTORE_FK_CHECKS" = "0" ]; then
    FK_RULE='s/FOREIGN_KEY_CHECKS *= *1/FOREIGN_KEY_CHECKS=0/g'
    echo "FK_CHECKS=off"
  fi
  IMPORT_LOG="$(mktemp)"
  case "$RESTORE_SQL" in
    *.gz) READ="gzip -dc" ;;
    *) READ="cat" ;;
  esac
  if ! { if [ "$RESTORE_FK_CHECKS" = "0" ]; then echo 'SET FOREIGN_KEY_CHECKS=0;'; fi; $READ "$SQL_FILE"; } \
      | sed -E \
          -e 's/utf8mb4_0900_[a-z_]+/utf8mb4_unicode_ci/g' \
          -e 's/DEFINER=`[^`]+`@`[^`]+`//g' \
          -e 's/DEFINER=[^ ]+@[^ ]+ //g' \
          -e '/^(USE|CREATE DATABASE) /d' \
          -e '/^\/\*![0-9]+ (USE|CREATE DATABASE) /d' \
          -e "$FK_RULE" \
      | $SUDO docker exec -i mariadb mariadb -uroot -p"$ROOT_PW" --default-character-set=utf8mb4 "$DB_NAME" \
      >"$IMPORT_LOG" 2>&1; then
    echo SQL_IMPORT_FAILED
    tail -n 20 "$IMPORT_LOG" | grep -v -i password || true
    rm -f "$IMPORT_LOG"
    # Jamais de base à moitié importée : retour à la sauvegarde prise juste avant.
    if [ -n "$BK" ] && $SUDO test -s "$BK"; then
      recreate_db
      if $SUDO gzip -dc "$BK" \
          | $SUDO docker exec -i mariadb mariadb -uroot -p"$ROOT_PW" --default-character-set=utf8mb4 "$DB_NAME" >/dev/null 2>&1; then
        echo "DB_ROLLED_BACK=$BK"
      else
        echo DB_ROLLBACK_FAILED
      fi
    fi
    unset ROOT_PW DB_PW
    exit 1
  fi
  rm -f "$IMPORT_LOG"
  TABLES="$(printf 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema="%s";' "$DB_NAME" \
    | $SUDO docker exec -i mariadb mariadb -uroot -p"$ROOT_PW" -N 2>/dev/null || echo '?')"
  unset ROOT_PW DB_PW
  echo "SQL_RESTORED=$DB_NAME"
  echo "TABLES=$TABLES"
fi

if [ -n "$RESTORE_UPLOADS" ]; then
  ARCHIVE="$RESTORE_DIR/$RESTORE_UPLOADS"
  [ -s "$ARCHIVE" ] || { echo RESTORE_FILE_MISSING; echo "FILE=$RESTORE_UPLOADS"; exit 1; }
  [ -n "$UPLOADS_VOLUME" ] || { echo NEED_UPLOADS_VOLUME; exit 1; }
  $SUDO docker volume inspect "$UPLOADS_VOLUME" >/dev/null 2>&1 || $SUDO docker volume create "$UPLOADS_VOLUME" >/dev/null
  MP="$($SUDO docker volume inspect -f '{{.Mountpoint}}' "$UPLOADS_VOLUME")"
  DEST="$MP"
  [ -n "$UPLOADS_SUBDIR" ] && DEST="$MP/$UPLOADS_SUBDIR"
  UBK=""
  if $SUDO test -d "$DEST" && [ -n "$($SUDO ls -A "$DEST" 2>/dev/null)" ]; then
    UBK="$ROOT/backups/uploads-before-restore-${TS}.tar.gz"
    $SUDO tar -C "$DEST" -czf "$UBK" .
    $SUDO chmod 600 "$UBK"
    echo "UPLOADS_BACKUP=$UBK"
  fi
  $SUDO mkdir -p "$DEST"
  case "$RESTORE_UPLOADS" in
    *.tar.gz.enc)
      # Sauvegarde de l’agent (BIZ-11) : le volume est remis à l’identique (propriétaires et droits compris).
      [ -z "$UPLOADS_SUBDIR" ] || { echo BAD_RESTORE_PATH; exit 1; }
      PASS=/opt/vps-autopilot/backups/.passphrase
      $SUDO test -s "$PASS" || { echo NEED_BACKUP_PASSPHRASE; exit 1; }
      MEMBER="$UPLOADS_VOLUME/_data"
      dec() { $SUDO openssl enc -d -aes-256-cbc -pbkdf2 -pass "file:$PASS" -in "$ARCHIVE"; }
      if dec | tar -tzf - 2>/dev/null | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
        echo BAD_ARCHIVE_PATH
        exit 1
      fi
      $SUDO find "$DEST" -mindepth 1 -delete
      if ! dec | $SUDO tar -C "$DEST" --numeric-owner -p --strip-components=2 -xzf - "$MEMBER"; then
        echo UPLOADS_EXTRACT_FAILED
        $SUDO find "$DEST" -mindepth 1 -delete || true
        if [ -n "$UBK" ]; then
          if $SUDO tar -C "$DEST" --numeric-owner -p -xzf "$UBK"; then
            echo "UPLOADS_ROLLED_BACK=$UBK"
          else
            echo UPLOADS_ROLLBACK_FAILED
          fi
        fi
        exit 1
      fi
      ;;
    *.zip)
      if ! $SUDO python3 -c 'import sys,zipfile; z=zipfile.ZipFile(sys.argv[1]); [sys.exit("BAD_ARCHIVE_PATH") for n in z.namelist() if n.startswith("/") or ".." in n.split("/")]; z.extractall(sys.argv[2])' \
          "$ARCHIVE" "$DEST"; then
        echo UPLOADS_EXTRACT_FAILED
        exit 1
      fi
      ;;
    *.tar.gz|*.tgz|*.tar)
      if tar -tf "$ARCHIVE" 2>/dev/null | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
        echo BAD_ARCHIVE_PATH
        exit 1
      fi
      if ! $SUDO tar -C "$DEST" --no-same-owner -xf "$ARCHIVE"; then
        echo UPLOADS_EXTRACT_FAILED
        exit 1
      fi
      ;;
    *) echo BAD_ARCHIVE_TYPE; exit 1 ;;
  esac
  COUNT="$($SUDO find "$DEST" -type f | wc -l | tr -d ' ')"
  echo "UPLOADS_RESTORED=$UPLOADS_VOLUME${UPLOADS_SUBDIR:+/$UPLOADS_SUBDIR}"
  echo "FILES=$COUNT"
fi

echo PLATFORM_RESTORED
