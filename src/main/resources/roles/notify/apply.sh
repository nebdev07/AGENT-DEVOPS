set -eu
# VPS Autopilot — alertes par e-mail envoyées par le VPS lui-même (BIZ-15, ADR-044).
# Variables : NOTIFY_MODE (set|test|status|remove)
#   set : NOTIFY_HOST NOTIFY_PORT NOTIFY_TLS (ssl|starttls|none) NOTIFY_USER NOTIFY_PASS_B64 (vide = garder)
#         NOTIFY_FROM NOTIFY_TO (adresses séparées par des virgules) NOTIFY_LABEL_B64 NOTIFY_MONTHLY (1|0)
# Le mot de passe n’est jamais affiché : base64 dans /opt/vps-autopilot/notify.env (600, root), lu par curl via -K.
NOTIFY_MODE="${NOTIFY_MODE:-}"
umask 077
if [ "$(id -u)" -eq 0 ]; then
  SUDO=""
else
  SUDO="sudo -n"
  $SUDO true || { echo NEED_PASSWORDLESS_SUDO; exit 1; }
fi
command -v systemctl >/dev/null 2>&1 || { echo NEED_SYSTEMD; exit 1; }
command -v curl >/dev/null 2>&1 || { echo NEED_CURL; exit 1; }

BASE=/opt/vps-autopilot
CONF=$BASE/notify.env
LAST=$BASE/notify-last.txt
SEND=/usr/local/bin/vps-autopilot-notify
UNIT_SCRIPT=/usr/local/bin/vps-autopilot-notify-unit
DRILL_SCRIPT=/usr/local/bin/vps-autopilot-notify-drill
TEMPLATE=/etc/systemd/system/vps-autopilot-notify-failure@.service
BACKUP_DROPIN=/etc/systemd/system/vps-autopilot-backup.service.d/notify.conf
DRILL_DROPIN=/etc/systemd/system/vps-autopilot-backup-drill.service.d/notify.conf

send_test() {
  if printf '%s\n' "Ceci est un message d’essai envoyé par VPS Autopilot depuis ce serveur." "" \
      "Vous recevrez un message ici si la sauvegarde de nuit ou l’exercice de restauration échoue," \
      "et, si l’option est active, un résumé après chaque exercice mensuel réussi." \
      | $SUDO "$SEND" test "Message d’essai"; then
    echo NOTIFY_TEST_OK
  else
    echo NOTIFY_TEST_FAILED
  fi
}

case "$NOTIFY_MODE" in
  status)
    if $SUDO test -s "$CONF" && $SUDO test -f "$BACKUP_DROPIN"; then
      echo NOTIFY_STATE=on
      $SUDO grep -E '^NOTIFY_(HOST|PORT|TLS|USER|FROM|TO|MONTHLY|LABEL_B64)=' "$CONF" | sed 's/^/CONF_/'
      if $SUDO grep -q '^NOTIFY_PASS_B64=.' "$CONF"; then echo CONF_NOTIFY_PASS_SET=1; else echo CONF_NOTIFY_PASS_SET=0; fi
    else
      echo NOTIFY_STATE=off
    fi
    $SUDO cat "$LAST" 2>/dev/null | grep -E '^NOTIFY_LAST_(AT|KIND|OK|ERROR)=' || true
    echo NOTIFY_DONE
    exit 0
    ;;
  test)
    $SUDO test -s "$CONF" || { echo NOTIFY_NOT_CONFIGURED; exit 1; }
    send_test
    echo NOTIFY_DONE
    exit 0
    ;;
  remove)
    $SUDO rm -f "$BACKUP_DROPIN" "$DRILL_DROPIN" "$TEMPLATE" "$SEND" "$UNIT_SCRIPT" "$DRILL_SCRIPT" "$CONF" "$LAST"
    $SUDO rmdir /etc/systemd/system/vps-autopilot-backup.service.d /etc/systemd/system/vps-autopilot-backup-drill.service.d 2>/dev/null || true
    $SUDO systemctl daemon-reload
    echo NOTIFY_REMOVED
    echo NOTIFY_DONE
    exit 0
    ;;
  set) ;;
  *) echo NEED_NOTIFY_SPEC; exit 1 ;;
