param(
    [ValidateSet('x86_64-pc-windows-msvc', 'aarch64-pc-windows-msvc')]
    [string] $Target = 'x86_64-pc-windows-msvc'
)

$ErrorActionPreference = 'Stop'
Write-Output "Windows integration target: $Target"
Write-Output "OS: $([Environment]::OSVersion.VersionString)"
$dll = Get-Item -LiteralPath "$env:SystemRoot/System32/webauthn.dll"
Write-Output "System webauthn.dll: $($dll.VersionInfo.FileVersion)"
$arguments = @('test', '-p', 'keyguard-windows-passkeys-core', '--lib', '--locked', '--offline', '--target', $Target, 'windows::integration_tests::')
$listing = & cargo @arguments -- --ignored --list
if ($LASTEXITCODE -ne 0) { throw 'Could not list Windows integration tests' }
$tests = @($listing | Where-Object { $_ -match '^windows::integration_tests::\w+: test$' })
if ($tests.Count -lt 6 -or $tests -notcontains 'windows::integration_tests::required_exports_are_available: test') {
    throw "The mandatory Windows integration suite was not selected: $listing"
}
& cargo @arguments -- --ignored --nocapture
if ($LASTEXITCODE -ne 0) { throw 'Windows plugin integration failed; required APIs must be available' }
