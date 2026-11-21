//! Where each browser looks for the manifest of a native messaging host, and
//! how this build of Keyguard is packaged.
//!
//! Sources: the browsers' native messaging docs, Chromium's
//! `launch_context_win.cc`, Brave's `brave_main_delegate.cc`, KeePassXC's
//! `NativeMessageInstaller.cpp`, and the browsers' Flathub manifests.
use super::BrowsersError;
use anyhow::{Context as _, Result, anyhow, ensure};
use clap::ValueEnum as _;
use keyguard_cli_protocol::browser::{FlatpakBrowser, HOST_EXECUTABLE, flatpak};
use serde::Serialize;
use std::{
    ffi::OsString,
    path::{Path, PathBuf},
};

/// The name of the host, which the extension passes to `connectNative`.
pub(crate) const HOST_NAME: &str = "com.artemchep.keyguard";

const MANIFEST_FILE: &str = "com.artemchep.keyguard.json";

/// The Flatpak build's launcher for the connector, next to each manifest.
const WRAPPER_FILE: &str = "com.artemchep.keyguard-host.sh";

/// Chromium writes this file into its profile root on the first run.
const LOCAL_STATE: &str = "Local State";

/// The lock that `install` and `uninstall` hold.
const LOCK_FILE: &str = "browsers.lock";

/// [`clap::ValueEnum::value_variants`] lists every browser, in this order.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, clap::ValueEnum, Serialize)]
#[serde(rename_all = "kebab-case")]
pub(crate) enum Browser {
    Chrome,
    Chromium,
    Edge,
    Brave,
    Vivaldi,
    Firefox,
    // The Flathub builds, on Linux only. A user may have both builds of a
    // browser, and each reads its own manifest.
    ChromeFlatpak,
    ChromiumFlatpak,
    EdgeFlatpak,
    BraveFlatpak,
    FirefoxFlatpak,
}

impl Browser {
    pub(crate) fn id(self) -> &'static str {
        match self {
            Self::Chrome => "chrome",
            Self::Chromium => "chromium",
            Self::Edge => "edge",
            Self::Brave => "brave",
            Self::Vivaldi => "vivaldi",
            Self::Firefox => "firefox",
            Self::ChromeFlatpak => "chrome-flatpak",
            Self::ChromiumFlatpak => "chromium-flatpak",
            Self::EdgeFlatpak => "edge-flatpak",
            Self::BraveFlatpak => "brave-flatpak",
            Self::FirefoxFlatpak => "firefox-flatpak",
        }
    }

    pub(crate) fn is_firefox(self) -> bool {
        matches!(self, Self::Firefox | Self::FirefoxFlatpak)
    }

    /// The Flathub build that this ID names.
    pub(crate) fn flatpak(self) -> Option<FlatpakBrowser> {
        match self {
            Self::ChromeFlatpak => Some(flatpak::CHROME),
            Self::ChromiumFlatpak => Some(flatpak::CHROMIUM),
            Self::EdgeFlatpak => Some(flatpak::EDGE),
            Self::BraveFlatpak => Some(flatpak::BRAVE),
            Self::FirefoxFlatpak => Some(flatpak::FIREFOX),
            Self::Chrome
            | Self::Chromium
            | Self::Edge
            | Self::Brave
            | Self::Vivaldi
            | Self::Firefox => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Os {
    Linux,
    Mac,
    Windows,
}

impl Os {
    pub(crate) const CURRENT: Self = if cfg!(windows) {
        Self::Windows
    } else if cfg!(target_os = "macos") {
        Self::Mac
    } else {
        Self::Linux
    };
}

/// How this build of Keyguard runs, which decides what the manifests start.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum Packaging {
    /// The manifests start the bundled connector.
    Native,
    /// Browsers outside the sandbox can't run the bundled connector, so the
    /// manifests start a wrapper that runs it through `flatpak run`.
    Flatpak { app_id: String },
    /// The AppImage is mounted at another path on each run, so the manifests
    /// start a copy of the connector.
    AppImage,
    /// The Microsoft Store or sideloaded MSIX build. Its folder changes on
    /// each update, and browsers may not start a program inside a package,
    /// so the manifests start the connector's execution alias.
    Msix {
        /// The family name of the package.
        family: Option<String>,
    },
}

/// Windows: the package whose identity this process has.
#[derive(Debug, Clone, PartialEq, Eq)]
// Only Windows has packages.
#[cfg_attr(not(windows), allow(dead_code))]
pub(crate) struct Package {
    /// Its folder.
    pub root: PathBuf,
    /// Its family name.
    pub family: Option<String>,
}

impl Packaging {
    pub(crate) fn id(&self) -> &'static str {
        match self {
            Self::Native => "native",
            Self::Flatpak { .. } => "flatpak",
            Self::AppImage => "appimage",
            Self::Msix { .. } => "msix",
        }
    }

    /// `exe` is this executable; `env` reads the environment;
    /// `flatpak_info` tells whether `/.flatpak-info` exists; `flatpak_root`
    /// is where Flatpak mounts the app, `/app`; `package` is the Windows
    /// package whose identity this process has.
    ///
    /// Variables alone don't count, since programs started from a terminal
    /// inherit them: from another Flatpak app's terminal, its `FLATPAK_ID`,
    /// and from an AppImage's, `APPIMAGE` and `APPDIR`. Nor does package
    /// identity, which a program started by another packaged app can
    /// inherit. Each packaging also needs `exe` inside it.
    pub(crate) fn detect(
        os: Os,
        exe: &Path,
        env: &impl Fn(&str) -> Option<OsString>,
        flatpak_info: bool,
        flatpak_root: &Path,
        package: Option<&Package>,
    ) -> Result<Self> {
        if os == Os::Windows
            && let Some(package) = package.filter(|package| is_inside(exe, &package.root))
        {
            return Ok(Self::Msix {
                family: package.family.clone(),
            });
        }
        if os != Os::Linux {
            return Ok(Self::Native);
        }
        let set = |name: &str| env(name).filter(|value| !value.is_empty());
        let flatpak_id = set("FLATPAK_ID");
        let flatpak = flatpak_id.is_some()
            || flatpak_info
            || env("container").as_deref() == Some("flatpak".as_ref());
        if flatpak && is_inside(exe, flatpak_root) {
            let app_id = flatpak_id
                .and_then(|id| id.into_string().ok())
                .filter(|id| is_flatpak_app_id(id))
                .ok_or_else(|| anyhow!("FLATPAK_ID is not set to a Flatpak app ID"))?;
            return Ok(Self::Flatpak { app_id });
        }
        // The AppImage's mount point.
        let app_dir = set("APPDIR")
            .map(PathBuf::from)
            .filter(|dir| dir.has_root());
        if set("APPIMAGE").is_some() && app_dir.is_some_and(|dir| is_inside(exe, &dir)) {
            return Ok(Self::AppImage);
        }
        Ok(Self::Native)
    }
}

