# Developer-only download. The installed application never downloads models.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskAssets = Join-Path $taskRoot 'androidApp/src/main/assets/ocr'
[IO.Directory]::CreateDirectory($taskAssets) | Out-Null
$taskManifestPath = Join-Path $taskAssets 'manifest.json'
$taskManifest = $null
if (Test-Path -LiteralPath $taskManifestPath) {
    $taskManifest = Get-Content -LiteralPath $taskManifestPath -Raw | ConvertFrom-Json
    $taskCommit = $taskManifest.commit
} else {
    $taskCommit = (Invoke-RestMethod -Uri 'https://api.github.com/repos/tesseract-ocr/tessdata_fast/commits/main' -Headers @{ 'User-Agent'='Masroufy-build' }).sha
}
if ($taskCommit -notmatch '^[a-f0-9]{40}$') { throw 'Invalid model revision.' }
$taskFiles = @()
foreach ($taskLanguage in @('eng','ara')) {
    $taskName = $taskLanguage + '.traineddata'
    $taskDestination = Join-Path $taskAssets $taskName
    $taskUrl = 'https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/' + $taskCommit + '/' + $taskName
    if (!(Test-Path -LiteralPath $taskDestination)) { Invoke-WebRequest -Uri $taskUrl -OutFile $taskDestination }
    $taskHash = (Get-FileHash -LiteralPath $taskDestination -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($taskManifest) {
        $taskExpected = $taskManifest.files | Where-Object name -eq $taskName
        if (!$taskExpected -or $taskExpected.sha256 -ne $taskHash) { throw 'OCR model checksum mismatch.' }
    }
    $taskFiles += @{ name=$taskName; sha256=$taskHash; bytes=(Get-Item -LiteralPath $taskDestination).Length; url=$taskUrl }
}
$taskLicense = Join-Path $taskAssets 'LICENSE.txt'
if (!(Test-Path -LiteralPath $taskLicense)) { Invoke-WebRequest -Uri ('https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/' + $taskCommit + '/LICENSE') -OutFile $taskLicense }
@{ source='tesseract-ocr/tessdata_fast'; commit=$taskCommit; license='Apache-2.0'; files=$taskFiles } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $taskManifestPath -Encoding utf8
$taskFiles | ForEach-Object { [pscustomobject]$_ | ConvertTo-Json -Compress }
