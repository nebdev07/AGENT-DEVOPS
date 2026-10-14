set -u
# VPS Autopilot — sortie d'une tâche détachée terminée. Lecture seule. Variable : JOB_ID.
DIR="$HOME/.vps-autopilot/jobs/$JOB_ID"
echo '###JOB_OUT###'
base64 -w0 "$DIR/out.log" 2>/dev/null || true
echo
echo '###JOB_ERR###'
base64 -w0 "$DIR/err.log" 2>/dev/null || true
echo
echo '###JOB_END###'
