param(
    [string]$Source = "D:\APP\rime-liur-lua-master",
    [string]$Target = "D:\CODE\weasel\android-ime\app\src\main\assets\rime"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $Source)) {
    throw "Source path not found: $Source"
}

New-Item -ItemType Directory -Force -Path $Target | Out-Null

$includePatterns = @("*.schema.yaml","*.dict.yaml","*.custom.yaml","*.yaml","*.lua","*.tsv","*.txt","*.json","predict.db")
$excludeMarkers = @("\\dist\\","\\build\\","\\sync\\","\\.git\\",".userdb")

Get-ChildItem -Path $Source -Recurse -File | ForEach-Object {
    $full = $_.FullName
    $relative = $full.Substring($Source.Length).TrimStart('\\')

    foreach ($m in $excludeMarkers) {
        if ($full -like "*$m*") { return }
    }

    $matched = $false
    foreach ($p in $includePatterns) {
        if ($_.Name -like $p) { $matched = $true; break }
    }
    if (-not $matched) { return }

    $dest = Join-Path $Target $relative
    $destDir = Split-Path -Parent $dest
    New-Item -ItemType Directory -Force -Path $destDir | Out-Null
    Copy-Item -LiteralPath $full -Destination $dest -Force
}

$profilePath = Join-Path $Target "android_ime_profile.json"
$syncTime = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
$json = @"
{
  "active_schema": "liur",
  "candidate_schemas": ["liur", "liur_lite", "liur_full", "easy_en", "allbpm", "terra_pinyin_onion"],
  "source": "D:\\APP\\rime-liur-lua-master",
  "synced_at": "$syncTime"
}
"@
Set-Content -Encoding UTF8 -Path $profilePath -Value $json
Write-Host "Rime scheme synced from $Source to $Target"