esac

NOTIFY_HOST="${NOTIFY_HOST:-}"
NOTIFY_PORT="${NOTIFY_PORT:-}"
NOTIFY_TLS="${NOTIFY_TLS:-}"
NOTIFY_USER="${NOTIFY_USER:-}"
NOTIFY_PASS_B64="${NOTIFY_PASS_B64:-}"
NOTIFY_FROM="${NOTIFY_FROM:-}"
NOTIFY_TO="${NOTIFY_TO:-}"
NOTIFY_LABEL_B64="${NOTIFY_LABEL_B64:-}"
NOTIFY_MONTHLY="${NOTIFY_MONTHLY:-1}"
case "$NOTIFY_HOST" in ''|*[!A-Za-z0-9.-]*) echo NEED_NOTIFY_SPEC; exit 1 ;; esac
case "$NOTIFY_PORT" in ''|*[!0-9]*) echo NEED_NOTIFY_SPEC; exit 1 ;; esac
case "$NOTIFY_TLS" in ssl|starttls|none) ;; *) echo NEED_NOTIFY_SPEC; exit 1 ;; esac
case "$NOTIFY_USER" in *[!A-Za-z0-9._%+@-]*) echo NEED_NOTIFY_SPEC; exit 1 ;; esac
case "$NOTIFY_FROM" in ''|*[!A-Za-z0-9._%+@-]*) echo NEED_NOTIFY_SPEC; exit 1 ;; esac
case "$NOTIFY_TO" in ''|*[!A-Za-z0-9._%+@,-]*) echo NEED_NOTIFY_SPEC; exit 1 ;; esac
case "$NOTIFY_PASS_B64$NOTIFY_LABEL_B64" in *[!A-Za-z0-9+/=]*) echo NEED_NOTIFY_SPEC; exit 1 ;; esac
case "$NOTIFY_MONTHLY" in 0|1) ;; *) echo NEED_NOTIFY_SPEC; exit 1 ;; esac

if [ -z "$NOTIFY_PASS_B64" ] && [ -n "$NOTIFY_USER" ]; then
  NOTIFY_PASS_B64="$($SUDO sed -n 's/^NOTIFY_PASS_B64=//p' "$CONF" 2>/dev/null || true)"
  [ -n "$NOTIFY_PASS_B64" ] || { echo NEED_NOTIFY_PASSWORD; exit 1; }
fi
[ -n "$NOTIFY_USER" ] || NOTIFY_PASS_B64=""

$SUDO mkdir -p "$BASE"
NEW="$CONF.new"
trap '$SUDO rm -f "$NEW"' EXIT
$SUDO install -m 600 -o root -g root /dev/null "$NEW"
printf '%s\n' "NOTIFY_HOST=$NOTIFY_HOST" "NOTIFY_PORT=$NOTIFY_PORT" "NOTIFY_TLS=$NOTIFY_TLS" \
  "NOTIFY_USER=$NOTIFY_USER" "NOTIFY_PASS_B64=$NOTIFY_PASS_B64" "NOTIFY_FROM=$NOTIFY_FROM" \
  "NOTIFY_TO=$NOTIFY_TO" "NOTIFY_LABEL_B64=$NOTIFY_LABEL_B64" "NOTIFY_MONTHLY=$NOTIFY_MONTHLY" \
  | $SUDO tee "$NEW" >/dev/null
unset NOTIFY_PASS_B64
$SUDO mv -f "$NEW" "$CONF"
$SUDO chmod 600 "$CONF"

