#requires -Version 7
<#
Qualifies a Latch MSI on a real Windows machine, the way users receive it.

The release workflow used to build the MSI and upload it without ever
installing it. Whether the updater delivered the bytes it meant to is the
updater's concern; this answers the other half -- is the package itself a
valid, installable, upgradable, removable Windows Installer package:

  1. its database opens through Windows Installer, and its identity
     (ProductName, ProductVersion, Manufacturer, UpgradeCode) is Latch's;
  2. a silent per-user install succeeds and lays down Latch.exe, the
     launcher config, every jar it names, and the bundled JVM;
  3. a silent uninstall succeeds and takes those files with it;
  4. with -BaselineRepo, a silent major upgrade from the newest older
     published release succeeds with the package staged where the updater
     stages it -- the path the auto-update incident went down.

Only native tooling is used: the WindowsInstaller.Installer COM object and
msiexec. Latch.exe is never launched -- an installed build registers itself
to run at login and calls GitHub on start -- so nothing here needs Wi-Fi,
credentials, DPAPI secrets or a UI. Every product this script installs is
removed again, even on failure.

Usage:
  pwsh packaging/qualify-windows-msi.ps1 -Msi <path> -Version <x.y.z>
       [-BaselineRepo owner/repo] [-ProbeLegacyStaging] [-LogDir <dir>]
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Msi,
    [Parameter(Mandatory)] [string] $Version,
    [string] $BaselineRepo,
    [switch] $ProbeLegacyStaging,
    [string] $LogDir = 'msi-logs'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$ExpectedProductName = 'Latch'
$ExpectedManufacturer = 'VinnovateIT'
# desktop/build.gradle.kts: upgradeUuid. Changing it would turn every upgrade
# into a side-by-side install, so it is pinned here as well.
$ExpectedUpgradeCode = '{6F3B9C84-1D52-4E7A-9B06-2A8F5C14D7E3}'

$InstallDir = Join-Path $env:LOCALAPPDATA 'Latch'
$LatchExe = Join-Path $InstallDir 'Latch.exe'
# Mirrors AppPaths.updatesDir on Windows.
$StagingDir = Join-Path $env:TEMP 'Latch-updates'
# Where updaters up to v1.4.2 staged the package: inside the install directory.
$LegacyStagingDir = Join-Path $InstallDir 'updates'

$Msi = (Resolve-Path -LiteralPath $Msi).Path
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$LogDir = (Resolve-Path -LiteralPath $LogDir).Path
$Summary = [System.Collections.Generic.List[string]]::new()

$installer = New-Object -ComObject WindowsInstaller.Installer

# The leading comma returns the COM result as one object. Without it the
# pipeline unrolls enumerable results: an empty RelatedProducts list would
# arrive as $null rather than as a list with Count 0.
function Get-ComProperty($obj, [string] $name, [object[]] $arguments = @()) {
    , $obj.GetType().InvokeMember($name, [Reflection.BindingFlags]::GetProperty, $null, $obj, $arguments)
}

function Invoke-ComMethod($obj, [string] $name, [object[]] $arguments = @()) {
    , $obj.GetType().InvokeMember($name, [Reflection.BindingFlags]::InvokeMethod, $null, $obj, $arguments)
}

function Write-Result([string] $line) {
    Write-Host $line
    $Summary.Add($line)
}

# Opens the package read-only through Windows Installer itself -- exactly the
# step that fails with "This installation package could not be opened" -- and
# returns its Property table.
function Read-MsiProperties([string] $path) {
    $db = $null
    $view = $null
    try {
        $db = Invoke-ComMethod $installer 'OpenDatabase' @($path, 0)
        $view = Invoke-ComMethod $db 'OpenView' @('SELECT `Property`, `Value` FROM `Property`')
        Invoke-ComMethod $view 'Execute' | Out-Null
        $props = @{}
        while ($null -ne ($record = Invoke-ComMethod $view 'Fetch')) {
            $props[(Get-ComProperty $record 'StringData' @(1))] = Get-ComProperty $record 'StringData' @(2)
        }
        Invoke-ComMethod $view 'Close' | Out-Null
        return $props
    } finally {
        # Released explicitly so no handle is still open on the file when
        # msiexec is handed the same package.
        foreach ($o in @($view, $db)) {
            if ($null -ne $o) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($o) }
        }
        [GC]::Collect()
        [GC]::WaitForPendingFinalizers()
    }
}

function Get-ProductState([string] $productCode) {
    [int](Get-ComProperty $installer 'ProductState' @($productCode))
}

function Get-RelatedProducts {
    $list = Get-ComProperty $installer 'RelatedProducts' @($ExpectedUpgradeCode)
    $count = Get-ComProperty $list 'Count'
    for ($i = 0; $i -lt $count; $i++) { Get-ComProperty $list 'Item' @($i) }
}

