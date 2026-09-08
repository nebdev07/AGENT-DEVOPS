set -eu
APPLY_DOCKER="${APPLY_DOCKER:-1}"
APPLY_COMPOSE="${APPLY_COMPOSE:-1}"
APPLY_DOCKER_GROUP="${APPLY_DOCKER_GROUP:-1}"
umask 022
if [ "$(id -u)" -eq 0 ]; then
  SUDO=""
  TARGET_USER="${SUDO_USER:-root}"
else
  SUDO="sudo -n"
  TARGET_USER="$(id -un)"
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

export DEBIAN_FRONTEND=noninteractive
if [ "$APPLY_DOCKER" = "1" ] || [ "$APPLY_COMPOSE" = "1" ]; then
  $SUDO apt-get update -y
  if [ "$APPLY_DOCKER" = "1" ] && [ "$APPLY_COMPOSE" = "1" ]; then
    $SUDO apt-get install -y docker.io docker-compose-v2 || $SUDO apt-get install -y docker.io docker-compose
  elif [ "$APPLY_DOCKER" = "1" ]; then
    $SUDO apt-get install -y docker.io
  else
    $SUDO apt-get install -y docker-compose-v2 || $SUDO apt-get install -y docker-compose
  fi
fi
if [ "$APPLY_DOCKER" = "1" ]; then
  $SUDO systemctl enable --now docker
fi
if [ "$APPLY_DOCKER_GROUP" = "1" ] && [ "$TARGET_USER" != "root" ]; then
  $SUDO usermod -aG docker "$TARGET_USER" || true
fi
echo DOCKER_APPLIED
