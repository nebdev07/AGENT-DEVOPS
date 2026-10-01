set -eu
# VPS Autopilot — console SQL : exécute un script dans la base de la plateforme, avec son propre utilisateur.
# Variables : PLATFORM_ID SQL_DIR SQL_MODE (read|write)
# Le script (déjà filtré par l’agent) a été envoyé par SFTP dans SQL_DIR/script.sql (supprimé en fin de script).
# write : la base est sauvegardée dans $ROOT/backups avant exécution (20 dernières gardées).
# --binary-mode : le client mariadb n’interprète aucune commande client (system, source…).
PLATFORM_ID="${PLATFORM_ID:-}"
SQL_DIR="${SQL_DIR:-}"
SQL_MODE="${SQL_MODE:-}"
MAX_OUTPUT_BYTES=4000000
umask 077
if [ "$(id -u)" -eq 0 ]; then
  SUDO=""
else
  SUDO="sudo -n"
  $SUDO true || { echo NEED_PASSWORDLESS_SUDO; exit 1; }
fi

cleanup() {
  case "$SQL_DIR" in
    /tmp/autopilot-sql-*) rm -rf "$SQL_DIR" ;;
  esac
}
trap cleanup EXIT

case "$SQL_DIR" in
  /tmp/autopilot-sql-*) ;;
  *) echo NEED_SQL_SPEC; exit 1 ;;
esac
case "$SQL_MODE" in
  read|write) ;;
  *) echo NEED_SQL_SPEC; exit 1 ;;
esac
[ -n "$PLATFORM_ID" ] && [ -s "$SQL_DIR/script.sql" ] || { echo NEED_SQL_SPEC; exit 1; }

if ! $SUDO docker ps --format '{{.Names}}' 2>/dev/null | grep -qx mariadb; then
  echo NEED_MARIADB
  exit 1
fi
ROOT=/opt/vps-autopilot/platforms/$PLATFORM_ID
DB_FILE="$ROOT/db.env"
if ! $SUDO test -s "$DB_FILE"; then
  echo NEED_DB_ENV
  exit 1
fi
DB_NAME="$($SUDO grep '^DB_NAME=' "$DB_FILE" | cut -d= -f2-)"
DB_USER="$($SUDO grep '^DB_USER=' "$DB_FILE" | cut -d= -f2-)"
DB_PW="$($SUDO grep '^DB_PASSWORD=' "$DB_FILE" | cut -d= -f2-)"
[ -n "$DB_NAME" ] && [ -n "$DB_USER" ] && [ -n "$DB_PW" ] || { echo NEED_DB_ENV; exit 1; }
echo "SQL_DB=$DB_NAME"

if [ "$SQL_MODE" = "write" ]; then
  TS="$(date +%Y%m%d-%H%M%S)"
  $SUDO mkdir -p "$ROOT/backups"
  BK="$ROOT/backups/${DB_NAME}-before-sql-${TS}.sql.gz"
  ROOT_PW="$($SUDO cat /opt/vps-autopilot/mariadb/root.pw)"
  set -o pipefail
  if ! $SUDO docker exec -e MYSQL_PWD="$ROOT_PW" mariadb mariadb-dump -uroot --single-transaction --routines --triggers "$DB_NAME" \
      | gzip | $SUDO tee "$BK" >/dev/null; then
    set +o pipefail
    unset ROOT_PW DB_PW
    $SUDO rm -f "$BK"
    echo SQL_BACKUP_FAILED
    exit 1
  fi
  set +o pipefail
  unset ROOT_PW
  $SUDO chmod 600 "$BK"
  echo "SQL_BACKUP=$BK"
  $SUDO find "$ROOT/backups" -maxdepth 1 -name "${DB_NAME}-before-sql-*.sql.gz" -printf '%T@ %p\n' \
    | sort -rn | tail -n +21 | cut -d' ' -f2- | while read -r OLD; do $SUDO rm -f "$OLD"; done
fi

OUT="$(mktemp)"
ERR="$(mktemp)"
if $SUDO docker exec -i -e MYSQL_PWD="$DB_PW" mariadb \
    mariadb --binary-mode --xml --default-character-set=utf8mb4 -u"$DB_USER" "$DB_NAME" \
    <"$SQL_DIR/script.sql" >"$OUT" 2>"$ERR"; then
  STATUS=ok
else
  STATUS=failed
fi
unset DB_PW
echo "SQL_OUTPUT_BYTES=$(wc -c <"$OUT" | tr -d ' ')"
echo "###SQL_BEGIN###"
head -c "$MAX_OUTPUT_BYTES" "$OUT"
echo
echo "###SQL_END###"
if [ "$STATUS" = failed ]; then
  echo SQL_FAILED
  grep -v -i password "$ERR" | tail -n 5 | sed 's/^/SQL_ERROR=/' || true
fi
rm -f "$OUT" "$ERR"
echo SQL_DONE
