#!/usr/bin/env bash
set -euo pipefail
[[ $# == 3 || $# == 4 ]] || { printf 'usage: rollback-release.sh previous-sha8 env-file go-file [root]\n' >&2; exit 1; }
exec bash "$(dirname -- "$0")/switch-release.sh" "$@"
