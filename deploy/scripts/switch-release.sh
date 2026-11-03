#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/release-common.sh"
[[ $# == 3 || $# == 4 ]] || die 'usage: switch-release.sh sha8 env-file go-file [root]'
sha=$1; env_file=$2; go_file=$3; root=${4:-/opt/pastport}
validate_sha; initialize
bash "$(dirname -- "$0")/check-env.sh" "$env_file" >/dev/null || die 'environment check failed'
[[ -f $go_file && ! -L $go_file && $(stat -c %u -- "$go_file") == 0 && $(stat -c %a -- "$go_file") == 600 ]] || die 'approved gate file required'
for gate in CODE DB STORAGE HTTPS ENV ARTIFACT; do
    [[ $(grep -c "^$gate=" "$go_file") == 1 ]] || die 'duplicate or conflicting gate'
    [[ $(grep -c "^$gate=GO$" "$go_file") == 1 ]] || die 'pre-start gate missing'
done
target="$root/releases/$sha.jar"; verify_release "$target"
gate_value() {
    [[ $(grep -c "^$1=" "$go_file") == 1 ]] || die 'deployment mode/commit approval missing or duplicated'
    sed -n "s/^$1=//p" "$go_file"
}
mode=$(gate_value RELEASE_MODE)
candidate=$(gate_value CANDIDATE_SHA)
[[ $candidate == "$(sed -n 's/^commit=//p' "${target%.jar}.manifest.txt")" ]] || die 'candidate approval differs from artifact'
[[ $mode == BOOTSTRAP || $mode == UPGRADE ]] || die 'explicit BOOTSTRAP or UPGRADE required'
previous=''
require_inactive() {
    local state
    state=$(systemctl show pastport.service --property=LoadState --property=ActiveState) || die 'STATE_UNKNOWN: service query failed; preserve links and artifacts'
    [[ $(printf '%s\n' "$state" | grep -c '^LoadState=') == 1 && $(printf '%s\n' "$state" | grep -c '^ActiveState=') == 1 ]] || die 'STATE_UNKNOWN: ambiguous service state; preserve links and artifacts'
    [[ $(printf '%s\n' "$state" | sed -n 's/^LoadState=//p') == loaded && $(printf '%s\n' "$state" | sed -n 's/^ActiveState=//p') == inactive ]] || die 'STATE_UNKNOWN: bootstrap requires loaded inactive service; preserve links and artifacts'
}
if [[ $mode == BOOTSTRAP ]]; then
    [[ ! -e $root/current.jar && ! -L $root/current.jar ]] || die 'bootstrap requires no current link; preserve and review existing state'
    require_inactive
fi
if [[ -e $root/current.jar || -L $root/current.jar ]]; then
    [[ -L $root/current.jar ]] || die 'current must be a symlink'
    previous=$(readlink -f -- "$root/current.jar")
    [[ $previous == "$root/releases/"* && $(dirname -- "$previous") == "$root/releases" ]] || die 'previous release outside root'
    verify_release "$previous"
fi
if [[ $mode == UPGRADE ]]; then
    [[ -n $previous ]] || die 'upgrade requires a reviewed previous release'
    approved_previous=$(gate_value PREVIOUS_SHA)
    [[ $approved_previous == "$(sed -n 's/^commit=//p' "${previous%.jar}.manifest.txt")" ]] || die 'previous approval differs from artifact'
    [[ $(gate_value PREVIOUS_COMPATIBILITY) == GO ]] || die 'previous schema/environment/storage compatibility approval required'
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
        systemctl stop pastport.service || die 'STOP_FAILED: bootstrap service stop failed; preserve current link and artifacts; keep public traffic blocked'
        require_inactive
        [[ -L $root/current.jar && $(readlink -- "$root/current.jar") == "$target" ]] || die 'bootstrap link changed; preserve and review existing state'
        rm -- "$root/current.jar"
        die 'bootstrap unhealthy; service confirmed inactive and new link removed; artifacts preserved; keep public traffic blocked'
    fi
    die 'new release unhealthy; reverted (DB changes are not reverted)'
fi
