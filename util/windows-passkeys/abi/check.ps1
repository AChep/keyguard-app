param(
    [ValidateSet('x64', 'arm64')]
    [string] $Architecture = 'x64',
    [switch] $Update,
    [switch] $CompileOnly
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/../../..").Path
$sdkVersion = '10.0.26100.0'
$sdk = "${env:ProgramFiles(x86)}/Windows Kits/10"
$vswhere = "${env:ProgramFiles(x86)}/Microsoft Visual Studio/Installer/vswhere.exe"
$installation = & $vswhere -latest -products '*' -requiresAny -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 Microsoft.VisualStudio.Component.VC.Tools.ARM64 -property installationPath
if (!$installation) { throw 'Visual Studio C++ tools are required' }
$toolVersion = (Get-Content "$installation/VC/Auxiliary/Build/Microsoft.VCToolsVersion.default.txt").Trim()
$toolchain = "$installation/VC/Tools/MSVC/$toolVersion"
$hostArchitecture = if ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -eq 'Arm64') { 'arm64' } else { 'x64' }
$compiler = "$toolchain/bin/Host$hostArchitecture/$Architecture/cl.exe"
if (!(Test-Path -LiteralPath $compiler)) { throw "Missing compiler: $compiler" }
if (!(Test-Path -LiteralPath "$sdk/Include/$sdkVersion/um/windows.h")) {
    throw "Windows SDK $sdkVersion is required"
}

foreach ($line in Get-Content "$PSScriptRoot/headers.sha256") {
    $hash, $name = $line -split '  ', 2
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath "$PSScriptRoot/headers/$name").Hash
    if ($actual -ne $hash) { throw "Pinned header hash mismatch: $name" }
}

# Keep the C++ signature checks connected to the production name/type pairs.
$loader = Get-Content "$root/util/windows-passkeys/rust/crates/keyguard-windows-passkeys-core/src/windows/mod.rs" -Raw
$probe = Get-Content "$PSScriptRoot/probe.cpp" -Raw
$exports = [regex]::Matches($loader, '(?:symbol|optional)!\(\s*c"(WebAuthN\w+)"\s*,\s*(\w+)\s*\)')
if ($exports.Count -ne 21) { throw 'Update the ABI verifier for the changed export table' }
foreach ($export in $exports) {
    $mapping = "SIGNATURE($($export.Groups[2].Value), $($export.Groups[1].Value),"
    if (!$probe.Contains($mapping)) { throw "Missing signature verification: $mapping" }
}

$output = "$root/target/windows-passkeys-abi/$Architecture"
New-Item -ItemType Directory -Force -Path $output | Out-Null
$previousInclude = $env:INCLUDE
$previousLib = $env:LIB
try {
    $env:INCLUDE = "$toolchain/include;$sdk/Include/$sdkVersion/ucrt;$sdk/Include/$sdkVersion/shared;$sdk/Include/$sdkVersion/um;$sdk/Include/$sdkVersion/winrt"
    $env:LIB = "$toolchain/lib/$Architecture;$sdk/Lib/$sdkVersion/ucrt/$Architecture;$sdk/Lib/$sdkVersion/um/$Architecture"
    Write-Output "ABI compiler: MSVC $toolVersion; SDK $sdkVersion; target $Architecture"
    $arguments = @('/nologo', '/std:c++17', '/EHsc', '/W4', '/WX', "/Fo$output/", "/Fe$output/probe.exe")
    if ($CompileOnly) { $arguments += '/c' }
    & $compiler @arguments "$PSScriptRoot/probe.cpp" "$PSScriptRoot/iid.cpp"
    if ($LASTEXITCODE -ne 0) { throw 'Microsoft header ABI probe did not compile' }
    if ($CompileOnly) { return }
    $generated = & "$output/probe.exe"
    if ($LASTEXITCODE -ne 0) { throw 'ABI probe failed' }
    $generatedPath = "$output/abi_layout.rs"
    $formatted = $generated | & rustfmt --edition 2024
    if ($LASTEXITCODE -ne 0) { throw 'Formatting the generated ABI checks failed' }
    [IO.File]::WriteAllText($generatedPath, ($formatted -join "`n") + "`n")
    $destination = "$root/util/windows-passkeys/rust/crates/keyguard-windows-passkeys-core/src/windows/abi_layout.rs"
    if ($Update) {
        Copy-Item -LiteralPath $generatedPath -Destination $destination
    } elseif ((Get-Content $destination -Raw).Replace("`r`n", "`n") -cne (Get-Content $generatedPath -Raw).Replace("`r`n", "`n")) {
        throw "ABI assertions differ from the pinned headers. Review $generatedPath and regenerate with -Update."
    }
    Write-Output "ABI assertions match the pinned headers ($Architecture)."
} finally {
    $env:INCLUDE = $previousInclude
    $env:LIB = $previousLib
}
