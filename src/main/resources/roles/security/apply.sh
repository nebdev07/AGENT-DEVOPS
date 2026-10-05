set -eu
# VPS Autopilot — baseline sécurité. N’applique que APPLY_*=1.
# Idempotent. Ne change pas le port SSH. Ne crée pas de user deploy.
APPLY_UFW="${APPLY_UFW:-1}"
APPLY_FAIL2BAN="${APPLY_FAIL2BAN:-1}"
APPLY_SSH="${APPLY_SSH:-1}"
APPLY_UPDATES="${APPLY_UPDATES:-1}"
umask 022
if [ "$(id -u)" -eq 0 ]; then
  SUDO=""
  REMOTE_USER="${SUDO_USER:-root}"
  AUTH_FILE="/root/.ssh/authorized_keys"
else
  SUDO="sudo -n"
  REMOTE_USER="$(id -un)"
  AUTH_FILE="${HOME}/.ssh/authorized_keys"
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

if [ "$APPLY_SSH" = "1" ]; then
  if ! grep -qE '^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp256|sk-ssh-ed25519) ' "$AUTH_FILE" 2>/dev/null; then
    echo NEED_AUTHORIZED_KEY
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
fi

export DEBIAN_FRONTEND=noninteractive
PKGS=""
if [ "$APPLY_UFW" = "1" ]; then
  PKGS="$PKGS ufw"
fi
if [ "$APPLY_FAIL2BAN" = "1" ]; then
  PKGS="$PKGS fail2ban"
fi
if [ "$APPLY_UPDATES" = "1" ]; then
  PKGS="$PKGS unattended-upgrades"
fi
if [ -n "$PKGS" ]; then
  $SUDO apt-get update -y
  # shellcheck disable=SC2086
  $SUDO apt-get install -y $PKGS
fi

if [ "$APPLY_UFW" = "1" ]; then
  $SUDO ufw default deny incoming
  $SUDO ufw default allow outgoing
  $SUDO ufw allow OpenSSH
  $SUDO ufw allow 22/tcp
  $SUDO ufw --force enable
fi

if [ "$APPLY_FAIL2BAN" = "1" ]; then
  $SUDO mkdir -p /etc/fail2ban/jail.d
  printf '%s\n' \
    '[sshd]' \
    'enabled = true' \
    'backend = systemd' \
    'maxretry = 4' \
    'findtime = 10m' \
    'bantime = 1h' \
    | $SUDO tee /etc/fail2ban/jail.d/sshd-autopilot.conf >/dev/null
  $SUDO systemctl enable --now fail2ban
  $SUDO systemctl restart fail2ban
fi

if [ "$APPLY_UPDATES" = "1" ]; then
  printf '%s\n' \
    'APT::Periodic::Update-Package-Lists "1";' \
    'APT::Periodic::Unattended-Upgrade "1";' \
    | $SUDO tee /etc/apt/apt.conf.d/20auto-upgrades >/dev/null
fi

if [ "$APPLY_SSH" = "1" ]; then
  if [ "$(id -u)" -eq 0 ]; then
    PERMIT_ROOT="prohibit-password"
    ALLOW_USERS="root"
  else
    PERMIT_ROOT="no"
    ALLOW_USERS="$REMOTE_USER"
  fi
  DROPIN=/etc/ssh/sshd_config.d/00-vps-autopilot.conf
  # Ne jamais enfermer dehors un admin humain (compte admin personnel) : on garde les AllowUsers déjà posés
  # et chaque membre du groupe sudo qui a une clé dans son authorized_keys.
  if [ "$(id -u)" -ne 0 ]; then
    PREV="$($SUDO awk '/^AllowUsers /{for (i=2; i<=NF; i++) print $i}' "$DROPIN" 2>/dev/null || true)"
    ADMINS=""
    for U in $(getent group sudo | cut -d: -f4 | tr ',' ' '); do
      H="$(getent passwd "$U" | cut -d: -f6)"
      if [ -n "$H" ] && $SUDO grep -qE '^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp256|sk-ssh-ed25519) ' "$H/.ssh/authorized_keys" 2>/dev/null; then
        ADMINS="$ADMINS $U"
      fi
    done
    for U in $PREV $ADMINS; do
      case " $ALLOW_USERS " in *" $U "*) ;; *) ALLOW_USERS="$ALLOW_USERS $U" ;; esac
    done
  fi
  echo "SSH_ALLOWED=$ALLOW_USERS"
  $SUDO mkdir -p /etc/ssh/sshd_config.d
  # OpenSSH : première valeur gagnante. Un drop-in 50-cloud-init
  # (PasswordAuthentication yes) l’emporte sur un fichier 99-*.
  printf '%s\n' \
    "PasswordAuthentication no" \
    "KbdInteractiveAuthentication no" \
    "PermitRootLogin ${PERMIT_ROOT}" \
    "PubkeyAuthentication yes" \
    "MaxAuthTries 3" \
    "X11Forwarding no" \
    "AllowUsers ${ALLOW_USERS}" \
    | $SUDO tee "$DROPIN" >/dev/null
  $SUDO rm -f /etc/ssh/sshd_config.d/99-vps-autopilot.conf
  if ! $SUDO "$SSHD_BIN" -t; then
    echo SSHD_TEST_FAILED
    $SUDO rm -f "$DROPIN"
    exit 1
  fi
  $SUDO systemctl reload ssh 2>/dev/null || $SUDO systemctl reload sshd
fi

echo SECURITY_APPLIED
