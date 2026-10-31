# SPDX-License-Identifier: LGPL-3.0-only
<#
.SYNOPSIS
    Builds the MobileGL renderer plugin APK (debug variants, debug-signed).

.EXAMPLE
    .\tools\build-plugin.ps1 -SdkRoot "C:\Users\me\AppData\Local\Android\Sdk"

.NOTES
    Requirements: JDK 17, Gradle 8.10.x, Android SDK (platform 34, build-tools 34),
    NDK 27.3.13750724. The repository submodules must be checked out
    (git submodule update --init --recursive).
#>
param(
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$NdkVersion = "27.3.13750724",
    [switch]$Release
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
if (-not $SdkRoot) {
    throw "ANDROID_HOME is not set. Pass -SdkRoot <path-to-Android-Sdk>."
}
if (-not (Get-Command gradle -ErrorAction SilentlyContinue)) {
    throw "gradle was not found on PATH. Install Gradle 8.10.x or build through GitHub Actions."
}

$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$localProps = Join-Path $repo "android-plugin\local.properties"
$ndkPath = Join-Path $SdkRoot "ndk\$NdkVersion"
Set-Content -Path $localProps -Value ("sdk.dir=" + $SdkRoot.Replace("\", "\\"))
Add-Content -Path $localProps -Value ("ndk.dir=" + $ndkPath.Replace("\", "\\"))

$tasks = if ($Release) {
    @(":app:assembleEsprytPluginRelease", ":app:assembleMagmaPluginRelease")
} else {
    @(":app:assembleEsprytPluginDebug", ":app:assembleMagmaPluginDebug")
}
Write-Host "Building: $($tasks -join ', ')"
Push-Location $repo
try {
    & gradle --no-daemon -p android-plugin @tasks -Pmobilegl.logLevel=MOBILEGL_LOG_LEVEL_INFO --parallel
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
Write-Host "APKs:"
Get-ChildItem -Recurse -File (Join-Path $repo "android-plugin/app/build/outputs/apk") -Filter *.apk |
    ForEach-Object { Write-Host ("  " + $_.FullName) }
