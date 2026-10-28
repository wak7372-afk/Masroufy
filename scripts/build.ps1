param([switch]$CoreOnly)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskJava = Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools') -Directory -Filter 'jdk-*' | Select-Object -First 1
if (!$taskJava) { throw 'Run scripts/setup-tools.ps1 first.' }
$env:JAVA_HOME = $taskJava.FullName
$env:GRADLE_USER_HOME = Join-Path ([IO.Path]::GetTempPath()) 'masroofi-gradle-v1'
$taskOldModules = Join-Path $taskRoot '.gradle/caches/modules-2'
$taskNewModules = Join-Path $env:GRADLE_USER_HOME 'caches/modules-2'
if ((Test-Path -LiteralPath $taskOldModules) -and !(Test-Path -LiteralPath $taskNewModules)) {
    New-Item -ItemType Directory -Path (Join-Path $env:GRADLE_USER_HOME 'caches') -Force | Out-Null
    Copy-Item -LiteralPath $taskOldModules -Destination $taskNewModules -Recurse
}
$env:ANDROID_HOME = Join-Path $taskRoot '.tools/android-sdk'
$taskGradle = Join-Path $taskRoot '.tools/gradle-8.14.3/bin/gradle.bat'
# Gradle Windows issue #31438 can leave completed, readable transforms without
# promoting their directory. Recover only complete Gradle-generated cache data.
$taskTransformRoot = [IO.Path]::GetFullPath((Join-Path $env:GRADLE_USER_HOME 'caches/8.14.3/transforms'))
if (Test-Path -LiteralPath $taskTransformRoot) {
    Get-ChildItem -LiteralPath $taskTransformRoot -Directory | ForEach-Object {
        if ($_.Name -match '^([a-f0-9]{32})-[a-f0-9-]{36}$') {
            $taskSource = [IO.Path]::GetFullPath($_.FullName)
            $taskTarget = [IO.Path]::GetFullPath((Join-Path $taskTransformRoot $Matches[1]))
            if (!$taskSource.StartsWith($taskTransformRoot + [IO.Path]::DirectorySeparatorChar) -or !$taskTarget.StartsWith($taskTransformRoot + [IO.Path]::DirectorySeparatorChar)) { throw 'Unsafe Gradle cache path.' }
            if (!(Test-Path -LiteralPath $taskTarget) -and (Test-Path -LiteralPath (Join-Path $taskSource 'metadata.bin')) -and (Test-Path -LiteralPath (Join-Path $taskSource 'results.bin'))) {
                Copy-Item -LiteralPath $taskSource -Destination $taskTarget -Recurse
            }
        }
    }
}
Push-Location $taskRoot
try {
    if ($CoreOnly) { & $taskGradle --no-daemon '-Dorg.gradle.internal.instrumentation.agent=false' -PcoreOnly=true :shared:jvmTest }
    else { & $taskGradle --no-daemon '-Dorg.gradle.internal.instrumentation.agent=false' :shared:jvmTest :androidApp:testReleaseUnitTest :androidApp:assembleRelease :androidApp:lintRelease wrapper --gradle-version 8.14.3 --distribution-type bin }
    if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
} finally { Pop-Location }
