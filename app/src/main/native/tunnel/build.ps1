param(
    [string]$GoExecutable,
    [string]$AndroidNdk,
    [string]$BuildRoot,
    [string]$OutputRoot,
    [string[]]$Abis = @("arm64-v8a")
)

$ErrorActionPreference = "Stop"
# Gradle can inherit PowerShell 7 module paths while launching Windows PowerShell 5.
Import-Module (Join-Path $PSHOME "Modules/Microsoft.PowerShell.Utility/Microsoft.PowerShell.Utility.psd1") -Force
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..\..\..")).Path
if ([string]::IsNullOrWhiteSpace($BuildRoot)) {
    $BuildRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot "..\.build-tunnel-native"))
}
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot "app\build\generated\tunnelJniLibs"
}
if ([string]::IsNullOrWhiteSpace($GoExecutable)) {
    $goCommand = Get-Command go.exe -ErrorAction SilentlyContinue
    if ($null -ne $goCommand) { $GoExecutable = $goCommand.Source }
    else { $GoExecutable = Join-Path $BuildRoot "go\bin\go.exe" }
}
if ([string]::IsNullOrWhiteSpace($AndroidNdk)) {
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_NDK_HOME)) {
        $AndroidNdk = $env:ANDROID_NDK_HOME
    } elseif (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
        $AndroidNdk = Join-Path $env:ANDROID_SDK_ROOT "ndk\30.0.15729638"
    } else {
        throw "Set ANDROID_NDK_HOME or pass -AndroidNdk for NDK 30.0.15729638-beta2."
    }
}

$goPath = (Resolve-Path $GoExecutable).Path
$ndkPath = (Resolve-Path $AndroidNdk).Path
$revisionFile = Join-Path $ndkPath "source.properties"
if (-not (Test-Path $revisionFile) -or ((Get-Content $revisionFile -Raw) -notmatch "Pkg\.Revision\s*=\s*30\.0\.15729638-beta2(?:\r?\n|$)")) {
    throw "The tunnel runtime is pinned to Android NDK 30.0.15729638-beta2."
}
$goVersion = (& $goPath version)
if ($LASTEXITCODE -ne 0 -or $goVersion -notmatch "go1\.26\.10(?:\s|$)") {
    throw "The tunnel runtime is pinned to Go 1.26.10; found '$goVersion'."
}

$modCache = Join-Path $BuildRoot "gopath\pkg\mod"
$goCache = Join-Path $BuildRoot "gocache"
$patchRoot = Join-Path $BuildRoot "tunnel-source-patches"
$patchedTsnetModule = Join-Path $patchRoot "tailscale"
$patchedTsnet = Join-Path $patchedTsnetModule "tsnet\tsnet.go"
$patchedCertStore = Join-Path $patchedTsnetModule "ipn\ipnlocal\cert.go"
$buildModFile = Join-Path $patchRoot "tunnel-build.mod"
$buildSumFile = Join-Path $patchRoot "tunnel-build.sum"
$stageRoot = Join-Path $BuildRoot "tunnel-native-output"
$temporaryRoot = Join-Path $BuildRoot "tmp"
New-Item -ItemType Directory -Force -Path $modCache, $goCache, $patchRoot, $stageRoot, $temporaryRoot | Out-Null

