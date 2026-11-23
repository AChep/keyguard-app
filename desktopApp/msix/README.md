# MSIX packaging

`AppxManifest.xml` and `Assets/` are packed together with the jpackage app image
by the `packageMsix` / `packageReleaseMsix` Gradle tasks (Windows only, needs the
Windows 10/11 SDK for `makeappx.exe` and `signtool.exe`).

The manifest also declares an initially disabled `KeyguardStartup` task. The app
enables it only on request and reads its effective state through `StartupTask`.
Its `--launch-at-login` argument keeps automatic startup separate from normal
launcher activation. Auto-launch is unavailable before Windows build 19041:
[`uap10` was introduced in Windows 10 version 2004](https://learn.microsoft.com/en-us/uwp/schemas/appxpackage/uapmanifestschema/element-f-application#uap10-was-introduced-in-windows-10-version-2004-100-build-19041),
so older systems would ignore the parameter. Disabling an existing registration
remains possible. This does not raise the application's minimum Windows version.
Verify automatic startup on build 19041 as part of the installed-package checks.

Two packages are produced from the same template:

| Flavor | Output | Signed by |
| --- | --- | --- |
| `store` | `Keyguard-<version>-<arch>-store.msix` | Microsoft Store on ingestion |
| `sideload` | `Keyguard-<version>-<arch>.msix` | us, only when a certificate is configured |

`<arch>` is `x64` or `arm64`, selected from the native Windows build host.
The release workflow builds both packages on native GitHub-hosted runners. It
publishes the signed sideload packages separately and wraps both Store packages
in one `.msixupload` file for Partner Center.

## Gradle properties

| Property | Used by | Default |
| --- | --- | --- |
| `msix_version` | both | `<appVersionName>.0`; CI passes `MAJOR.MINOR.(PATCH*100+n).0`, see `.github/get_msix_version.py` |
| `msix_publisher_display_name` | both | `Artem Chepurnyi` |
| `msix_store_identity_name` | store | none, the store flavor is skipped when unset |
| `msix_store_publisher` | store | none, the store flavor is skipped when unset |
| `msix_sideload_identity_name` | sideload | `ArtemChepurnyi.Keyguard` |
| `msix_sideload_publisher` | sideload | `CN=Artem Chepurnyi`; must equal the signing certificate subject |
| `msix_sideload_pfx_path` | signing | none, the package is left unsigned when unset |
| `msix_sideload_pfx_password` | signing | none |
| `msix_sdk_bin_dir` | tools | highest matching `Windows Kits\10\bin\10.*\<arch>`, with an x64 fallback |

The store identity values come from Partner Center, "Product identity" page.

## Local build with a self-signed certificate

```powershell
$cert = New-SelfSignedCertificate -Type Custom -Subject "CN=Keyguard Dev" -KeyUsage DigitalSignature `
  -FriendlyName "Keyguard Dev" -CertStoreLocation "Cert:\CurrentUser\My" `
  -TextExtension @("2.5.29.37={text}1.3.6.1.5.5.7.3.3", "2.5.29.19={text}")
$password = ConvertTo-SecureString -String "dev" -Force -AsPlainText
Export-PfxCertificate -Cert $cert -FilePath keyguard-dev.pfx -Password $password
# Elevated: trust the certificate, otherwise Add-AppxPackage rejects the package.
Export-Certificate -Cert $cert -FilePath keyguard-dev.cer
Import-Certificate -FilePath keyguard-dev.cer -CertStoreLocation Cert:\LocalMachine\TrustedPeople

./gradlew :desktopApp:packageMsix `
  "-Pmsix_sideload_publisher=CN=Keyguard Dev" `
  "-Pmsix_sideload_pfx_path=$PWD\keyguard-dev.pfx" `
  "-Pmsix_sideload_pfx_password=dev"
Add-AppxPackage desktopApp\build\compose\binaries\main\msix\Keyguard-*.msix
```

Remove with `Get-AppxPackage ArtemChepurnyi.Keyguard | Remove-AppxPackage`.

## Passkey provider

The package registers `Keyguard.exe --windows-passkey-provider` as a COM server.
The CLSID is derived from the package identity and publisher, so it stays stable
across upgrades and x64/ARM64 builds. Store and sideload packages have separate
provider identities. `keyguard-passkeys.json` must contain the same CLSID as the
manifest; the final native-bundle check verifies this.

Provider registration requires a Windows 11 build with the third-party passkey
APIs. Enable it in Keyguard's Autofill settings, accept the public metadata cache,
then enable Keyguard in Windows passkey settings. The MSI and unpackaged builds
cannot register a provider.

To check PRF, use an isolated test vault and a test relying party that requests
`prf.eval.first` at registration and sign-in. Select Keyguard for both operations.
Registration must return `prf.enabled = true` and a 32-byte `prf.results.first`.
Sign in with that credential and the same input: its 32-byte result must match
registration. Missing results leave browser/provider PRF interoperability
unverified, even when the native encoder tests pass.

## Browser connector

Browsers may start a program inside a package only through an execution alias.
The package declares `keyguard-browser-host.exe` for `app\resources\keyguard-browser-host.exe`,
and `keyguard browsers` points the native messaging manifests at
`%LOCALAPPDATA%\Microsoft\WindowsApps\keyguard-browser-host.exe`. The alias keeps
its path across updates.

Store and sideload packages declare the same alias, and only one installed package
can own it (Settings > Apps > Advanced app settings > App execution aliases).
Browsers start that package's connector. The other package's `keyguard browsers`
reports its connectors as needing repair, and `install` fails with `host_missing`,
until the alias is switched to it there.

Removing a package runs no Keyguard code: its manifests and registry keys stay and
point at the removed alias. Another build's install, or its repair at startup,
rewrites them.

To check a build, install it, turn on the browser extension in Keyguard, and pair
Chrome, Edge and Firefox. Then install a newer version: the connectors must keep
working without a repair.

## Assets

Generated from `../flatpak/icon.svg` (`brew install librsvg imagemagick`), run from `Assets/`:

```sh
SVG=../../flatpak/icon.svg
rsvg-convert -w 44 -h 44 $SVG -o Square44x44Logo.png
rsvg-convert -w 88 -h 88 $SVG -o Square44x44Logo.scale-200.png
for s in 16 24 32 48 256; do rsvg-convert -w $s -h $s $SVG -o Square44x44Logo.targetsize-${s}_altform-unplated.png; done
rsvg-convert -w 112 -h 112 $SVG | magick png:- -background none -gravity center -extent 150x150 Square150x150Logo.png
rsvg-convert -w 224 -h 224 $SVG | magick png:- -background none -gravity center -extent 300x300 Square150x150Logo.scale-200.png
rsvg-convert -w 112 -h 112 $SVG | magick png:- -background none -gravity center -extent 310x150 Wide310x150Logo.png
rsvg-convert -w 224 -h 224 $SVG | magick png:- -background none -gravity center -extent 620x300 Wide310x150Logo.scale-200.png
rsvg-convert -w 50 -h 50 $SVG -o StoreLogo.png
rsvg-convert -w 100 -h 100 $SVG -o StoreLogo.scale-200.png
```