/// Whether `exe` is inside `directory`, also once the links of `directory`
/// are resolved, as `Context::discover` resolves those of `exe` on Unix.
/// Windows resolves both, which also gives them the same form and case.
fn is_inside(exe: &Path, directory: &Path) -> bool {
    exe.starts_with(directory)
        || std::fs::canonicalize(directory).is_ok_and(|directory| {
            exe.starts_with(&directory)
                || (cfg!(windows)
                    && std::fs::canonicalize(exe).is_ok_and(|exe| exe.starts_with(&directory)))
        })
}

/// Letters, digits, `_`, `-` and `.`, which also keeps it safe in the
/// wrapper script.
fn is_flatpak_app_id(id: &str) -> bool {
    (1..=255).contains(&id.len())
        && id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-' | b'.'))
}

/// Where a browser looks for Keyguard's manifest.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Location {
    /// Files of which any one shows that the browser was used for this user.
    pub markers: Vec<PathBuf>,
    pub manifest: PathBuf,
    /// Windows: the key under `HKEY_CURRENT_USER` whose default value is the
    /// path of the manifest.
    pub registry_key: Option<String>,
    /// A Flatpak browser's copy of the connector, next to its manifest: its
    /// sandbox sees none of Keyguard's files.
    pub host_copy: Option<PathBuf>,
}

impl Location {
    pub(crate) fn directory(&self) -> &Path {
        self.manifest.parent().unwrap_or(&self.manifest)
    }

    /// The Flatpak build's launcher of the connector.
    pub(crate) fn wrapper(&self) -> PathBuf {
        self.directory().join(WRAPPER_FILE)
    }
}

/// The user's directories and this build of Keyguard.
#[derive(Debug, Clone)]
pub(crate) struct Context {
    pub os: Os,
    pub home: PathBuf,
    /// Linux: the host's `XDG_CONFIG_HOME`, which the Chromium browsers use.
    pub config_home: PathBuf,
    /// Linux: the host's `XDG_DATA_HOME`, which holds the AppImage's copy of
    /// the connector.
    pub data_home: PathBuf,
    /// Windows: `%LOCALAPPDATA%`.
    pub local_app_data: PathBuf,
    /// Windows: `%APPDATA%`.
    pub roaming_app_data: PathBuf,
    pub packaging: Packaging,
    /// The connector bundled next to this CLI.
    pub bundled_host: PathBuf,
    /// Reads the reparse data of the MSIX package's alias of its connector.
    pub read_alias: fn(&Path) -> std::io::Result<Vec<u8>>,
}

impl Context {
    pub(crate) fn discover() -> Result<Self> {
        ensure!(
            std::env::var_os("APP_SANDBOX_CONTAINER_ID").is_none(),
            BrowsersError::Unsupported
        );
        let home = dirs::home_dir().context("could not locate the user home")?;
        let exe = std::env::current_exe().context("could not locate the keyguard executable")?;
        // The connector is next to the executable, not next to a link to it.
        #[cfg(unix)]
        let exe = std::fs::canonicalize(&exe).unwrap_or(exe);
        #[cfg(windows)]
        let package = current_package();
        #[cfg(not(windows))]
        let package = None;
        Self::new(
            Os::CURRENT,
            home,
            &exe,
            &|name| std::env::var_os(name),
            Path::new("/.flatpak-info").exists(),
            Path::new("/app"),
            package.as_ref(),
        )
    }

