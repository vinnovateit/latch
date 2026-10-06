<#
.SYNOPSIS
    Latch Universal Windows Installer Script
.DESCRIPTION
    Installs, updates, or uninstalls Latch Desktop for Windows.
    Usage:
        irm https://latch.vinnovateit.com/install.ps1 | iex
        .\install.ps1 -Uninstall
        .\install.ps1 -Reinstall
        .\install.ps1 -Launch
#>
[CmdletBinding()]
param(
    [switch]$Uninstall,
    [switch]$Reinstall,
    [switch]$Quiet,
    [switch]$Launch
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = if ($Quiet) { 'SilentlyContinue' } else { 'Continue' }

$UpgradeCode = '{6F3B9C84-1D52-4E7A-9B06-2A8F5C14D7E3}'
$DefaultInstallDir = Join-Path $env:LOCALAPPDATA 'Latch'
$DefaultExe = Join-Path $DefaultInstallDir 'Latch.exe'
$Repo = 'vinnovateit/latch'

function Get-InstalledLatch {
    $regPath = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$UpgradeCode"
    if (Test-Path $regPath) {
        $props = Get-ItemProperty $regPath
        return [PSCustomObject]@{
            IsInstalled = $true
            Version = $props.DisplayVersion
            InstallLocation = $props.InstallLocation
            ExePath = if ($props.InstallLocation) { Join-Path $props.InstallLocation 'Latch.exe' } else { $DefaultExe }
        }
    }
    if (Test-Path $DefaultExe) {
        $fvi = (Get-Item $DefaultExe).VersionInfo
        return [PSCustomObject]@{
            IsInstalled = $true
            Version = $fvi.ProductVersion
            InstallLocation = $DefaultInstallDir
            ExePath = $DefaultExe
        }
    }
    return [PSCustomObject]@{
        IsInstalled = $false
        Version = $null
        InstallLocation = $DefaultInstallDir
        ExePath = $DefaultExe
    }
}

$installed = Get-InstalledLatch

if ($Uninstall) {
    Write-Host "==== Uninstalling Latch Desktop ====" -ForegroundColor Yellow
    if ($installed.IsInstalled) {
        $logPath = Join-Path $env:TEMP 'Latch-msiexec-uninstall.log'
        $proc = Start-Process msiexec.exe -ArgumentList "/x $UpgradeCode /qn /norestart /L*V `"$logPath`"" -Wait -PassThru
        if ($proc.ExitCode -eq 0 -or $proc.ExitCode -eq 3010) {
            Write-Host "==== Latch Desktop uninstalled successfully! ====" -ForegroundColor Green
        } else {
            Write-Error "Uninstall failed with exit code $($proc.ExitCode). Log: $logPath"
        }
    } else {
        Write-Host "Latch Desktop is not currently installed." -ForegroundColor DarkGray
    }
    return
}

if ($Launch) {
    if ($installed.IsInstalled -and (Test-Path $installed.ExePath)) {
        Start-Process $installed.ExePath
        return
    } else {
        Write-Error "Latch Desktop is not installed."
    }
}

Write-Host "==== Latch Desktop Setup (Windows) ====" -ForegroundColor Cyan

# Fetch latest release info
Write-Host "--> Checking latest release from GitHub..." -ForegroundColor Gray
$apiUrl = "https://api.github.com/repos/$Repo/releases/latest"
$headers = @{ 'User-Agent' = 'LatchInstaller-PS'; 'Accept' = 'application/vnd.github+json' }

$release = $null
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 -bor [Net.SecurityProtocolType]::Tls13
    $release = Invoke-RestMethod -Uri $apiUrl -Headers $headers -TimeoutSec 15
} catch {
    # Fallback to web redirect
    $webReq = [System.Net.WebRequest]::Create("https://github.com/$Repo/releases/latest")
    $webReq.AllowAutoRedirect = $false
    $webReq.Timeout = 10000
    $webResp = $webReq.GetResponse()
    $loc = $webResp.GetResponseHeader("Location")
    $webResp.Close()
    if ($loc) {
        $tag = $loc.Substring($loc.LastIndexOf('/') + 1).TrimStart('v')
        $release = [PSCustomObject]@{
            tag_name = "v$tag"
            assets = @(
                [PSCustomObject]@{
                    name = "LatchSetup.msi"
                    browser_download_url = "https://github.com/$Repo/releases/download/v$tag/LatchSetup.msi"
                }
            )
        }
    }
}

if (-not $release) {
    Write-Error "Could not retrieve latest release metadata. Please check your internet connection."
}

$latestVersion = $release.tag_name.TrimStart('v')
$msiAsset = $release.assets | Where-Object { $_.name -like "*LatchSetup.msi*" -or ($_.name -like "Latch*.msi") } | Select-Object -First 1

if (-not $msiAsset) {
    Write-Error "No Windows installer package (.msi) found in the latest release."
}

# Compare versions
$isNewer = $false
if ($installed.IsInstalled -and $installed.Version) {
    try {
        $vCurrent = [Version]$installed.Version.TrimStart('v')
        $vLatest = [Version]$latestVersion
        if ($vLatest -gt $vCurrent) { $isNewer = $true }
    } catch {
        if ($installed.Version -ne $latestVersion) { $isNewer = $true }
    }
}

if ($installed.IsInstalled -and (-not $isNewer) -and (-not $Reinstall)) {
    Write-Host "Latch v$($installed.Version) is already installed and up to date." -ForegroundColor Green
    if (-not $Quiet) {
        Write-Host "Starting Latch..." -ForegroundColor Gray
        Start-Process $installed.ExePath
    }
    return
}

# Download MSI
$stagingDir = Join-Path $env:TEMP 'Latch-updates'
if (-not (Test-Path $stagingDir)) { New-Item -ItemType Directory -Force -Path $stagingDir | Out-Null }
$tempMsi = Join-Path $stagingDir 'LatchSetup.msi'

Write-Host "--> Downloading Latch v$latestVersion..." -ForegroundColor Yellow
Invoke-WebRequest -Uri $msiAsset.browser_download_url -OutFile $tempMsi -UseBasicParsing

# Run silent installation
Write-Host "--> Installing Latch v$latestVersion..." -ForegroundColor Yellow
$logPath = Join-Path $stagingDir 'msiexec-setup.log'
$proc = Start-Process msiexec.exe -ArgumentList "/i `"$tempMsi`" /qn /norestart /L*V `"$logPath`"" -Wait -PassThru

if ($proc.ExitCode -eq 0 -or $proc.ExitCode -eq 3010) {
    Write-Host "==== Latch Desktop v$latestVersion installed successfully! ====" -ForegroundColor Green
    if (-not $Quiet) {
        Start-Process $DefaultExe
    }
} else {
    Write-Error "Installation failed with exit code $($proc.ExitCode). Log: $logPath"
}
