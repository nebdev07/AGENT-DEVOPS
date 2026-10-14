set -eu
# VPS Autopilot — secrets d’exécution d’une plateforme dans svc-<service>.local.env (600, deploy).
# Variables : PLATFORM_ID SECRET_MODE (list|set|unset) SECRET_SERVICE SECRET_NAME SECRET_VALUE_B64 SECRET_RESTART (1|0)
# La valeur n’est jamais affichée ni écrite ailleurs que dans le fichier final (tube vers un fichier 600 créé avant).
PLATFORM_ID="${PLATFORM_ID:-}"
SECRET_MODE="${SECRET_MODE:-}"
SECRET_SERVICE="${SECRET_SERVICE:-}"
SECRET_NAME="${SECRET_NAME:-}"
SECRET_VALUE_B64="${SECRET_VALUE_B64:-}"
SECRET_RESTART="${SECRET_RESTART:-1}"
umask 077
if [ "$(id -u)" -eq 0 ]; then
  SUDO=""
else
  SUDO="sudo -n"
  $SUDO true || { echo NEED_PASSWORDLESS_SUDO; exit 1; }
fi

case "$PLATFORM_ID" in
  ''|*[!a-z0-9-]*) echo NEED_SECRET_SPEC; exit 1 ;;
esac
ROOT=/opt/vps-autopilot/platforms/$PLATFORM_ID
$SUDO test -f "$ROOT/docker-compose.yml" || { echo NEED_PLATFORM_DEPLOYED; exit 1; }

if [ "$SECRET_MODE" = "list" ]; then
  for F in $($SUDO find "$ROOT" -maxdepth 1 -name 'svc-*.local.env' -type f | sort); do
    SID="$(basename "$F" .local.env)"
    SID="${SID#svc-}"
    echo "SECRET_SERVICE=$SID"
    $SUDO sed -n 's/^\([A-Za-z_][A-Za-z0-9_.]*\)=.*/\1/p' "$F" | while read -r N; do
      echo "SECRET_NAME=$SID:$N"
    done
  done
  echo SECRET_LIST_DONE
  exit 0
fi

case "$SECRET_SERVICE" in
  ''|*[!a-z0-9-]*) echo NEED_SECRET_SPEC; exit 1 ;;
esac
case "$SECRET_NAME" in
  ''|[0-9.]*|*[!A-Za-z0-9_.]*) echo NEED_SECRET_SPEC; exit 1 ;;
esac
LOCALF="$ROOT/svc-${SECRET_SERVICE}.local.env"
$SUDO test -f "$LOCALF" || { echo NEED_PLATFORM_DEPLOYED; exit 1; }

NEW="$LOCALF.new"
trap '$SUDO rm -f "$NEW"' EXIT
$SUDO install -m 600 -o deploy -g deploy /dev/null "$NEW"
NAME_RE="$(printf '%s' "$SECRET_NAME" | sed 's/\./\\./g')"
if $SUDO grep -q "^${NAME_RE}=" "$LOCALF"; then
  EXISTED=1
else
  EXISTED=0
fi

case "$SECRET_MODE" in
  set)
    [ -n "$SECRET_VALUE_B64" ] || { echo NEED_SECRET_SPEC; exit 1; }
    VALUE="$(printf '%s' "$SECRET_VALUE_B64" | base64 -d)" || { echo NEED_SECRET_SPEC; exit 1; }
    case "$VALUE" in
      ''|*"'"*) unset VALUE; echo NEED_SECRET_SPEC; exit 1 ;;
    esac
    { $SUDO grep -v "^${NAME_RE}=" "$LOCALF" || true; printf "%s='%s'\n" "$SECRET_NAME" "$VALUE"; } \
      | $SUDO tee "$NEW" >/dev/null
    unset VALUE SECRET_VALUE_B64
    if [ "$EXISTED" = "1" ]; then echo "SECRET_REPLACED=$SECRET_NAME"; else echo "SECRET_ADDED=$SECRET_NAME"; fi
    ;;
  unset)
    if [ "$EXISTED" = "0" ]; then
      echo "SECRET_ABSENT=$SECRET_NAME"
      echo SECRET_DONE
      exit 0
    fi
    { $SUDO grep -v "^${NAME_RE}=" "$LOCALF" || true; } | $SUDO tee "$NEW" >/dev/null
    echo "SECRET_REMOVED=$SECRET_NAME"
    ;;
  *) echo NEED_SECRET_SPEC; exit 1 ;;
esac
$SUDO mv -f "$NEW" "$LOCALF"
$SUDO chown deploy:deploy "$LOCALF"
$SUDO chmod 600 "$LOCALF"

if [ "$SECRET_RESTART" != "1" ]; then
  echo RESTART_SKIPPED
elif ! $SUDO docker ps -a --format '{{.Names}}' | grep -qx "plat-${PLATFORM_ID}-${SECRET_SERVICE}"; then
  echo RESTART_SKIPPED_NOT_DEPLOYED
elif $SUDO docker compose --project-directory "$ROOT" -f "$ROOT/docker-compose.yml" up -d --no-build "$SECRET_SERVICE" >/dev/null 2>&1; then
  echo "RESTARTED=$SECRET_SERVICE"
else
  echo "RESTART_FAILED=$SECRET_SERVICE"
fi
echo SECRET_DONE
