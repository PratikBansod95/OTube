# Build Brave adblock-rust JNI library for Android.
# Prerequisites: Rust, Android NDK, cargo-ndk, MSVC Build Tools (Windows host linker)
#
# Usage (from repo root):
#   powershell -ExecutionPolicy Bypass -File scripts\build-native-adblock.ps1

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$Ffi = Join-Path $Root "external\adblock-ffi"
$Out = Join-Path $Root "app\src\main\jniLibs"

$env:Path = "$env:USERPROFILE\.cargo\bin;" + $env:Path

# Load MSVC environment on Windows (needed for host build scripts)
$vcvarsCandidates = @(
    "${env:ProgramFiles(x86)}\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat",
    "${env:ProgramFiles}\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat",
    "${env:ProgramFiles(x86)}\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
)
foreach ($vc in $vcvarsCandidates) {
    if (Test-Path $vc) {
        cmd /c "`"$vc`" && set" | ForEach-Object {
            if ($_ -match '^(.*?)=(.*)$') {
                Set-Item -Path "Env:$($matches[1])" -Value $matches[2]
            }
        }
        $env:Path = "$env:USERPROFILE\.cargo\bin;" + $env:Path
        Write-Host "Loaded MSVC env from $vc"
        break
    }
}

$NdkRoot = $env:ANDROID_NDK_HOME
if (-not $NdkRoot) {
    $Sdk = $env:ANDROID_HOME
    if (-not $Sdk) { $Sdk = "$env:LOCALAPPDATA\Android\Sdk" }
    $NdkVersions = Get-ChildItem (Join-Path $Sdk "ndk") -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending
    if ($NdkVersions) { $NdkRoot = $NdkVersions[0].FullName }
}
if (-not $NdkRoot -or -not (Test-Path $NdkRoot)) {
    throw "Android NDK not found. Set ANDROID_NDK_HOME or install an NDK via sdkmanager."
}

$env:ANDROID_NDK_HOME = $NdkRoot
Write-Host "Using NDK: $NdkRoot"
Write-Host "Building adblock_ffi..."

if (-not (Get-Command cargo-ndk -ErrorAction SilentlyContinue)) {
    cargo install cargo-ndk
}

Push-Location $Ffi
try {
    cargo ndk -t arm64-v8a -t x86_64 -o $Out build --release
} finally {
    Pop-Location
}

Write-Host "Native libraries written to $Out"
Get-ChildItem -Recurse $Out -Filter "*.so" | ForEach-Object { Write-Host "  $($_.FullName) ($([math]::Round($_.Length/1MB, 2)) MB)" }
