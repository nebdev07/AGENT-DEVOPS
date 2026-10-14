set -eu
# VPS Autopilot — sauvegardes chiffrées (ADR-034, ADR-041). Copie hors-site = poste opérateur (Java).
# Installe /usr/local/bin/vps-autopilot-backup + minuteur quotidien, puis fait une première sauvegarde.
# Variables : BACKUP_PASSPHRASE (32 hex, state/backup.key de l’opérateur), APPLY_ENCRYPTED_DUMP,
#             APPLY_OFFSITE, APPLY_RESTORE_PROOF.
APPLY_ENCRYPTED_DUMP="${APPLY_ENCRYPTED_DUMP:-1}"
APPLY_OFFSITE="${APPLY_OFFSITE:-1}"
APPLY_RESTORE_PROOF="${APPLY_RESTORE_PROOF:-1}"
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

DIR=/opt/vps-autopilot/backups
$SUDO mkdir -p "$DIR"
$SUDO chmod 700 "$DIR"

if [ "$APPLY_ENCRYPTED_DUMP" = "1" ]; then
  case "${BACKUP_PASSPHRASE:-}" in
    [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]*) ;;
    *) echo NEED_BACKUP_PASSPHRASE; exit 1 ;;
  esac
  command -v openssl >/dev/null 2>&1 || { echo OPENSSL_MISSING; exit 1; }
  command -v gzip >/dev/null 2>&1 || { echo GZIP_MISSING; exit 1; }

  # Le minuteur doit chiffrer sans l’agent : la phrase vit sur le serveur, lisible par root seul.
  printf '%s' "$BACKUP_PASSPHRASE" | $SUDO tee "$DIR/.passphrase" >/dev/null
  $SUDO chmod 600 "$DIR/.passphrase"

  $SUDO tee /usr/local/bin/vps-autopilot-backup >/dev/null <<'BACKUP'
#!/bin/bash
# VPS Autopilot — sauvegarde chiffrée de MariaDB (installée par le rôle backups). Lancée par root.
set -euo pipefail
umask 077
DIR=/opt/vps-autopilot/backups
KEEP="${KEEP:-14}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
PLAIN="$DIR/dump-$STAMP.sql.gz"
ENC="$PLAIN.enc"
ERR="$(mktemp)"
trap 'rm -f "$PLAIN" "$ERR"' EXIT
[ -s "$DIR/.passphrase" ] || { echo NEED_BACKUP_PASSPHRASE; exit 1; }

HAS_DB=0
if command -v docker >/dev/null 2>&1; then
  NAMES="$(docker ps --format '{{.Names}}')" || { echo DOCKER_MISSING; exit 1; }
  case "
$NAMES
" in *"
mariadb
"*) HAS_DB=1 ;; esac
fi
if [ "$HAS_DB" = "1" ]; then
  [ -s /opt/vps-autopilot/mariadb/root.pw ] || { echo MARIADB_ROOT_PW_MISSING; exit 1; }
  DUMP=mariadb-dump
  docker exec mariadb sh -c 'command -v mariadb-dump' >/dev/null 2>&1 || DUMP=mysqldump
  if ! docker exec -e MYSQL_PWD="$(cat /opt/vps-autopilot/mariadb/root.pw)" mariadb \
      "$DUMP" -uroot --all-databases --single-transaction --routines --triggers --events 2>"$ERR" \
      | gzip -c > "$PLAIN"; then
    echo BACKUP_DUMP_FAILED
    head -n 5 "$ERR" | sed 's/^/DUMP_ERR: /'
    exit 1
  fi
  LAST="$(gzip -dc "$PLAIN" | tail -n 5)"
  case "$LAST" in
    *"-- Dump completed"*) ;;
    *) echo BACKUP_DUMP_INCOMPLETE; exit 1 ;;
  esac
  DUMPED=mariadb-all-databases
else
  printf -- '-- VPS Autopilot: no mariadb container\n' | gzip -c > "$PLAIN"
  DUMPED=none
fi

openssl enc -aes-256-cbc -pbkdf2 -pass "file:$DIR/.passphrase" -in "$PLAIN" -out "$ENC"
chmod 600 "$ENC"
ls -1t "$DIR"/dump-*.sql.gz.enc 2>/dev/null | tail -n +"$((KEEP + 1))" | xargs -r rm -f
touch "$DIR/.vps-autopilot-backups"
echo "BACKUP_READY=$ENC"
echo "BACKUP_SHA256=$(sha256sum "$ENC" | awk '{print $1}')"
echo "BACKUP_BYTES=$(stat -c %s "$ENC")"
echo "BACKUP_DUMPED=$DUMPED"
logger -t vps-autopilot-backup "sauvegarde $ENC ($DUMPED)" 2>/dev/null || true
BACKUP
  $SUDO chmod 700 /usr/local/bin/vps-autopilot-backup

  if command -v systemctl >/dev/null 2>&1; then
    printf '%s\n' '[Unit]' 'Description=VPS Autopilot - sauvegarde chiffree' 'After=docker.service' \
      '[Service]' 'Type=oneshot' 'ExecStart=/usr/local/bin/vps-autopilot-backup' \
      | $SUDO tee /etc/systemd/system/vps-autopilot-backup.service >/dev/null
    printf '%s\n' '[Unit]' 'Description=VPS Autopilot - sauvegarde quotidienne' \
      '[Timer]' 'OnCalendar=*-*-* 02:30:00' 'RandomizedDelaySec=30min' 'Persistent=true' \
      '[Install]' 'WantedBy=timers.target' \
      | $SUDO tee /etc/systemd/system/vps-autopilot-backup.timer >/dev/null
    $SUDO systemctl daemon-reload
    if $SUDO systemctl enable --now vps-autopilot-backup.timer >/dev/null 2>&1; then
      echo BACKUP_TIMER=on
    else
      echo BACKUP_TIMER=failed
    fi
  fi

  $SUDO /usr/local/bin/vps-autopilot-backup
fi

if [ "$APPLY_OFFSITE" = "1" ]; then
  echo OFFSITE_EXPECTED=1
fi
if [ "$APPLY_RESTORE_PROOF" = "1" ]; then
  echo RESTORE_PROOF_EXPECTED=1
fi

$SUDO touch "$DIR/.vps-autopilot-backups"
echo BACKUPS_APPLIED
