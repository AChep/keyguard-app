[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$WixDirectory,
    [Parameter(Mandatory)][string]$WorkingDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Join-Path $WorkingDirectory ([guid]::NewGuid().ToString())
$image = Join-Path $root 'app image with spaces'
$output = Join-Path $root 'output'
New-Item -ItemType Directory -Force -Path "$image/runtime/bin/server", "$image/app" | Out-Null
$bytes = [byte[]]::new(256)
$bytes[0] = 0x4d; $bytes[1] = 0x5a
[BitConverter]::GetBytes([int]64).CopyTo($bytes, 0x3c)
[BitConverter]::GetBytes([int]0x4550).CopyTo($bytes, 64)
[BitConverter]::GetBytes([uint16]0xaa64).CopyTo($bytes, 68)
[IO.File]::WriteAllBytes("$image/Keyguard.exe", $bytes)
[IO.File]::WriteAllBytes("$image/runtime/bin/server/jvm.dll", $bytes)
[IO.File]::WriteAllText("$image/app/payload & spaces.txt", 'fixture payload')

function Assert-Fails([scriptblock]$Operation, [string]$Message) {
    try { & $Operation } catch {
        if ($_.Exception.Message -notlike "*$Message*") { throw }
        return
    }
    throw "Expected failure containing: $Message"
}

& "$PSScriptRoot/package_windows_arm64_msi.ps1" -AppImage $image -Version 1.2.3 `
    -WixDirectory $WixDirectory -OutputDirectory $output
& "$PSScriptRoot/verify_windows_msi.ps1" -Directory $output -Version 1.2.3 -Architecture aarch64
$package = Join-Path $output 'Keyguard-1.2.3-aarch64.msi'
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.OpenDatabase($package, 0)
function Read-MsiValue([string]$Query) {
    $view = $database.OpenView($Query)
    $record = $null
    try {
        [void]$view.Execute()
        $record = $view.Fetch()
        if ($null -eq $record) { throw "MSI query returned no row: $Query" }
        $record.StringData(1)
    } finally {
        if ($null -ne $record) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($record) }
        [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($view)
    }
}
try {
    if ((Read-MsiValue 'SELECT `Value` FROM `Property` WHERE `Property` = ''UpgradeCode''') -ne
        '{846C6281-F349-4833-9E0E-AAE1C06006A0}') { throw 'Upgrade identity changed.' }
    if ((Read-MsiValue 'SELECT `Value` FROM `Property` WHERE `Property` = ''ALLUSERS''') -ne '1') {
        throw 'MSI is not machine-wide.'
    }
    $attributes = [int](Read-MsiValue 'SELECT `Attributes` FROM `Upgrade` WHERE `ActionProperty` = ''WIX_UPGRADE_DETECTED''')
    if (($attributes -band 512) -eq 0) { throw 'Same-version upgrades are not enabled.' }
    $remove = [int](Read-MsiValue 'SELECT `Sequence` FROM `InstallExecuteSequence` WHERE `Action` = ''RemoveExistingProducts''')
    $initialize = [int](Read-MsiValue 'SELECT `Sequence` FROM `InstallExecuteSequence` WHERE `Action` = ''InstallInitialize''')
    $installFiles = [int](Read-MsiValue 'SELECT `Sequence` FROM `InstallExecuteSequence` WHERE `Action` = ''InstallFiles''')
    if ($remove -le $initialize -or $remove -ge $installFiles) {
        throw 'Upgrade must remove the old architecture inside the transaction, before installing files.'
    }
    if ((Read-MsiValue 'SELECT `Directory_` FROM `Shortcut` WHERE `Shortcut` = ''KeyguardDesktopShortcut''') -ne 'DesktopFolder') {
        throw 'Desktop shortcut is missing.'
    }
} finally {
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($database)
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer)
}

# Dark extracts ARM64 cabinets even when this test is run on an x64 host.
$extracted = Join-Path $root 'extracted'
$decompiled = Join-Path $root 'package.wxs'
& "$WixDirectory/dark.exe" -nologo $package -x $extracted -o $decompiled
if ($LASTEXITCODE -ne 0) { throw "WiX dark exited $LASTEXITCODE." }
[xml]$xml = Get-Content -LiteralPath $decompiled -Raw
$namespaces = [Xml.XmlNamespaceManager]::new($xml.NameTable)
$namespaces.AddNamespace('w', 'http://schemas.microsoft.com/wix/2006/wi')
$files = $xml.SelectNodes('//w:File', $namespaces)
if ($files.Count -ne 3) { throw 'MSI payload has missing or unexpected files.' }
foreach ($file in $files) {
    $source = switch ($file.Name) {
        'Keyguard.exe' { "$image/Keyguard.exe" }
        'jvm.dll' { "$image/runtime/bin/server/jvm.dll" }
        'payload & spaces.txt' { "$image/app/payload & spaces.txt" }
        default { throw "Unexpected MSI payload: $($file.Name)" }
    }
    if ((Get-FileHash -LiteralPath $source).Hash -ne (Get-FileHash -LiteralPath $file.Source).Hash) {
        throw "MSI payload differs: $source"
    }
}
Assert-Fails { & "$PSScriptRoot/verify_windows_msi.ps1" -Directory $output -Version 9.9.9 -Architecture aarch64 } 'Expected exactly one MSI'
Assert-Fails {
    & "$PSScriptRoot/package_windows_arm64_msi.ps1" -AppImage $image -Version 256.0.0 `
        -WixDirectory $WixDirectory -OutputDirectory $output
} 'exceeds MSI version limits'
[BitConverter]::GetBytes([uint16]0x8664).CopyTo($bytes, 68)
[IO.File]::WriteAllBytes("$image/runtime/bin/server/jvm.dll", $bytes)
Assert-Fails {
    & "$PSScriptRoot/package_windows_arm64_msi.ps1" -AppImage $image -Version 1.2.3 `
        -WixDirectory $WixDirectory -OutputDirectory $output
} 'Expected an ARM64 PE image'
Write-Output 'ARM64 MSI packaging regression tests passed.'
