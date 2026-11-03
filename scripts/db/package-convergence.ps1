param([Parameter(Mandatory=$true)][string]$Destination)
$ErrorActionPreference = 'Stop'
$sourceRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$outputRoot = [System.IO.Path]::GetFullPath($Destination)
if (Test-Path -LiteralPath $outputRoot) { throw 'Choose a new package directory' }
$head = git -C $sourceRoot rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve source commit' }
$changes = git -C $sourceRoot status --porcelain
if ($changes) { throw 'Commit reviewed changes before packaging' }
New-Item -ItemType Directory -Path $outputRoot | Out-Null
$archive = Join-Path $outputRoot 'source.zip'
git -C $sourceRoot archive --format=zip --output=$archive $head
if ($LASTEXITCODE -ne 0) { throw 'Archive failed' }
Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $outputRoot 'source')
$packageSource = Join-Path $outputRoot 'source'
$manifest = Get-ChildItem -LiteralPath $packageSource -Recurse -File | Sort-Object FullName | ForEach-Object {
    $relative = $_.FullName.Substring($packageSource.Length + 1).Replace('\', '/')
    (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $relative
}
$manifest | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $outputRoot 'SHA256SUMS')
@("source_commit=$head", 'entrypoint=gradlew dbConvergence', 'instructions=source/docs/DB_CONVERGENCE_RUNBOOK.md') | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $outputRoot 'SOURCE.txt')
Get-FileHash -LiteralPath $archive -Algorithm SHA256 | Select-Object Hash
