# Keyguard

[![Crowdin](https://badges.crowdin.net/keyguard/localized.svg)](https://crowdin.com/project/keyguard)

Keyguard is a third-party client for the [Bitwarden® platform](https://bitwarden.com/) and [KeePass](https://keepass.info/) (KDBX) files. It autofills your logins, supports passkeys, works offline, and runs a Watchtower that finds leaked and reused passwords and other issues. Read more in the [documentation](https://keyguard.dev/docs/).

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
- a **[powerful](https://keyguard.dev/docs/search/)** and **fast search**;
- a support for creating & using **[passkeys](https://keyguard.dev/docs/passkeys/)** - a modern alternative to passwords.
- a [watchtower](https://keyguard.dev/docs/watchtower/) that finds items with **Pwned passwords**, **Vulnerable accounts**, **Reused passwords**, **Inactive two factor authentication**, **Inactive passkeys**, **Unsecure Websites** as well as **Duplicate**, **Incomplete** and **Expiring** items, and other;
- **[multi-account support](https://keyguard.dev/docs/multi-account/)** 🌠 with secure login and two-factor authentication support;
- add items 🌠 and sends _(Bitwarden, 🌠)_, modify 🌠, and view your vault **[offline](https://keyguard.dev/features/offline-access/)**;
- automatically manage [vault backup repository](https://keyguard.dev/docs/backups/);
- upload [attachments](https://keyguard.dev/features/attachments/) 🌠 to the vault;
- view attachments in-app without saving them;
- [unlock vault](https://keyguard.dev/docs/lock-and-unlock/) with a password, biometrics or a YubiKey;
- **[export](https://keyguard.dev/features/data-export/) individual** or organization items, **including the attachments**; 
- beautiful **Light**/**Dark** [theme](https://keyguard.dev/docs/appearance/);
- a [generator](https://keyguard.dev/docs/generator/) with **SSH & GPG keys**, **[Email forwarders](https://keyguard.dev/docs/email-relays/)**, **[Custom wordlists](https://keyguard.dev/docs/generator/#custom-wordlists)** support, and many other features; 
- a support for [shortcuts](https://keyguard.dev/docs/shortcuts/), [placeholders](https://keyguard.dev/docs/placeholders/) and [URL overrides](https://keyguard.dev/docs/url-overrides/);
- a support for an [SSH agent](https://keyguard.dev/docs/ssh-agent/) - interacting with SSH-based services on Android and Desktop platform;
- a support for a [GPG agent](https://keyguard.dev/docs/gpg-agent/) - interacting with GPG-based services on Android ([OpenKeychain-compatible](https://github.com/open-keychain/open-keychain) provider) and Desktop platform; GPG tools are available on all platforms;
- a support for the [credential exchange](https://keyguard.dev/docs/credential-exchange/) protocol on Android;
- a [smart conflict resolution](https://keyguard.dev/docs/sync-and-conflicts/);
- and much more!

🌠 _— requires a premium license: one-time or subscription._

#### Platforms
Keyguard is written using Kotlin Multiplatform + Compose Multiplatform and largely dependent on the JVM libraries. Keyguard is focusing to be **Android** first, other platforms might have some features missing.

- [Android](#android), including Wear OS;
- [Linux](#linux);
- [Windows](#windows);
- [macOS](#macos).

> [!NOTE]
> There are currently no Keyguard browser extension nor an iOS app, please beware of downloading similar named apps as I'm not affiliated with those in any way.

#### Accounts
Keyguard is focusing to provide a full support of the Bitwarden® platform, while the KeePass support is implemented through a prism of Bitwarden's features. 
Read more about implementation details:

- [Bitwarden](https://keyguard.dev/docs/bitwarden/self-hosted/), including the [custom-field conventions](https://keyguard.dev/docs/item-extras/) Keyguard adds on top;
- [KeePass](https://keyguard.dev/docs/keepass/);

#### Looks

|        |        |        |
| :----: | :----: | :----: |
| ![1](https://github.com/AChep/keyguard-app/blob/master/screenshots/phone/Screenshot_20250825_174631.png) | ![2](https://github.com/AChep/keyguard-app/blob/master/screenshots/phone/Screenshot_20250825_174459.png) | ![3](https://github.com/AChep/keyguard-app/blob/master/screenshots/phone/Screenshot_20250825_174520.png) | 
| ![4](https://github.com/AChep/keyguard-app/blob/master/screenshots/phone/Screenshot_20250825_174547.png) | ![5](https://github.com/AChep/keyguard-app/blob/master/screenshots/phone/Screenshot_20250825_174558.png) | ![6](https://github.com/AChep/keyguard-app/blob/master/screenshots/phone/Screenshot_20250825_174612.png) |

|        |
| :----: |
| ![](https://github.com/AChep/keyguard-app/blob/master/screenshots/tablet10/Screenshot_20250825_180106.png) |

|                                                                                         |                                                                                         |                                                                                             |
|:---------------------------------------------------------------------------------------:|:---------------------------------------------------------------------------------------:|:-------------------------------------------------------------------------------------------:|
| ![1](https://github.com/AChep/keyguard-app/blob/master/screenshots/wear/vault_list.png) | ![2](https://github.com/AChep/keyguard-app/blob/master/screenshots/wear/vault_info.png) | ![3](https://github.com/AChep/keyguard-app/blob/master/screenshots/wear/vault_info_2fa.png) | 

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

##### [AUR](https://aur.archlinux.org/) Keyguard [package](https://aur.archlinux.org/packages/keyguard-bin)
```sh
yay -S keyguard-bin
```

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
