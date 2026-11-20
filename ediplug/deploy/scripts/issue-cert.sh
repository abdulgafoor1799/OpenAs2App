#!/usr/bin/env bash
# EDIPLUG AS2 node: Let's Encrypt certificate via Cloudflare DNS-01 (works for public and private VMs: no inbound
# port 80 needed), plus a deploy hook that copies the renewed certificate to the nginx certs folder and reloads nginx.
#
# Standalone:
#   sudo ./issue-cert.sh --hostname as2-test.ediplug.com --email ops@ediplug.com \
#        --cloudflare-ini /root/.secrets/certbot/cloudflare.ini --certs-dir /opt/ediplug/nginx/certs
#   Renewal: the stock certbot.timer (twice a day) renews /etc/letsencrypt and runs the hook.
# HA (state on FSS):
#   sudo ./issue-cert.sh --hostname as2.ediplug.com --email ops@ediplug.com \
#        --cloudflare-ini /mnt/ediplug-shared/secrets/cloudflare.ini --certs-dir /mnt/ediplug-shared/certs \
#        --config-dir /mnt/ediplug-shared/letsencrypt
#   Renewal: ha-node.sh renew-cert (systemd timer, runs only on the active node).
#
# cloudflare.ini (mode 600):  dns_cloudflare_api_token = <token with Zone:DNS:Edit on ediplug.com>
set -euo pipefail

usage() { sed -n '2,16p' "$0"; exit 1; }
HOST=""; EMAIL=""; CF=""; CERTS=""; CONF=/etc/letsencrypt
while [ $# -gt 0 ]; do
    case "$1" in
        --hostname) HOST=$2; shift 2 ;;
        --email) EMAIL=$2; shift 2 ;;
        --cloudflare-ini) CF=$2; shift 2 ;;
        --certs-dir) CERTS=$2; shift 2 ;;
        --config-dir) CONF=$2; shift 2 ;;
        *) usage ;;
    esac
done
[ -n "$HOST" ] && [ -n "$EMAIL" ] && [ -n "$CF" ] && [ -n "$CERTS" ] || usage
[ "$(id -u)" -eq 0 ] || { echo "run with sudo" >&2; exit 1; }
[ -r "$CF" ] || { echo "Cloudflare credentials $CF not found" >&2; exit 1; }
chmod 600 "$CF"
mkdir -p "$CERTS" "$CONF/renewal-hooks/deploy"

HOOK="$CONF/renewal-hooks/deploy/ediplug-$HOST-cert-sync.sh"
cat > "$HOOK" <<EOF
#!/bin/sh
# Copies the renewed $HOST certificate to $CERTS and reloads nginx. Other certificates are ignored.
set -e
LIVE=$CONF/live/$HOST
[ "\${RENEWED_LINEAGE:-\$LIVE}" = "\$LIVE" ] || exit 0
install -m 644 "\$LIVE/fullchain.pem" "$CERTS/fullchain.pem.new" && mv -f "$CERTS/fullchain.pem.new" "$CERTS/fullchain.pem"
install -m 600 "\$LIVE/privkey.pem"   "$CERTS/privkey.pem.new"   && mv -f "$CERTS/privkey.pem.new"   "$CERTS/privkey.pem"
docker exec ediplug-nginx nginx -s reload >/dev/null 2>&1 || true
EOF
chmod 755 "$HOOK"

if [ -e "$CONF/live/$HOST/fullchain.pem" ]; then
    echo "== certificate for $HOST already exists in $CONF (renewal is automatic); syncing it"
else
    echo "== requesting certificate for $HOST (DNS-01, Cloudflare)"
    certbot certonly --non-interactive --agree-tos -m "$EMAIL" \
        --config-dir "$CONF" \
        --dns-cloudflare --dns-cloudflare-credentials "$CF" --dns-cloudflare-propagation-seconds 30 \
        -d "$HOST"
fi
RENEWED_LINEAGE="$CONF/live/$HOST" "$HOOK"
openssl x509 -in "$CERTS/fullchain.pem" -noout -subject -issuer -enddate
echo "Certificate files: $CERTS/fullchain.pem, $CERTS/privkey.pem"
