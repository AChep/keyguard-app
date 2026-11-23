# Run only on a disposable Windows ARM64 CI runner: this installs/uninstalls Keyguard.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$X64Msi,
    [Parameter(Mandatory)][string]$Arm64Msi,
    [Parameter(Mandatory)][string]$PreviousArm64Msi,
    [Parameter(Mandatory)][string]$RegistrationFixture,
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
$registrationHelper = (Resolve-Path -LiteralPath $RegistrationFixture).Path
$startupFixture = $null
$previousAttestation = $env:KEYGUARD_STARTUP_DISPOSABLE_ACCOUNT
$startupOperation = 0

function Initialize-StartupFixture {
    $nonce = [guid]::NewGuid().ToString('N')
    $identity = "com.artemchep.keyguard.startuptest.$nonce"
    $account = (& whoami).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $account) { throw 'Could not identify the disposable account.' }
    $root = Join-Path $logs "startup-$nonce"
    New-Item -ItemType Directory -Path $root | Out-Null
    $utf8 = [Text.UTF8Encoding]::new($false)
    $config = Join-Path $root 'config.json'
    $configuration = @{
        run_id = $nonce; account = $account; backend = 'windows'; id = $identity
        launcher = (Join-Path $installDirectory 'Keyguard.exe')
        arguments = @('--launch-at-login')
    } | ConvertTo-Json -Depth 4
    [IO.File]::WriteAllText($config, $configuration, $utf8)
    [IO.File]::WriteAllText((Join-Path $root 'DISPOSABLE_ACCOUNT'),
        "disposable startup-test account`n$account`n$nonce`n", $utf8)
    $startupDirectory = [Environment]::GetFolderPath('Startup')
    New-Item -ItemType Directory -Force -Path $startupDirectory | Out-Null
    $script:startupFixture = @{
        Config = $config; Root = $root
        Shortcut = (Join-Path $startupDirectory "$identity-autostart.lnk")
        Sentinel = (Join-Path $startupDirectory "unrelated-startup-fixture-$nonce.lnk")
        Hash = $null
    }
    if ((Test-Path -LiteralPath $startupFixture.Shortcut) -or
        (Test-Path -LiteralPath $startupFixture.Sentinel)) { throw 'Fixture startup path already exists.' }
    $shell = New-Object -ComObject WScript.Shell
    try {
        $link = $shell.CreateShortcut($startupFixture.Sentinel)
        try {
            $link.TargetPath = Join-Path $env:SystemRoot 'System32/notepad.exe'
            $link.Description = 'Unrelated startup sentinel; must survive fixture operations'
            $link.Save()
        } finally {
            [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($link)
        }
    } finally {
        [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($shell)
    }
    $startupFixture.Hash = (Get-FileHash -LiteralPath $startupFixture.Sentinel).Hash
    $env:KEYGUARD_STARTUP_DISPOSABLE_ACCOUNT = $account
}

function Invoke-StartupFixture([string]$Operation) {
    $output = & $registrationHelper --config $startupFixture.Config --operation $Operation
    $code = $LASTEXITCODE
    $script:startupOperation++
    $output | Set-Content -LiteralPath (Join-Path $startupFixture.Root "$startupOperation-$Operation.json")
    if ($code -ne 0) { throw "Startup fixture $Operation failed ($code): $output" }
    return ($output | ConvertFrom-Json)
}

function Assert-StartupState([int]$Expected) {
    $result = Invoke-StartupFixture 'query'
    if ($result.after -ne $Expected) { throw "Startup state was $($result.after), expected $Expected." }
    if (-not (Test-Path -LiteralPath $startupFixture.Sentinel) -or
        (Get-FileHash -LiteralPath $startupFixture.Sentinel).Hash -ne $startupFixture.Hash) {
        throw 'Startup operation or installer changed the unrelated shortcut.'
    }
}

function Enable-StartupFixture {
    Assert-StartupState 1 # DISABLED
    $result = Invoke-StartupFixture 'enable'
    if ($result.after -ne 0) { throw 'Startup shortcut did not report ENABLED after enable.' }
    Assert-StartupState 0 # ENABLED: valid path/arguments, and no Task Manager disable in StartupApproved.
}

function Assert-StaleStartupAndClean {
    Assert-StartupState 5 # Uninstall leaves the user-owned shortcut, now STALE.
    $result = Invoke-StartupFixture 'disable'
    if (-not $result.cleanup_complete -or (Test-Path -LiteralPath $startupFixture.Shortcut)) {
        throw 'Explicit startup cleanup did not remove the fixture-owned shortcut.'
    }
    Assert-StartupState 1
}

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
    Initialize-StartupFixture
    Invoke-Msi '/i' (Resolve-Path -LiteralPath $X64Msi).Path 'install-x64'
    Enable-StartupFixture
    Invoke-Msi '/i' (Resolve-Path -LiteralPath $Arm64Msi).Path 'replace-x64-same-version'
    Assert-InstalledArm64
    Assert-StartupState 0
    Invoke-Msi '/x' $arm64Product 'uninstall-after-x64'
    Assert-Uninstalled
    Assert-StaleStartupAndClean

    Invoke-Msi '/i' (Resolve-Path -LiteralPath $PreviousArm64Msi).Path 'install-previous-arm64'
    Enable-StartupFixture
    Invoke-Msi '/i' (Resolve-Path -LiteralPath $Arm64Msi).Path 'upgrade-arm64'
    Assert-InstalledArm64
    Assert-StartupState 0
    Invoke-Msi '/x' $arm64Product 'uninstall-after-arm64'
    Assert-Uninstalled
    Assert-StaleStartupAndClean
    Write-Output 'ARM64 MSI installation, upgrades, startup staleness/cleanup, shortcuts, uninstall, and user-data preservation passed.'
} finally {
    try {
        if ($startupFixture -and (Test-Path -LiteralPath (Join-Path $startupFixture.Root 'registration.owned'))) {
            $result = Invoke-StartupFixture 'disable'
            if (-not $result.cleanup_complete) { throw 'Startup cleanup requires retry; preserve the run evidence.' }
        }
    } finally {
        try {
            foreach ($product in @(Get-InstalledKeyguard)) {
                Invoke-Msi '/x' $product 'cleanup'
            }
        } finally {
            if ($startupFixture -and (Test-Path -LiteralPath $startupFixture.Sentinel)) {
                Remove-Item -LiteralPath $startupFixture.Sentinel
            }
            $env:KEYGUARD_STARTUP_DISPOSABLE_ACCOUNT = $previousAttestation
            [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer)
        }
    }
}
