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
# vps-autopilot-backup v2
# VPS Autopilot — sauvegarde chiffrée de MariaDB et des volumes de fichiers (installée par le rôle backups). Root.
set -euo pipefail
umask 077
DIR=/opt/vps-autopilot/backups
KEEP="${KEEP:-14}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
PLAIN="$DIR/dump-$STAMP.sql.gz"
ENC="$PLAIN.enc"
ERR="$(mktemp)"
FPART=""
trap 'rm -f "$PLAIN" "$ERR" ${FPART:+"$FPART"}' EXIT
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

# Fichiers déposés par les utilisateurs (BIZ-11) : volumes plat_<id>_uploads (plateformes), *app_data_<slug> (apps).
# Archive chiffrée en flux (aucune copie en clair sur le disque), même horodatage que le dump.
VOLS=""
if command -v docker >/dev/null 2>&1; then
  VOLS="$(docker volume ls -q 2>/dev/null \
    | grep -E '^(plat_[a-z0-9_]+_uploads|([a-z0-9][a-z0-9_.-]*_)?app_data_[A-Za-z0-9_.-]+)$' || true)"
fi
MEMBERS=()
if [ -n "$VOLS" ]; then
  VROOT="$(docker info -f '{{.DockerRootDir}}')/volumes"
  for V in $VOLS; do
    MP="$(docker volume inspect -f '{{.Mountpoint}}' "$V" 2>/dev/null || true)"
    if [ "$MP" = "$VROOT/$V/_data" ] && [ -d "$MP" ]; then
      MEMBERS+=("$V/_data")
      echo "FILES_VOLUME=$V:$(find "$MP" -type f | wc -l | tr -d ' ')"
    else
      echo "FILES_SKIPPED=$V"
    fi
  done
fi
if [ "${#MEMBERS[@]}" -eq 0 ]; then
  echo FILES_NONE
  exit 0
fi
FENC="$DIR/files-$STAMP.tar.gz.enc"
FPART="$FENC.part"
set +e
tar -C "$VROOT" -czf - "${MEMBERS[@]}" 2>"$ERR" \
  | openssl enc -aes-256-cbc -pbkdf2 -pass "file:$DIR/.passphrase" -out "$FPART"
ST=("${PIPESTATUS[@]}")
set -e
# tar sort 1 si un fichier change pendant la lecture : l’archive reste lisible.
if [ "${ST[0]}" -gt 1 ] || [ "${ST[1]}" -ne 0 ]; then
  echo BACKUP_FILES_FAILED
  grep -v 'file changed as we read it' "$ERR" | head -n 5 | sed 's/^/FILES_ERR: /'
  exit 1
fi
mv "$FPART" "$FENC"
FPART=""
chmod 600 "$FENC"
ls -1t "$DIR"/files-*.tar.gz.enc 2>/dev/null | tail -n +"$((KEEP + 1))" | xargs -r rm -f
echo "FILES_READY=$FENC"
echo "FILES_SHA256=$(sha256sum "$FENC" | awk '{print $1}')"
echo "FILES_BYTES=$(stat -c %s "$FENC")"
logger -t vps-autopilot-backup "fichiers $FENC (${#MEMBERS[@]} volume(s))" 2>/dev/null || true
BACKUP
  $SUDO chmod 700 /usr/local/bin/vps-autopilot-backup

  # Exercice de restauration automatique (BIZ-12) : la dernière sauvegarde du VPS est restaurée dans des bases et un
  # dossier jetables, comptée contre le contenu du dump et de l’archive, puis tout est supprimé. Résultat lu par l’analyse.
  $SUDO tee /usr/local/bin/vps-autopilot-backup-drill >/dev/null <<'DRILL'
