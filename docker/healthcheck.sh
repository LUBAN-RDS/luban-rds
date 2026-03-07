#!/bin/sh
# ==============================================================================
# Luban-RDS Health Check Script
# ==============================================================================

set -e

HOST="${HEALTHCHECK_HOST:-localhost}"
PORT="${LUBAN_RDS_PORT:-9736}"
TIMEOUT="${HEALTHCHECK_TIMEOUT:-5}"

if command -v nc >/dev/null 2>&1; then
    if nc -z -w "$TIMEOUT" "$HOST" "$PORT" 2>/dev/null; then
        exit 0
    fi
elif command -v timeout >/dev/null 2>&1; then
    if timeout "$TIMEOUT" sh -c "echo > /dev/tcp/$HOST/$PORT" 2>/dev/null; then
        exit 0
    fi
else
    if echo > "/dev/tcp/$HOST/$PORT" 2>/dev/null; then
        exit 0
    fi
fi

exit 1
