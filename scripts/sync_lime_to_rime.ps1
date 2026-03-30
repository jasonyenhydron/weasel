param(
    [string]$DbPath = "D:\CODE\weasel\data\lime.db",
    [string]$RimeUserDir = "$env:APPDATA\Rime",
    [string]$WeaselRoot = "C:\Program Files (x86)\Rime\weasel-0.17.4"
)

$ErrorActionPreference = "Stop"

# Sync flow: backup current dictionary, export from lime.db, then redeploy Weasel.
$scriptPath = $MyInvocation.MyCommand.Definition
if (-not $scriptPath) {
    throw "Unable to resolve script path."
}

$scriptDir = Split-Path -Parent $scriptPath
$importScript = Join-Path -Path $scriptDir -ChildPath "import_likeime_db.py"
$dictPath = Join-Path -Path $RimeUserDir -ChildPath "openxiami_CustomWord.dict.yaml"
$deployerPath = Join-Path -Path $WeaselRoot -ChildPath "WeaselDeployer.exe"

if (-not (Test-Path -LiteralPath $DbPath)) {
    throw "Database not found: $DbPath"
}

if (-not (Test-Path -LiteralPath $importScript)) {
    throw "Import script not found: $importScript"
}

if (-not (Test-Path -LiteralPath $RimeUserDir)) {
    New-Item -ItemType Directory -Path $RimeUserDir -Force | Out-Null
}

if (Test-Path -LiteralPath $dictPath) {
    $timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
    $backupPath = "$dictPath.bak_$timestamp"
    Copy-Item -LiteralPath $dictPath -Destination $backupPath -Force
    Write-Host "Backup created: $backupPath"
}

python $importScript --db $DbPath --out $dictPath

if (-not (Test-Path -LiteralPath $deployerPath)) {
    throw "WeaselDeployer.exe not found: $deployerPath"
}

& $deployerPath /deploy

$dictFile = Get-Item -LiteralPath $dictPath
Write-Host "Sync finished: $($dictFile.FullName)"
Write-Host "Size: $($dictFile.Length) bytes"
Write-Host "Updated: $($dictFile.LastWriteTime)"