$env:GOPATH = Join-Path $BuildRoot "gopath"
$env:GOMODCACHE = $modCache
$env:GOCACHE = $goCache
$env:GOTOOLCHAIN = "local"
$env:GOFLAGS = ""
$env:TEMP = $temporaryRoot
$env:TMP = $temporaryRoot
Push-Location $PSScriptRoot
try {
    & $goPath mod download
    if ($LASTEXITCODE -ne 0) { throw "Pinned Go module download failed." }
    & $goPath mod verify
    if ($LASTEXITCODE -ne 0) { throw "Pinned Go module verification failed." }

    $tsnetSource = Join-Path $modCache "tailscale.com@v1.96.4\tsnet\tsnet.go"
    if (-not (Test-Path $tsnetSource)) { throw "Pinned Tailscale tsnet source was not downloaded." }
    $tsnetHash = (Get-FileHash -LiteralPath $tsnetSource -Algorithm SHA256).Hash
    if ($tsnetHash -ne "56CE84F1361F7A0BE6330840B399944D72531F40181A61BC3D646F787E7F7002") {
        throw "Pinned tsnet source differs from the reviewed v1.96.4 source; refusing to patch it."
    }
    $certStoreSource = Join-Path $modCache "tailscale.com@v1.96.4\ipn\ipnlocal\cert.go"
    if (-not (Test-Path $certStoreSource)) { throw "Pinned Tailscale certificate-store source was not downloaded." }
    $certStoreHash = (Get-FileHash -LiteralPath $certStoreSource -Algorithm SHA256).Hash
    if ($certStoreHash -ne "64D1C0D09178EF892FC6BFD7AD99C17A052E649DECEFAF5715640E90079FD6EA") {
        throw "Pinned certificate-store source differs from the reviewed v1.96.4 source; refusing to patch it."
    }
    $tsnetModule = Split-Path -Parent (Split-Path -Parent $tsnetSource)
    New-Item -ItemType Directory -Force -Path $patchedTsnetModule | Out-Null
    Get-ChildItem -LiteralPath $tsnetModule -Force | Copy-Item -Destination $patchedTsnetModule -Recurse -Force
    Get-ChildItem -LiteralPath $patchedTsnetModule -Recurse -File | ForEach-Object { $_.IsReadOnly = $false }
    $source = [IO.File]::ReadAllText($tsnetSource)
    $startMarker = "func (s *Server) startLogger("
    $endMarker = "type closeOnErrorPool"
    $start = $source.IndexOf($startMarker, [StringComparison]::Ordinal)
    $end = $source.IndexOf($endMarker, $start, [StringComparison]::Ordinal)
    $licenseImport = "`"tailscale.com/logpolicy`""
    if ($start -lt 0 -or $end -lt 0 -or $source.IndexOf($licenseImport, [StringComparison]::Ordinal) -lt 0) {
        throw "Pinned tsnet logger patch markers changed; refusing to build."
    }
    $newline = if ($source.Contains("`r`n")) { "`r`n" } else { "`n" }
    $replacement = @(
        "func (s *Server) startLogger(closePool *closeOnErrorPool, health *health.Tracker, tsLogf logger.Logf) error {",
        "`treturn nil",
        "}",
        ""
    ) -join $newline
    $patched = $source.Substring(0, $start) + $replacement + $source.Substring($end)
    $patched = [regex]::Replace($patched, "(?m)^\s*`"tailscale.com/(?:logpolicy|util/clientmetric|util/testenv)`"\r?\n", "")
    $networkAnchor = "// LocalClient returns a LocalClient that speaks to s."
    $networkInsert = $patched.IndexOf($networkAnchor, [StringComparison]::Ordinal)
    if ($networkInsert -lt 0 -or $patched.Contains("func (s *Server) NetworkChanged()")) {
        throw "Pinned tsnet network-monitor patch markers changed; refusing to build."
    }
    $networkMethod = @(
        "// NetworkChanged asks the platform monitor to rescan network interfaces.",
        "func (s *Server) NetworkChanged() {",
        "`tif s.netMon != nil {",
        "`t`ts.netMon.InjectEvent()",
        "`t}",
        "}",
        "",
        ""
    ) -join $newline
    $patched = $patched.Insert($networkInsert, $networkMethod)
    $resetError = 'fmt.Errorf("tsnet.Up: clearing serve config: %w", err)'
    if (-not $patched.Contains($resetError)) { throw "Pinned Serve error wrapper changed." }
    $patched = $patched.Replace($resetError, 'fmt.Errorf("tsnet.Up: clearing serve config: %w", srvResetErr)')
    # http.Server.Serve can serve h2 only when this already-wrapped listener advertises it.
    # Match ListenTLS's config, leaving ListenFunnel's independent config unchanged.
    $privateTlsConfig = "return tls.NewListener(ln, &tls.Config{" + $newline + "`t`tGetCertificate: s.getCert,"
    if (-not $patched.Contains($privateTlsConfig)) { throw "Pinned private TLS config changed." }
    $patched = $patched.Replace($privateTlsConfig,
        $privateTlsConfig + $newline + "`t`t" + 'NextProtos: []string{"h2", "http/1.1"},')
    [IO.File]::WriteAllText($patchedTsnet, $patched, [Text.UTF8Encoding]::new($false))

    $localCertPath = Join-Path $patchedTsnetModule "ipn\localapi\cert.go"
    $localCert = [IO.File]::ReadAllText($localCertPath)
    $certConstraint = '//go:build !ios && !android && !js && !ts_omit_acme'
    if (-not $localCert.Contains($certConstraint)) { throw "Pinned LocalAPI cert constraint changed." }
    [IO.File]::WriteAllText($localCertPath, $localCert.Replace($certConstraint,
        '//go:build !ios && !js && !ts_omit_acme'), [Text.UTF8Encoding]::new($false))
    $stubPath = Join-Path $patchedTsnetModule "ipn\localapi\disabled_stubs.go"
    $stub = [IO.File]::ReadAllText($stubPath)
    if (-not $stub.Contains('//go:build ios || android || js')) { throw "Pinned cert stub constraint changed." }
    [IO.File]::WriteAllText($stubPath, $stub.Replace('//go:build ios || android || js',
        '//go:build ios || js'), [Text.UTF8Encoding]::new($false))

    $certSource = [IO.File]::ReadAllText($certStoreSource)
    $certMethodStart = "func (b *LocalBackend) getCertStore() (certStore, error) {"
    $certMethodEnd = "// ConfigureCertsForTest"
    $certStart = $certSource.IndexOf($certMethodStart, [StringComparison]::Ordinal)
    $certEnd = $certSource.IndexOf($certMethodEnd, $certStart, [StringComparison]::Ordinal)
    if ($certStart -lt 0 -or $certEnd -lt 0) {
        throw "Pinned certificate-store patch markers changed; refusing to build."
    }
    $certReplacement = @(
        "func (b *LocalBackend) getCertStore() (certStore, error) {",
        "`tswitch b.store.(type) {",
        "`tcase *store.FileStore, *mem.Store:",
        "`tdefault:",
        "`t`treturn certStateStore{StateStore: b.store}, nil",
        "`t}",
        "`tdir, err := b.certDir()",
        "`tif err != nil {",
        "`t`treturn nil, err",
        "`t}",
        "`tif testX509Roots != nil && !testenv.InTest() {",
        "`t`tpanic(`"use of test hook outside of tests`")",
        "`t}",
        "`treturn certFileStore{dir: dir, testRoots: testX509Roots}, nil",
        "}",
        ""
    ) -join $newline
    $patchedCert = $certSource.Substring(0, $certStart) + $certReplacement + $certSource.Substring($certEnd)
    $patchedCert = [regex]::Replace($patchedCert, '(?m)^\t"tailscale.com/hostinfo"\r?\n', "")
    [IO.File]::WriteAllText($patchedCertStore, $patchedCert, [Text.UTF8Encoding]::new($false))
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot "go.mod") -Destination $buildModFile -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot "go.sum") -Destination $buildSumFile -Force
    $replacementPath = $patchedTsnetModule.Replace("\", "/")
    & $goPath mod edit "-modfile=$buildModFile" "-replace=tailscale.com=$replacementPath"
    if ($LASTEXITCODE -ne 0) { throw "Could not create the pinned Tailscale source patch modfile." }

    $toolchainBin = Join-Path $ndkPath "toolchains\llvm\prebuilt\windows-x86_64\bin"
    $abiTargets = @{
        "arm64-v8a"     = @{ Arch = "arm64"; Compiler = "aarch64-linux-android26-clang.cmd"; GoArm = "" }
        "armeabi-v7a"   = @{ Arch = "arm"; Compiler = "armv7a-linux-androideabi26-clang.cmd"; GoArm = "7" }
        "x86_64"        = @{ Arch = "amd64"; Compiler = "x86_64-linux-android26-clang.cmd"; GoArm = "" }
        "x86"           = @{ Arch = "386"; Compiler = "i686-linux-android26-clang.cmd"; GoArm = "" }
    }
    New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
    foreach ($abi in $Abis) {
        if (-not $abiTargets.ContainsKey($abi)) { throw "Unsupported Android ABI '$abi'." }
        $target = $abiTargets[$abi]
        $clang = Join-Path $toolchainBin $target.Compiler
        if (-not (Test-Path $clang)) { throw "NDK compiler wrapper '$($target.Compiler)' was not found." }
        $env:GOOS = "android"
        $env:GOARCH = $target.Arch
        $env:CGO_ENABLED = "1"
        $env:CC = $clang
        $env:CGO_CFLAGS = "-I$PSScriptRoot"
        $env:CGO_CPPFLAGS = "-I$PSScriptRoot"
        $env:CGO_LDFLAGS = "-llog"
        if ([string]::IsNullOrEmpty($target.GoArm)) { Remove-Item Env:GOARM -ErrorAction SilentlyContinue }
        else { $env:GOARM = $target.GoArm }
        $abiStage = Join-Path $stageRoot $abi
        New-Item -ItemType Directory -Force -Path $abiStage | Out-Null
        $output = Join-Path $abiStage "libcamera_tunnel.so"
        & $goPath build "-modfile=$buildModFile" -tags=ts_omit_logtail -trimpath -buildmode=c-shared `
            -ldflags="-s -w -buildid=" -o $output .
        if ($LASTEXITCODE -ne 0) { throw "Android tunnel build failed for ABI '$abi'." }
        $abiOutput = Join-Path $OutputRoot $abi
        New-Item -ItemType Directory -Force -Path $abiOutput | Out-Null
        Copy-Item -LiteralPath $output -Destination (Join-Path $abiOutput "libcamera_tunnel.so") -Force
        $header = Join-Path $abiStage "libcamera_tunnel.h"
        if (Test-Path $header) { Remove-Item -LiteralPath $header -Force }
    }
} finally {
    Pop-Location
}
