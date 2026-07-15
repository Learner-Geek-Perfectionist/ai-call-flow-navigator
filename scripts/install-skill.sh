#!/bin/sh
set -eu

SCRIPT_DIR=$(
  CDPATH=
  cd -- "$(dirname -- "$0")"
  pwd
)
INSTALLER="$SCRIPT_DIR/install-skill.py"

python_is_compatible() {
  "$1" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 8) else 1)' \
    >/dev/null 2>&1
}

if command -v python3 >/dev/null 2>&1 && python_is_compatible python3; then
  exec python3 "$INSTALLER" "$@"
fi

if command -v python >/dev/null 2>&1 && python_is_compatible python; then
  exec python "$INSTALLER" "$@"
fi

echo "Python 3.8 or newer is required to install the Skill." >&2
exit 1
