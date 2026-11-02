#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/release-common.sh"
[[ $# == 3 || $# == 4 ]] || die 'usage: switch-release.sh sha8 env-file go-file [root]'
sha=$1; env_file=$2; go_file=$3; root=${4:-/opt/pastport}
validate_sha; initialize
bash "$(dirname -- "$0")/check-env.sh" "$env_file" >/dev/null || die 'environment check failed'
[[ -f $go_file && ! -L $go_file && $(stat -c %u -- "$go_file") == 0 && $(stat -c %a -- "$go_file") == 600 ]] || die 'approved gate file required'
for gate in CODE DB STORAGE HTTPS ENV ARTIFACT; do
    [[ $(grep -c "^$gate=GO$" "$go_file") == 1 ]] || die 'pre-start gate missing'
done
target="$root/releases/$sha.jar"; verify_release "$target"
previous=''
if [[ -e $root/current.jar || -L $root/current.jar ]]; then
    [[ -L $root/current.jar ]] || die 'current must be a symlink'
    previous=$(readlink -f -- "$root/current.jar")
    [[ $previous == "$root/releases/"* && $(dirname -- "$previous") == "$root/releases" ]] || die 'previous release outside root'
    verify_release "$previous"
fi
printf '%s\n' "$previous" > "$root/previous-release.txt"
activate "$target"
if systemctl restart pastport.service && healthy; then
    printf 'INTERNAL HEALTH OK: %s; SMOKE GO still required before exposure\n' "$sha"
else
    if [[ -n $previous ]]; then
        activate "$previous"
        systemctl restart pastport.service && healthy || die 'rollback health failed: keep public traffic blocked'
    else
        systemctl stop pastport.service || true
        rm -- "$root/current.jar"
    fi
    die 'new release unhealthy; reverted (DB changes are not reverted)'
fi
