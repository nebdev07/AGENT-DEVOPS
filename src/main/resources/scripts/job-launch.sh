set -eu
# VPS Autopilot — lance un script de rôle détaché de la session SSH (ADR-040).
# Variables : JOB_ID JOB_SCRIPT_B64. Rejouable : un dossier de tâche existant n'est jamais relancé.
umask 077
BASE="$HOME/.vps-autopilot/jobs"
DIR="$BASE/$JOB_ID"
mkdir -p "$BASE"
if [ -d "$DIR" ]; then
  echo "JOB_STARTED=$JOB_ID"
  echo "JOB_MODE=$(cat "$DIR/started" 2>/dev/null || echo unknown)"
  exit 0
fi
find "$BASE" -mindepth 1 -maxdepth 1 -type d -mtime +14 -exec rm -rf {} + 2>/dev/null || true
mkdir "$DIR"
printf '%s' "$JOB_SCRIPT_B64" | base64 -d > "$DIR/script.sh"
cat > "$DIR/run.sh" <<'RUN'
#!/bin/bash
umask 077
cd "$(dirname "$0")"
echo $$ > pid
/bin/bash ./script.sh > out.log 2> err.log < /dev/null
rc=$?
rm -f ./script.sh
echo "$rc" > exit.tmp && mv exit.tmp exit
RUN

MODE=nohup
if [ "$(id -u)" -eq 0 ]; then SUDO=""; else SUDO="sudo -n"; fi
if command -v systemd-run >/dev/null 2>&1 && { [ -z "$SUDO" ] || sudo -n true 2>/dev/null; }; then
  if $SUDO systemd-run --quiet --collect --unit="vps-autopilot-job-$JOB_ID" \
      --uid="$(id -u)" --gid="$(id -g)" --setenv=HOME="$HOME" --working-directory="$DIR" \
      /bin/bash "$DIR/run.sh" >/dev/null 2>"$DIR/launch.err"; then
    MODE=systemd
  fi
fi
if [ "$MODE" = nohup ]; then
  setsid nohup /bin/bash "$DIR/run.sh" >/dev/null 2>&1 < /dev/null &
fi
echo "$MODE" > "$DIR/started"
echo "JOB_STARTED=$JOB_ID"
echo "JOB_MODE=$MODE"
