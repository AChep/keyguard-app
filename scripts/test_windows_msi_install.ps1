# Run only on a disposable Windows ARM64 CI runner: this installs/uninstalls Keyguard.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$X64Msi,
    [Parameter(Mandatory)][string]$Arm64Msi,
    [Parameter(Mandatory)][string]$PreviousArm64Msi,
    [Parameter(Mandatory)][string]$LogDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_ARCH -ne 'ARM64') {
    throw 'Installation tests require a disposable GitHub Actions ARM64 runner.'
}
New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null
$logs = (Resolve-Path -LiteralPath $LogDirectory).Path
$installer = New-Object -ComObject WindowsInstaller.Installer
$upgradeCode = '{846C6281-F349-4833-9E0E-AAE1C06006A0}'
$installDirectory = Join-Path $env:ProgramFiles 'Keyguard'
$shortcut = Join-Path ([Environment]::GetFolderPath('CommonDesktopDirectory')) 'Keyguard.lnk'

function Get-InstalledKeyguard {
    $products = $installer.RelatedProducts($upgradeCode)
    try {
        # Enumerate the COM StringList through the pipeline. Its Count property
        # is not exposed by PowerShell 7's COM adapter (scalar Count returns 1).
        $products | ForEach-Object { [string]$_ }
    } finally {
        [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($products)
    }
}

function Invoke-Msi([string]$Operation, [string]$Package, [string]$Label) {
    $log = Join-Path $logs "$Label.log"
    $process = Start-Process -FilePath 'msiexec.exe' -WindowStyle Hidden -Wait -PassThru `
        -ArgumentList @($Operation, "`"$Package`"", '/qn', '/norestart', '/l*v', "`"$log`"")
    if ($process.ExitCode -notin @(0, 3010)) {
        throw "MSI $Label exited $($process.ExitCode). See $log."
    }
}

function Assert-InstalledArm64 {
    $products = @(Get-InstalledKeyguard)
    if ($products.Count -ne 1 -or $products[0] -ne $script:arm64Product) {
        throw "Expected only the release ARM64 product to be installed; found $products."
    }
    if (-not (Test-Path -LiteralPath $shortcut -PathType Leaf)) {
        throw "Desktop shortcut is missing: $shortcut"
    }
    $target = $installer.ShortcutTarget($shortcut)
    try {
        if ($target.StringData(1) -ne $script:arm64Product -or
            $installer.ComponentPath($target.StringData(1), $target.StringData(3)) -ne
                (Join-Path $installDirectory 'Keyguard.exe')) {
            throw 'Desktop shortcut does not resolve to the installed ARM64 launcher.'
        }
    } finally {
        [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($target)
    }
    python (Join-Path $PSScriptRoot 'verify_native_bundle.py') desktop $installDirectory --platform windows --arch aarch64
    if ($LASTEXITCODE -ne 0) { throw 'Installed ARM64 bundle verification failed.' }
}

function Assert-Uninstalled {
    if (@(Get-InstalledKeyguard).Count -ne 0 -or
        (Test-Path -LiteralPath (Join-Path $installDirectory 'Keyguard.exe')) -or
        (Test-Path -LiteralPath $shortcut)) {
        throw 'Uninstall left an installed product, launcher, or desktop shortcut.'
    }
    foreach ($sentinel in $script:sentinels) {
        if (-not (Test-Path -LiteralPath $sentinel) -or
            (Get-Content -LiteralPath $sentinel -Raw) -ne $script:sentinelValue) {
            throw "Uninstall changed user data: $sentinel"
        }
    }
}

if (@(Get-InstalledKeyguard).Count -ne 0 -or (Test-Path -LiteralPath $installDirectory)) {
    throw 'Keyguard already exists on this runner; refusing to run destructive installer tests.'
}
$database = $installer.OpenDatabase((Resolve-Path -LiteralPath $Arm64Msi).Path, 0)
$view = $database.OpenView('SELECT `Value` FROM `Property` WHERE `Property` = ''ProductCode''')
[void]$view.Execute()
$record = $view.Fetch()
$arm64Product = $record.StringData(1)
foreach ($object in @($record, $view, $database)) {
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($object)
}
# AppDirs stores the vault in LocalAppData and configuration in roaming AppData.
$sentinelValue = [guid]::NewGuid().ToString()
$sentinels = @($env:LOCALAPPDATA, $env:APPDATA) | ForEach-Object {
    $directory = Join-Path $_ 'ArtemChepurnyi/keyguard'
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $path = Join-Path $directory 'msi-test-vault-sentinel'
    Set-Content -LiteralPath $path -Value $sentinelValue -NoNewline
    $path
}
try {
    Invoke-Msi '/i' (Resolve-Path -LiteralPath $X64Msi).Path 'install-x64'
    Invoke-Msi '/i' (Resolve-Path -LiteralPath $Arm64Msi).Path 'replace-x64-same-version'
    Assert-InstalledArm64
    Invoke-Msi '/x' $arm64Product 'uninstall-after-x64'
    Assert-Uninstalled

    Invoke-Msi '/i' (Resolve-Path -LiteralPath $PreviousArm64Msi).Path 'install-previous-arm64'
    Invoke-Msi '/i' (Resolve-Path -LiteralPath $Arm64Msi).Path 'upgrade-arm64'
    Assert-InstalledArm64
    Invoke-Msi '/x' $arm64Product 'uninstall-after-arm64'
    Assert-Uninstalled
    Write-Output 'ARM64 MSI installation, upgrades, shortcut, uninstall, and user-data preservation passed.'
} finally {
    foreach ($product in @(Get-InstalledKeyguard)) {
        Invoke-Msi '/x' $product 'cleanup'
    }
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer)
}
