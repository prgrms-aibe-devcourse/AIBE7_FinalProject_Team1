#!/usr/bin/env bash
set -euo pipefail
scripts=$(cd -- "$(dirname -- "$0")/../scripts" && pwd)
temporary=$(mktemp -d)
trap 'rm -rf -- "$temporary"' EXIT
export FIXTURE="$temporary/remote" MOCK_LOG="$temporary/operations" MOCK_FAILURE="$temporary/failure"
mkdir -p "$FIXTURE" "$temporary/bin"
export PATH="$temporary/bin:$PATH"
cat > "$temporary/bin/aws" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
[[ $1 == s3 && $2 == cp && $3 == s3://release-fixture/artifacts/* ]]
cp -- "$FIXTURE/${3##*/}" "$4"
STUB
cat > "$temporary/bin/systemctl" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$MOCK_LOG"
if [[ $1 == is-active ]]; then [[ -f $MOCK_LOG.active ]]; exit; fi
if [[ $1 == restart ]]; then touch "$MOCK_LOG.active"; fi
if [[ $1 == stop ]]; then rm -f "$MOCK_LOG.active"; fi
STUB
cat > "$temporary/bin/curl" <<'STUB'
#!/usr/bin/env bash
if [[ -f $MOCK_FAILURE ]]; then rm -- "$MOCK_FAILURE"; exit 1; fi
STUB
cat > "$temporary/bin/sleep" <<'STUB'
#!/usr/bin/env bash
exit 0
STUB
chmod +x "$temporary/bin/"*
count=0
pass() { count=$((count+1)); printf 'ok %s - %s\n' "$count" "$1"; }
reject() { if "$@" >"$temporary/rejected" 2>&1; then printf 'unexpected success\n' >&2; exit 1; fi; }
assert_no_values() {
    local output=$1 fixture=$2 key value
    while IFS='=' read -r key value; do
        [[ -z $value ]] && continue
        if grep -Fq -- "$value" "$output"; then printf 'value disclosed for %s\n' "$key" >&2; exit 1; fi
    done < "$fixture"
}
env_file="$temporary/pastport.env"
cat > "$env_file" <<'ENV'
SPRING_PROFILES_ACTIVE=prod,supabase
SERVER_PORT=8080
SUPABASE_DB_URL=jdbc:postgresql://synthetic.invalid:5432/test?sslmode=require
SUPABASE_DB_USERNAME=synthetic
SUPABASE_DB_PASSWORD=NEVER_PRINT_THIS_PASSWORD
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=
JWT_SECRET=synthetic-jwt-value-over-32-bytes-only
PRODUCT_SERIAL_SECRET=synthetic-serial-value-over-32-bytes-only
STORAGE_SIGNING_SECRET=synthetic-storage-value-over-32-bytes-only
STORAGE_TYPE=s3
STORAGE_S3_BUCKET=synthetic-content-bucket
STORAGE_S3_REGION=ap-northeast-2
STORAGE_S3_PREFIX=app-files
CORS_ALLOWED_ORIGINS=https://synthetic.invalid
SUPABASE_URL=https://synthetic.invalid
SUPABASE_SECRET=synthetic-auth-secret
CHAT_API_ENABLED=false
LISTING_HISTORY_ENABLED=false
TRADE_PURCHASE_REQUESTS_ENABLED=false
TRADE_P3_ENABLED=false
TRADE_P4_ENABLED=false
AI_SUMMARY_ENABLED=false
AI_LISTING_ENABLED=false
GEMINI_API_KEY=synthetic-gemini-key-never-print
LOG_FILE=/var/lib/pastport/logs/app.log
DOTENV_PATH=/etc/pastport/disabled.env
ENV
chmod 600 "$env_file"
bash "$scripts/check-env.sh" "$env_file" > "$temporary/env-output"
! grep -Eq 'NEVER_PRINT|synthetic|jdbc:|https://' "$temporary/env-output"
assert_no_values "$temporary/env-output" "$env_file"
for key in GEMINI_API_KEY LOG_FILE DOTENV_PATH; do grep -qx "$key: SET" "$temporary/env-output"; done
pass 'valid env; no values printed'
cp "$env_file" "$temporary/valid.env"
for key in GEMINI_API_KEY LOG_FILE DOTENV_PATH; do
    cp "$temporary/valid.env" "$env_file"
    sed -i "/^$key=/d" "$env_file"
    reject bash "$scripts/check-env.sh" "$env_file"
    [[ $(cat "$temporary/rejected") == "$key: INVALID"$'\nRESULT: FAIL' ]]
    assert_no_values "$temporary/rejected" "$temporary/valid.env"
    pass "missing $key rejected without values"
done
for replacement in 'GEMINI_API_KEY=' 'LOG_FILE=' 'DOTENV_PATH=' 'LOG_FILE=/var/log/pastport/app.log' 'LOG_FILE=./logs/app.log' 'LOG_FILE=/tmp/pastport.log' 'DOTENV_PATH=.env' 'DOTENV_PATH=/var/lib/pastport/.env'; do
    cp "$temporary/valid.env" "$env_file"
    key=${replacement%%=*}; sed -i "/^$key=/d" "$env_file"; printf '%s\n' "$replacement" >> "$env_file"
    reject bash "$scripts/check-env.sh" "$env_file"
    [[ $(cat "$temporary/rejected") == "$key: INVALID"$'\nRESULT: FAIL' ]]
    assert_no_values "$temporary/rejected" "$temporary/valid.env"
    assert_no_values "$temporary/rejected" "$env_file"
    pass "invalid $key rejected without values"
done
cp "$temporary/valid.env" "$env_file"
printf 'JWT_SECRET=NEVER_PRINT_DUPLICATE\n' >> "$env_file"
reject bash "$scripts/check-env.sh" "$env_file"
! grep -q NEVER_PRINT "$temporary/rejected"
pass 'duplicate rejected without secret output'
cp "$temporary/valid.env" "$env_file"
sed -i '/^JWT_SECRET=/d' "$env_file"
reject bash "$scripts/check-env.sh" "$env_file"; pass 'missing key rejected'
cp "$temporary/valid.env" "$env_file"; chmod 644 "$env_file"
reject bash "$scripts/check-env.sh" "$env_file"; pass 'unsafe mode rejected'
chmod 600 "$env_file"; chown 65534 "$env_file"
reject bash "$scripts/check-env.sh" "$env_file"; pass 'non-root owner rejected'
chown 0 "$env_file"
for replacement in 'REDIS_PORT=invalid' 'SPRING_PROFILES_ACTIVE=local' 'STORAGE_TYPE=local' 'JWT_SECRET=short' 'CHAT_API_ENABLED=yes' 'SPRING_FLYWAY_ENABLED=false' 'AWS_ACCESS_KEY_ID=NEVER_PRINT_AWS'; do
    cp "$temporary/valid.env" "$env_file"
    key=${replacement%%=*}; sed -i "/^$key=/d" "$env_file"; printf '%s\n' "$replacement" >> "$env_file"
    reject bash "$scripts/check-env.sh" "$env_file"
    pass "invalid $key rejected"
done
cp "$temporary/valid.env" "$env_file"
sed -i 's/^STORAGE_SIGNING_SECRET=.*/STORAGE_SIGNING_SECRET=synthetic-jwt-value-over-32-bytes-only/' "$env_file"
reject bash "$scripts/check-env.sh" "$env_file"; pass 'signing secret must differ from JWT'
cp "$temporary/valid.env" "$env_file"
go_file="$temporary/gates"
printf '%s=GO\n' CODE DB STORAGE HTTPS ENV ARTIFACT > "$go_file"; chmod 600 "$go_file"
approve_mode() {
    local mode=$1 candidate=$2 previous=${3:-}
    sed -i '/^RELEASE_MODE=/d; /^CANDIDATE_SHA=/d; /^PREVIOUS_SHA=/d; /^PREVIOUS_COMPATIBILITY=/d' "$go_file"
    printf 'RELEASE_MODE=%s\nCANDIDATE_SHA=%s00000000000000000000000000000000\n' "$mode" "$candidate" >> "$go_file"
    if [[ -n $previous ]]; then
        printf 'PREVIOUS_SHA=%s00000000000000000000000000000000\nPREVIOUS_COMPATIBILITY=GO\n' "$previous" >> "$go_file"
    fi
}
fixture() {
    local sha=$1 digest
    printf 'dummy jar %s\n' "$sha" > "$FIXTURE/$sha.jar"
    digest=$(sha256sum "$FIXTURE/$sha.jar" | cut -d' ' -f1)
    printf '%s  %s.jar\n' "$digest" "$sha" > "$FIXTURE/$sha.jar.sha256"
    printf 'commit=%s00000000000000000000000000000000\nsha256=%s\n' "$sha" "$digest" > "$FIXTURE/$sha.manifest.txt"
}
root="$temporary/application"
reject bash "$scripts/stage-release.sh" s3://release-fixture/artifacts aaaaaaaa "$temporary/unexpected/.."
[[ ! -e $temporary/unexpected ]]; pass 'non-canonical root rejected before filesystem changes'
fixture aaaaaaaa; fixture bbbbbbbb
bash "$scripts/stage-release.sh" s3://release-fixture/artifacts aaaaaaaa "$root"
[[ ! -e $root/current.jar ]]; pass 'staging verifies checksum and never activates'
reject bash "$scripts/stage-release.sh" s3://release-fixture/artifacts aaaaaaaa "$root"; pass 'staged artifacts cannot be overwritten'
fixture cccccccc; printf 'corrupt' >> "$FIXTURE/cccccccc.jar"
reject bash "$scripts/stage-release.sh" s3://release-fixture/artifacts cccccccc "$root"
[[ ! -e $root/releases/cccccccc.jar ]]; pass 'bad checksum cannot be staged'
reject bash "$scripts/switch-release.sh" aaaaaaaa "$env_file" "$go_file" "$root"; pass 'missing deployment mode rejected'
approve_mode INVALID aaaaaaaa
reject bash "$scripts/switch-release.sh" aaaaaaaa "$env_file" "$go_file" "$root"; pass 'unknown deployment mode rejected'
approve_mode UPGRADE aaaaaaaa bbbbbbbb
reject bash "$scripts/switch-release.sh" aaaaaaaa "$env_file" "$go_file" "$root"; pass 'upgrade without predecessor rejected'
approve_mode BOOTSTRAP aaaaaaaa
touch "$MOCK_LOG.active"
reject bash "$scripts/switch-release.sh" aaaaaaaa "$env_file" "$go_file" "$root"
[[ ! -e $root/current.jar && -f $MOCK_LOG.active ]]; pass 'bootstrap preserves active service and links'
rm "$MOCK_LOG.active"
bash "$scripts/switch-release.sh" aaaaaaaa "$env_file" "$go_file" "$root"
[[ $(readlink "$root/current.jar") == "$root/releases/aaaaaaaa.jar" ]]; pass 'atomic activation with mocked service and HTTP'
bash "$scripts/stage-release.sh" s3://release-fixture/artifacts bbbbbbbb "$root"
reject bash "$scripts/switch-release.sh" bbbbbbbb "$env_file" "$go_file" "$root"
[[ $(readlink "$root/current.jar") == "$root/releases/aaaaaaaa.jar" ]]; pass 'bootstrap refuses stale existing link'
approve_mode UPGRADE bbbbbbbb cccccccc
reject bash "$scripts/switch-release.sh" bbbbbbbb "$env_file" "$go_file" "$root"; pass 'wrong predecessor approval rejected'
approve_mode UPGRADE bbbbbbbb aaaaaaaa
sed -i 's/^PREVIOUS_COMPATIBILITY=GO$/PREVIOUS_COMPATIBILITY=NO/' "$go_file"
reject bash "$scripts/switch-release.sh" bbbbbbbb "$env_file" "$go_file" "$root"; pass 'unverified predecessor compatibility rejected'
approve_mode UPGRADE bbbbbbbb aaaaaaaa
bash "$scripts/switch-release.sh" bbbbbbbb "$env_file" "$go_file" "$root"
[[ $(cat "$root/previous-release.txt") == "$root/releases/aaaaaaaa.jar" ]]; pass 'previous target recorded'
approve_mode UPGRADE aaaaaaaa bbbbbbbb
bash "$scripts/rollback-release.sh" aaaaaaaa "$env_file" "$go_file" "$root"
[[ $(readlink "$root/current.jar") == "$root/releases/aaaaaaaa.jar" ]]; pass 'explicit rollback checks and restores artifact'
approve_mode UPGRADE bbbbbbbb aaaaaaaa
sed -i 's/^DB=GO$/DB=NO/' "$go_file"
reject bash "$scripts/switch-release.sh" bbbbbbbb "$env_file" "$go_file" "$root"
[[ $(readlink "$root/current.jar") == "$root/releases/aaaaaaaa.jar" ]]; pass 'DB NO prevents activation'
sed -i 's/^DB=NO$/DB=GO/' "$go_file"
printf 'DB=NO\n' >> "$go_file"
reject bash "$scripts/switch-release.sh" bbbbbbbb "$env_file" "$go_file" "$root"
sed -i '/^DB=NO$/d' "$go_file"; pass 'conflicting approval cannot override DB NO'
printf corrupt >> "$root/releases/bbbbbbbb.jar"
reject bash "$scripts/switch-release.sh" bbbbbbbb "$env_file" "$go_file" "$root"; pass 'activation verifies artifact again'
cp "$FIXTURE/bbbbbbbb.jar" "$root/releases/bbbbbbbb.jar"
cat > "$temporary/bin/curl" <<'STUB'
#!/usr/bin/env bash
[[ $(readlink "$MOCK_ROOT/current.jar") != "$MOCK_ROOT/releases/bbbbbbbb.jar" ]]
STUB
export MOCK_ROOT="$root"
reject bash "$scripts/switch-release.sh" bbbbbbbb "$env_file" "$go_file" "$root"
[[ $(readlink "$root/current.jar") == "$root/releases/aaaaaaaa.jar" ]]; pass 'unhealthy new release automatically restores healthy predecessor'
bootstrap_root="$temporary/bootstrap-failure"
bash "$scripts/stage-release.sh" s3://release-fixture/artifacts bbbbbbbb "$bootstrap_root"
approve_mode BOOTSTRAP bbbbbbbb
rm -f "$MOCK_LOG.active"
export MOCK_ROOT="$bootstrap_root"
reject bash "$scripts/switch-release.sh" bbbbbbbb "$env_file" "$go_file" "$bootstrap_root"
[[ ! -e $bootstrap_root/current.jar && ! -L $bootstrap_root/current.jar && ! -f $MOCK_LOG.active && -f $bootstrap_root/releases/bbbbbbbb.jar ]]
pass 'bootstrap failure stops service, removes only new link, preserves artifacts'
printf 'PASS: %s deployment tests (no AWS/systemd/network)\n' "$count"
