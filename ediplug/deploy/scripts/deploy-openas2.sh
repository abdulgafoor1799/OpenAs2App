#!/usr/bin/env bash
# EDIPLUG AS2 node: deploy an OpenAS2 image tag from ECR (tags are chosen at deploy time).
#
#   ./deploy-openas2.sh --list              # tags in ECR, newest first
#   ./deploy-openas2.sh --current           # what is configured / running
#   ./deploy-openas2.sh <tag>               # e.g. arm64-v2: check tag + CPU, pull, (re)start, wait until healthy
#   ./deploy-openas2.sh --rollback          # back to the tag that was deployed before
#   ./deploy-openas2.sh --start             # start the stack with the configured image (no pull)
#
# Run as the admin user (member of the docker group). Options: --dir /opt/ediplug (default).
# Reads from <dir>/.env: ECR_REGISTRY, ECR_REPOSITORY, AWS_REGION, ECR_AWS_PROFILE (AWS CLI profile holding the
# pull-only key: aws configure --profile ecr-pull) and optionally ECR_AWS_CREDENTIALS_FILE (a credentials file with
# that profile, e.g. on FSS in the HA setup). Writes OPENAS2_IMAGE (and OPENAS2_IMAGE_PREVIOUS) into .env.
# In the HA setup use ha-node.sh: it refuses to start while another node holds the shared state.
set -euo pipefail

DIR=/opt/ediplug; ACTION=""; TAG=""
while [ $# -gt 0 ]; do
    case "$1" in
        --dir) DIR=$2; shift 2 ;;
        --list|--current|--rollback|--start) ACTION=$1; shift ;;
        -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
        -*) echo "unknown option $1" >&2; exit 1 ;;
        *) TAG=$1; ACTION=deploy; shift ;;
    esac
done
[ -n "$ACTION" ] || { sed -n '2,14p' "$0"; exit 1; }
ENV_FILE="$DIR/.env"
[ -r "$ENV_FILE" ] || { echo "$ENV_FILE not found" >&2; exit 1; }
cd "$DIR"

envget() { grep -E "^$1=" "$ENV_FILE" | tail -n 1 | cut -d= -f2- || true; }
envset() { if grep -q "^$1=" "$ENV_FILE"; then sed -i --follow-symlinks "s|^$1=.*|$1=$2|" "$ENV_FILE"; else echo "$1=$2" >> "$ENV_FILE"; fi; }

REGISTRY=$(envget ECR_REGISTRY); REPO=$(envget ECR_REPOSITORY); REGION=$(envget AWS_REGION)
PROFILE=$(envget ECR_AWS_PROFILE); PROFILE=${PROFILE:-ecr-pull}
CREDS=$(envget ECR_AWS_CREDENTIALS_FILE)
[ -n "$CREDS" ] && export AWS_SHARED_CREDENTIALS_FILE="$CREDS"
AWS=(aws --profile "$PROFILE" --region "${REGION:-ap-south-1}")

# HA node (ha-node.sh install wrote /etc/default/ediplug-ha): only the node that owns the shared state may start
# OpenAS2. ha-node.sh start takes ownership first and then calls this script.
ha_guard() {
    [ -r /etc/default/ediplug-ha ] || return 0
    local shared owner
    shared=$(grep -E '^SHARED=' /etc/default/ediplug-ha | cut -d= -f2-)
    owner=$(awk '{print $1}' "${shared:-/mnt/ediplug-shared}/ha/active-node" 2>/dev/null || true)
    if [ "$owner" != "$(hostname)" ]; then
        echo "!! HA node: this host does not own the shared state (owner: ${owner:-none}). Use: sudo ha-node.sh start" >&2
        exit 1
    fi
}

host_arch() { case "$(uname -m)" in aarch64|arm64) echo arm64 ;; x86_64|amd64) echo amd64 ;; *) uname -m ;; esac; }

wait_healthy() {
    echo "== waiting for OpenAS2 to become healthy (up to 4 min)"
    for _ in $(seq 1 48); do
        state=$(docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' EDIPLUG_AS2_SERVER 2>/dev/null || echo missing)
        case "$state" in
            "running healthy") echo "   healthy"; return 0 ;;
            exited*|dead*|missing) break ;;
        esac
        sleep 5
    done
    echo "!! OpenAS2 is not healthy ($state). Last log lines:" >&2
    docker logs --tail 40 EDIPLUG_AS2_SERVER 2>&1 | sed 's/^/   /' >&2
    return 1
}

summary() {
    docker compose ps --format 'table {{.Name}}\t{{.Image}}\t{{.Status}}'
    docker logs EDIPLUG_AS2_SERVER 2>&1 | grep -E "ediplug-entrypoint|Loaded|Server v.* started" | tail -n 8 | cut -c1-200 || true
}

case "$ACTION" in
--list)
    [ -n "$REGISTRY" ] && [ -n "$REPO" ] || { echo "set ECR_REGISTRY and ECR_REPOSITORY in .env" >&2; exit 1; }
    "${AWS[@]}" ecr describe-images --repository-name "$REPO" --filter tagStatus=TAGGED \
        --query 'reverse(sort_by(imageDetails,&imagePushedAt))[].[join(`,`,imageTags),imagePushedAt,imageDigest]' --output text
    ;;
--current)
    echo "configured: $(envget OPENAS2_IMAGE)"
    echo "previous:   $(envget OPENAS2_IMAGE_PREVIOUS)"
    docker inspect -f 'running:    {{.Config.Image}} ({{.Image}}) {{.State.Status}}' EDIPLUG_AS2_SERVER 2>/dev/null || echo "running:    (not running)"
    ;;
--start)
    ha_guard
    docker compose up -d
    wait_healthy && summary
    ;;
--rollback)
    PREV=$(envget OPENAS2_IMAGE_PREVIOUS)
    [ -n "$PREV" ] || { echo "no OPENAS2_IMAGE_PREVIOUS in .env" >&2; exit 1; }
    exec "$0" --dir "$DIR" "${PREV##*:}"
    ;;
deploy)
    ha_guard
    [ -n "$REGISTRY" ] && [ -n "$REPO" ] || { echo "set ECR_REGISTRY and ECR_REPOSITORY in .env" >&2; exit 1; }
    IMAGE="$REGISTRY/$REPO:$TAG"
    echo "== checking $IMAGE in ECR"
    "${AWS[@]}" ecr describe-images --repository-name "$REPO" --image-ids imageTag="$TAG" \
        --query 'imageDetails[0].imageDigest' --output text
    echo "== ECR login"
    "${AWS[@]}" ecr get-login-password | docker login --username AWS --password-stdin "$REGISTRY" >/dev/null
    echo "== pulling"
    docker pull -q "$IMAGE"
    img_arch=$(docker image inspect -f '{{.Architecture}}' "$IMAGE")
    if [ "$img_arch" != "$(host_arch)" ]; then
        echo "!! $IMAGE is $img_arch but this host is $(host_arch): use the matching tag (arm64-* / x86-*)" >&2
        exit 1
    fi
    CUR=$(envget OPENAS2_IMAGE)
    [ -n "$CUR" ] && [ "$CUR" != "$IMAGE" ] && envset OPENAS2_IMAGE_PREVIOUS "$CUR"
    envset OPENAS2_IMAGE "$IMAGE"
    echo "== starting $IMAGE"
    docker compose up -d
    if wait_healthy; then
        summary
    else
        echo "Roll back with: $0 --rollback   (previous: $(envget OPENAS2_IMAGE_PREVIOUS))" >&2
        exit 1
    fi
    docker logout "$REGISTRY" >/dev/null 2>&1 || true
    ;;
esac
