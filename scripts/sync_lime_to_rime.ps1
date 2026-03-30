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
$exportRelatedScript = Join-Path -Path $scriptDir -ChildPath "export_related_tsv.py"
$exportEmojiScript = Join-Path -Path $scriptDir -ChildPath "export_emoji_tsv.py"
$workspaceRoot = Split-Path -Parent $scriptDir
$rimeTemplateDir = Join-Path -Path $workspaceRoot -ChildPath "rime_user"
$dictPath = Join-Path -Path $RimeUserDir -ChildPath "openxiami_CustomWord.dict.yaml"
$relatedPath = Join-Path -Path $RimeUserDir -ChildPath "likeime_related.tsv"
$emojiPath = Join-Path -Path $RimeUserDir -ChildPath "likeime_emoji_tw.tsv"
$deployerPath = Join-Path -Path $WeaselRoot -ChildPath "WeaselDeployer.exe"

if (-not (Test-Path -LiteralPath $DbPath)) {
    throw "Database not found: $DbPath"
}

if (-not (Test-Path -LiteralPath $importScript)) {
    throw "Import script not found: $importScript"
}

if (-not (Test-Path -LiteralPath $exportRelatedScript)) {
    throw "Related export script not found: $exportRelatedScript"
}

if (-not (Test-Path -LiteralPath $exportEmojiScript)) {
    throw "Emoji export script not found: $exportEmojiScript"
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

if (Test-Path -LiteralPath $rimeTemplateDir) {
    Copy-Item -LiteralPath (Join-Path $rimeTemplateDir "liur.schema.yaml") -Destination (Join-Path $RimeUserDir "liur.schema.yaml") -Force
    Copy-Item -LiteralPath (Join-Path $rimeTemplateDir "rime.lua") -Destination (Join-Path $RimeUserDir "rime.lua") -Force
    $targetLuaDir = Join-Path -Path $RimeUserDir -ChildPath "lua"
    New-Item -ItemType Directory -Path $targetLuaDir -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $rimeTemplateDir "lua\liu_related_filter.lua") -Destination (Join-Path $targetLuaDir "liu_related_filter.lua") -Force
    Copy-Item -LiteralPath (Join-Path $rimeTemplateDir "lua\liu_emoji_translator.lua") -Destination (Join-Path $targetLuaDir "liu_emoji_translator.lua") -Force
}

python $importScript --db $DbPath --out $dictPath
python $exportRelatedScript --db $DbPath --out $relatedPath
python $exportEmojiScript --db (Join-Path (Split-Path -Parent $DbPath) "emoji.db") --out $emojiPath --locale tw

if (-not (Test-Path -LiteralPath $deployerPath)) {
    throw "WeaselDeployer.exe not found: $deployerPath"
}

& $deployerPath /deploy

$dictFile = Get-Item -LiteralPath $dictPath
Write-Host "Sync finished: $($dictFile.FullName)"
Write-Host "Size: $($dictFile.Length) bytes"
Write-Host "Updated: $($dictFile.LastWriteTime)"
