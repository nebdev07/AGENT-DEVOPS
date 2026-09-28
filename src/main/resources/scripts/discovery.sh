#!/bin/bash
# VPS Autopilot V0 — discovery READ-only. Aucune écriture, aucun apt, aucun systemctl start/stop.
set -u

section() { printf '\n###%s###\n' "$1"; }

SUDO=""
if command -v sudo >/dev/null 2>&1 && sudo -n true >/dev/null 2>&1; then
  SUDO="sudo -n"
fi

section OS
cat /etc/os-release 2>/dev/null || true

section KERNEL
uname -s
uname -r
uname -m

section CPU
nproc 2>/dev/null || echo 0
awk -F: '/model name/ {gsub(/^[ \t]+/, "", $2); print $2; exit}' /proc/cpuinfo 2>/dev/null || true

section MEM
awk '/MemTotal:|MemAvailable:|MemFree:/ {print}' /proc/meminfo 2>/dev/null || true

section DISK
df -B1 -P / 2>/dev/null | tail -n +2 || true

section HOST
hostname 2>/dev/null || true
hostname -I 2>/dev/null || true

section PORTS
if command -v ss >/dev/null 2>&1; then
  ss -tuln 2>/dev/null || true
elif command -v netstat >/dev/null 2>&1; then
  netstat -tuln 2>/dev/null || true
fi

section SERVICES
systemctl list-units --type=service --state=running --no-pager --plain --no-legend 2>/dev/null | awk '{print $1}' || true

section DOCKER
if command -v docker >/dev/null 2>&1; then
  echo PRESENT
  docker --version 2>/dev/null || true
  $SUDO docker compose version 2>/dev/null || docker compose version 2>/dev/null || docker-compose --version 2>/dev/null || echo COMPOSE_ABSENT
  $SUDO docker info --format '{{.ServerVersion}}' 2>/dev/null || docker info --format '{{.ServerVersion}}' 2>/dev/null || echo DAEMON_DOWN
  echo "GROUPS=$(id -nG 2>/dev/null || true)"
else
  echo ABSENT
fi

section NGINX
if command -v nginx >/dev/null 2>&1; then
  echo PRESENT
  nginx -v 2>&1 || true
else
  echo ABSENT
fi

section MARIADB
if command -v docker >/dev/null 2>&1; then
  if $SUDO docker ps --format '{{.Names}}' 2>/dev/null | grep -qx mariadb \
      || docker ps --format '{{.Names}}' 2>/dev/null | grep -qx mariadb; then
    echo PRESENT
    $SUDO docker inspect -f '{{.Config.Image}}' mariadb 2>/dev/null || true
  else
    echo ABSENT
  fi
else
  echo ABSENT
fi

section REDIS
if command -v docker >/dev/null 2>&1; then
  if $SUDO docker ps --format '{{.Names}}' 2>/dev/null | grep -qx redis \
      || docker ps --format '{{.Names}}' 2>/dev/null | grep -qx redis; then
    echo PRESENT
    $SUDO docker inspect -f '{{.Config.Image}}' redis 2>/dev/null || docker inspect -f '{{.Config.Image}}' redis 2>/dev/null || true
  else
    echo ABSENT
  fi
elif command -v redis-server >/dev/null 2>&1; then
  echo PRESENT
  echo REDIS_HOST
  redis-server --version 2>/dev/null || true
else
  echo ABSENT
fi

section TRAEFIK
if command -v docker >/dev/null 2>&1; then
  if $SUDO docker ps --format '{{.Names}}' 2>/dev/null | grep -qi traefik \
      || docker ps --format '{{.Names}}' 2>/dev/null | grep -qi traefik; then
    echo PRESENT
    $SUDO docker inspect -f '{{.Config.Image}}' traefik 2>/dev/null || docker inspect -f '{{.Config.Image}}' traefik 2>/dev/null || true
    $SUDO docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' traefik 2>/dev/null \
      | grep -E '^DOCKER_API_VERSION=' || docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' traefik 2>/dev/null \
      | grep -E '^DOCKER_API_VERSION=' || echo DOCKER_API_VERSION_ABSENT
  else
    echo ABSENT
  fi