function Invoke-Msiexec([string] $arguments, [string] $logName) {
    $log = Join-Path $LogDir $logName
    $p = Start-Process -FilePath "$env:SystemRoot\System32\msiexec.exe" `
        -ArgumentList "$arguments /L*V `"$log`"" -Wait -PassThru
    [pscustomobject]@{ ExitCode = $p.ExitCode; Log = $log }
}

function Assert-MsiSuccess($result, [string] $what) {
    switch ($result.ExitCode) {
        0 { return }
        # Success codes that still mean something went wrong for this package:
        # a per-user install of files nothing is running cannot legitimately
        # need a reboot, so either one means files were held open.
        3010 { throw "${what}: msiexec exit 3010 (reboot required), unexpected for a per-user Latch install. Log: $($result.Log)" }
        1641 { throw "${what}: msiexec exit 1641 (reboot initiated), unexpected for a per-user Latch install. Log: $($result.Log)" }
        default { throw "${what}: msiexec exit $($result.ExitCode). Log: $($result.Log)" }
    }
}

function Assert-PackageIdentity([hashtable] $props, [string] $expectedVersion, [string] $label) {
    $checks = [ordered]@{
        ProductName    = $ExpectedProductName
        ProductVersion = $expectedVersion
        Manufacturer   = $ExpectedManufacturer
        UpgradeCode    = $ExpectedUpgradeCode
    }
    foreach ($key in $checks.Keys) {
        $actual = $props[$key]
        if ($actual -ne $checks[$key]) {
            throw "$label ${key} is '$actual', expected '$($checks[$key])'"
        }
    }
    if ($props['ProductCode'] -notmatch '^\{[0-9A-F-]{36}\}$') {
        throw "$label has no valid ProductCode: '$($props['ProductCode'])'"
    }
}

function Assert-Installed([string] $productCode, [string] $expectedVersion) {
    $state = Get-ProductState $productCode
    if ($state -ne 5) { throw "Product $productCode state is $state after install, expected 5 (installed)" }
    $installedVersion = Get-ComProperty $installer 'ProductInfo' @($productCode, 'VersionString')
    if ($installedVersion -ne $expectedVersion) {
        throw "Installed version is '$installedVersion', expected '$expectedVersion'"
    }
    # 0 = per-user. perUserInstall = true is what keeps installs and
    # updates free of UAC prompts; a per-machine package would need elevation.
    $assignment = Get-ComProperty $installer 'ProductInfo' @($productCode, 'AssignmentType')
    if ($assignment -ne '0') { throw "Product is not a per-user install (AssignmentType $assignment)" }

    if (-not (Test-Path -LiteralPath $LatchExe -PathType Leaf)) { throw "Latch.exe missing: $LatchExe" }

    $cfgPath = Join-Path $InstallDir 'app\Latch.cfg'
    if (-not (Test-Path -LiteralPath $cfgPath -PathType Leaf)) { throw "Launcher config missing: $cfgPath" }
    $cfg = Get-Content -LiteralPath $cfgPath
    $jars = @($cfg | Select-String -Pattern '^app\.classpath=\$APPDIR[\\/](.+)$' |
        ForEach-Object { $_.Matches[0].Groups[1].Value })
    if ($jars.Count -eq 0) { throw "Latch.cfg names no classpath entries" }
    foreach ($jar in $jars) {
        $jarPath = Join-Path $InstallDir "app\$jar"
        if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) { throw "Classpath entry missing: $jarPath" }
    }
    if (-not ($cfg -match '^app\.mainclass=com\.vinnovateit\.latch\.desktop\.MainKt$')) {
        throw "Latch.cfg does not launch com.vinnovateit.latch.desktop.MainKt"
    }
    $cfgVersion = $cfg | Select-String -Pattern 'jpackage\.app-version=(\S+)' |
        ForEach-Object { $_.Matches[0].Groups[1].Value } | Select-Object -First 1
    if ($cfgVersion -and $cfgVersion -ne $expectedVersion) {
        throw "Latch.cfg carries app version '$cfgVersion', expected '$expectedVersion'"
    }
    $jvm = Join-Path $InstallDir 'runtime\bin\server\jvm.dll'
    if (-not (Test-Path -LiteralPath $jvm -PathType Leaf)) { throw "Bundled JVM missing: $jvm" }

    $exeVersion = (Get-Item -LiteralPath $LatchExe).VersionInfo.ProductVersion
    $location = Get-ComProperty $installer 'ProductInfo' @($productCode, 'InstallLocation')
    Write-Result "  installed $expectedVersion at $location (per-user); Latch.exe ProductVersion '$exeVersion'; $($jars.Count) classpath jars present; bundled JVM present"
}

