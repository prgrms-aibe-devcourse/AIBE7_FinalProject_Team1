param(
    [ValidateSet('basic', 'postgres', 'browser')][string]$Mode = 'basic',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$BrowserModules
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Join-Path $PSScriptRoot '..')
Get-ChildItem Env: | Where-Object Name -Match '^(DB_|REDIS_|AI_|GEMINI_|SUPABASE_|JWT_|STORAGE_|PRODUCT_SERIAL_|TEST_|SALE_DRAFT_|OWN_INT_|DEPLOY_DDL_|P[2345]_|SPRING_|DOTENV_|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|GRADLE_OPTS)' | ForEach-Object { Remove-Item -LiteralPath ('Env:' + $_.Name) }
$env:JAVA_HOME = $JavaHome
$env:DOTENV_PATH = 'build/disabled-env/.env'
$env:JWT_SECRET = 'p5-integration-synthetic-test-secret-at-least-32-bytes'
$env:AI_SUMMARY_ENABLED = 'false'
$env:AI_LISTING_ENABLED = 'false'
$env:DB_URL = 'jdbc:h2:mem:p5_v2;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH'
$env:DB_DRIVER = 'org.h2.Driver'
$env:DB_USERNAME = 'sa'
$env:DB_PASSWORD = ''
$env:REDIS_HOST = '127.0.0.1'
$env:REDIS_PORT = '56412'
$env:REDIS_PASSWORD = ''
if ($Mode -ne 'basic') {
    foreach ($resource in @('p5-v2-postgres-20261102', 'p5-v2-redis-20261102')) {
        $labels = & rdctl shell -- /usr/bin/docker inspect --format '{{json .Config.Labels}}' $resource
        if ($LASTEXITCODE -ne 0 -or ($labels | ConvertFrom-Json).'codex.task' -ne 'p5-integration-v2') { throw 'Dedicated test container identity check failed.' }
    }
    foreach ($prefix in @('OWN_INT_TEST', 'SALE_DRAFT_TEST', 'DEPLOY_DDL_TEST')) {
        [Environment]::SetEnvironmentVariable($prefix + '_DB_URL', 'jdbc:postgresql://127.0.0.1:55462/sale_ai_test', 'Process')
        [Environment]::SetEnvironmentVariable($prefix + '_DB_USER', 'p5_v2_test', 'Process')
        [Environment]::SetEnvironmentVariable($prefix + '_DB_PASSWORD', 'disposable-p5-v2-only', 'Process')
    }
    $env:OWN_INT_TEST_ENABLED = 'true'
    $env:OWN_INT_TEST_REDIS_HOST = '127.0.0.1'
    $env:OWN_INT_TEST_REDIS_PORT = '56412'
    $env:DEPLOY_DDL_TEST_ENABLED = 'true'
}
if ($Mode -eq 'browser') {
    $env:P5_UI_ENABLED = 'true'
    $env:P5_UI_NODE = (Get-Command node).Source
    $env:P5_UI_SCRIPT = (Resolve-Path 'src/test/browser/direct-transfer.cjs').Path
    $env:P5_UI_EVIDENCE = Join-Path (Get-Location) ('build/p5-browser-evidence-' + [DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff'))
    if (!$BrowserModules) { throw 'Provide BrowserModules containing playwright-core and jsqr.' }
    $env:NODE_PATH = (Resolve-Path -LiteralPath $BrowserModules).Path
    & ./gradlew.bat test --tests '*DirectTransferPostgresTest.p5A09BrowserFlow' --console=plain
} elseif ($Mode -eq 'postgres') {
    & ./gradlew.bat test --tests '*DirectTransferPostgresTest' --tests '*InternalTradeOwnershipHttpPostgresTest' --tests '*H40MigrationCompatibilityTest' --tests '*DeployMigrationPostgresTest' --tests '*NormalizeOwnershipVersionPostgresTest' --tests '*ListingDraftOwnershipHttpTest' --console=plain
} else {
    & ./gradlew.bat spotlessCheck test build --console=plain
}
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if ($Mode -eq 'basic') {
    & node --test --test-timeout=10000 src/test/js/*.test.mjs
    exit $LASTEXITCODE
}
