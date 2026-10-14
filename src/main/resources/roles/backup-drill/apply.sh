set -eu
# VPS Autopilot — exercice de restauration (ADR-041). Chaque base extraite d’une sauvegarde est importée dans une
# base temporaire restore_check_<hex>, ses tables comptées, puis la base temporaire est supprimée.
# Les bases des applications ne sont jamais touchées.
# Variables : DRILL_DIR (/tmp/autopilot-restore-drill-*, fichiers envoyés par SFTP, supprimé en fin de script)
#             DRILL_MANIFEST_B64 : lignes « fichier<TAB>base<TAB>tables attendues »
DRILL_DIR="${DRILL_DIR:-}"
DRILL_MANIFEST_B64="${DRILL_MANIFEST_B64:-}"
umask 077
if [ "$(id -u)" -eq 0 ]; then
  SUDO=""
else
  SUDO="sudo -n"
  $SUDO true || { echo NEED_PASSWORDLESS_SUDO; exit 1; }
fi
case "$DRILL_DIR" in
  /tmp/autopilot-restore-drill-*) ;;
  *) echo NEED_DRILL_SPEC; exit 1 ;;
esac
[ -n "$DRILL_MANIFEST_B64" ] || { echo NEED_DRILL_SPEC; exit 1; }

SCRATCH=""
ROOT_PW=""
sql_root() {
  $SUDO docker exec -i -e MYSQL_PWD="$ROOT_PW" mariadb mariadb -uroot "$@"
}
cleanup() {
  if [ -n "$SCRATCH" ]; then
    printf 'DROP DATABASE IF EXISTS `%s`;\n' "$SCRATCH" | sql_root >/dev/null 2>&1 || true
  fi
  rm -rf "$DRILL_DIR"
}
trap cleanup EXIT

NAMES="$($SUDO docker ps --format '{{.Names}}' 2>/dev/null)" || { echo DOCKER_MISSING; exit 1; }
case "
$NAMES
" in *"
mariadb
"*) ;; *) echo NEED_MARIADB; exit 1 ;; esac
ROOT_PW="$($SUDO cat /opt/vps-autopilot/mariadb/root.pw)"

FAILED=0
MANIFEST="$(printf '%s' "$DRILL_MANIFEST_B64" | base64 -d)"
while IFS="$(printf '\t')" read -r FILE DB EXPECTED || [ -n "${FILE:-}" ]; do
  [ -z "${FILE:-}" ] && continue
  case "$FILE" in *..*|*/*) echo "BAD_DRILL_FILE=$FILE"; exit 1 ;; esac
  [ -s "$DRILL_DIR/$FILE" ] || { echo "DRILL_FILE_MISSING=$FILE"; exit 1; }
  SCRATCH="restore_check_$(openssl rand -hex 4)"
  printf 'CREATE DATABASE `%s` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\n' "$SCRATCH" | sql_root
  LOG="$(mktemp)"
  if gzip -dc "$DRILL_DIR/$FILE" \
      | sed -E -e 's/DEFINER=`[^`]+`@`[^`]+`//g' -e '/^(USE|CREATE DATABASE) /d' \
      | sql_root --default-character-set=utf8mb4 "$SCRATCH" >"$LOG" 2>&1; then
    COUNT="$(printf "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='%s' AND table_type='BASE TABLE';\n" "$SCRATCH" \
      | sql_root -N)"
    echo "DRILL_DB=$DB:$EXPECTED:$COUNT"
  else
    FAILED=1
    echo "DRILL_IMPORT_FAILED=$DB"
    grep -m 3 '^ERROR' "$LOG" | sed 's/^/DRILL_ERR: /' || true
  fi
  rm -f "$LOG"
  printf 'DROP DATABASE IF EXISTS `%s`;\n' "$SCRATCH" | sql_root
  SCRATCH=""
done <<EOF
$MANIFEST
EOF
unset ROOT_PW
[ "$FAILED" = "0" ] || exit 1
echo DRILL_DONE
