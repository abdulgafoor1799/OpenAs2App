#!/bin/sh
# EDIPLUG container entrypoint: prepares /opt/openas2/config, applies an optional runtime openas2.properties, then
# hands over to the stock start-container.sh (which starts OpenAS2 in the foreground).
#
# Runtime properties (no image rebuild needed):
#   Mount a folder holding openas2.properties at /opt/openas2/override (or point OPENAS2_PROPERTIES_OVERRIDE at a file).
#   An empty or missing folder = image defaults.
#   OPENAS2_PROPERTIES_OVERRIDE_MODE=merge (default)  baked openas2.properties + your file; your keys win, every other
#                                                      key keeps the image value. Put only the keys you change.
#   OPENAS2_PROPERTIES_OVERRIDE_MODE=replace           your file is used instead of the baked one (must be complete).
#   The effective file is rebuilt on every container start, so editing the mounted file + restart is enough.
#   If OPENAS2_PROPERTIES_FILE is set to anything other than the default path, that file is used as-is (s5/s6 way).
set -eu

BASE=${OPENAS2_BASE:-/opt/openas2}
DEFAULT_PROPS="$BASE/config/openas2.properties"
DEFAULT_OVERRIDE="$BASE/override/openas2.properties"
PROPS=${OPENAS2_PROPERTIES_FILE:-$DEFAULT_PROPS}
OVERRIDE=${OPENAS2_PROPERTIES_OVERRIDE:-$DEFAULT_OVERRIDE}
MODE=${OPENAS2_PROPERTIES_OVERRIDE_MODE:-merge}

log() { echo "[ediplug-entrypoint] $*"; }
fail() { echo "[ediplug-entrypoint] ERROR: $*" >&2; exit 1; }

# Same rule as the stock script: a fresh container gets config/ from the baked template.
if [ ! -e "$BASE/config/config.xml" ]; then
    log "Populating $BASE/config from config_template"
    cp -a "$BASE/config_template/." "$BASE/config/"
fi

if [ "$PROPS" != "$DEFAULT_PROPS" ]; then
    [ -r "$PROPS" ] || fail "OPENAS2_PROPERTIES_FILE=$PROPS is not readable"
    log "Using properties file $PROPS as-is (OPENAS2_PROPERTIES_FILE is set; override file ignored)"
else
    TEMPLATE="$BASE/config_template/openas2.properties"
    if [ -e "$OVERRIDE" ]; then
        [ -f "$OVERRIDE" ] || fail "$OVERRIDE is not a file (mount the folder that contains openas2.properties, or the file itself; Docker creates a directory when a bind-mount source file is missing)"
        [ -r "$OVERRIDE" ] || fail "override file $OVERRIDE exists but is not readable by UID $(id -u)"
        # Keys only (values may hold secrets): first token of every non-comment line
        KEYS=$(sed -e 's/\r$//' "$OVERRIDE" | grep -Ev '^[[:space:]]*([#!]|$)' | sed -E 's/^[[:space:]]*([^=:[:space:]]+).*/\1/' | tr '\n' ' ')
        case "$MODE" in
            merge)
                # java.util.Properties keeps the LAST value of a repeated key, so appending = overriding.
                { cat "$TEMPLATE"; printf '\n\n# ---- runtime override: %s (merged at container start) ----\n' "$OVERRIDE"
                  sed -e 's/\r$//' "$OVERRIDE"; printf '\n'; } > "$PROPS.tmp"
                log "Merged runtime properties from $OVERRIDE over the image defaults; keys: ${KEYS:-none}" ;;
            replace)
                sed -e 's/\r$//' "$OVERRIDE" > "$PROPS.tmp"
                log "Replaced image properties with $OVERRIDE; keys: ${KEYS:-none}" ;;
            *) fail "OPENAS2_PROPERTIES_OVERRIDE_MODE must be merge or replace, got '$MODE'" ;;
        esac
        mv -f "$PROPS.tmp" "$PROPS"
    else
        [ "$OVERRIDE" = "$DEFAULT_OVERRIDE" ] || fail "OPENAS2_PROPERTIES_OVERRIDE=$OVERRIDE does not exist"
        cp -f "$TEMPLATE" "$PROPS"
        log "No runtime properties override; using the image's openas2.properties"
    fi
fi

# storageBaseDir (the data directory) can itself be overridden; make sure it is writable before the JVM starts.
DATA_DIR=$(sed -e 's/\r$//' "$PROPS" | sed -nE 's/^[[:space:]]*storageBaseDir[[:space:]]*[=:][[:space:]]*(.*)$/\1/p' | tail -n 1)
DATA_DIR=$(printf '%s' "$DATA_DIR" | sed "s#%home%#$BASE/config#g")
DATA_DIR=${DATA_DIR:-$BASE/data}
mkdir -p "$DATA_DIR" 2>/dev/null || true
if ! ( : > "$DATA_DIR/.write-test" ) 2>/dev/null; then
    fail "data directory $DATA_DIR is not writable by UID $(id -u) GID $(id -g); on the host run: chown -R 1000:0 <dir>"
fi
rm -f "$DATA_DIR/.write-test"
log "Data directory: $DATA_DIR"

exec "$BASE/bin/start-container.sh"