    /// See [`Packaging::detect`] for `env`, `flatpak_info`, `flatpak_root`
    /// and `package`.
    pub(crate) fn new(
        os: Os,
        home: PathBuf,
        exe: &Path,
        env: &impl Fn(&str) -> Option<OsString>,
        flatpak_info: bool,
        flatpak_root: &Path,
        package: Option<&Package>,
    ) -> Result<Self> {
        let packaging = Packaging::detect(os, exe, env, flatpak_info, flatpak_root, package)?;
        // XDG directories must be absolute. Windows always sets its folders
        // with a drive; `has_root` keeps the tables testable on any OS.
        let absolute = |name: &str| env(name).map(PathBuf::from).filter(|path| path.has_root());
        // Inside Flatpak, XDG_CONFIG_HOME is the app's own directory. Flatpak
        // passes the host's as HOST_XDG_CONFIG_HOME, but only when it is set.
        let config_home = match packaging {
            Packaging::Flatpak { .. } => absolute("HOST_XDG_CONFIG_HOME"),
            _ => absolute("XDG_CONFIG_HOME"),
        }
        .unwrap_or_else(|| home.join(".config"));
        let data_home =
            absolute("XDG_DATA_HOME").unwrap_or_else(|| home.join(".local").join("share"));
        let local_app_data =
            absolute("LOCALAPPDATA").unwrap_or_else(|| home.join("AppData").join("Local"));
        let roaming_app_data =
            absolute("APPDATA").unwrap_or_else(|| home.join("AppData").join("Roaming"));
        let directory = exe
            .parent()
            .context("could not locate the directory of the keyguard executable")?;
        let host = match os {
            Os::Windows => format!("{HOST_EXECUTABLE}.exe"),
            Os::Linux | Os::Mac => HOST_EXECUTABLE.to_owned(),
        };
        Ok(Self {
            os,
            bundled_host: directory.join(host),
            home,
            config_home,
            data_home,
            local_app_data,
            roaming_app_data,
            packaging,
            read_alias: super::alias::reparse_data,
        })
    }

    /// Whether `path` is in the folder of a browser of Flathub, which the
    /// browser can change as it likes.
    pub(crate) fn in_flatpak_browser(&self, path: &Path) -> bool {
        self.os == Os::Linux
            && flatpak::ALL
                .iter()
                .any(|browser| path.starts_with(browser.folder(&self.home)))
    }

    /// The browsers of this OS. Flathub's run on Linux only.
    pub(crate) fn browsers(&self) -> Vec<Browser> {
        Browser::value_variants()
            .iter()
            .copied()
            .filter(|browser| self.os == Os::Linux || browser.flatpak().is_none())
            .collect()
    }

    /// The directories that may hold the files this command writes. Each
    /// file and its parents up to the root are checked before writing.
    pub(crate) fn roots(&self) -> Vec<PathBuf> {
        // The sandbox shares only the manifest directories; their parents
        // in the sandbox are Flatpak's own.
        if matches!(self.packaging, Packaging::Flatpak { .. }) {
            return self
                .browsers()
                .into_iter()
                .map(|browser| self.location(browser).directory().to_owned())
                .collect();
        }
        vec![
            self.home.clone(),
            self.config_home.clone(),
            self.data_home.clone(),
            self.local_app_data.clone(),
            self.roaming_app_data.clone(),
        ]
    }

    /// Linux: the AppImage's copy of the connector.
    pub(crate) fn host_copy(&self) -> Option<PathBuf> {
        (self.os == Os::Linux).then(|| {
            self.data_home
                .join("keyguard")
                .join("browser-host")
                .join(HOST_EXECUTABLE)
        })
    }

    /// The lock that `install` and `uninstall` hold, in a folder that only
    /// Keyguard writes, never a browser's.
    pub(crate) fn lock_file(&self) -> PathBuf {
        let folder = match (&self.packaging, self.os) {
            (_, Os::Windows) => self.local_app_data.join("Keyguard"),
            // The app's own folder, which each of its sandboxes shares.
            (Packaging::Flatpak { .. }, _) => self.data_home.join("keyguard"),
            _ => self.home.join(".keyguard"),
        };
        folder.join(LOCK_FILE)
    }

    /// Windows: the execution alias that the MSIX package declares for its
    /// connector. Windows creates it when it installs the package.
    pub(crate) fn host_alias(&self) -> Option<PathBuf> {
        (self.os == Os::Windows).then(|| {
            join(&self.local_app_data, &["Microsoft", "WindowsApps"])
                .join(format!("{HOST_EXECUTABLE}.exe"))
        })
    }

    /// What the manifest at `location` starts: a Flatpak browser's copy of
    /// the connector, or else what this packaging needs.
    pub(crate) fn host_target(&self, location: &Location) -> PathBuf {
        if let Some(copy) = &location.host_copy {
            return copy.clone();
        }
        match self.packaging {
            Packaging::Native => self.bundled_host.clone(),
            Packaging::Flatpak { .. } => location.wrapper(),
            Packaging::AppImage => self
                .host_copy()
                .unwrap_or_else(|| self.bundled_host.clone()),
            Packaging::Msix { .. } => self
                .host_alias()
                .unwrap_or_else(|| self.bundled_host.clone()),
        }
    }