$SUDO tee "$SEND" >/dev/null <<'SEND'
#!/bin/bash
# vps-autopilot-notify v1
# VPS Autopilot — envoie un e-mail (corps sur l’entrée standard). Usage : vps-autopilot-notify <type> <sujet>. Root.
set -uo pipefail
umask 077
CONF=/opt/vps-autopilot/notify.env
LAST=/opt/vps-autopilot/notify-last.txt
KIND="${1:-info}"
SUBJECT="${2:-VPS Autopilot}"
[ -s "$CONF" ] || { echo NOTIFY_NOT_CONFIGURED; exit 3; }
# shellcheck disable=SC1090
. "$CONF"
d() { printf '%s' "$1" | base64 -d 2>/dev/null; }
enc() { printf '=?UTF-8?B?%s?=' "$(printf '%s' "$1" | base64 -w0)"; }
LABEL="$(d "${NOTIFY_LABEL_B64:-}")"
[ -n "$LABEL" ] || LABEL="$(hostname)"
BODY="$(cat)"
W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT
case "$NOTIFY_TLS" in
  ssl) URL="smtps://$NOTIFY_HOST:$NOTIFY_PORT"; TLS=() ;;
  starttls) URL="smtp://$NOTIFY_HOST:$NOTIFY_PORT"; TLS=(--ssl-reqd) ;;
  *) URL="smtp://$NOTIFY_HOST:$NOTIFY_PORT"; TLS=() ;;
esac
{
  printf 'From: %s <%s>\r\n' "$(enc "VPS Autopilot")" "$NOTIFY_FROM"
  printf 'To: %s\r\n' "$NOTIFY_TO"
  printf 'Subject: %s\r\n' "$(enc "[$LABEL] $SUBJECT")"
  printf 'Date: %s\r\n' "$(LC_ALL=C date -R)"
  printf 'Message-ID: <%s.%s@vps-autopilot>\r\n' "$(date +%s)" "$(openssl rand -hex 6 2>/dev/null || echo 0)"
  printf 'MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n'
  printf 'X-VPS-Autopilot-Kind: %s\r\n\r\n' "$KIND"
  printf '%s\n\n-- \nServeur : %s (%s)\nMessage automatique de VPS Autopilot.\n' "$BODY" "$LABEL" "$(hostname)" \
    | base64 -w 76 | sed 's/$/\r/'
} > "$W/mail"
{
  printf 'url = "%s"\n' "$URL"
  printf 'mail-from = "%s"\n' "$NOTIFY_FROM"
  for R in ${NOTIFY_TO//,/ }; do printf 'mail-rcpt = "%s"\n' "$R"; done
  if [ -n "${NOTIFY_USER:-}" ]; then
    printf 'user = "%s:%s"\n' "$NOTIFY_USER" "$(d "${NOTIFY_PASS_B64:-}" | sed 's/\\/\\\\/g; s/"/\\"/g')"
  fi
} > "$W/curl.conf"
OK=0
if curl -sS --max-time 60 "${TLS[@]}" -K "$W/curl.conf" --upload-file "$W/mail" 2>"$W/err"; then OK=1; fi
ERRLINE="$(head -n 1 "$W/err" | tr -cd 'A-Za-z0-9 ._:()/-' | cut -c1-200)"
{
  echo "NOTIFY_LAST_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "NOTIFY_LAST_KIND=$KIND"
  echo "NOTIFY_LAST_OK=$OK"
  [ "$OK" = "1" ] || echo "NOTIFY_LAST_ERROR=$ERRLINE"
} > "$LAST.tmp" && mv -f "$LAST.tmp" "$LAST"
chmod 600 "$LAST"
if [ "$OK" = "1" ]; then
  logger -t vps-autopilot-notify "envoye ($KIND)" 2>/dev/null || true
  echo NOTIFY_SENT
  exit 0
fi
logger -t vps-autopilot-notify "echec ($KIND) $ERRLINE" 2>/dev/null || true
echo "NOTIFY_SEND_FAILED=$ERRLINE"
exit 1
SEND

$SUDO tee "$UNIT_SCRIPT" >/dev/null <<'UNIT'
#!/bin/bash
# vps-autopilot-notify-unit v1
# VPS Autopilot — alerte quand une tâche automatique échoue (OnFailure=). Root.
set -uo pipefail
U="${1:-}"
case "$U" in
  vps-autopilot-backup.service) WHAT="La sauvegarde automatique a échoué" ;;
  vps-autopilot-backup-drill.service) WHAT="L’exercice de restauration automatique a échoué" ;;
  *) WHAT="Une tâche automatique a échoué ($U)" ;;
