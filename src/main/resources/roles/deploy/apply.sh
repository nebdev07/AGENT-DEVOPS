set -eu
# VPS Autopilot — compte deploy (V3). Docker, pas sudo, pas de mot de passe.
# N’applique que APPLY_*=1. Idempotent. Ne déploie aucune app.
APPLY_USER="${APPLY_USER:-1}"
APPLY_KEYS="${APPLY_KEYS:-1}"
APPLY_SSH_ALLOW="${APPLY_SSH_ALLOW:-1}"
umask 077
if [ "$(id -u)" -eq 0 ]; then
  SUDO=""
  REMOTE_USER="${SUDO_USER:-root}"
  AUTH_SRC="/root/.ssh/authorized_keys"
else
  SUDO="sudo -n"
  REMOTE_USER="$(id -un)"
  AUTH_SRC="${HOME}/.ssh/authorized_keys"
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

if ! command -v docker >/dev/null 2>&1; then
  echo DOCKER_MISSING
  exit 1
fi
if ! $SUDO docker info >/dev/null 2>&1; then
  echo DOCKER_MISSING
  exit 1
fi

DEPLOY_USER=deploy

if [ "$APPLY_USER" = "1" ]; then
  if ! id "$DEPLOY_USER" >/dev/null 2>&1; then
    $SUDO useradd --create-home --shell /bin/bash --user-group "$DEPLOY_USER"
  fi
  $SUDO usermod -aG docker "$DEPLOY_USER"
  $SUDO passwd -l "$DEPLOY_USER" >/dev/null 2>&1 || true
  if $SUDO test -d /etc/sudoers.d; then
    $SUDO rm -f /etc/sudoers.d/"$DEPLOY_USER"
  fi
fi

if [ "$APPLY_KEYS" = "1" ]; then
  if ! id "$DEPLOY_USER" >/dev/null 2>&1; then
    echo NEED_DEPLOY_USER
    exit 1
  fi
  if ! grep -qE '^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp256|sk-ssh-ed25519) ' "$AUTH_SRC" 2>/dev/null; then
    echo NEED_AUTHORIZED_KEY
    exit 1
  fi
  DEPLOY_HOME="$($SUDO getent passwd "$DEPLOY_USER" | awk -F: '{print $6}')"
  $SUDO mkdir -p "$DEPLOY_HOME/.ssh"
  $SUDO chmod 700 "$DEPLOY_HOME/.ssh"
  $SUDO touch "$DEPLOY_HOME/.ssh/authorized_keys"
  $SUDO chmod 600 "$DEPLOY_HOME/.ssh/authorized_keys"
  $SUDO chown -R "$DEPLOY_USER:$DEPLOY_USER" "$DEPLOY_HOME/.ssh"
  grep -E '^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp256|sk-ssh-ed25519) ' "$AUTH_SRC" | while read -r line; do
    if ! $SUDO grep -qxF "$line" "$DEPLOY_HOME/.ssh/authorized_keys"; then
      printf '%s\n' "$line" | $SUDO tee -a "$DEPLOY_HOME/.ssh/authorized_keys" >/dev/null
    fi
  done
  $SUDO chown "$DEPLOY_USER:$DEPLOY_USER" "$DEPLOY_HOME/.ssh/authorized_keys"
fi

if [ "$APPLY_SSH_ALLOW" = "1" ]; then
  if ! id "$DEPLOY_USER" >/dev/null 2>&1; then
    echo NEED_DEPLOY_USER
    exit 1
  fi
  SSHD_BIN=""
  if [ -x /usr/sbin/sshd ]; then
    SSHD_BIN=/usr/sbin/sshd
  elif command -v sshd >/dev/null 2>&1; then
    SSHD_BIN="$(command -v sshd)"
  else
    echo SSHD_MISSING
    exit 1
  fi
  DROPIN=/etc/ssh/sshd_config.d/00-vps-autopilot.conf
  if ! $SUDO test -f "$DROPIN"; then
    echo NEED_SSH_DROPIN
    exit 1
  fi
  BAK="${DROPIN}.bak-deploy"
  $SUDO cp "$DROPIN" "$BAK"
  KEEP="$REMOTE_USER"
  $SUDO awk -v add="$DEPLOY_USER" -v keep="$KEEP" '
    BEGIN { done=0 }
    /^AllowUsers / {
      found=0
      for (i=2; i<=NF; i++) if ($i==add) found=1
      if (!found) $0 = $0 " " add
      done=1
    }
    { print }
    END {
      if (!done) print "AllowUsers " keep " " add
    }
  ' "$DROPIN" | $SUDO tee "${DROPIN}.new" >/dev/null
  $SUDO mv "${DROPIN}.new" "$DROPIN"
  if ! $SUDO "$SSHD_BIN" -t; then
    echo SSHD_TEST_FAILED
    $SUDO mv "$BAK" "$DROPIN"
    exit 1
  fi
  $SUDO rm -f "$BAK"
  $SUDO systemctl reload ssh 2>/dev/null || $SUDO systemctl reload sshd
fi

echo DEPLOY_APPLIED
