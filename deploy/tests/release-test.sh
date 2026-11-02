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
ENV
chmod 600 "$env_file"
bash "$scripts/check-env.sh" "$env_file" > "$temporary/env-output"
! grep -Eq 'NEVER_PRINT|synthetic|jdbc:|https://' "$temporary/env-output"
pass 'valid env; no values printed'
cp "$env_file" "$temporary/valid.env"
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
fixture() {
    local sha=$1 digest
    printf 'dummy jar %s\n' "$sha" > "$FIXTURE/$sha.jar"
    digest=$(sha256sum "$FIXTURE/$sha.jar" | cut -d' ' -f1)
    printf '%s  %s.jar\n' "$digest" "$sha" > "$FIXTURE/$sha.jar.sha256"
    printf 'commit=%s00000000000000000000000000000000\nsha256=%s\n' "$sha" "$digest" > "$FIXTURE/$sha.manifest.txt"
}
root="$temporary/application"
fixture aaaaaaaa; fixture bbbbbbbb
bash "$scripts/stage-release.sh" s3://release-fixture/artifacts aaaaaaaa "$root"
[[ ! -e $root/current.jar ]]; pass 'staging verifies checksum and never activates'
reject bash "$scripts/stage-release.sh" s3://release-fixture/artifacts aaaaaaaa "$root"; pass 'staged artifacts cannot be overwritten'
fixture cccccccc; printf 'corrupt' >> "$FIXTURE/cccccccc.jar"
reject bash "$scripts/stage-release.sh" s3://release-fixture/artifacts cccccccc "$root"
[[ ! -e $root/releases/cccccccc.jar ]]; pass 'bad checksum cannot be staged'
bash "$scripts/switch-release.sh" aaaaaaaa "$env_file" "$go_file" "$root"
[[ $(readlink "$root/current.jar") == "$root/releases/aaaaaaaa.jar" ]]; pass 'atomic activation with mocked service and HTTP'
bash "$scripts/stage-release.sh" s3://release-fixture/artifacts bbbbbbbb "$root"
bash "$scripts/switch-release.sh" bbbbbbbb "$env_file" "$go_file" "$root"
[[ $(cat "$root/previous-release.txt") == "$root/releases/aaaaaaaa.jar" ]]; pass 'previous target recorded'
bash "$scripts/rollback-release.sh" aaaaaaaa "$env_file" "$go_file" "$root"
[[ $(readlink "$root/current.jar") == "$root/releases/aaaaaaaa.jar" ]]; pass 'explicit rollback checks and restores artifact'
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
printf 'PASS: %s deployment tests (no AWS/systemd/network)\n' "$count"
