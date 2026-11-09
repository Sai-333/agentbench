#!/usr/bin/env bash
# Idempotent host bootstrap for AgentBench on Oracle Cloud Always Free (Ubuntu aarch64/x86_64).
# - Installs Docker Engine + Compose plugin
# - Adds invoking user to docker group
# - Optional UFW baseline (SSH only; HTTP/HTTPS commented for Tunnel-first demos)
# Secret-free: never writes credentials or .env contents.
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root: sudo bash scripts/oracle/bootstrap.sh" >&2
  exit 1
fi

TARGET_USER="${SUDO_USER:-${USER}}"
export DEBIAN_FRONTEND=noninteractive

echo "==> apt update / base packages"
apt-get update -y
apt-get install -y ca-certificates curl gnupg lsb-release ufw

if ! command -v docker >/dev/null 2>&1; then
  echo "==> Installing Docker Engine + Compose plugin"
  install -m 0755 -d /etc/apt/keyrings
  if [[ ! -f /etc/apt/keyrings/docker.gpg ]]; then
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
      | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    chmod a+r /etc/apt/keyrings/docker.gpg
  fi
  ARCH="$(dpkg --print-architecture)"
  # shellcheck source=/dev/null
  . /etc/os-release
  CODENAME="${VERSION_CODENAME:-jammy}"
  echo "deb [arch=${ARCH} signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu ${CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -y
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
else
  echo "==> Docker already present; ensuring Compose plugin"
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin || true
fi

systemctl enable --now docker

if id "${TARGET_USER}" >/dev/null 2>&1; then
  echo "==> Adding ${TARGET_USER} to docker group"
  usermod -aG docker "${TARGET_USER}"
fi

echo "==> UFW baseline (SSH only)"
ufw default deny incoming
ufw default allow outgoing
ufw allow OpenSSH
# Uncomment when terminating TLS on-host with Caddy/nginx:
# ufw allow 80/tcp
# ufw allow 443/tcp
ufw --force enable

echo
echo "Bootstrap complete."
echo "  docker:  $(docker --version 2>/dev/null || echo missing)"
echo "  compose: $(docker compose version 2>/dev/null || echo missing)"
echo "Log out/in (or: newgrp docker) so ${TARGET_USER} can run docker without sudo."
echo "Next: cp .env.example .env  # set secrets"
echo "      docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build"
echo "      bash scripts/oracle/healthcheck.sh"