esac
{
  echo "$WHAT."
  echo
  echo "Date : $(date '+%d/%m/%Y %H:%M %Z')"
  if [ "$U" = "vps-autopilot-backup-drill.service" ] && [ -f /opt/vps-autopilot/backups/drill-last.txt ]; then
    echo
    echo "Résultat de l’exercice :"
    grep -E '^DRILL_(DB|VOL|PROBLEM)=' /opt/vps-autopilot/backups/drill-last.txt | head -n 20
  fi
  echo
  echo "Dernières lignes du journal de la tâche :"
  INV="$(systemctl show -p InvocationID --value "$U" 2>/dev/null || true)"
  if [ -n "$INV" ]; then
    journalctl "_SYSTEMD_INVOCATION_ID=$INV" + "INVOCATION_ID=$INV" -n 15 --no-pager -o cat 2>/dev/null | tail -n 15
  else
    journalctl -u "$U" -n 15 --no-pager -o cat 2>/dev/null | tail -n 15
  fi
  echo
  echo "Les applications et les données en service ne sont pas modifiées par cet échec."
  echo "L’opérateur doit analyser le serveur et relancer la tâche depuis la console."
} | /usr/local/bin/vps-autopilot-notify failure "Échec : $WHAT"
UNIT

$SUDO tee "$DRILL_SCRIPT" >/dev/null <<'DRILLMAIL'
#!/bin/bash
# vps-autopilot-notify-drill v1
# VPS Autopilot — résumé après un exercice de restauration réussi (ExecStartPost=). Root.
set -uo pipefail
. /opt/vps-autopilot/notify.env 2>/dev/null || exit 0
[ "${NOTIFY_MONTHLY:-0}" = "1" ] || exit 0
F=/opt/vps-autopilot/backups/drill-last.txt
[ -f "$F" ] || exit 0
{
  echo "L’exercice de restauration automatique a réussi."
  echo
  echo "La dernière sauvegarde de ce serveur a été réimportée à part (bases et dossiers jetables,"
  echo "supprimés ensuite), sans toucher aux données en service. Contenu retrouvé :"
  echo
  sed -n 's/^DRILL_DB=\([^:]*\):\([0-9]*\):\([0-9]*\)$/- Base \1 : \3 tables restaurées sur \2/p;
          s/^DRILL_VOL=\([^:]*\):\([0-9]*\):\([0-9]*\)$/- Fichiers \1 : \3 fichiers restaurés sur \2/p' "$F"
  echo
  echo "Sauvegarde testée : $(sed -n 's/^DRILL_DUMP=//p' "$F")"
  echo "Date de l’exercice : $(date '+%d/%m/%Y %H:%M %Z')"
} | /usr/local/bin/vps-autopilot-notify drill-ok "Exercice de restauration réussi"
DRILLMAIL
$SUDO chmod 700 "$SEND" "$UNIT_SCRIPT" "$DRILL_SCRIPT"

printf '%s\n' '[Unit]' 'Description=VPS Autopilot - alerte apres echec de %i' \
  '[Service]' 'Type=oneshot' "ExecStart=$UNIT_SCRIPT %i" \
  | $SUDO tee "$TEMPLATE" >/dev/null
$SUDO mkdir -p "${BACKUP_DROPIN%/*}" "${DRILL_DROPIN%/*}"
printf '%s\n' '[Unit]' 'OnFailure=vps-autopilot-notify-failure@%n.service' \
  | $SUDO tee "$BACKUP_DROPIN" >/dev/null
printf '%s\n' '[Unit]' 'OnFailure=vps-autopilot-notify-failure@%n.service' \
  '[Service]' "ExecStartPost=-$DRILL_SCRIPT" \
  | $SUDO tee "$DRILL_DROPIN" >/dev/null
$SUDO chmod 755 "${BACKUP_DROPIN%/*}" "${DRILL_DROPIN%/*}"
$SUDO chmod 644 "$TEMPLATE" "$BACKUP_DROPIN" "$DRILL_DROPIN"
$SUDO systemctl daemon-reload
echo NOTIFY_CONFIGURED
send_test
echo NOTIFY_DONE
