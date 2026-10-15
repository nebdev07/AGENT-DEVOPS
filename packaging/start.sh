#!/bin/sh
# VPS Autopilot : démarre la console sur http://127.0.0.1:8088 (Java 21 requis).
set -eu
cd "$(dirname "$0")"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
MAJOR="$("$JAVA" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)"
if [ -z "$MAJOR" ] || [ "$MAJOR" -lt 21 ]; then
  echo "Java 21 ou plus est requis (trouvé : ${MAJOR:-aucun})." >&2
  exit 1
fi
if [ ! -f .env ]; then
  cp .env.example .env
  echo "Fichier .env créé : renseigne DEVOPS_SSH_KEY (chemin de la clé privée de l'agent), puis relance."
  exit 1
fi
mkdir -p state
echo "Console : http://127.0.0.1:8088  (Ctrl+C pour arrêter)"
exec "$JAVA" -jar vps-autopilot.jar