    pub(crate) fn location(&self, browser: Browser) -> Location {
        let mut location = match (browser.flatpak(), self.os) {
            (Some(flatpak), _) => self.flatpak(flatpak),
            (None, Os::Linux) => self.linux(browser),
            (None, Os::Mac) => self.macos(browser),
            (None, Os::Windows) => self.windows(browser),
        };
        // The sandbox sees only the manifest directories that exist on the
        // host, so a browser counts as found once its directory exists.
        if matches!(self.packaging, Packaging::Flatpak { .. }) {
            location.markers = vec![location.directory().to_owned()];
        }
        location
    }

    /// A browser of Flathub, found once Flatpak made its folder. It reads
    /// manifests only from its folder, and starts only what is in it.
    fn flatpak(&self, browser: FlatpakBrowser) -> Location {
        let directory = browser.manifests(&self.home);
        Location {
            markers: vec![browser.folder(&self.home)],
            manifest: directory.join(MANIFEST_FILE),
            registry_key: None,
            host_copy: Some(directory.join(HOST_EXECUTABLE)),
        }
    }

    fn linux(&self, browser: Browser) -> Location {
        let chromium = |root: &[&str]| {
            let root = join(&self.config_home, root);
            Location {
                markers: vec![root.join(LOCAL_STATE)],
                manifest: root.join("NativeMessagingHosts").join(MANIFEST_FILE),
                registry_key: None,
                host_copy: None,
            }
        };
        match browser {
            Browser::Chrome => chromium(&["google-chrome"]),
            Browser::Chromium => chromium(&["chromium"]),
            Browser::Edge => chromium(&["microsoft-edge"]),
            Browser::Brave => chromium(&["BraveSoftware", "Brave-Browser"]),
            Browser::Vivaldi => chromium(&["vivaldi"]),
            // Firefox 147 and later keep new profiles in XDG_CONFIG_HOME, but
            // still read manifests only from ~/.mozilla. Bug 2005167, open as
            // of November 2026, adds XDG_CONFIG_HOME/mozilla in front of it.
            Browser::Firefox => Location {
                markers: vec![
                    join(&self.home, &[".mozilla", "firefox"]),
                    join(&self.config_home, &["mozilla", "firefox"]),
                ],
                manifest: join(
                    &self.home,
                    &[".mozilla", "native-messaging-hosts", MANIFEST_FILE],
                ),
                registry_key: None,
                host_copy: None,
            },
            _ => unreachable!("location() handles the Flatpak browsers"),
        }
    }

    fn macos(&self, browser: Browser) -> Location {
        let support = join(&self.home, &["Library", "Application Support"]);
        let chromium = |root: &[&str]| {
            let root = join(&support, root);
            Location {
                markers: vec![root.join(LOCAL_STATE)],
                manifest: root.join("NativeMessagingHosts").join(MANIFEST_FILE),
                registry_key: None,
                host_copy: None,
            }
        };
        match browser {
            Browser::Chrome => chromium(&["Google", "Chrome"]),
            Browser::Chromium => chromium(&["Chromium"]),
            Browser::Edge => chromium(&["Microsoft Edge"]),
            // Brave reads Chrome's directory on macOS.
            Browser::Brave => Location {
                markers: vec![join(
                    &support,
                    &["BraveSoftware", "Brave-Browser", LOCAL_STATE],
                )],
                ..chromium(&["Google", "Chrome"])
            },
            Browser::Vivaldi => chromium(&["Vivaldi"]),
            Browser::Firefox => Location {
                markers: vec![support.join("Firefox")],
                manifest: join(
                    &support,
                    &["Mozilla", "NativeMessagingHosts", MANIFEST_FILE],
                ),
                registry_key: None,
                host_copy: None,
            },
            _ => unreachable!("location() handles the Flatpak browsers"),
        }
    }

    fn windows(&self, browser: Browser) -> Location {
        let manifest = |id: &str| {
            join(
                &self.local_app_data,
                &["Keyguard", "NativeMessagingHosts", id, MANIFEST_FILE],
            )
        };
        let key = |vendor: &str| {
            Some(format!(
                "Software\\{vendor}\\NativeMessagingHosts\\{HOST_NAME}"
            ))
        };
        let user_data = |root: &[&str]| {
            vec![
                join(&self.local_app_data, root)
                    .join("User Data")
                    .join(LOCAL_STATE),
            ]
        };
        let chrome = Location {
            markers: user_data(&["Google", "Chrome"]),
            manifest: manifest("chrome"),
            registry_key: key("Google\\Chrome"),
            host_copy: None,
        };
        match browser {
            Browser::Chrome => chrome,
            Browser::Chromium => Location {
                markers: user_data(&["Chromium"]),
                manifest: manifest("chromium"),
                registry_key: key("Chromium"),
                host_copy: None,
            },
            // Edge reads its own key first, then Chromium's and Chrome's.
            Browser::Edge => Location {
                markers: user_data(&["Microsoft", "Edge"]),
                manifest: manifest("edge"),
                registry_key: key("Microsoft\\Edge"),
                host_copy: None,
            },
            // Brave and Vivaldi read Chromium's key, then Chrome's.
            Browser::Brave => Location {
                markers: user_data(&["BraveSoftware", "Brave-Browser"]),
                ..chrome
            },
            Browser::Vivaldi => Location {
                markers: user_data(&["Vivaldi"]),
                ..chrome
            },
            Browser::Firefox => Location {
                markers: vec![join(&self.roaming_app_data, &["Mozilla", "Firefox"])],
                manifest: manifest("firefox"),
                registry_key: key("Mozilla"),
                host_copy: None,
            },
            _ => unreachable!("location() handles the Flatpak browsers"),
        }
    }
}

