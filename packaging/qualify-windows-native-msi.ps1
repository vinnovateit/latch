param(
    [Parameter(Mandatory = $true)][string] $Msi,
    [Parameter(Mandatory = $true)][string] $Version
)

$ErrorActionPreference = 'Stop'

# Identity must match desktop/packaging/windows/LatchNative.wxs.
$expected = [ordered]@{
    ProductName    = 'Latch'
    Manufacturer   = 'VinnovateIT'
    ProductVersion = $Version
    UpgradeCode    = '{6F3B9C84-1D52-4E7A-9B06-2A8F5C14D7E3}'
}

if (-not (Test-Path -LiteralPath $Msi -PathType Leaf)) { throw "MSI missing: $Msi" }
$msiPath = (Resolve-Path -LiteralPath $Msi).Path
$installer = New-Object -ComObject WindowsInstaller.Installer
$db = $installer.OpenDatabase($msiPath, 0)
$view = $db.OpenView('SELECT Property, Value FROM Property')
$view.Execute()
$properties = @{}
$row = $view.Fetch()
while ($row) {
    $properties[$row.StringData(1)] = $row.StringData(2)
    $row = $view.Fetch()
}
foreach ($key in $expected.Keys) {
    if ($properties[$key] -ne $expected[$key]) {
        throw "$key is '$($properties[$key])', expected '$($expected[$key])'"
    }
}
if ($properties['ALLUSERS']) { throw 'Native MSI must be per-user (ALLUSERS must be absent)' }
Write-Host "Identity OK: $($expected.ProductName) $Version, per-user"

function Invoke-Msi([string[]] $Arguments, [string] $LogName) {
    $log = Join-Path (Get-Location) $LogName
    $args = $Arguments + @("/L*v `"$log`"")
    $process = Start-Process -FilePath "$env:SystemRoot\System32\msiexec.exe" `
        -ArgumentList $args -Wait -PassThru
    if ($process.ExitCode -ne 0) {
        throw "msiexec failed ($($process.ExitCode)); log: $log"
    }
}

$productCode = $properties['ProductCode']
$installDir = Join-Path $env:LOCALAPPDATA 'Latch'
Invoke-Msi @("/i `"$msiPath`"", '/qn', '/norestart') 'native-install.log'

$assignment = $installer.ProductInfo($productCode, 'AssignmentType')
if ($assignment -ne 0) { throw "Not a per-user install (AssignmentType $assignment)" }
foreach ($name in 'Latch.exe', 'data.kres', 'icudtl.dat', 'skiko-windows-x64.dll') {
    if (-not (Test-Path -LiteralPath (Join-Path $installDir $name) -PathType Leaf)) {
        throw "Installed file missing: $name"
    }
}
Write-Host "Install OK: $installDir contains the native runtime files"

Invoke-Msi @("/x $productCode", '/qn', '/norestart') 'native-uninstall.log'
if (Test-Path -LiteralPath (Join-Path $installDir 'Latch.exe')) {
    throw 'Latch.exe survived uninstall'
}
Write-Host 'Uninstall OK'
