#!/usr/bin/env bash
# EDIPLUG AS2 node: one-time host preparation on Ubuntu 22.04 / 24.04 (ARM64 or x86-64).
# Installs Docker Engine + Compose v2 plugin (Docker's apt repository), AWS CLI v2, certbot with the Cloudflare DNS
# plugin, git, jq and (with --with-nfs, for the HA setup) the NFS client. Safe to run again.
#
#   sudo ./vm-bootstrap.sh              # standalone node
#   sudo ./vm-bootstrap.sh --with-nfs   # HA node (FSS mount)
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "run with sudo" >&2; exit 1; }
WITH_NFS=0
[ "${1:-}" = "--with-nfs" ] && WITH_NFS=1
RUN_USER=${SUDO_USER:-ubuntu}
export DEBIAN_FRONTEND=noninteractive

echo "== base packages"
apt-get update -q
apt-get install -y -q ca-certificates curl gnupg unzip git jq certbot python3-certbot-dns-cloudflare
[ "$WITH_NFS" -eq 1 ] && apt-get install -y -q nfs-common

echo "== Docker Engine + Compose v2"
if ! docker compose version >/dev/null 2>&1; then
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
    chmod a+r /etc/apt/keyrings/docker.asc
    codename=$(. /etc/os-release && echo "$VERSION_CODENAME")
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $codename stable" \
        > /etc/apt/sources.list.d/docker.list
    apt-get update -q
    apt-get install -y -q docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
systemctl enable --now docker
usermod -aG docker "$RUN_USER"

echo "== AWS CLI v2"
if ! command -v aws >/dev/null 2>&1; then
    tmp=$(mktemp -d)
    curl -fsSo "$tmp/awscliv2.zip" "https://awscli.amazonaws.com/awscli-exe-linux-$(uname -m).zip"
    unzip -q "$tmp/awscliv2.zip" -d "$tmp"
    "$tmp/aws/install"
    rm -rf "$tmp"
fi

echo
docker --version
docker compose version
aws --version
certbot --version
echo
echo "Done. Log out and back in (or run: newgrp docker) so '$RUN_USER' can use docker without sudo."
echo "OCI: allow TCP 80 and 443 in the VM's security list / NSG. Published Docker ports do not need iptables rules."
