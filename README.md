# Keyguard

[![Crowdin](https://badges.crowdin.net/keyguard/localized.svg)](https://crowdin.com/project/keyguard)

Keyguard is a multi-client for the [Bitwarden® platform](https://bitwarden.com/) and [KeePass](https://keepass.info/) (KDBX), created to provide the best user experience possible.

_Can be used with any Bitwarden® installation. This product is not associated with the Bitwarden project nor Bitwarden, Inc. Bitwarden® is a registered trademark of Bitwarden Inc._

<a href="https://play.google.com/store/apps/details?id=com.artemchep.keyguard">
  <img alt="Get Keyguard on Google Play" vspace="20"
       src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png" height="60" />
</a>

<a href="https://gh.artemchep.com/keyguard-repo-fdroid/repo">
  <img src="https://github.com/AChep/keyguard-app/blob/master/artwork/badge_fdroid.png"
       alt="Get Keyguard F-Droid repository"
       vspace="20"
       height="60" />
</a>

<a href='https://flathub.org/apps/com.artemchep.keyguard'>
    <img vspace="20" hspace="10" height="42" alt='Get it on Flathub' src='https://flathub.org/api/badge?locale=en'/>
</a>

#### Highlights
- a beautiful rich and responsive **Material You** user interface;
- a **powerful** and **fast search**;
- a support for creating & using **passkeys** - a modern alternative to passwords.
- a watchtower that finds items with **Pwned passwords**, **Vulnerable accounts**, **Reused passwords**, **Inactive two factor authentication**, **Inactive passkeys**, **Unsecure Websites** as well as **Duplicate**, **Incomplete** and **Expiring** items, and other;
- **multi-account support** 🌠 with secure login and two-factor authentication support;
- add items 🌠 and sends _(Bitwarden, 🌠)_, modify 🌠, and view your vault **offline**;
- **export individual** or organization items, **including the attachments**; 
- beautiful **Light**/**Dark theme**;
- a generator with **SSH keys**, **Email forwarders**, **Custom wordlists** support, and many other features; 
- a support for [shortcuts](wiki/SHORTCUTS.md), [placeholders](wiki/PLACEHOLDERS.md) and [URL overrides](wiki/URL_OVERRIDE.md);
- a [smart conflict resolution](wiki/CONFLICTS.md);
- and much more!

🌠 _— requires a premium license: one-time or subscription._

#### Platforms
Keyguard is written using Kotlin Multiplatform + Compose Multiplatform and largely dependent on the JVM libraries. Keyguard is focusing to be **Android** first, other platforms might have some features missing.

- [Android](#android);
- [Linux](#linux);
- [Windows](#windows);
- [macOS](#macos).

#### Accounts
Keyguard is focusing to provide a full support of the Bitwarden® platform, while the KeePass support is implemented through a prism of Bitwarden's features. 
Read more about implementation details:

- [Bitwarden](wiki/BITWARDEN.md);
- [KeePass](wiki/KEEPASS.md);

#### Looks

|        |        |        |
| :----: | :----: | :----: |
| ![1](https://github.com/AChep/keyguard-app/blob/master/screenshots/phone/Screenshot_20250825_174631.png) | ![2](https://github.com/AChep/keyguard-app/blob/master/screenshots/phone/Screenshot_20250825_174459.png) | ![3](https://github.com/AChep/keyguard-app/blob/master/screenshots/phone/Screenshot_20250825_174520.png) | 
| ![4](https://github.com/AChep/keyguard-app/blob/master/screenshots/phone/Screenshot_20250825_174547.png) | ![5](https://github.com/AChep/keyguard-app/blob/master/screenshots/phone/Screenshot_20250825_174558.png) | ![6](https://github.com/AChep/keyguard-app/blob/master/screenshots/phone/Screenshot_20250825_174612.png) |

|        |
| :----: |
| ![](https://github.com/AChep/keyguard-app/blob/master/screenshots/tablet10/Screenshot_20250825_180106.png) |

## Localisation

 [Help us to translate the app](https://crowdin.com/project/keyguard). Even a short glance is helpful. 
 If you do not see the language you want to translate to, do not hesitate to [open an issue](https://github.com/AChep/AcDisplay/issues/new) with a request to add it.

 
## Install

The app is available in multiple package repositories:

### Android
You can find the `.apk` binary on the [releases page](https://github.com/AChep/keyguard-app/releases/latest).
- [Play Store](https://play.google.com/store/apps/details?id=com.artemchep.keyguard);
- [F-Droid custom repo](https://gh.artemchep.com/keyguard-repo-fdroid/repo).

### Linux
You can find the `.flatpak` binary on the [releases page](https://github.com/AChep/keyguard-app/releases/latest).
- [Flathub](https://flathub.org/apps/com.artemchep.keyguard).

### macOS
You can find the `.dmg` binaries for Apple and Intel processors on the [releases page](https://github.com/AChep/keyguard-app/releases/latest).

##### [Homebrew](https://brew.sh/) Keyguard [cask](https://formulae.brew.sh/cask/keyguard)
```sh
brew install --cask keyguard
```

### Windows
You can find the `.msi` binary on the [releases page](https://github.com/AChep/keyguard-app/releases/latest).

##### [Scoop](https://scoop.sh/) Keyguard [bucket](https://github.com/AChep/keyguard-repo-scoop)
```pwsh
scoop bucket add keyguard https://github.com/AChep/keyguard-repo-scoop
scoop install keyguard/keyguard
```

##### [Scoop](https://scoop.sh/) Extras [bucket](https://github.com/ScoopInstaller/Extras)
```pwsh
scoop bucket add extras
scoop install keyguard
```

##### [WinGet](https://aka.ms/getwinget)
```pwsh
winget install --id ArtemChepurnyi.Keyguard
```

## License

The source code is available for **personal use** only.
