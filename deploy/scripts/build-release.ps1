param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $repository
try {
    if (-not $JavaHome) { throw 'JDK 17 JAVA_HOME required' }
    $java = Join-Path $JavaHome 'bin/java.exe'
    $version = (& $java --version | ForEach-Object { "$_" }) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $version -notmatch '^(openjdk|java) 17\.') { throw 'JDK 17 required' }
    $env:JAVA_HOME = $JavaHome
    if (git status --porcelain) { throw 'Clean worktree required' }
    Get-ChildItem Env: | Where-Object Name -Match '^(DB_|REDIS_|AI_|GEMINI_|SUPABASE_|JWT_|STORAGE_|AWS_|PRODUCT_SERIAL_|TEST_|SALE_DRAFT_|OWN_INT_|DEPLOY_DDL_|P[2345]_|SPRING_|DOTENV_|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|GRADLE_OPTS)' | ForEach-Object { Remove-Item -LiteralPath ('Env:' + $_.Name) }
    $env:DOTENV_PATH = 'build/disabled-env/.env'
    $env:JWT_SECRET = 'release-build-synthetic-test-secret-at-least-32-bytes'
    $env:AI_SUMMARY_ENABLED = 'false'
    $env:AI_LISTING_ENABLED = 'false'
    $env:DB_URL = 'jdbc:h2:mem:release_build;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH'
    $env:DB_DRIVER = 'org.h2.Driver'
    $env:DB_USERNAME = 'sa'
    $env:DB_PASSWORD = ''
    $env:REDIS_HOST = '127.0.0.1'
    $env:REDIS_PORT = '1'
    $env:REDIS_PASSWORD = ''
    $commit = (git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[0-9a-f]{40}$') { throw 'Cannot resolve commit' }
    Write-Output "commit=$commit"
    & ./gradlew.bat spotlessCheck --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Spotless failed' }
    $tests = @(Get-ChildItem src/test/js/*.test.mjs | ForEach-Object FullName)
    & node --test --test-timeout=10000 @tests
    if ($LASTEXITCODE -ne 0) { throw 'Node tests failed' }
    & ./gradlew.bat clean build -x spotlessCheck --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
    if (git status --porcelain) { throw 'Worktree changed while building' }
    if ((git rev-parse HEAD).Trim() -ne $commit) { throw 'Commit changed while building' }
    $jars = @(Get-ChildItem build/libs/*.jar | Where-Object { $_.Name -notlike '*-plain.jar' })
    if ($jars.Count -ne 1) { throw 'Expected exactly one boot JAR' }
    $output = Join-Path $repository 'build/release'
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $name = $commit.Substring(0, 8)
    $artifact = Join-Path $output "$name.jar"
    Copy-Item -LiteralPath $jars[0].FullName -Destination $artifact
    $digest = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText("$artifact.sha256", "$digest  $name.jar`n", $encoding)
    [IO.File]::WriteAllText((Join-Path $output "$name.manifest.txt"), "commit=$commit`nbuilt_at=$([DateTime]::UtcNow.ToString('o'))`njava=$($version.Split("`n")[0])`nsha256=$digest`n", $encoding)
    Write-Output "release=$output"
} finally { Pop-Location }
