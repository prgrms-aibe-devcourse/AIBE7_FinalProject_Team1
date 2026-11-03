#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
fail() { printf '%s: INVALID\nRESULT: FAIL\n' "$1"; exit 1; }
[[ $# == 1 && -f $1 && ! -L $1 ]] || fail ENV_FILE
[[ $(stat -c %a -- "$1") == 600 ]] || fail ENV_MODE
[[ $(stat -c %u -- "$1") == 0 ]] || fail ENV_OWNER
declare -A values=()
while IFS= read -r line || [[ -n $line ]]; do
    line=${line%$'\r'}
    [[ -z $line || $line == \#* ]] && continue
    [[ $line =~ ^([A-Z][A-Z0-9_]*)=(.*)$ ]] || fail ENV_SYNTAX
    key=${BASH_REMATCH[1]}; value=${BASH_REMATCH[2]}
    [[ ! -v values[$key] ]] || fail "$key"
    [[ $value != *[[:space:]]* && $value != *\"* && $value != *\'* && $value != *\`* && $value != *\$* ]] || fail "$key"
    values[$key]=$value
done < "$1"
required=(SPRING_PROFILES_ACTIVE SERVER_PORT SUPABASE_DB_URL SUPABASE_DB_USERNAME SUPABASE_DB_PASSWORD REDIS_HOST REDIS_PORT JWT_SECRET PRODUCT_SERIAL_SECRET STORAGE_TYPE STORAGE_S3_BUCKET STORAGE_S3_REGION STORAGE_S3_PREFIX STORAGE_SIGNING_SECRET CORS_ALLOWED_ORIGINS SUPABASE_URL SUPABASE_SECRET CHAT_API_ENABLED LISTING_HISTORY_ENABLED TRADE_PURCHASE_REQUESTS_ENABLED TRADE_P3_ENABLED TRADE_P4_ENABLED AI_SUMMARY_ENABLED AI_LISTING_ENABLED GEMINI_API_KEY LOG_FILE DOTENV_PATH)
[[ -v values[REDIS_PASSWORD] ]] || fail REDIS_PASSWORD
for key in "${required[@]}"; do
    [[ -n ${values[$key]:-} ]] || fail "$key"
done
[[ ${values[LOG_FILE]} == /var/lib/pastport/logs/app.log ]] || fail LOG_FILE
[[ ${values[DOTENV_PATH]} == /etc/pastport/disabled.env ]] || fail DOTENV_PATH
case ${values[SPRING_PROFILES_ACTIVE]} in
    prod,supabase|prod,supabase,db-convergence|prod,supabase,db-legacy-convergence) ;;
    *) fail PROFILE_STORAGE ;;
esac
[[ ${values[STORAGE_TYPE]} == s3 ]] || fail PROFILE_STORAGE
for key in SERVER_PORT REDIS_PORT; do
    value=${values[$key]}
    [[ $value =~ ^[0-9]{1,5}$ ]] && ((10#$value >= 1 && 10#$value <= 65535)) || fail "$key"
done
[[ ${values[SERVER_PORT]} == 8080 ]] || fail SERVER_PORT
for key in JWT_SECRET PRODUCT_SERIAL_SECRET STORAGE_SIGNING_SECRET; do
    ((${#values[$key]} >= 32)) || fail "$key"
done
[[ ${values[STORAGE_SIGNING_SECRET]} != "${values[JWT_SECRET]}" ]] || fail STORAGE_SIGNING_SECRET
for key in "${!values[@]}"; do
    if [[ $key == *_ENABLED ]]; then
        [[ ${values[$key]} == true || ${values[$key]} == false ]] || fail "$key"
    fi
    [[ $key != AWS_ACCESS_KEY_ID && $key != AWS_SECRET_ACCESS_KEY && $key != AWS_SESSION_TOKEN ]] || fail AWS_STATIC_CREDENTIALS
    [[ $key != SPRING_FLYWAY_ENABLED || ${values[$key]} == true ]] || fail FLYWAY
    [[ $key != SPRING_JPA_HIBERNATE_DDL_AUTO || ${values[$key]} == validate ]] || fail DDL_AUTO
done
[[ ${values[SUPABASE_DB_URL]} == jdbc:postgresql://* && ${values[SUPABASE_DB_URL]} == *sslmode=require* ]] || fail SUPABASE_DB_URL
[[ ${values[CORS_ALLOWED_ORIGINS]} == https://* && ${values[CORS_ALLOWED_ORIGINS]} != *\** ]] || fail CORS_ALLOWED_ORIGINS
for key in "${required[@]}"; do printf '%s: SET\n' "$key"; done
printf 'RESULT: OK\n'
