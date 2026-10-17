$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskJava = Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools') -Directory -Filter 'jdk-*' | Select-Object -First 1
$env:JAVA_HOME = $taskJava.FullName
$taskApk = Join-Path $taskRoot 'androidApp/build/outputs/apk/debug/androidApp-debug.apk'
$taskBuildTools = Join-Path $taskRoot '.tools/android-sdk/build-tools/35.0.0'
& (Join-Path $taskBuildTools 'apksigner.bat') verify --verbose $taskApk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$taskBadging = & (Join-Path $taskBuildTools 'aapt.exe') dump badging $taskApk
if ($LASTEXITCODE -ne 0 -or !($taskBadging -match "package: name='om.masroofi.app'")) { throw 'APK metadata verification failed.' }
if ($taskBadging -match "uses-permission: name='android.permission.INTERNET'") { throw 'Unexpected Internet permission in the local-only app.' }
$taskBadging | Select-String 'package:|sdkVersion:|targetSdkVersion:|application-label:|launchable-activity:'
foreach ($taskResultsPath in @('shared/build/test-results/jvmTest', 'androidApp/build/test-results/testDebugUnitTest')) {
    $taskResults = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot $taskResultsPath) -Filter 'TEST-*.xml')
    if ($taskResults.Count -eq 0) { throw "Missing test results: $taskResultsPath" }
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
