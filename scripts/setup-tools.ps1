param([switch]$CoreOnly)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskTools = Join-Path $taskRoot '.tools'
New-Item -ItemType Directory -Path $taskTools -Force | Out-Null
function Get-CheckedArchive([string]$Uri, [string]$Destination, [string]$Hash) {
    if (!(Test-Path -LiteralPath $Destination)) { Invoke-WebRequest -Uri $Uri -OutFile $Destination -TimeoutSec 600 }
    if ((Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Hash.Trim().ToLowerInvariant()) { throw "Checksum mismatch: $Destination" }
}
$taskJava = Get-ChildItem -LiteralPath $taskTools -Directory -Filter 'jdk-*' | Select-Object -First 1
if (!$taskJava) {
    Write-Host 'Downloading verified Java 17...'
    $taskAsset = (Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse')[0].binary.package
    Get-CheckedArchive $taskAsset.link (Join-Path $taskTools 'jdk.zip') $taskAsset.checksum
    Expand-Archive -LiteralPath (Join-Path $taskTools 'jdk.zip') -DestinationPath $taskTools -Force
    $taskJava = Get-ChildItem -LiteralPath $taskTools -Directory -Filter 'jdk-*' | Select-Object -First 1
}
$taskGradle = Join-Path $taskTools 'gradle-8.14.3'
if (!(Test-Path -LiteralPath $taskGradle)) {
    Write-Host 'Downloading verified Gradle 8.14.3...'
    $taskResponse = (Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-8.14.3-bin.zip.sha256').Content
    $taskHash = if ($taskResponse -is [byte[]]) { [Text.Encoding]::UTF8.GetString($taskResponse) } else { [string]$taskResponse }
    Get-CheckedArchive 'https://services.gradle.org/distributions/gradle-8.14.3-bin.zip' (Join-Path $taskTools 'gradle-8.14.3.zip') $taskHash
    Expand-Archive -LiteralPath (Join-Path $taskTools 'gradle-8.14.3.zip') -DestinationPath $taskTools -Force
}
$env:JAVA_HOME = $taskJava.FullName
$env:GRADLE_USER_HOME = Join-Path $taskRoot '.gradle'
if (!$CoreOnly) {
    $taskSdk = Join-Path $taskTools 'android-sdk'
    $taskManager = Join-Path $taskSdk 'cmdline-tools\latest\bin\sdkmanager.bat'
    if (!(Test-Path -LiteralPath $taskManager)) {
        Write-Host 'Downloading verified Android command line tools...'
        Get-CheckedArchive 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' (Join-Path $taskTools 'android-tools.zip') '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
        Expand-Archive -LiteralPath (Join-Path $taskTools 'android-tools.zip') -DestinationPath (Join-Path $taskSdk 'cmdline-tools') -Force
        $taskSource = [IO.Path]::GetFullPath((Join-Path $taskSdk 'cmdline-tools\cmdline-tools'))
        $taskTarget = [IO.Path]::GetFullPath((Join-Path $taskSdk 'cmdline-tools\latest'))
        if (!$taskSource.StartsWith($taskTools + [IO.Path]::DirectorySeparatorChar) -or !$taskTarget.StartsWith($taskTools + [IO.Path]::DirectorySeparatorChar)) { throw 'Tool path is outside the workspace tools directory.' }
        Move-Item -LiteralPath $taskSource -Destination $taskTarget
    }
    $env:ANDROID_HOME = $taskSdk
    Write-Host 'Installing SDK platform 35 and build tools...'
    1..100 | ForEach-Object { 'y' } | & $taskManager --sdk_root=$taskSdk --licenses | Out-Null
    & $taskManager --sdk_root=$taskSdk 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
    if ($LASTEXITCODE -ne 0) { throw 'Android SDK install failed.' }
    ('sdk.dir=' + $taskSdk.Replace('\', '/')) | Set-Content -LiteralPath (Join-Path $taskRoot 'local.properties') -Encoding utf8
}
Write-Host 'Local build tools are ready.'
