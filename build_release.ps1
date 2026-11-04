# Cura:CareYou Local Release Build Runner
# Builds Release APK completely offline without consuming GitHub Actions quota.

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

$jdkPath = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
$sdkPath = "C:\Users\kanji\AppData\Local\Android\Sdk"

if (Test-Path $jdkPath) {
    $env:JAVA_HOME = $jdkPath
    $env:Path = "$jdkPath\bin;$env:Path"
}
if (Test-Path $sdkPath) {
    $env:ANDROID_HOME = $sdkPath
}

Write-Output "[1/3] Running unit tests..."
.\gradlew.bat :app:testDebugUnitTest --no-daemon
if ($LASTEXITCODE -ne 0) {
    Write-Error "Tests failed. Aborting build."
}

Write-Output "[2/3] Building Release APK..."
.\gradlew.bat :app:assembleRelease --no-daemon
if ($LASTEXITCODE -ne 0) {
    Write-Error "Release APK build failed."
}

$apkSource = "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apkSource)) {
    Write-Error "APK not found at $apkSource"
}

$distDir = "dist"
if (-not (Test-Path $distDir)) {
    New-Item -ItemType Directory -Path $distDir | Out-Null
}

$buildGradle = Get-Content "app\build.gradle.kts" -Raw
$verName = "latest"
if ($buildGradle -match 'versionName\s*=\s*"([^"]+)"') {
    $verName = $matches[1]
}

$apkTarget = "$distDir\Cura_CareYou_v$verName.apk"
Copy-Item -Path $apkSource -Destination $apkTarget -Force

Write-Output "[3/3] Build complete!"
Write-Output "APK Output: $(Resolve-Path $apkTarget)"

$adbPath = "$sdkPath\platform-tools\adb.exe"
if (Test-Path $adbPath) {
    $devices = & $adbPath devices | Where-Object { $_ -match '\tdevice$' }
    if ($devices) {
        Write-Output "Connected Android device detected. Installing APK..."
        & $adbPath install -r $apkTarget
        if ($LASTEXITCODE -eq 0) {
            Write-Output "Installed successfully to device!"
        }
    }
}
