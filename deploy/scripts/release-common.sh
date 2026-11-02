#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
die() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }
initialize() {
    [[ $EUID == 0 ]] || die 'root required'
    [[ $root == /* && $root != / && ! -L $root ]] || die 'invalid release root'
    [[ $(realpath -m -- "$root") == "$root" ]] || die 'release root must be canonical'
    mkdir -p -- "$root/releases"
    root=$(realpath -e -- "$root")
    [[ ! -L $root/releases && $(stat -c %u -- "$root") == 0 && $(stat -c %u -- "$root/releases") == 0 ]] || die 'unsafe release directory'
    local mode
    for directory in "$root" "$root/releases"; do
        mode=$(stat -c %a -- "$directory")
        (( (8#$mode & 0022) == 0 )) || die 'writable release directory'
    done
    exec 9>"$root/release.lock"
    flock -n 9 || die 'another release operation is running'
}
validate_sha() { [[ $sha =~ ^[0-9a-f]{8}$ ]] || die 'expected eight lowercase commit characters'; }
verify_release() {
    local target=$1 filename digest expected commit
    filename=$(basename -- "$target")
    [[ -f $target && ! -L $target && -f $target.sha256 && ! -L $target.sha256 && -f ${target%.jar}.manifest.txt && ! -L ${target%.jar}.manifest.txt ]] || die 'release files missing or linked'
    for filename in "$target" "$target.sha256" "${target%.jar}.manifest.txt"; do
        [[ $(stat -c %u -- "$filename") == 0 && $(stat -c %a -- "$filename") == 644 ]] || die 'release ownership or mode invalid'
    done
    filename=$(basename -- "$target")
    expected=$(cat -- "$target.sha256")
    [[ $expected =~ ^([0-9a-f]{64})\ \ ([0-9a-f]{8}\.jar)$ && ${BASH_REMATCH[2]} == "$filename" ]] || die 'invalid checksum format'
    digest=${BASH_REMATCH[1]}
    [[ $(sha256sum -- "$target" | cut -d' ' -f1) == "$digest" ]] || die 'checksum mismatch'
    commit=$(sed -n 's/^commit=//p' "${target%.jar}.manifest.txt")
    [[ $commit =~ ^[0-9a-f]{40}$ && ${commit:0:8}.jar == "$filename" ]] || die 'manifest commit mismatch'
    [[ $(sed -n 's/^sha256=//p' "${target%.jar}.manifest.txt") == "$digest" ]] || die 'manifest checksum mismatch'
}
activate() {
    local target=$1 temporary="$root/.current.$$"
    ln -s -- "$target" "$temporary"
    mv -Tf -- "$temporary" "$root/current.jar"
}
healthy() {
    local attempt
    for ((attempt=0; attempt<30; attempt++)); do
        if systemctl is-active --quiet pastport.service && curl --silent --fail --max-time 3 --output /dev/null http://127.0.0.1:8080/; then return 0; fi
        sleep 2
    done
    return 1
}
