#!/bin/sh
# ==============================================================================
# Luban-RDS Docker Entrypoint Script
# ==============================================================================

set -e

: "${LUBAN_RDS_PORT:=9736}"
: "${LUBAN_RDS_BIND:=0.0.0.0}"
: "${LUBAN_RDS_DATA_DIR:=/data}"
: "${LUBAN_RDS_PERSIST_MODE:=rdb}"
: "${LUBAN_RDS_MAXMEMORY:=0}"
: "${LUBAN_RDS_DATABASES:=16}"
: "${LUBAN_RDS_TIMEOUT:=0}"
: "${LUBAN_RDS_TCP_KEEPALIVE:=300}"
: "${LUBAN_RDS_MAXMEMORY_POLICY:=noeviction}"
: "${LUBAN_RDS_SLOWLOG_SLOWER_THAN:=10000}"
: "${LUBAN_RDS_SLOWLOG_MAX_LEN:=128}"
: "${JAVA_OPTS:=-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=50}"

CONFIG_FILE="${LUBAN_RDS_CONFIG_FILE:-/app/config/luban-rds.conf}"

if [ ! -f "$CONFIG_FILE" ]; then
    echo "Generating configuration from environment variables..."
    cat > "$CONFIG_FILE" << EOF
# Luban-RDS Configuration (Auto-generated)
# Generated at: $(date -Iseconds)

# Network
bind ${LUBAN_RDS_BIND}
port ${LUBAN_RDS_PORT}
timeout ${LUBAN_RDS_TIMEOUT}
tcp-keepalive ${LUBAN_RDS_TCP_KEEPALIVE}

# General
databases ${LUBAN_RDS_DATABASES}
loglevel notice
logfile ""

# Persistence
persist-mode ${LUBAN_RDS_PERSIST_MODE}
dir ${LUBAN_RDS_DATA_DIR}
dbfilename dump.rdb
appendfilename appendonly.aof

# Memory Management
maxmemory ${LUBAN_RDS_MAXMEMORY}
maxmemory-policy ${LUBAN_RDS_MAXMEMORY_POLICY}

# Slow Log
slowlog-log-slower-than ${LUBAN_RDS_SLOWLOG_SLOWER_THAN}
slowlog-max-len ${LUBAN_RDS_SLOWLOG_MAX_LEN}
EOF

    if [ -n "${LUBAN_RDS_REQUIREPASS}" ]; then
        echo "requirepass ${LUBAN_RDS_REQUIREPASS}" >> "$CONFIG_FILE"
    fi
    
    echo "Configuration file generated at: $CONFIG_FILE"
fi

mkdir -p "$LUBAN_RDS_DATA_DIR"

if [ "$(id -u)" = "0" ]; then
    chown -R luban:luban "$LUBAN_RDS_DATA_DIR"
    exec su-exec luban "$@"
fi

exec "$@"
