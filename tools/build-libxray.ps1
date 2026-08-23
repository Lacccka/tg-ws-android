param(
    [string]$Tag = "v26.7.28",
    [ValidateSet("release", "source")]
    [string]$Mode = "release",
    [string]$Python = "python",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# SHA-256 of libXray.aar inside the official XTLS/libXray Android artifact
# produced by Build libXray run 30426588453 for tag v26.7.28.
# GitHub Actions artifact digest: sha256:95ba3c2dee0e11afea22bf2bb83947d50adf2930cfbeeb86d49edcae8b4d7e93
$PinnedAarSha256 = @{
    "v26.7.28" = "4708a361a74f7e955635dbe3661cefb459bdc867423c3b1826a2c5a6ea4ac77d"
}

function Require-Command([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found in PATH."
    }
}

function Verify-Aar([string]$AarPath, [string]$ExpectedSha256) {
    if (-not (Test-Path -LiteralPath $AarPath)) {
        throw "libXray AAR not found: $AarPath"
    }

    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $AarPath).Hash.ToLowerInvariant()
    if ($actualHash -ne $ExpectedSha256.ToLowerInvariant()) {
        throw "libXray.aar SHA-256 mismatch. Expected $ExpectedSha256, got $actualHash"
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($AarPath)
    try {
        $entries = @($archive.Entries | ForEach-Object { $_.FullName })
        if ($entries -notcontains "classes.jar") {
            throw "libXray.aar does not contain classes.jar"
        }
        if ($entries -notcontains "jni/arm64-v8a/libgojni.so") {
            throw "libXray.aar does not contain jni/arm64-v8a/libgojni.so"
        }
    }
    finally {
        $archive.Dispose()
    }

    return $actualHash
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$cacheRoot = Join-Path $repoRoot ".cache\libxray"
$appLibDir = Join-Path $repoRoot "app\libs"
$aarTarget = Join-Path $appLibDir "libXray.aar"
$sourcesTarget = Join-Path $appLibDir "libXray-sources.jar"

New-Item -ItemType Directory -Force -Path $cacheRoot | Out-Null
New-Item -ItemType Directory -Force -Path $appLibDir | Out-Null

if (-not $PinnedAarSha256.ContainsKey($Tag)) {
    throw "Tag '$Tag' is not pinned in tools/build-libxray.ps1. Add and verify its official AAR SHA-256 before using it."
}
$expectedAarSha256 = $PinnedAarSha256[$Tag]

if ($Mode -eq "release") {
    $releaseCache = Join-Path $cacheRoot "release\$Tag"
    $zipPath = Join-Path $releaseCache "libxray-android.zip"
    $extractPath = Join-Path $releaseCache "extracted"
    $releaseUrl = "https://github.com/XTLS/libXray/releases/download/$Tag/libxray-android.zip"

    New-Item -ItemType Directory -Force -Path $releaseCache | Out-Null
    if ($Force) {
        Remove-Item -Force -ErrorAction SilentlyContinue $zipPath
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $extractPath
    }

    if (-not (Test-Path -LiteralPath $zipPath)) {
        Write-Host "Downloading official XTLS/libXray Android release artifact $Tag..."
        Write-Host $releaseUrl
        Invoke-WebRequest -Uri $releaseUrl -OutFile $zipPath
    }
    else {
        Write-Host "Using cached official artifact: $zipPath"
    }

    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $extractPath
    New-Item -ItemType Directory -Force -Path $extractPath | Out-Null
    Expand-Archive -LiteralPath $zipPath -DestinationPath $extractPath -Force

    $builtAar = Get-ChildItem -LiteralPath $extractPath -Recurse -File -Filter "libXray.aar" |
        Select-Object -First 1
    if ($null -eq $builtAar) {
        throw "Official release archive does not contain libXray.aar"
    }

    $verifiedHash = Verify-Aar -AarPath $builtAar.FullName -ExpectedSha256 $expectedAarSha256
    Copy-Item -Force -LiteralPath $builtAar.FullName -Destination $aarTarget

    $builtSources = Get-ChildItem -LiteralPath $extractPath -Recurse -File -Filter "libXray-sources.jar" |
        Select-Object -First 1
    if ($null -ne $builtSources) {
        Copy-Item -Force -LiteralPath $builtSources.FullName -Destination $sourcesTarget
    }

    Write-Host ""
    Write-Host "Official libXray ready: $aarTarget"
    Write-Host "tag: $Tag"
    Write-Host "sha256: $verifiedHash"
}
else {
    Require-Command git
    Require-Command go
    Require-Command $Python

    $sourceDir = Join-Path $cacheRoot "source\$Tag"
    if ($Force -and (Test-Path -LiteralPath $sourceDir)) {
        Remove-Item -Recurse -Force $sourceDir
    }
    New-Item -ItemType Directory -Force -Path (Split-Path $sourceDir -Parent) | Out-Null

    if (-not (Test-Path -LiteralPath $sourceDir)) {
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
        Write-Host (& go version)
        Write-Host "Building Android libXray with the upstream build script..."
        & $Python build/main.py android
        if ($LASTEXITCODE -ne 0) { throw "libXray Android build failed" }

        $builtAarPath = Join-Path $sourceDir "libXray.aar"
        if (-not (Test-Path -LiteralPath $builtAarPath)) {
            throw "Upstream build completed but libXray.aar was not produced."
        }

        $sourceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $builtAarPath).Hash.ToLowerInvariant()
        Write-Host "Source-build AAR sha256: $sourceHash"
        if ($sourceHash -ne $expectedAarSha256) {
            Write-Warning "Source build is not byte-identical to the pinned official artifact. This can happen when gomobile/toolchain versions differ. The default release mode is the reproducible test path."
        }

        Copy-Item -Force -LiteralPath $builtAarPath -Destination $aarTarget
        $builtSourcesPath = Join-Path $sourceDir "libXray-sources.jar"
        if (Test-Path -LiteralPath $builtSourcesPath) {
            Copy-Item -Force -LiteralPath $builtSourcesPath -Destination $sourcesTarget
        }
    }
    finally {
        Pop-Location
    }
}

$finalHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $aarTarget).Hash.ToLowerInvariant()
$size = (Get-Item -LiteralPath $aarTarget).Length
Write-Host "size: $size bytes"
Write-Host "installed sha256: $finalHash"
Write-Host ""
Write-Host "Now build the Xray-enabled private sideload APK:"
Write-Host ".\gradlew.bat clean assemblePrivateSideload"
