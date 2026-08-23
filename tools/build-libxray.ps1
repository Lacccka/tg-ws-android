param(
    [string]$Tag = "v26.7.28",
    [string]$Python = "python",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Require-Command([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found in PATH."
    }
}

Require-Command git
Require-Command go
Require-Command $Python

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$cacheRoot = Join-Path $repoRoot ".cache\libxray"
$sourceDir = Join-Path $cacheRoot $Tag
$appLibDir = Join-Path $repoRoot "app\libs"
$aarTarget = Join-Path $appLibDir "libXray.aar"
$sourcesTarget = Join-Path $appLibDir "libXray-sources.jar"

if ($Force -and (Test-Path $sourceDir)) {
    Remove-Item -Recurse -Force $sourceDir
}

New-Item -ItemType Directory -Force -Path $cacheRoot | Out-Null
New-Item -ItemType Directory -Force -Path $appLibDir | Out-Null

if (-not (Test-Path $sourceDir)) {
    Write-Host "Cloning XTLS/libXray $Tag..."
    & git clone --depth 1 --branch $Tag https://github.com/XTLS/libXray.git $sourceDir
    if ($LASTEXITCODE -ne 0) { throw "git clone libXray failed" }
}
else {
    Write-Host "Using cached libXray source: $sourceDir"
}

Push-Location $sourceDir
try {
    $head = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw "Unable to resolve libXray HEAD" }
    Write-Host "libXray source commit: $head"

    $goVersion = (& go version)
    if ($LASTEXITCODE -ne 0) { throw "go version failed" }
    Write-Host $goVersion

    Write-Host "Building Android libXray with the upstream build script..."
    & $Python build/main.py android
    if ($LASTEXITCODE -ne 0) { throw "libXray Android build failed" }

    $builtAar = Join-Path $sourceDir "libXray.aar"
    $builtSources = Join-Path $sourceDir "libXray-sources.jar"
    if (-not (Test-Path $builtAar)) {
        throw "Upstream build completed but libXray.aar was not produced."
    }

    Copy-Item -Force $builtAar $aarTarget
    if (Test-Path $builtSources) {
        Copy-Item -Force $builtSources $sourcesTarget
    }

    $hash = (Get-FileHash -Algorithm SHA256 $aarTarget).Hash.ToLowerInvariant()
    $size = (Get-Item $aarTarget).Length
    Write-Host ""
    Write-Host "libXray ready: $aarTarget"
    Write-Host "size: $size bytes"
    Write-Host "sha256: $hash"
    Write-Host ""
    Write-Host "Now rebuild private sideload so Gradle packages the local AAR:"
    Write-Host ".\gradlew.bat clean assemblePrivateSideload"
}
finally {
    Pop-Location
}