/// The package whose identity this process has, as in the Microsoft Store
/// and sideloaded MSIX builds.
#[cfg(windows)]
fn current_package() -> Option<Package> {
    use std::os::windows::ffi::OsStringExt;
    use windows_sys::Win32::Storage::Packaging::Appx::{
        GetCurrentPackageFamilyName, GetCurrentPackagePath,
    };
    let root = package_string(GetCurrentPackagePath)?;
    Some(Package {
        root: OsString::from_wide(&root).into(),
        family: package_string(GetCurrentPackageFamilyName)
            .and_then(|family| String::from_utf16(&family).ok()),
    })
}

/// What `query`, such as `GetCurrentPackagePath`, gives about the package
/// whose identity this process has, without the terminating null.
#[cfg(windows)]
fn package_string(
    query: unsafe extern "system" fn(
        *mut u32,
        windows_sys::core::PWSTR,
    ) -> windows_sys::Win32::Foundation::WIN32_ERROR,
) -> Option<Vec<u16>> {
    use windows_sys::Win32::Foundation::{ERROR_INSUFFICIENT_BUFFER, ERROR_SUCCESS};
    let mut length = 0;
    // SAFETY: A size query, without a buffer. Without package identity, it
    // fails with APPMODEL_ERROR_NO_PACKAGE.
    let status = unsafe { query(&mut length, std::ptr::null_mut()) };
    if status != ERROR_INSUFFICIENT_BUFFER {
        return None;
    }
    let mut string = vec![0u16; length as usize];
    // SAFETY: string holds the `length` UTF-16 units that the size query
    // asked for, and `length` says so.
    let status = unsafe { query(&mut length, string.as_mut_ptr()) };
    if status != ERROR_SUCCESS {
        return None;
    }
    // The length counts the terminating null.
    string.truncate(length as usize);
    if string.last() == Some(&0) {
        string.pop();
    }
    Some(string)
}

