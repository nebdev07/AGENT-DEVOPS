set -eu
# VPS Autopilot — dump chiffré (MariaDB si présent). Copie hors-site = poste opérateur (Java).
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

mariadb_running() {
  command -v docker >/dev/null 2>&1 || return 1
  $SUDO docker ps --format '{{.Names}}' 2>/dev/null | grep -qx mariadb
}

if [ "$APPLY_ENCRYPTED_DUMP" = "1" ]; then
  if [ -z "${BACKUP_PASSPHRASE:-}" ]; then
    echo NEED_BACKUP_PASSPHRASE
    exit 1
  fi
  if ! command -v openssl >/dev/null 2>&1; then
    echo OPENSSL_MISSING
    exit 1
  fi
  if ! command -v gzip >/dev/null 2>&1; then
    echo GZIP_MISSING
    exit 1
  fi

  STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
  HOST_NAME="$(hostname -s 2>/dev/null || hostname || echo unknown)"
  MANIFEST="$DIR/manifest-${STAMP}.txt"
  PLAIN="$DIR/dump-${STAMP}.sql.gz"
  ENC="$DIR/dump-${STAMP}.sql.gz.enc"
  DUMPED=none

  {
    echo "hostname=${HOST_NAME}"
    echo "date_utc=${STAMP}"
    echo "agent=vps-autopilot"
  } | $SUDO tee "$MANIFEST" >/dev/null

  if mariadb_running; then
    ROOT_PW=""
    if $SUDO test -s /opt/vps-autopilot/mariadb/root.pw; then
      ROOT_PW="$($SUDO cat /opt/vps-autopilot/mariadb/root.pw)"
    fi
    if [ -z "$ROOT_PW" ]; then
      echo MARIADB_ROOT_PW_MISSING
      exit 1
    fi
    $SUDO docker exec mariadb mysqldump -uroot -p"${ROOT_PW}" --all-databases --single-transaction --routines --triggers 2>/dev/null \
      | gzip -c | $SUDO tee "$PLAIN" >/dev/null
    unset ROOT_PW
    DUMPED=mariadb-all-databases
    echo "dumped=${DUMPED}" | $SUDO tee -a "$MANIFEST" >/dev/null
  else
    printf '# no mariadb container\n' | gzip -c | $SUDO tee "$PLAIN" >/dev/null
    echo "dumped=none" | $SUDO tee -a "$MANIFEST" >/dev/null
  fi

  # Inclure le manifeste en tête du flux clair avant chiffrement : on chiffre le dump gzip.
  $SUDO openssl enc -aes-256-cbc -pbkdf2 -pass pass:"${BACKUP_PASSPHRASE}" -in "$PLAIN" -out "$ENC"
  $SUDO rm -f "$PLAIN"
  $SUDO chmod 600 "$ENC" "$MANIFEST"
  SHA="$($SUDO sha256sum "$ENC" | awk '{print $1}')"
  echo BACKUP_READY="$ENC"
  echo BACKUP_SHA256="$SHA"
  echo BACKUP_MANIFEST="$MANIFEST"
fi

if [ "$APPLY_OFFSITE" = "1" ]; then
  echo OFFSITE_EXPECTED=1
fi
if [ "$APPLY_RESTORE_PROOF" = "1" ]; then
  echo RESTORE_PROOF_EXPECTED=1
fi

$SUDO touch "$DIR/.vps-autopilot-backups"
echo BACKUPS_APPLIED