else
  echo ABSENT
fi

section DEPLOY
if id deploy >/dev/null 2>&1 && id -nG deploy 2>/dev/null | grep -qw docker; then
  echo PRESENT
  id -nG deploy 2>/dev/null || true
else
  echo ABSENT
fi

section BACKUPS
if $SUDO test -f /opt/vps-autopilot/backups/.vps-autopilot-backups 2>/dev/null \
    || test -f /opt/vps-autopilot/backups/.vps-autopilot-backups 2>/dev/null; then
  echo PRESENT
  $SUDO ls -1 /opt/vps-autopilot/backups/*.enc 2>/dev/null | tail -n 3 \
    || ls -1 /opt/vps-autopilot/backups/*.enc 2>/dev/null | tail -n 3 \
    || true
else
  echo ABSENT
fi

section APPS
if command -v docker >/dev/null 2>&1; then
  $SUDO docker ps --filter label=vps.autopilot.app --format '{{.Label "vps.autopilot.app"}}' 2>/dev/null \
    || docker ps --filter label=vps.autopilot.app --format '{{.Label "vps.autopilot.app"}}' 2>/dev/null \
    || true
fi

section FIREWALL
if command -v ufw >/dev/null 2>&1; then
  echo ENGINE=ufw
  if [ -n "$SUDO" ]; then
    $SUDO ufw status 2>/dev/null || true
  else
    ufw status 2>/dev/null || true
  fi
elif command -v firewall-cmd >/dev/null 2>&1; then
  echo ENGINE=firewalld
  firewall-cmd --state 2>/dev/null || true
elif command -v nft >/dev/null 2>&1; then
  echo ENGINE=nft
  echo UNKNOWN
else
  echo ENGINE=unknown
  echo UNKNOWN
fi

section FAIL2BAN
if systemctl is-active fail2ban >/dev/null 2>&1; then
  echo ACTIVE
elif command -v fail2ban-client >/dev/null 2>&1; then
  echo INSTALLED
elif systemctl cat fail2ban.service >/dev/null 2>&1; then
  echo INSTALLED
else
  echo ABSENT
fi

section SSHD
SSHD_BIN=""
if [ -x /usr/sbin/sshd ]; then
  SSHD_BIN=/usr/sbin/sshd
elif command -v sshd >/dev/null 2>&1; then
  SSHD_BIN="$(command -v sshd)"
fi
if [ -n "$SSHD_BIN" ]; then
  if [ -n "$SUDO" ]; then
    $SUDO "$SSHD_BIN" -T 2>/dev/null | grep -E '^(permitrootlogin|passwordauthentication|pubkeyauthentication|port) ' || echo UNKNOWN
  else
    "$SSHD_BIN" -T 2>/dev/null | grep -E '^(permitrootlogin|passwordauthentication|pubkeyauthentication|port) ' || echo UNKNOWN
  fi
else
  echo UNKNOWN
fi

section SIGNALS
if command -v php >/dev/null 2>&1; then
  echo PHP_PRESENT
fi
if command -v apache2 >/dev/null 2>&1 || command -v httpd >/dev/null 2>&1; then
  echo APACHE_PRESENT
fi
if command -v nginx >/dev/null 2>&1; then
  echo NGINX_HOST_PRESENT
fi
if command -v mariadbd >/dev/null 2>&1 || command -v mysqld >/dev/null 2>&1; then
  echo MARIADB_HOST
fi
if ps -eo comm= 2>/dev/null | grep -Eiq 'xmrig|minerd|kinsing|kdevtmpfsi|cryptonight'; then
  echo MINER_PROCESS
fi

section WHO
whoami 2>/dev/null || true
id -un 2>/dev/null || true
id -Gn 2>/dev/null || true