fn join(base: &Path, parts: &[&str]) -> PathBuf {
    parts
        .iter()
        .fold(base.to_owned(), |path, part| path.join(part))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A native build's CLI.
    const EXE: &str = "/opt/keyguard/lib/app/resources/keyguard";

    /// The Flatpak build's CLI, inside its app root `/app`.
    const FLATPAK_EXE: &str = "/app/lib/app/resources/keyguard";

    fn context(os: Os, env: &[(&str, &str)], flatpak_info: bool) -> Result<Context> {
        context_at(os, Path::new(EXE), env, flatpak_info)
    }

    fn context_at(os: Os, exe: &Path, env: &[(&str, &str)], flatpak_info: bool) -> Result<Context> {
        context_in_package(os, exe, env, flatpak_info, None)
    }

    fn context_in_package(
        os: Os,
        exe: &Path,
        env: &[(&str, &str)],
        flatpak_info: bool,
        package: Option<&Package>,
    ) -> Result<Context> {
        let env: Vec<(String, OsString)> = env
            .iter()
            .map(|(name, value)| ((*name).to_owned(), OsString::from(value)))
            .collect();
        Context::new(
            os,
            "/home/user".into(),
            exe,
            &|name| {
                env.iter()
                    .find(|(key, _)| key == name)
                    .map(|(_, value)| value.clone())
            },
            flatpak_info,
            Path::new("/app"),
            package,
        )
    }

    /// The browsers outside Flatpak.
    fn table(context: &Context) -> Vec<(Browser, String, Option<String>)> {
        context
            .browsers()
            .into_iter()
            .filter(|browser| browser.flatpak().is_none())
            .map(|browser| {
                let location = context.location(browser);
                (
                    browser,
                    location.manifest.to_string_lossy().replace('\\', "/"),
                    location.registry_key,
                )
            })
            .collect()
    }

    #[test]
    fn linux_paths_follow_xdg_config_home() {
        let context = context(Os::Linux, &[("XDG_CONFIG_HOME", "/xdg")], false).unwrap();
        let file = "NativeMessagingHosts/com.artemchep.keyguard.json";
        assert_eq!(
            table(&context),
            [
                (Browser::Chrome, format!("/xdg/google-chrome/{file}"), None),
                (Browser::Chromium, format!("/xdg/chromium/{file}"), None),
                (Browser::Edge, format!("/xdg/microsoft-edge/{file}"), None),
                (
                    Browser::Brave,
                    format!("/xdg/BraveSoftware/Brave-Browser/{file}"),
                    None
                ),
                (Browser::Vivaldi, format!("/xdg/vivaldi/{file}"), None),
                (
                    Browser::Firefox,
                    "/home/user/.mozilla/native-messaging-hosts/com.artemchep.keyguard.json".into(),
                    None
                ),
            ]
        );
        assert_eq!(
            context.location(Browser::Chrome).markers,
            [PathBuf::from("/xdg/google-chrome/Local State")]
        );
        assert_eq!(
            context.location(Browser::Firefox).markers,
            [
                PathBuf::from("/home/user/.mozilla/firefox"),
                PathBuf::from("/xdg/mozilla/firefox"),
            ]
        );
        assert_eq!(context.packaging, Packaging::Native);
        assert_eq!(
            context.bundled_host,
            Path::new("/opt/keyguard/lib/app/resources/keyguard-browser-host")
        );
    }

    #[test]
    fn linux_defaults_to_dot_config() {
        let context = context(Os::Linux, &[("XDG_CONFIG_HOME", "relative")], false).unwrap();
        assert_eq!(
            context.location(Browser::Chromium).manifest,
            Path::new(
                "/home/user/.config/chromium/NativeMessagingHosts/com.artemchep.keyguard.json"
            )
        );
    }

    #[test]
    fn macos_paths_are_in_application_support() {
        let context = context(Os::Mac, &[("FLATPAK_ID", "ignored")], false).unwrap();
        let support = "/home/user/Library/Application Support";
        let file = "NativeMessagingHosts/com.artemchep.keyguard.json";
        assert_eq!(
            table(&context),
            [
                (
                    Browser::Chrome,
                    format!("{support}/Google/Chrome/{file}"),
                    None
                ),
                (
                    Browser::Chromium,
                    format!("{support}/Chromium/{file}"),
                    None
                ),
                (
                    Browser::Edge,
                    format!("{support}/Microsoft Edge/{file}"),
                    None
                ),
                // Brave reads Chrome's directory.
                (
                    Browser::Brave,
                    format!("{support}/Google/Chrome/{file}"),
                    None
                ),
                (Browser::Vivaldi, format!("{support}/Vivaldi/{file}"), None),
                (Browser::Firefox, format!("{support}/Mozilla/{file}"), None),
            ]
        );
        assert_eq!(
            context.location(Browser::Brave).markers,
            [PathBuf::from(format!(
                "{support}/BraveSoftware/Brave-Browser/Local State"
            ))]
        );
        assert_eq!(context.packaging, Packaging::Native);
    }

    #[test]
    fn windows_manifests_are_registered_per_vendor() {
        let context = context(
            Os::Windows,
            &[("LOCALAPPDATA", "/local"), ("APPDATA", "/roaming")],
            false,
        )
        .unwrap();
        let ours = "/local/Keyguard/NativeMessagingHosts";
        let file = "com.artemchep.keyguard.json";
        let key = |vendor: &str| {
            Some(format!(
                "Software\\{vendor}\\NativeMessagingHosts\\com.artemchep.keyguard"
            ))
        };
        assert_eq!(
            table(&context),
            [
                (
                    Browser::Chrome,
                    format!("{ours}/chrome/{file}"),
                    key("Google\\Chrome")
                ),
                (
                    Browser::Chromium,
                    format!("{ours}/chromium/{file}"),
                    key("Chromium")
                ),
                (
                    Browser::Edge,
                    format!("{ours}/edge/{file}"),
                    key("Microsoft\\Edge")
                ),
                (
                    Browser::Brave,
                    format!("{ours}/chrome/{file}"),
                    key("Google\\Chrome")
                ),
                (
                    Browser::Vivaldi,
                    format!("{ours}/chrome/{file}"),
                    key("Google\\Chrome")
                ),
                (
                    Browser::Firefox,
                    format!("{ours}/firefox/{file}"),
                    key("Mozilla")
                ),
            ]
        );
        assert_eq!(
            context.location(Browser::Firefox).markers,
            [PathBuf::from("/roaming/Mozilla/Firefox")]
        );
        assert!(context.bundled_host.ends_with("keyguard-browser-host.exe"));
    }

    #[test]
    fn flatpak_uses_the_host_config_home_and_a_wrapper() {
        let context = context_at(
            Os::Linux,
            Path::new(FLATPAK_EXE),
            &[
                ("FLATPAK_ID", "com.artemchep.keyguard"),
                (
                    "XDG_CONFIG_HOME",
                    "/home/user/.var/app/com.artemchep.keyguard/config",
                ),
                ("HOST_XDG_CONFIG_HOME", "/host-config"),
                ("APPIMAGE", "/ignored.AppImage"),
            ],
            true,
        )
        .unwrap();
        assert_eq!(
            context.packaging,
            Packaging::Flatpak {
                app_id: "com.artemchep.keyguard".into()
            }
        );
        let chrome = context.location(Browser::Chrome);
        assert_eq!(
            chrome.manifest,
            Path::new(
                "/host-config/google-chrome/NativeMessagingHosts/com.artemchep.keyguard.json"
            )
        );
        assert_eq!(chrome.markers, [chrome.directory().to_owned()]);
        // Only the shared directories hold files to write.
        let roots = context.roots();
        assert_eq!(roots.len(), 11);
        assert!(roots.contains(&chrome.directory().to_owned()));
        assert!(!roots.contains(&PathBuf::from("/home/user")));
        assert_eq!(
            context.host_target(&chrome),
            Path::new(
                "/host-config/google-chrome/NativeMessagingHosts/com.artemchep.keyguard-host.sh"
            )
        );

        // Without HOST_XDG_CONFIG_HOME, the host uses ~/.config.
        let context = context_without_host_config();
        assert_eq!(
            context.location(Browser::Edge).manifest,
            Path::new(
                "/home/user/.config/microsoft-edge/NativeMessagingHosts/com.artemchep.keyguard.json"
            )
        );
    }

    /// The folder of an installed MSIX package, and its CLI.
    const PACKAGE_ROOT: &str = "/Program Files/WindowsApps/Keyguard_2.0.0.0_x64__abcdefghjkmnp";
    const PACKAGE_EXE: &str =
        "/Program Files/WindowsApps/Keyguard_2.0.0.0_x64__abcdefghjkmnp/app/resources/keyguard.exe";

    fn package() -> Package {
        Package {
            root: PACKAGE_ROOT.into(),
            family: Some("Keyguard_abcdefghjkmnp".into()),
        }
    }

    #[test]
    fn msix_manifests_start_the_execution_alias() {
        let context = context_in_package(
            Os::Windows,
            Path::new(PACKAGE_EXE),
            &[("LOCALAPPDATA", "/local"), ("APPDATA", "/roaming")],
            false,
            Some(&package()),
        )
        .unwrap();
        assert_eq!(
            context.packaging,
            Packaging::Msix {
                family: package().family
            }
        );
        assert_eq!(context.packaging.id(), "msix");
        let location = context.location(Browser::Edge);
        // Manifests and keys stay where the other builds write them.
        assert_eq!(
            location.manifest,
            Path::new("/local/Keyguard/NativeMessagingHosts/edge/com.artemchep.keyguard.json")
        );
        assert_eq!(
            context.host_target(&location),
            Path::new("/local/Microsoft/WindowsApps/keyguard-browser-host.exe")
        );
        assert_eq!(
            context.bundled_host,
            Path::new(PACKAGE_ROOT).join("app/resources/keyguard-browser-host.exe")
        );
    }

    #[test]
    fn package_identity_needs_the_cli_inside_the_package() {
        // A CLI of the MSI build, run by another packaged app.
        let context = context_in_package(
            Os::Windows,
            Path::new("/Program Files/Keyguard/app/resources/keyguard.exe"),
            &[("LOCALAPPDATA", "/local")],
            false,
            Some(&package()),
        )
        .unwrap();
        assert_eq!(context.packaging, Packaging::Native);
        let location = context.location(Browser::Chrome);
        assert_eq!(context.host_target(&location), context.bundled_host);
        // Only Windows has packages.
        let context = context_in_package(
            Os::Linux,
            Path::new(PACKAGE_EXE),
            &[],
            false,
            Some(&package()),
        )
        .unwrap();
        assert_eq!(context.packaging, Packaging::Native);
        assert_eq!(context.host_alias(), None);
    }

    #[test]
    fn flatpak_browsers_start_a_copy_next_to_their_manifest() {
        let directory = Path::new(
            "/home/user/.var/app/com.brave.Browser/config/BraveSoftware/Brave-Browser/NativeMessagingHosts",
        );
        let appimage = [
            ("APPIMAGE", "/home/user/Keyguard.AppImage"),
            ("APPDIR", APPDIR),
        ];
        let flatpak = [("FLATPAK_ID", "com.artemchep.keyguard")];
        for (exe, env, packaging) in [
            (EXE, &[][..], "native"),
            (APPIMAGE_EXE, &appimage[..], "appimage"),
            (FLATPAK_EXE, &flatpak[..], "flatpak"),
        ] {
            let context = context_at(Os::Linux, Path::new(exe), env, false).unwrap();
            assert_eq!(context.packaging.id(), packaging);
            let location = context.location(Browser::BraveFlatpak);
            assert_eq!(
                location.manifest,
                directory.join("com.artemchep.keyguard.json")
            );
            let copy = directory.join("keyguard-browser-host");
            assert_eq!(location.host_copy.as_deref(), Some(&*copy));
            assert_eq!(context.host_target(&location), copy);
            assert_eq!(location.registry_key, None);
            // Inside Flatpak, the sandbox sees only the folder of manifests.
            let marker = if packaging == "flatpak" {
                directory
            } else {
                Path::new("/home/user/.var/app/com.brave.Browser")
            };
            assert_eq!(location.markers, [marker]);
        }

        let context = context(Os::Linux, &[("XDG_CONFIG_HOME", "/xdg")], false).unwrap();
        assert_eq!(
            context.location(Browser::FirefoxFlatpak).manifest,
            Path::new(
                "/home/user/.var/app/org.mozilla.firefox/.mozilla/native-messaging-hosts/com.artemchep.keyguard.json"
            )
        );
        assert_eq!(context.location(Browser::Chrome).host_copy, None);
        assert_eq!(context.browsers().len(), 11);
    }

    #[test]
    fn only_linux_has_flatpak_browsers() {
        for os in [Os::Mac, Os::Windows] {
            let context = context(os, &[("LOCALAPPDATA", "/local")], false).unwrap();
            let browsers = context.browsers();
            assert_eq!(browsers.len(), 6, "{os:?}");
            assert!(browsers.iter().all(|browser| browser.flatpak().is_none()));
        }
    }

    #[test]
    fn browser_ids_match_the_cli_and_the_report() {
        for &browser in Browser::value_variants() {
            let id = browser.id();
            assert_eq!(browser.to_possible_value().unwrap().get_name(), id);
            assert_eq!(serde_json::to_value(browser).unwrap(), id);
            assert_eq!(
                browser.flatpak().is_some(),
                id.ends_with("-flatpak"),
                "{id}"
            );
        }
        assert!(Browser::FirefoxFlatpak.is_firefox());
    }

    fn context_without_host_config() -> Context {
        context_at(
            Os::Linux,
            Path::new(FLATPAK_EXE),
            &[
                ("FLATPAK_ID", "com.artemchep.keyguard"),
                (
                    "XDG_CONFIG_HOME",
                    "/home/user/.var/app/com.artemchep.keyguard/config",
                ),
            ],
            false,
        )
        .unwrap()
    }

    #[test]
    fn flatpak_needs_a_valid_app_id() {
        for (env, info) in [
            (&[][..], true),
            (&[("container", "flatpak")][..], false),
            (&[("FLATPAK_ID", "com.example.app'; rm -rf ~")][..], false),
        ] {
            assert!(
                context_at(Os::Linux, Path::new(FLATPAK_EXE), env, info).is_err(),
                "{env:?}"
            );
        }
    }

    #[test]
    fn inherited_flatpak_variables_need_the_app_root() {
        // A native keyguard run from the terminal of another Flatpak app.
        for (env, info) in [
            (&[("FLATPAK_ID", "com.visualstudio.code")][..], false),
            (
                &[
                    ("FLATPAK_ID", "com.visualstudio.code"),
                    ("container", "flatpak"),
                ][..],
                true,
            ),
            (&[("FLATPAK_ID", "com.example.app'; rm -rf ~")][..], false),
        ] {
            let context = context(Os::Linux, env, info).unwrap();
            assert_eq!(context.packaging, Packaging::Native, "{env:?}");
            assert_eq!(
                context.location(Browser::Chrome).markers,
                [PathBuf::from(
                    "/home/user/.config/google-chrome/Local State"
                )]
            );
        }
    }

    /// The AppImage's CLI, inside its mount point [`APPDIR`].
    const APPIMAGE_EXE: &str = "/tmp/.mount_KeyguaAbCdEf/lib/app/resources/keyguard";
    const APPDIR: &str = "/tmp/.mount_KeyguaAbCdEf";

    #[test]
    fn appimage_starts_a_copy_in_xdg_data_home() {
        let context = context_at(
            Os::Linux,
            Path::new(APPIMAGE_EXE),
            &[
                ("APPIMAGE", "/home/user/Keyguard.AppImage"),
                ("APPDIR", APPDIR),
                ("XDG_DATA_HOME", "/data"),
            ],
            false,
        )
        .unwrap();
        assert_eq!(context.packaging, Packaging::AppImage);
        let location = context.location(Browser::Firefox);
        assert_eq!(
            context.host_target(&location),
            Path::new("/data/keyguard/browser-host/keyguard-browser-host")
        );
        // An empty APPIMAGE or APPDIR is not an AppImage.
        for env in [
            [("APPIMAGE", ""), ("APPDIR", APPDIR)],
            [("APPIMAGE", "/home/user/Keyguard.AppImage"), ("APPDIR", "")],
        ] {
            let context = context_at(Os::Linux, Path::new(APPIMAGE_EXE), &env, false).unwrap();
            assert_eq!(context.packaging, Packaging::Native, "{env:?}");
        }
    }

    #[test]
    fn inherited_appimage_variables_need_the_mount_point() {
        // A native keyguard run from a terminal that an AppImage started.
        let env = [
            ("APPIMAGE", "/home/user/Other.AppImage"),
            ("APPDIR", "/tmp/.mount_OtherAbCdEf"),
        ];
        let context = context(Os::Linux, &env, false).unwrap();
        assert_eq!(context.packaging, Packaging::Native);
        // APPIMAGE alone is not enough.
        let env = [("APPIMAGE", "/home/user/Keyguard.AppImage")];
        let context = context_at(Os::Linux, Path::new(APPIMAGE_EXE), &env, false).unwrap();
        assert_eq!(context.packaging, Packaging::Native);
    }

    #[cfg(unix)]
    #[test]
    fn the_mount_point_may_be_a_link() {
        // `discover` resolves the links of the executable's path.
        let temp = tempfile::tempdir().unwrap();
        let mount = std::fs::canonicalize(temp.path()).unwrap().join("mount");
        std::fs::create_dir(&mount).unwrap();
        let link = temp.path().join("link");
        std::os::unix::fs::symlink(&mount, &link).unwrap();
        let env = [
            ("APPIMAGE", "/home/user/Keyguard.AppImage"),
            ("APPDIR", link.to_str().unwrap()),
        ];
        let context = context_at(Os::Linux, &mount.join("keyguard"), &env, false).unwrap();
        assert_eq!(context.packaging, Packaging::AppImage);
    }
}