#!/bin/bash
# vps-autopilot-backup-drill v1
# VPS Autopilot — exercice de restauration automatique de la dernière sauvegarde (installé par le rôle backups). Root.
set -uo pipefail
umask 077
DIR=/opt/vps-autopilot/backups
PASS="$DIR/.passphrase"
OUT="$DIR/drill-last.txt"
HIST="$DIR/drill-history.log"
W=""
SCRATCH=""
ROOT_PW=""
sql_root() { docker exec -i -e MYSQL_PWD="$ROOT_PW" mariadb mariadb -uroot "$@"; }
cleanup() {
  if [ -n "$SCRATCH" ]; then
    printf 'DROP DATABASE IF EXISTS `%s`;\n' "$SCRATCH" | sql_root >/dev/null 2>&1 || true
  fi
  case "$W" in /var/tmp/autopilot-auto-drill-*) rm -rf "$W" ;; esac
}
trap cleanup EXIT
LINES=()
OK=1
fail() { OK=0; LINES+=("DRILL_PROBLEM=$1"); }
finish() {
  {
    echo "DRILL_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "DRILL_DUMP=${DUMP##*/}"
    printf '%s\n' "${LINES[@]}"
    echo "DRILL_OK=$OK"
  } > "$OUT.tmp" && mv -f "$OUT.tmp" "$OUT"
  chmod 600 "$OUT"
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) ok=$OK ${DUMP##*/}" >> "$HIST"
  tail -n 24 "$HIST" > "$HIST.tmp" && mv -f "$HIST.tmp" "$HIST"
  cat "$OUT"
  logger -t vps-autopilot-backup-drill "exercice ok=$OK ${DUMP##*/}" 2>/dev/null || true
  [ "$OK" = "1" ]
  exit $?
}

DUMP="$(ls -1t "$DIR"/dump-*.sql.gz.enc 2>/dev/null | head -n 1)"
[ -n "$DUMP" ] || { DUMP=none; fail NO_BACKUP; finish; }
[ -s "$PASS" ] || { fail NEED_BACKUP_PASSPHRASE; finish; }
S="${DUMP##*/dump-}"
FILES="$DIR/files-${S%.sql.gz.enc}.tar.gz.enc"
ENC_BYTES="$(stat -c %s "$DUMP")"
[ -f "$FILES" ] && ENC_BYTES=$((ENC_BYTES + $(stat -c %s "$FILES")))
# Place : dump et fichiers en clair ≈ 10 × la taille chiffrée, + 200 Mo.
NEED=$((ENC_BYTES * 10 + 209715200))
AVAIL="$(df -P -B1 /var/tmp | awk 'NR==2 {print $4}')"
if [ "$AVAIL" -lt "$NEED" ]; then
  fail "NO_SPACE:$NEED:$AVAIL"
  finish
fi
W="$(mktemp -d /var/tmp/autopilot-auto-drill-XXXXXX)"
mkdir -p "$W/db"

if ! openssl enc -d -aes-256-cbc -pbkdf2 -pass "file:$PASS" -in "$DUMP" | gzip -dc | awk -v out="$W/db" '
  BEGIN { cur = ""; seen = 0; foot = 0; nh = 0; nf = 0 }
  /^-- Current Database: `/ {
    seen = 1; name = $0; sub(/^-- Current Database: `/, "", name); sub(/`.*$/, "", name)
    if (name ~ /^(mysql|information_schema|performance_schema|sys)$/ || name !~ /^[A-Za-z0-9_$-]+$/) { cur = ""; next }
    cur = name
    # Une base peut revenir en fin de dump (routines, événements) : en-tête et compteur une seule fois.
    if (!(cur in dbs)) {
      dbs[cur] = 1; count[cur] = 0
      for (i = 1; i <= nh; i++) print head[i] > (out "/" cur ".sql")
    }
    next
  }
  $0 == "/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;" { foot = 1; cur = "" }
  foot { tail[++nf] = $0; next }
  !seen { head[++nh] = $0; next }
  cur == "" { next }
  /^(CREATE DATABASE |USE `)/ { next }
  /^CREATE TABLE / { count[cur]++ }
  { gsub(/DEFINER=`[^`]+`@`[^`]+`/, ""); print > (out "/" cur ".sql") }
  END {
    for (d in dbs) {
      for (i = 1; i <= nf; i++) print tail[i] > (out "/" d ".sql")
      close(out "/" d ".sql")
      print count[d] > (out "/" d ".expected")
    }
  }'; then
  fail DUMP_UNREADABLE
  finish
fi

