#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/release-common.sh"
[[ $# == 2 || $# == 3 ]] || die 'usage: stage-release.sh s3://release-bucket/prefix sha8 [root]'
uri=${1%/}; sha=$2; root=${3:-/opt/pastport}
[[ $uri =~ ^s3://[a-z0-9][a-z0-9.-]+/[a-zA-Z0-9/_-]+$ ]] || die 'explicit release S3 prefix required'
validate_sha; initialize
[[ ! -e $root/releases/$sha.jar && ! -e $root/releases/$sha.jar.sha256 && ! -e $root/releases/$sha.manifest.txt ]] || die 'release already exists'
temporary=$(mktemp -d "$root/releases/.stage.XXXXXX")
trap 'rm -rf -- "$temporary"' EXIT
for filename in "$sha.jar" "$sha.jar.sha256" "$sha.manifest.txt"; do
    aws s3 cp "$uri/$filename" "$temporary/$filename" --only-show-errors >/dev/null 2>&1 || die 'artifact download failed'
    chown root:root -- "$temporary/$filename"; chmod 0644 -- "$temporary/$filename"
done
verify_release "$temporary/$sha.jar"
for filename in "$sha.jar" "$sha.jar.sha256" "$sha.manifest.txt"; do mv -- "$temporary/$filename" "$root/releases/$filename"; done
printf 'STAGED: %s\n' "$sha"
