# MSIX packaging

`AppxManifest.xml` and `Assets/` are packed together with the jpackage app image
by the `packageMsix` / `packageReleaseMsix` Gradle tasks (Windows only, needs the
Windows 10/11 SDK for `makeappx.exe` and `signtool.exe`).

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
