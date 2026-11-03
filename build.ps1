param(
    [switch]$Release,
    [switch]$Check,
    [switch]$Offline
)

# Local process settings only. No global Java/Android installation is changed.
$ErrorActionPreference = 'Stop'
$projectDirectory = $PSScriptRoot
$savedJavaHome = $env:JAVA_HOME
$savedAndroidHome = $env:ANDROID_HOME
$savedPath = $env:Path
$exitCode = 0
try {
    if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath "$env:JAVA_HOME\bin\java.exe")) {
        $javaCandidates = @(
            "$env:LOCALAPPDATA\Temp\opencode\android-build\jdk17",
            "$env:ProgramFiles\Android\Android Studio\jbr"
        )
        $env:JAVA_HOME = $javaCandidates | Where-Object { Test-Path -LiteralPath "$_\bin\java.exe" } | Select-Object -First 1
    }
    if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17 or later, then run build.ps1 again.' }
    if (-not (Test-Path -LiteralPath "$projectDirectory\local.properties")) {
        if (-not $env:ANDROID_HOME) {
            $env:ANDROID_HOME = @("$env:LOCALAPPDATA\Android\Sdk", "$env:LOCALAPPDATA\Temp\opencode\android-build\sdk") |
                Where-Object { Test-Path -LiteralPath "$_\platforms\android-35" } | Select-Object -First 1
        }
        if (-not $env:ANDROID_HOME) { throw 'Set ANDROID_HOME to an Android SDK with platform 35 and accept its licenses.' }
    }
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    # An ASCII output path avoids JDK 17's Windows argument-file encoding issue.
    $pathBytes = [Text.Encoding]::UTF8.GetBytes($projectDirectory)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $buildId = ([BitConverter]::ToString($sha.ComputeHash($pathBytes))).Replace('-', '').Substring(0, 12) }
    finally { $sha.Dispose() }
    $buildRoot = Join-Path ([IO.Path]::GetTempPath()) "pivoskan-build-$buildId"
    $variant = if ($Release) { 'release' } else { 'debug' }
    if ($Release -and -not (Test-Path -LiteralPath "$projectDirectory\keystore.properties")) {
        throw 'A signed release needs keystore.properties. See README.md; use build.ps1 without -Release for an installable debug APK.'
    }
    $tasks = @()
    if ($Check) { $tasks += ':app:testDebugUnitTest'; $tasks += ':app:lintRelease' }
    $tasks += if ($Release) { ':app:assembleRelease' } else { ':app:assembleDebug' }
    $arguments = @($tasks) + @("-PpivoskanBuildDir=$($buildRoot.Replace('\', '/'))", '--no-daemon')
    if ($Offline) { $arguments += '--offline' }
    Push-Location -LiteralPath $projectDirectory
    try {
        & '.\gradlew.bat' @arguments
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) { throw "Gradle failed (exit $exitCode)." }
    } finally { Pop-Location }
    $apk = Join-Path $buildRoot "app\outputs\apk\$variant\app-$variant.apk"
    if (-not (Test-Path -LiteralPath $apk)) { throw "APK not found: $apk" }
    $delivery = Join-Path $projectDirectory 'dist'
    New-Item -ItemType Directory -Path $delivery -Force | Out-Null
    $moduleConfig = [IO.File]::ReadAllText((Join-Path $projectDirectory 'app\build.gradle.kts'))
    $versionMatch = [regex]::Match($moduleConfig, 'versionName\s*=\s*"([0-9.]+)"')
    if (-not $versionMatch.Success) { throw 'Cannot determine release version.' }
    $releaseName = 'Pivoskan-' + $versionMatch.Groups[1].Value + '.apk'
    $destination = Join-Path $delivery $(if ($Release) { $releaseName } else { 'Pivoskan-debug.apk' })
    Copy-Item -LiteralPath $apk -Destination $destination -Force
    Write-Host "APK: $destination"
    Write-Host "Build outputs: $buildRoot"
} catch {
    Write-Error $_ -ErrorAction Continue
    $exitCode = 1
} finally {
    $env:JAVA_HOME = $savedJavaHome
    $env:ANDROID_HOME = $savedAndroidHome
    $env:Path = $savedPath
}
exit $exitCode
