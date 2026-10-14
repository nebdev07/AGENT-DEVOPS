set -u
# VPS Autopilot — état d'une tâche détachée. Lecture seule. Variable : JOB_ID.
DIR="$HOME/.vps-autopilot/jobs/$JOB_ID"
if [ ! -d "$DIR" ]; then
  echo JOB_UNKNOWN
  exit 0
fi
if [ -f "$DIR/exit" ]; then
  echo "JOB_EXIT=$(cat "$DIR/exit")"
  exit 0
fi
if [ -f "$DIR/pid" ] && kill -0 "$(cat "$DIR/pid")" 2>/dev/null; then
  echo JOB_RUNNING
  echo "JOB_TAIL=$(tail -n 1 "$DIR/out.log" 2>/dev/null | tr -cd '[:print:]' | cut -c1-160)"
  exit 0
fi
# Le processus a pu finir entre les deux contrôles.
if [ -f "$DIR/exit" ]; then
  echo "JOB_EXIT=$(cat "$DIR/exit")"
elif [ -f "$DIR/pid" ]; then
  echo JOB_LOST
else
  echo JOB_STARTING
fi
