param([string]$BuildRoot = '')
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskJava = Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools') -Directory -Filter 'jdk-*' | Select-Object -First 1
$env:JAVA_HOME = $taskJava.FullName
$taskBuildRoot = if ($BuildRoot) { [IO.Path]::GetFullPath($BuildRoot) } else { $taskRoot }
$taskAndroidBuild = Join-Path $taskBuildRoot 'androidApp/build'
if (!(Test-Path -LiteralPath (Join-Path $taskAndroidBuild 'outputs'))) { $taskAndroidBuild = Join-Path $taskBuildRoot 'androidApp' }
$taskApk = Join-Path $taskAndroidBuild 'outputs/apk/debug/androidApp-debug.apk'
$taskBuildTools = Join-Path $taskRoot '.tools/android-sdk/build-tools/35.0.0'
& (Join-Path $taskBuildTools 'apksigner.bat') verify --verbose $taskApk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$taskBadging = & (Join-Path $taskBuildTools 'aapt.exe') dump badging $taskApk
if ($LASTEXITCODE -ne 0 -or !($taskBadging -match "package: name='om.masroofi.app'")) { throw 'APK metadata verification failed.' }
if ($taskBadging -match "uses-permission: name='android.permission.INTERNET'") { throw 'Unexpected Internet permission in the local-only app.' }
$taskBadging | Select-String 'package:|sdkVersion:|targetSdkVersion:|application-label:|launchable-activity:'
foreach ($taskSuite in @(@{ Project='shared'; Task='jvmTest' }, @{ Project='androidApp'; Task='testDebugUnitTest' })) {
    $taskCandidates = @(
        "$($taskSuite.Project)/build/test-results/$($taskSuite.Task)",
        "$($taskSuite.Project)/test-results/$($taskSuite.Task)",
        "$($taskSuite.Project)/statement-test-results/$($taskSuite.Task)/xml"
    )
    $taskResultsDirectory = $taskCandidates | ForEach-Object { Join-Path $taskBuildRoot $_ } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (!$taskResultsDirectory) { throw "Missing test results: $($taskSuite.Task)" }
    $taskResults = @(Get-ChildItem -LiteralPath $taskResultsDirectory -Filter 'TEST-*.xml')
    if ($taskResults.Count -eq 0) { throw "Missing test results: $($taskSuite.Task)" }
    $taskResults | ForEach-Object {
    [xml]$taskTest = Get-Content -LiteralPath $_.FullName
    if ([int]$taskTest.testsuite.failures -ne 0 -or [int]$taskTest.testsuite.errors -ne 0) { throw 'Unit tests failed.' }
    [pscustomobject]@{ Suite=$taskTest.testsuite.name; Tests=$taskTest.testsuite.tests; Failures=$taskTest.testsuite.failures; Errors=$taskTest.testsuite.errors } | ConvertTo-Json -Compress
    }
}
$taskZip = Join-Path $taskRoot '.tools/gradle-8.14.3.zip'
$taskWrapper = Join-Path $taskRoot 'gradle/wrapper/gradle-wrapper.properties'
$taskHash = (Get-FileHash -LiteralPath $taskZip -Algorithm SHA256).Hash.ToLowerInvariant()
$taskProperties = Get-Content -LiteralPath $taskWrapper | Where-Object { !($_ -like 'distributionSha256Sum=*') }
($taskProperties + "distributionSha256Sum=$taskHash") | Set-Content -LiteralPath $taskWrapper -Encoding utf8
Write-Host 'Package, signatures and tests verified; wrapper checksum pinned.'
