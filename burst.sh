#!/usr/bin/env bash
set -euo pipefail

# One-command burst script for the Seat Reservation Service
# Usage:
#   ./burst.sh [BASE_URL]
# Examples:
#   ./burst.sh https://seat-reservation-api-kundan.onrender.com
#   ./burst.sh http://localhost:8080

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PYTHON_BIN="${PYTHON_BIN:-python3}"

if ! command -v "${PYTHON_BIN}" >/dev/null 2>&1; then
    echo "Error: Python 3 is required to run the burst test." >&2
    exit 1
fi

BASE_URL="${1:-${BASE_URL:-https://seat-reservation-api-kundan.onrender.com}}"

# Default credentials for local development if not already provided
if [[ "${BASE_URL}" == *"localhost"* || "${BASE_URL}" == *"127.0.0.1"* ]]; then
    export JWT_SECRET="${JWT_SECRET:-test-jwt-secret-must-be-at-least-32-bytes-long-for-hs256!}"
    export DEMO_TOKEN_KEY="${DEMO_TOKEN_KEY:-demo-secret-key-for-local-evaluation}"
fi

exec "${PYTHON_BIN}" "${SCRIPT_DIR}/scripts/burst-test.py" "${BASE_URL}" "${@:2}"
