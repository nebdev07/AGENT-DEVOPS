set -eu
# VPS Autopilot — exercice de restauration (ADR-041, BIZ-11). Chaque base extraite d’une sauvegarde est importée dans
# une base temporaire restore_check_<hex>, ses tables comptées, puis la base temporaire est supprimée. L’archive des
# fichiers est extraite dans un dossier temporaire de /var/tmp, ses fichiers comptés par volume, puis le dossier
# supprimé. Les bases et volumes des applications ne sont jamais touchés.
# Variables : DRILL_DIR (/tmp/autopilot-restore-drill-*, fichiers envoyés par SFTP, supprimé en fin de script)
#             DRILL_MANIFEST_B64 : lignes « fichier<TAB>base<TAB>tables attendues » (vide = pas de base)
#             DRILL_FILES : archive files-*.tar.gz.enc envoyée (vide = pas de fichiers)
#             DRILL_FILES_MANIFEST_B64 : lignes « volume<TAB>fichiers attendus » ; DRILL_FILES_BYTES : taille extraite
DRILL_DIR="${DRILL_DIR:-}"
DRILL_MANIFEST_B64="${DRILL_MANIFEST_B64:-}"
DRILL_FILES="${DRILL_FILES:-}"
DRILL_FILES_MANIFEST_B64="${DRILL_FILES_MANIFEST_B64:-}"
DRILL_FILES_BYTES="${DRILL_FILES_BYTES:-0}"
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
[ -n "$DRILL_MANIFEST_B64" ] || [ -n "$DRILL_FILES" ] || { echo NEED_DRILL_SPEC; exit 1; }
case "$DRILL_FILES_BYTES" in ''|*[!0-9]*) echo NEED_DRILL_SPEC; exit 1 ;; esac

SCRATCH=""
FSCRATCH=""
ROOT_PW=""
sql_root() {
  $SUDO docker exec -i -e MYSQL_PWD="$ROOT_PW" mariadb mariadb -uroot "$@"
}
cleanup() {
  if [ -n "$SCRATCH" ]; then
    printf 'DROP DATABASE IF EXISTS `%s`;\n' "$SCRATCH" | sql_root >/dev/null 2>&1 || true
  fi
  case "$FSCRATCH" in
    /var/tmp/autopilot-files-drill-*) $SUDO rm -rf "$FSCRATCH" ;;
  esac
  rm -rf "$DRILL_DIR"
}
trap cleanup EXIT

FAILED=0

if [ -n "$DRILL_MANIFEST_B64" ]; then
  NAMES="$($SUDO docker ps --format '{{.Names}}' 2>/dev/null)" || { echo DOCKER_MISSING; exit 1; }
  case "
$NAMES
" in *"
mariadb
"*) ;; *) echo NEED_MARIADB; exit 1 ;; esac
  ROOT_PW="$($SUDO cat /opt/vps-autopilot/mariadb/root.pw)"

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
fi

if [ -n "$DRILL_FILES" ]; then
  case "$DRILL_FILES" in *..*|*/*) echo "BAD_DRILL_FILE=$DRILL_FILES"; exit 1 ;; esac
  [ -s "$DRILL_DIR/$DRILL_FILES" ] || { echo "DRILL_FILE_MISSING=$DRILL_FILES"; exit 1; }
  PASS=/opt/vps-autopilot/backups/.passphrase
  $SUDO test -s "$PASS" || { echo NEED_BACKUP_PASSPHRASE; exit 1; }
  # Place exigée : taille extraite + 10 % + 100 Mo de marge.
  NEED=$((DRILL_FILES_BYTES + DRILL_FILES_BYTES / 10 + 104857600))
  AVAIL="$(df -P -B1 /var/tmp | awk 'NR==2 {print $4}')"
  if [ "$AVAIL" -lt "$NEED" ]; then
    FAILED=1
    echo "DRILL_FILES_NO_SPACE=$NEED:$AVAIL"
  else
    FSCRATCH="$($SUDO mktemp -d /var/tmp/autopilot-files-drill-XXXXXX)"
    if $SUDO openssl enc -d -aes-256-cbc -pbkdf2 -pass "file:$PASS" -in "$DRILL_DIR/$DRILL_FILES" \
        | $SUDO tar -C "$FSCRATCH" --numeric-owner -xzf -; then
      FMANIFEST="$(printf '%s' "$DRILL_FILES_MANIFEST_B64" | base64 -d)"
      while IFS="$(printf '\t')" read -r VOL EXPECTED || [ -n "${VOL:-}" ]; do
        [ -z "${VOL:-}" ] && continue
        case "$VOL" in *..*|*/*) echo "BAD_DRILL_FILE=$VOL"; exit 1 ;; esac
        COUNT="$($SUDO find "$FSCRATCH/$VOL/_data" -type f 2>/dev/null | wc -l | tr -d ' ')"
        echo "DRILL_VOL=$VOL:$EXPECTED:$COUNT"
      done <<EOF
$FMANIFEST
EOF
    else
      FAILED=1
      echo DRILL_FILES_EXTRACT_FAILED
    fi
    $SUDO rm -rf "$FSCRATCH"
    FSCRATCH=""
  fi
fi

[ "$FAILED" = "0" ] || exit 1
echo DRILL_DONE