function Assert-Removed([string] $productCode) {
    $state = Get-ProductState $productCode
    if ($state -eq 5) { throw "Product $productCode is still installed after uninstall" }
    foreach ($path in @($LatchExe, (Join-Path $InstallDir 'app'), (Join-Path $InstallDir 'runtime'))) {
        if (Test-Path -LiteralPath $path) { throw "Left behind after uninstall: $path" }
    }
    Assert-NoLatchProcess
    Write-Result "  uninstalled; Latch.exe, app\ and runtime\ removed; install directory present afterwards: $(Test-Path -LiteralPath $InstallDir)"
}

function Assert-NoLatchProcess {
    $procs = @(Get-Process -Name 'Latch' -ErrorAction SilentlyContinue)
    if ($procs.Count -gt 0) {
        $procs | Stop-Process -Force -ErrorAction SilentlyContinue
        throw "A Latch process was running; nothing here launches Latch"
    }
}

function Install-LatchPackage([string] $path, [string] $logName, [string] $what) {
    $result = Invoke-Msiexec "/i `"$path`" /qn /norestart" $logName
    Assert-MsiSuccess $result $what
    Write-Result "  ${what}: msiexec exit 0"
}

function Uninstall-Product([string] $productCode, [string] $logName, [string] $what) {
    $result = Invoke-Msiexec "/x $productCode /qn /norestart" $logName
    Assert-MsiSuccess $result $what
    Write-Result "  ${what}: msiexec exit 0"
}

# Leaves the runner as it found it, whatever state a failure left behind.
function Remove-EverythingLatch {
    Get-Process -Name 'Latch' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    foreach ($code in @(Get-RelatedProducts)) {
        Write-Host "Cleanup: removing $code"
        $r = Invoke-Msiexec "/x $code /qn /norestart" "cleanup-$($code.Trim('{}')).log"
        if ($r.ExitCode -ne 0) { Write-Host "::warning::cleanup uninstall of $code exited $($r.ExitCode)" }
    }
    foreach ($dir in @($InstallDir, $StagingDir)) {
        Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Get-BaselineMsi {
    $headers = @{ Accept = 'application/vnd.github+json'; 'X-GitHub-Api-Version' = '2022-11-28' }
    if ($env:GH_TOKEN) { $headers.Authorization = "Bearer $env:GH_TOKEN" }
    $releases = Invoke-RestMethod -Uri "https://api.github.com/repos/$BaselineRepo/releases?per_page=50" -Headers $headers
    $current = [version]$Version
    $candidates = foreach ($release in $releases) {
        if ($release.draft -or $release.prerelease) { continue }
        $v = $null
        if (-not [version]::TryParse(($release.tag_name -replace '^v', ''), [ref]$v)) { continue }
        if ($v -ge $current) { continue }
        $asset = @($release.assets | Where-Object name -EQ 'Latch-Setup.msi') +
            @($release.assets | Where-Object name -Like '*.msi') | Select-Object -First 1
        if ($null -eq $asset) { continue }
        [pscustomobject]@{ Version = $v; Tag = $release.tag_name; Asset = $asset }
    }
    $pick = $candidates | Sort-Object Version -Descending | Select-Object -First 1
    if ($null -eq $pick) { throw "No published release older than $Version carries an MSI" }

    # The same bar the updater now sets: the bytes must be the asset the API
    # describes, by size and SHA-256, before anything installs them.
    $digestProperty = $pick.Asset.PSObject.Properties['digest']
    $digest = if ($null -ne $digestProperty) { [string]$digestProperty.Value } else { '' }
    if ($digest -notmatch '^sha256:([0-9a-fA-F]{64})$') { throw "Baseline $($pick.Tag) asset has no sha256 digest" }
    $expectedSha = $Matches[1]
    $path = Join-Path ([IO.Path]::GetTempPath()) "latch-baseline-$($pick.Tag).msi"
    Invoke-WebRequest -Uri $pick.Asset.browser_download_url -OutFile $path
    $size = (Get-Item -LiteralPath $path).Length
    if ($size -ne [long]$pick.Asset.size) { throw "Baseline size $size, expected $($pick.Asset.size)" }
    $sha = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    if ($sha -ne $expectedSha.ToUpperInvariant()) { throw "Baseline sha256 $sha, expected $expectedSha" }
    Write-Result "Baseline: $($pick.Tag) $($pick.Asset.name), $size bytes, sha256 verified"
    [pscustomobject]@{ Path = $path; Version = $pick.Version.ToString() }
}

# --- main -----------------------------------------------------------------------

try {
    if (@(Get-RelatedProducts).Count -gt 0) {
        throw "A Latch product is already installed on this machine; refusing to qualify on a dirty runner"
    }

    Write-Result "MSI: $(Split-Path -Leaf $Msi), $((Get-Item -LiteralPath $Msi).Length) bytes, sha256 $((Get-FileHash -LiteralPath $Msi -Algorithm SHA256).Hash)"

    # A. The database opens and says it is Latch $Version.
    $props = Read-MsiProperties $Msi
    Assert-PackageIdentity $props $Version 'Package'
    $productCode = $props['ProductCode']
    Write-Result "Database opened: ProductName '$($props.ProductName)', ProductVersion '$($props.ProductVersion)', Manufacturer '$($props.Manufacturer)', UpgradeCode $($props.UpgradeCode), ProductCode $productCode"

    # B-D. Fresh install, verify, uninstall, verify.
    Write-Result "Fresh install:"
    Install-LatchPackage $Msi 'install.log' 'silent install'
    Assert-Installed $productCode $Version
    Assert-NoLatchProcess
    Uninstall-Product $productCode 'uninstall.log' 'silent uninstall'
    Assert-Removed $productCode

    if ($BaselineRepo) {
        $baseline = Get-BaselineMsi
        $baselineProps = Read-MsiProperties $baseline.Path
        $baselineCode = $baselineProps['ProductCode']
        $staged = "Latch-$Version.msi"

        if ($ProbeLegacyStaging) {
            # Reproduces what the pre-fix updater did: stage the new package
            # inside the install directory, then upgrade silently. Recorded,
            # not asserted -- the outcome is decided by the *installed* old
            # product's uninstall, which no change here can alter.
            Write-Result "Legacy staging probe (package inside $LegacyStagingDir):"
            Install-LatchPackage $baseline.Path 'legacy-baseline-install.log' "baseline $($baseline.Version) install"
            New-Item -ItemType Directory -Force -Path $LegacyStagingDir | Out-Null
            $legacyPackage = Join-Path $LegacyStagingDir $staged
            Copy-Item -LiteralPath $Msi -Destination $legacyPackage
            $r = Invoke-Msiexec "/i `"$legacyPackage`" /qn /norestart" 'legacy-upgrade.log'
            Write-Result "  upgrade exit code $($r.ExitCode); staged package still exists: $(Test-Path -LiteralPath $legacyPackage); Latch.exe present: $(Test-Path -LiteralPath $LatchExe); new product state $(Get-ProductState $productCode); baseline product state $(Get-ProductState $baselineCode)"
            Remove-EverythingLatch
            Assert-NoLatchProcess
        }

        # The path the updater takes now: the package staged outside the
        # install directory, installed over the previous release.
        Write-Result "Upgrade from $($baseline.Version) (package staged in $StagingDir):"
        Install-LatchPackage $baseline.Path 'baseline-install.log' "baseline $($baseline.Version) install"
        Assert-Installed $baselineCode $baseline.Version
        # Stands in for user data: Latch keeps settings, credentials and
        # history in %LOCALAPPDATA%\Latch, which is also the install directory.
        $sentinel = Join-Path $InstallDir 'qualification-sentinel.txt'
        Set-Content -LiteralPath $sentinel -Value 'user data stand-in'

        New-Item -ItemType Directory -Force -Path $StagingDir | Out-Null
        $package = Join-Path $StagingDir $staged
        Copy-Item -LiteralPath $Msi -Destination $package
        Install-LatchPackage $package 'upgrade.log' "silent upgrade to $Version"
        Assert-Installed $productCode $Version
        if ((Get-ProductState $baselineCode) -eq 5) { throw "Baseline $baselineCode is still installed next to ${Version}: the major upgrade did not replace it" }
        if (-not (Test-Path -LiteralPath $package)) { throw "The staged package was deleted during the upgrade" }
        Write-Result "  baseline product removed; staged package intact"
        if (-not (Test-Path -LiteralPath $sentinel)) {
            Write-Host "::warning::A file in %LOCALAPPDATA%\Latch did not survive the upgrade (the old product's uninstall removes its install directory recursively, and the data directory is the same folder)."
            Write-Result "  user-data stand-in in the install directory survived the upgrade: False"
        } else {
            Write-Result "  user-data stand-in in the install directory survived the upgrade: True"
        }
        Assert-NoLatchProcess
        Uninstall-Product $productCode 'upgrade-uninstall.log' 'silent uninstall after upgrade'
        Assert-Removed $productCode
    }

    Write-Result "MSI qualification passed."
} finally {
    Remove-EverythingLatch
    $left = @(Get-RelatedProducts)
    if ($left.Count -gt 0) { Write-Host "::error::Latch products still installed after cleanup: $($left -join ', ')" }
    if ($env:GITHUB_STEP_SUMMARY) {
        Add-Content -LiteralPath $env:GITHUB_STEP_SUMMARY -Value (@('### Windows MSI qualification', '```') + $Summary + @('```'))
    }
}
