#!/usr/bin/env bash
# EDIPLUG AS2 HA node (OCI NLB -> private VM, shared state on FSS). Only ONE node may run OpenAS2 on the shared data
# at a time; this script enforces it with an owner file + heartbeat on FSS.
#
#   sudo ./ha-node.sh install          # once per VM: systemd timers (heartbeat every 20 s, certificate renewal 2x/day)
#   sudo ./ha-node.sh start [--force]  # take over and start the stack (refuses while another node's heartbeat is fresh)
#   sudo ./ha-node.sh stop             # stop the stack and release the shared state
#   ./ha-node.sh status                # owner, heartbeat age, local containers
#   (timers) heartbeat | renew-cert
#
# --force: only after you have FENCED the other node (stopped/terminated it in the OCI console), e.g. when it died
# without releasing the lock and you do not want to wait STALE_SECONDS.
#
# Settings: /etc/default/ediplug-ha (created by install):
#   SHARED=/mnt/ediplug-shared  DEPLOY_DIR=/opt/ediplug  AS2_HOSTNAME=as2.ediplug.com  STALE_SECONDS=90
set -euo pipefail

CONF=/etc/default/ediplug-ha
[ -r "$CONF" ] && . "$CONF"
SHARED=${SHARED:-/mnt/ediplug-shared}
DEPLOY_DIR=${DEPLOY_DIR:-/opt/ediplug}
STALE_SECONDS=${STALE_SECONDS:-90}
OWNER_FILE="$SHARED/ha/active-node"
ME=$(hostname)
SCRIPT=$(readlink -f "$0")

log() { echo "[ha-node] $*"; logger -t ediplug-ha "$*" 2>/dev/null || true; }
need_root() { [ "$(id -u)" -eq 0 ] || { echo "run with sudo" >&2; exit 1; }; }
need_fss() { mountpoint -q "$SHARED" || { log "ERROR: $SHARED (FSS) is not mounted"; exit 1; }; }
owner() { [ -r "$OWNER_FILE" ] && awk '{print $1}' "$OWNER_FILE" || true; }
beat_age() { [ -r "$OWNER_FILE" ] && echo $(( $(date +%s) - $(awk '{print $2}' "$OWNER_FILE") )) || echo 999999; }
write_owner() {   # atomic on NFS: write a temp file, then rename
    mkdir -p "$SHARED/ha"
    echo "$ME $(date +%s)" > "$OWNER_FILE.$ME.tmp" && mv -f "$OWNER_FILE.$ME.tmp" "$OWNER_FILE"
}
openas2_running() { [ "$(docker inspect -f '{{.State.Running}}' EDIPLUG_AS2_SERVER 2>/dev/null || echo false)" = true ]; }
compose() { (cd "$DEPLOY_DIR" && docker compose "$@"); }

cmd=${1:-status}; shift || true
case "$cmd" in
install)
    need_root
    if [ ! -e "$CONF" ]; then
        printf 'SHARED=%s\nDEPLOY_DIR=%s\nAS2_HOSTNAME=%s\nSTALE_SECONDS=%s\n' "$SHARED" "$DEPLOY_DIR" "${AS2_HOSTNAME:-as2.ediplug.com}" "$STALE_SECONDS" > "$CONF"
        log "wrote $CONF (check AS2_HOSTNAME)"
    fi
    cat > /etc/systemd/system/ediplug-ha-heartbeat.service <<EOF
[Unit]
Description=EDIPLUG AS2 HA heartbeat (owner file on FSS)
[Service]
Type=oneshot
ExecStart=$SCRIPT heartbeat
EOF
    cat > /etc/systemd/system/ediplug-ha-heartbeat.timer <<EOF
[Unit]
Description=EDIPLUG AS2 HA heartbeat every 20 s
[Timer]
OnBootSec=30
OnUnitActiveSec=20
AccuracySec=1
[Install]
WantedBy=timers.target
EOF
    cat > /etc/systemd/system/ediplug-cert-renew.service <<EOF
