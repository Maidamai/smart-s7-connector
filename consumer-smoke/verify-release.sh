#!/usr/bin/env bash
# Stable entry point. Python performs strict metadata parsing with stdlib only.
# Example: S7_VERSION=1.0.0-rc.2 PUBKEY_FILE=/trusted/public-key.asc bash verify-release.sh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "${PYTHON:-python3}" "${SCRIPT_DIR}/verify_release.py" "$@"
