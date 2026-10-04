[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$AppImage,
    [Parameter(Mandatory)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version,
    [Parameter(Mandatory)][string]$WixDirectory,
    [Parameter(Mandatory)][string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$image = (Resolve-Path -LiteralPath $AppImage).Path
$wix = (Resolve-Path -LiteralPath $WixDirectory).Path
$template = Join-Path $PSScriptRoot '../desktopApp/msi/arm64.wxs'
$icon = Join-Path $PSScriptRoot '../desktopApp/icon.ico'
foreach ($required in @($template, $icon, "$image/Keyguard.exe", "$image/runtime/bin/server/jvm.dll",
        "$wix/heat.exe", "$wix/candle.exe", "$wix/light.exe")) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required packaging input is missing: $required"
    }
}
$parsedVersion = [version]$Version
if ($parsedVersion.Major -gt 255 -or $parsedVersion.Minor -gt 255 -or $parsedVersion.Build -gt 65535) {
    throw "Version $Version exceeds MSI version limits."
}
# jpackage 21 labels MSI packages x64 even on ARM64. Build only the installer
# with WiX; retain the complete Compose/JBR app image and its native launcher.
foreach ($binary in @("$image/Keyguard.exe", "$image/runtime/bin/server/jvm.dll")) {
    $bytes = [IO.File]::ReadAllBytes($binary)
    if ($bytes.Length -lt 64 -or $bytes[0] -ne 0x4d -or $bytes[1] -ne 0x5a) {
        throw "Expected an ARM64 PE image: $binary"
    }
    $offset = [BitConverter]::ToInt32($bytes, 0x3c)
    if ($offset -lt 0 -or $offset -gt $bytes.Length - 6 -or
        [BitConverter]::ToUInt32($bytes, $offset) -ne 0x4550 -or
        [BitConverter]::ToUInt16($bytes, $offset + 4) -ne 0xaa64) {
        throw "Expected an ARM64 PE image: $binary"
    }
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$output = (Resolve-Path -LiteralPath $OutputDirectory).Path
$package = Join-Path $output "Keyguard-$Version-aarch64.msi"
$unexpected = @(Get-ChildItem -LiteralPath $output -Filter '*.msi' -File |
    Where-Object { $_.FullName -ne $package })
if ($unexpected.Count -ne 0) {
    throw "Output directory contains other MSI packages: $output"
}
$intermediate = Join-Path $output '.wix-arm64'
New-Item -ItemType Directory -Force -Path $intermediate | Out-Null
$fragment = Join-Path $intermediate 'app-image.wxs'

# Harvest files without registering bundled DLLs or touching the user's vault.
# WiX generates stable file component GUIDs from their destination paths.
& "$wix/heat.exe" dir $image -nologo -ag -srd -sreg -scom `
    -dr INSTALLDIR -cg AppImage -var var.AppImage -out $fragment
if ($LASTEXITCODE -ne 0) { throw "WiX heat exited $LASTEXITCODE." }
# Attach an advertised desktop shortcut to the launcher's file component, so
# Windows Installer owns its lifetime and resolves it after upgrades/repairs.
[xml]$harvested = Get-Content -LiteralPath $fragment -Raw
$namespace = 'http://schemas.microsoft.com/wix/2006/wi'
$namespaces = [Xml.XmlNamespaceManager]::new($harvested.NameTable)
$namespaces.AddNamespace('w', $namespace)
$launcher = $harvested.SelectSingleNode('//w:File[@Source="$(var.AppImage)\Keyguard.exe"]', $namespaces)
if ($null -eq $launcher) { throw 'WiX did not harvest the Keyguard launcher.' }
$shortcut = $harvested.CreateElement('Shortcut', $namespace)
foreach ($attribute in @{
    Id = 'KeyguardDesktopShortcut'; Name = 'Keyguard'; Directory = 'DesktopFolder'
    WorkingDirectory = 'INSTALLDIR'; Icon = 'Keyguard.exe'; Advertise = 'yes'
}.GetEnumerator()) {
    $shortcut.SetAttribute($attribute.Key, $attribute.Value)
}
[void]$launcher.AppendChild($shortcut)
$harvested.Save($fragment)
& "$wix/candle.exe" -nologo -arch arm64 "-dAppImage=$image" "-dVersion=$Version" `
    "-dIcon=$icon" -out "$intermediate\" $template $fragment
if ($LASTEXITCODE -ne 0) { throw "WiX candle exited $LASTEXITCODE." }
& "$wix/light.exe" -nologo -spdb -out $package `
    "$intermediate/arm64.wixobj" "$intermediate/app-image.wixobj"
if ($LASTEXITCODE -ne 0) { throw "WiX light exited $LASTEXITCODE." }
if (-not (Test-Path -LiteralPath $package -PathType Leaf)) {
    throw "WiX did not produce $package."
}
Write-Output $package