HAS_DB=0
for F in "$W"/db/*.sql; do [ -e "$F" ] && HAS_DB=1 && break; done
if [ "$HAS_DB" = "1" ]; then
  if ! docker ps --format '{{.Names}}' 2>/dev/null | grep -qx mariadb; then
    fail NEED_MARIADB
  else
    ROOT_PW="$(cat /opt/vps-autopilot/mariadb/root.pw)"
    for F in "$W"/db/*.sql; do
      DB="$(basename "$F" .sql)"
      EXPECTED="$(cat "$W/db/$DB.expected")"
      SCRATCH="restore_check_$(openssl rand -hex 4)"
      printf 'CREATE DATABASE `%s` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\n' "$SCRATCH" | sql_root
      if sql_root --default-character-set=utf8mb4 "$SCRATCH" < "$F" >"$W/import.log" 2>&1; then
        COUNT="$(printf "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='%s' AND table_type='BASE TABLE';\n" "$SCRATCH" | sql_root -N)"
        LINES+=("DRILL_DB=$DB:$EXPECTED:$COUNT")
        [ "$COUNT" = "$EXPECTED" ] || OK=0
      else
        fail "IMPORT_FAILED:$DB"
      fi
      printf 'DROP DATABASE IF EXISTS `%s`;\n' "$SCRATCH" | sql_root
      SCRATCH=""
      rm -f "$F"
    done
    unset ROOT_PW
  fi
fi

if [ -f "$FILES" ]; then
  mkdir -p "$W/files"
  if openssl enc -d -aes-256-cbc -pbkdf2 -pass "file:$PASS" -in "$FILES" | tar -tzf - 2>/dev/null | { grep -v '/$' || true; } > "$W/files.list" \
      && openssl enc -d -aes-256-cbc -pbkdf2 -pass "file:$PASS" -in "$FILES" | tar -C "$W/files" --numeric-owner -xzf -; then
    for V in $(cut -d/ -f1 "$W/files.list" | sort -u); do
      EXPECTED="$(awk -v v="$V/_data/" 'index($0, v) == 1' "$W/files.list" | wc -l | tr -d ' ')"
      COUNT="$(find "$W/files/$V/_data" -type f 2>/dev/null | wc -l | tr -d ' ')"
      LINES+=("DRILL_VOL=$V:$EXPECTED:$COUNT")
      [ "$COUNT" = "$EXPECTED" ] || OK=0
    done
  else
    fail FILES_EXTRACT_FAILED
  fi
fi
finish
DRILL
  $SUDO chmod 700 /usr/local/bin/vps-autopilot-backup-drill

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
    printf '%s\n' '[Unit]' 'Description=VPS Autopilot - exercice de restauration' 'After=docker.service' \
      '[Service]' 'Type=oneshot' 'Nice=10' 'ExecStart=/usr/local/bin/vps-autopilot-backup-drill' \
      | $SUDO tee /etc/systemd/system/vps-autopilot-backup-drill.service >/dev/null
    printf '%s\n' '[Unit]' 'Description=VPS Autopilot - exercice de restauration mensuel' \
      '[Timer]' 'OnCalendar=*-*-01 04:00:00' 'RandomizedDelaySec=1h' 'Persistent=true' \
      '[Install]' 'WantedBy=timers.target' \
      | $SUDO tee /etc/systemd/system/vps-autopilot-backup-drill.timer >/dev/null
    $SUDO systemctl daemon-reload
    if $SUDO systemctl enable --now vps-autopilot-backup-drill.timer >/dev/null 2>&1; then
      echo DRILL_TIMER=on
    else
      echo DRILL_TIMER=failed
    fi
  fi

  $SUDO /usr/local/bin/vps-autopilot-backup
  # Premier exercice tout de suite : l’installation prouve que la sauvegarde se restaure.
  $SUDO /usr/local/bin/vps-autopilot-backup-drill || echo AUTO_DRILL_FAILED
fi

if [ "$APPLY_OFFSITE" = "1" ]; then
  echo OFFSITE_EXPECTED=1
fi
if [ "$APPLY_RESTORE_PROOF" = "1" ]; then
  echo RESTORE_PROOF_EXPECTED=1
fi

$SUDO touch "$DIR/.vps-autopilot-backups"
echo BACKUPS_APPLIED
