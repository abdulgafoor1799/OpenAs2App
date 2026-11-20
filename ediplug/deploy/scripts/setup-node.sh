#!/usr/bin/env bash
# EDIPLUG AS2 node: creates / refreshes the deploy folder from this git checkout.
#   - docker-compose.yml (= docker-compose.tls.yml), docker-compose.ha.yml, nginx template: always refreshed from git
#   - .env and override/openas2.properties: created once, never overwritten
#   - data/ (owned by UID 1000) and nginx/certs/
#
# Standalone (public VM):
#   sudo ./setup-node.sh --hostname as2-test.ediplug.com
# HA (private VM, shared state on FSS mounted at /mnt/ediplug-shared):
#   sudo ./setup-node.sh --hostname as2.ediplug.com --shared /mnt/ediplug-shared
#   -> data/, certs/, letsencrypt/, config/.env, config/override/ and ha/ live on FSS; /opt/ediplug/.env is a link.
set -euo pipefail

usage() { sed -n '2,13p' "$0"; exit 1; }
HOST=""; DIR=/opt/ediplug; SHARED=""
while [ $# -gt 0 ]; do
    case "$1" in
        --hostname) HOST=$2; shift 2 ;;
        --dir) DIR=$2; shift 2 ;;
        --shared) SHARED=$2; shift 2 ;;
        *) usage ;;
    esac
done
[ -n "$HOST" ] || usage
[ "$(id -u)" -eq 0 ] || { echo "run with sudo" >&2; exit 1; }
SRC=$(cd "$(dirname "$0")/.." && pwd)          # ediplug/deploy in the git checkout
RUN_USER=${SUDO_USER:-ubuntu}

set_env() {   # set_env KEY VALUE FILE: replace or append KEY=VALUE (follows a symlinked .env)
    if grep -q "^$1=" "$3"; then sed -i --follow-symlinks "s|^$1=.*|$1=$2|" "$3"; else echo "$1=$2" >> "$3"; fi
}

echo "== deploy folder $DIR (from $SRC)"
mkdir -p "$DIR/nginx"
install -m 644 "$SRC/docker-compose.tls.yml" "$DIR/docker-compose.yml"
install -m 644 "$SRC/docker-compose.ha.yml"  "$DIR/docker-compose.ha.yml"
install -m 644 "$SRC/nginx/nginx-tls.conf.template" "$DIR/nginx/nginx-tls.conf.template"

if [ -z "$SHARED" ]; then
    ENV_FILE="$DIR/.env"; OVR="$DIR/override"; DATA="$DIR/data"; CERTS="$DIR/nginx/certs"
else
    mountpoint -q "$SHARED" || { echo "$SHARED is not mounted (FSS)" >&2; exit 1; }
    mkdir -p "$SHARED/config" "$SHARED/data" "$SHARED/certs" "$SHARED/letsencrypt" "$SHARED/secrets" "$SHARED/ha"
    chmod 700 "$SHARED/secrets"
    ENV_FILE="$SHARED/config/.env"; OVR="$SHARED/config/override"; DATA="$SHARED/data"; CERTS="$SHARED/certs"
fi
mkdir -p "$OVR" "$DATA" "$CERTS"
install -m 644 "$SRC/override/openas2.properties.example" "$OVR/openas2.properties.example"

if [ ! -e "$OVR/openas2.properties" ]; then
    printf '# %s: async MDNs must come back to this node (the image default is prod)\nas2_async_mdn_url=https://%s/mdn\n' \
        "$HOST" "$HOST" > "$OVR/openas2.properties"
    echo "   created $OVR/openas2.properties"
fi

if [ ! -e "$ENV_FILE" ]; then
    install -m 600 "$SRC/.env.example" "$ENV_FILE"
    set_env AS2_PUBLIC_HOSTNAME "$HOST" "$ENV_FILE"
    created_env=1
fi
chmod 600 "$ENV_FILE"
chown "$RUN_USER": "$ENV_FILE"
if [ -n "$SHARED" ]; then
    ln -sfn "$ENV_FILE" "$DIR/.env"
    set_env AS2_DATA_DIR "$DATA" "$ENV_FILE"
    set_env AS2_CERTS_DIR "$CERTS" "$ENV_FILE"
    set_env AS2_OVERRIDE_DIR "$OVR" "$ENV_FILE"
    set_env COMPOSE_FILE "docker-compose.yml:docker-compose.ha.yml" "$ENV_FILE"
fi

# ownership: deploy files -> the admin user; data -> UID 1000 (the OpenAS2 container user), never the other way round
chown "$RUN_USER": "$DIR" "$DIR/nginx" "$DIR/docker-compose.yml" "$DIR/docker-compose.ha.yml"
chown -R "$RUN_USER": "$DIR/nginx/nginx-tls.conf.template" "$OVR"
chown -R 1000:0 "$DATA" && chmod -R g+rwX "$DATA"

echo
echo "Deploy folder ready: $DIR"
[ -n "$SHARED" ] && echo "Shared state on $SHARED (data, certs, letsencrypt, config/.env, config/override)"
if [ "${created_env:-0}" = 1 ]; then
    echo "NEXT: fill in $ENV_FILE (ECR_*, AS2_DB_*, AS2_KEY_ENCRYPTION_PASSWORD, AS2_KEYSTORE_PASSWORD, SMTP_*)"
else
    echo "$ENV_FILE already existed and was kept."
fi