[Unit]
Description=EDIPLUG AS2 certificate renewal (active node only, config on FSS)
[Service]
Type=oneshot
ExecStart=$SCRIPT renew-cert
EOF
    cat > /etc/systemd/system/ediplug-cert-renew.timer <<EOF
[Unit]
Description=EDIPLUG AS2 certificate renewal twice a day
[Timer]
OnCalendar=*-*-* 03,15:17:00
RandomizedDelaySec=1800
Persistent=true
[Install]
WantedBy=timers.target
EOF
    systemctl daemon-reload
    systemctl enable --now ediplug-ha-heartbeat.timer ediplug-cert-renew.timer
    log "installed timers (heartbeat, cert renewal)"
    ;;
start)
    need_root; need_fss
    FORCE=0; [ "${1:-}" = "--force" ] && FORCE=1
    cur=$(owner); age=$(beat_age)
    if [ -n "$cur" ] && [ "$cur" != "$ME" ] && [ "$age" -lt "$STALE_SECONDS" ] && [ "$FORCE" -ne 1 ]; then
        log "REFUSED: $cur is active (heartbeat ${age}s ago). Stop it first (ha-node.sh stop on $cur), or fence it"
        log "(stop/terminate the instance in OCI) and wait ${STALE_SECONDS}s / use --force."
        exit 1
    fi
    [ -n "$cur" ] && [ "$cur" != "$ME" ] && log "taking over from $cur (heartbeat ${age}s ago, force=$FORCE)"
    write_owner
    IMAGE=$(grep -E '^OPENAS2_IMAGE=' "$DEPLOY_DIR/.env" | tail -n 1 | cut -d= -f2-)
    [ -n "$IMAGE" ] || { log "ERROR: OPENAS2_IMAGE is not set in $DEPLOY_DIR/.env"; exit 1; }
    log "starting the stack on $ME with $IMAGE"
    # ECR login + pull (a replacement VM has neither), CPU check, start, wait until healthy
    "$(dirname "$SCRIPT")/deploy-openas2.sh" --dir "$DEPLOY_DIR" "${IMAGE##*:}"
    ;;
stop)
    need_root
    log "stopping the stack on $ME"
    compose down
    if mountpoint -q "$SHARED" && [ "$(owner)" = "$ME" ]; then rm -f "$OWNER_FILE"; log "released the shared state"; fi
    ;;
heartbeat)
    mountpoint -q "$SHARED" || { openas2_running && { log "FSS not mounted: stopping OpenAS2"; compose stop openas2; }; exit 0; }
    openas2_running || exit 0
    cur=$(owner); age=$(beat_age)
    if [ -n "$cur" ] && [ "$cur" != "$ME" ] && [ "$age" -lt "$STALE_SECONDS" ]; then
        # another node took over: never run two OpenAS2 on the same data
        log "SPLIT-BRAIN GUARD: $cur owns the shared state (heartbeat ${age}s ago); stopping the stack on $ME"
        compose down
        exit 0
    fi
    write_owner
    ;;
renew-cert)
    need_fss
    [ "$(owner)" = "$ME" ] || exit 0          # only the active node renews (one Let's Encrypt state on FSS)
    certbot renew --quiet --config-dir "$SHARED/letsencrypt"
    ;;
status)
    if mountpoint -q "$SHARED"; then
        echo "FSS:        $SHARED mounted"
        cur=$(owner)
        if [ -n "$cur" ]; then echo "owner:      $cur (heartbeat $(beat_age)s ago; stale after ${STALE_SECONDS}s)"; else echo "owner:      none (released)"; fi
    else
        echo "FSS:        $SHARED NOT mounted"
    fi
    echo "this node:  $ME"
    (cd "$DEPLOY_DIR" && docker compose ps --format 'table {{.Name}}\t{{.Status}}') 2>/dev/null || true
    ;;
*) sed -n '2,17p' "$0"; exit 1 ;;
esac
