param(
    [ValidateSet('basic', 'selected', 'browser')][string]$Mode = 'basic',
    [Parameter(Mandatory = $true)][string]$Evidence,
    [string]$BrowserScript,
    [string]$BrowserModules,
    [string[]]$TestFilter,
    [string]$JavaHome = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
)
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $Evidence) { throw 'Use a new evidence directory for each run.' }
New-Item -ItemType Directory -Path $Evidence | Out-Null
$evidencePath = (Resolve-Path -LiteralPath $Evidence).Path
$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $repository
Get-ChildItem Env: | Where-Object Name -Match '^(DB_|REDIS_|AI_|GEMINI_|SUPABASE_|JWT_|STORAGE_|PRODUCT_SERIAL_|TEST_|SALE_DRAFT_|OWN_INT_|P[2345]_|SPRING_|DOTENV_|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|GRADLE_OPTS)' | ForEach-Object { Remove-Item -LiteralPath ('Env:' + $_.Name) }
$env:JAVA_HOME = $JavaHome
$env:DOTENV_PATH = 'build/disabled-env/.env'
$env:AI_SUMMARY_ENABLED = 'false'
$env:AI_LISTING_ENABLED = 'false'
$env:DB_URL = 'jdbc:h2:mem:p5_main;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH'
$env:DB_DRIVER = 'org.h2.Driver'
$env:DB_USERNAME = 'sa'
$env:DB_PASSWORD = ''
$env:REDIS_HOST = '127.0.0.1'
$env:REDIS_PORT = '56402'
$env:REDIS_PASSWORD = ''
$env:REDIS_SSL_ENABLED = 'false'
if ($Mode -ne 'basic') {
    foreach ($resource in @('p5-main-postgres-20261102', 'p5-main-redis-20261102')) {
        $labels = & rdctl shell -- /usr/bin/docker inspect --format '{{json .Config.Labels}}' $resource
        if ($LASTEXITCODE -ne 0 -or ($labels | ConvertFrom-Json).'codex.task' -ne 'p5-main-20261102') { throw 'Dedicated container identity check failed.' }
    }
    foreach ($prefix in @('OWN_INT_TEST', 'SALE_DRAFT_TEST')) {
        [Environment]::SetEnvironmentVariable($prefix + '_DB_URL', 'jdbc:postgresql://127.0.0.1:55452/sale_ai_test', 'Process')
        [Environment]::SetEnvironmentVariable($prefix + '_DB_USER', 'p5_main_test', 'Process')
        [Environment]::SetEnvironmentVariable($prefix + '_DB_PASSWORD', 'disposable-p5-main-only', 'Process')
    }
    $env:OWN_INT_TEST_ENABLED = 'true'
    $env:OWN_INT_TEST_REDIS_HOST = '127.0.0.1'
    $env:OWN_INT_TEST_REDIS_PORT = '56402'
}
if ($Mode -eq 'browser') {
    if (!(Test-Path -LiteralPath $BrowserScript)) { throw 'Browser script is required.' }
    $env:P5_UI_ENABLED = 'true'
    $env:P5_UI_NODE = (Get-Command node).Source
    $env:P5_UI_SCRIPT = (Resolve-Path -LiteralPath $BrowserScript).Path
    $env:P5_UI_EVIDENCE = Join-Path $evidencePath 'browser'
    $env:NODE_PATH = (Resolve-Path -LiteralPath $BrowserModules).Path
}
$arguments = @('--no-daemon', '--console=plain')
if ($Mode -eq 'basic') { $arguments += @('spotlessCheck', 'test', 'build') }
elseif ($Mode -eq 'selected') { $arguments += @('test', '--tests', '*DirectTransferPostgresTest', '--tests', '*InternalTradeOwnershipHttpPostgresTest', '--tests', '*ListingDraftOwnershipHttpTest', '--tests', '*ListingCancelIntegrationPostgresTest') }
else { $arguments += @('test', '--tests', '*DirectTransferPostgresTest.p5A09BrowserFlow') }
if ($Mode -eq 'selected' -and $TestFilter) {
    $arguments = @('--no-daemon', '--console=plain', 'test')
    foreach ($filter in $TestFilter) { $arguments += @('--tests', $filter) }
}
@{ mode=$Mode; head=(& git rev-parse HEAD); arguments=$arguments; started=(Get-Date).ToUniversalTime().ToString('o') } | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 (Join-Path $evidencePath 'command.json')
$ErrorActionPreference = 'Continue'
& .\gradlew.bat @arguments *> (Join-Path $evidencePath 'gradle.log')
$gradleExit = $LASTEXITCODE
$nodeExit = $null
if ($Mode -eq 'basic') {
    & node --test --test-timeout=10000 src/test/js/*.test.mjs *> (Join-Path $evidencePath 'node.log')
    $nodeExit = $LASTEXITCODE
}
$ErrorActionPreference = 'Stop'
if (Test-Path build/test-results/test) { Copy-Item -LiteralPath build/test-results/test -Destination (Join-Path $evidencePath 'junit') -Recurse }
@{ gradleExit=$gradleExit; nodeExit=$nodeExit; finished=(Get-Date).ToUniversalTime().ToString('o') } | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $evidencePath 'exit.json')
Get-Content -LiteralPath (Join-Path $evidencePath 'gradle.log') -Tail 20
if ($gradleExit -ne 0) { exit $gradleExit }
if ($null -ne $nodeExit -and $nodeExit -ne 0) { exit $nodeExit }
