[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Directory,
    [Parameter(Mandatory)][string]$Version,
    [Parameter(Mandatory)][ValidateSet('x86_64', 'aarch64')][string]$Architecture,
    [string]$ExtractDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$packages = @(Get-ChildItem -LiteralPath $Directory -Filter '*.msi' -File)
$suffix = if ($Architecture -eq 'aarch64') { '-aarch64' } else { '' }
$expectedName = "Keyguard-$Version$suffix.msi"
if ($packages.Count -ne 1 -or $packages[0].Name -cne $expectedName) {
    throw "Expected exactly one MSI named $expectedName in $Directory."
}
$package = $packages[0].FullName
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $null
$summary = $null
$view = $null
$record = $null
try {
    $database = $installer.OpenDatabase($package, 0)
    $summary = $database.SummaryInformation(0)
    $expectedPlatform = if ($Architecture -eq 'aarch64') { 'Arm64' } else { 'x64' }
    if (($summary.Property(7) -split ';')[0] -cne $expectedPlatform) {
        throw "Expected $expectedPlatform MSI metadata; found $($summary.Property(7))."
    }
    $view = $database.OpenView('SELECT `Value` FROM `Property` WHERE `Property` = ''ProductVersion''')
    [void]$view.Execute()
    $record = $view.Fetch()
    if ($null -eq $record -or $record.StringData(1) -ne $Version) {
        throw "MSI ProductVersion does not match $Version."
    }
    if ($Architecture -eq 'aarch64' -and $summary.Property(14) -lt 500) {
        throw 'ARM64 MSI requires Windows Installer 5.0.'
    }
} finally {
    foreach ($object in @($record, $view, $summary, $database, $installer)) {
        if ($null -ne $object) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($object) }
    }
}

if ($ExtractDirectory) {
    New-Item -ItemType Directory -Force -Path $ExtractDirectory | Out-Null
    $target = (Resolve-Path -LiteralPath $ExtractDirectory).Path
    $log = "$target.log"
    $process = Start-Process -FilePath 'msiexec.exe' -WindowStyle Hidden -Wait -PassThru `
        -ArgumentList @('/a', "`"$package`"", '/qn', "TARGETDIR=`"$target`"", '/l*v', "`"$log`"")
    if ($process.ExitCode -ne 0) {
        throw "MSI administrative extraction exited $($process.ExitCode). See $log."
    }
}
Write-Output "Verified $expectedName ($Architecture)."
