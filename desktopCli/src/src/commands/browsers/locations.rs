//! Where each browser looks for the manifest of a native messaging host, and
//! how this build of Keyguard is packaged.
//!
//! Sources: the browsers' native messaging docs, Chromium's
//! `launch_context_win.cc`, Brave's `brave_main_delegate.cc`, and KeePassXC's
//! `NativeMessageInstaller.cpp`.
use super::BrowsersError;
use anyhow::{Context as _, Result, anyhow, ensure};
use clap::ValueEnum as _;
use keyguard_cli_protocol::browser::HOST_EXECUTABLE;
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

/// [`clap::ValueEnum::value_variants`] lists every browser, in this order.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, clap::ValueEnum, Serialize)]
#[serde(rename_all = "lowercase")]
pub(crate) enum Browser {
    Chrome,
    Chromium,
    Edge,
    Brave,
    Vivaldi,
    Firefox,
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
        }
    }

    pub(crate) fn is_firefox(self) -> bool {
        self == Self::Firefox
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
}

impl Packaging {
    pub(crate) fn id(&self) -> &'static str {
        match self {
            Self::Native => "native",
            Self::Flatpak { .. } => "flatpak",
            Self::AppImage => "appimage",
        }
    }

    /// `exe` is this executable; `env` reads the environment;
    /// `flatpak_info` tells whether `/.flatpak-info` exists; `flatpak_root`
    /// is where Flatpak mounts the app, `/app`.
    ///
    /// Variables alone don't count, since programs started from a terminal
    /// inherit them: from another Flatpak app's terminal, its `FLATPAK_ID`,
    /// and from an AppImage's, `APPIMAGE` and `APPDIR`. Each packaging also
    /// needs `exe` inside it.
    pub(crate) fn detect(
        os: Os,
        exe: &Path,
        env: &impl Fn(&str) -> Option<OsString>,
        flatpak_info: bool,
        flatpak_root: &Path,
    ) -> Result<Self> {
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
/// are resolved, as `Context::discover` resolves those of `exe`.
fn is_inside(exe: &Path, directory: &Path) -> bool {
    exe.starts_with(directory)
        || std::fs::canonicalize(directory).is_ok_and(|directory| exe.starts_with(directory))
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
}

impl Context {
    pub(crate) fn discover() -> Result<Self> {
        ensure!(
            std::env::var_os("APP_SANDBOX_CONTAINER_ID").is_none(),
            BrowsersError::Unsupported
        );
        // Manifests would start the connector from the versioned
        // WindowsApps folder, which browsers may not run.
        #[cfg(windows)]
        ensure!(!has_package_identity(), BrowsersError::Unsupported);
        let home = dirs::home_dir().context("could not locate the user home")?;
        let exe = std::env::current_exe().context("could not locate the keyguard executable")?;
        // The connector is next to the executable, not next to a link to it.
        #[cfg(unix)]
        let exe = std::fs::canonicalize(&exe).unwrap_or(exe);
        Self::new(
            Os::CURRENT,
            home,
            &exe,
            &|name| std::env::var_os(name),
            Path::new("/.flatpak-info").exists(),
            Path::new("/app"),
        )
    }

    /// See [`Packaging::detect`] for `env`, `flatpak_info` and
    /// `flatpak_root`.
    pub(crate) fn new(
        os: Os,
        home: PathBuf,
        exe: &Path,
        env: &impl Fn(&str) -> Option<OsString>,
        flatpak_info: bool,
        flatpak_root: &Path,
    ) -> Result<Self> {
        let packaging = Packaging::detect(os, exe, env, flatpak_info, flatpak_root)?;
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
        })
    }

    /// The directories that may hold the files this command writes. Each
    /// file and its parents up to the root are checked before writing.
    pub(crate) fn roots(&self) -> Vec<PathBuf> {
        // The sandbox shares only the manifest directories; their parents
        // in the sandbox are Flatpak's own.
        if matches!(self.packaging, Packaging::Flatpak { .. }) {
            return Browser::value_variants()
                .iter()
                .copied()
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

    /// What the manifest at `location` starts.
    pub(crate) fn host_target(&self, location: &Location) -> PathBuf {
        match self.packaging {
            Packaging::Native => self.bundled_host.clone(),
            Packaging::Flatpak { .. } => location.wrapper(),
            Packaging::AppImage => self
                .host_copy()
                .unwrap_or_else(|| self.bundled_host.clone()),
        }
    }

    pub(crate) fn location(&self, browser: Browser) -> Location {
        let mut location = match self.os {
            Os::Linux => self.linux(browser),
            Os::Mac => self.macos(browser),
            Os::Windows => self.windows(browser),
        };
        // The sandbox sees only the manifest directories that exist on the
        // host, so a browser counts as found once its directory exists.
        if matches!(self.packaging, Packaging::Flatpak { .. }) {
            location.markers = vec![location.directory().to_owned()];
        }
        location
    }

    fn linux(&self, browser: Browser) -> Location {
        let chromium = |root: &[&str]| {
            let root = join(&self.config_home, root);
            Location {
                markers: vec![root.join(LOCAL_STATE)],
                manifest: root.join("NativeMessagingHosts").join(MANIFEST_FILE),
                registry_key: None,
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
            },
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
            },
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
        };
        match browser {
            Browser::Chrome => chrome,
            Browser::Chromium => Location {
                markers: user_data(&["Chromium"]),
                manifest: manifest("chromium"),
                registry_key: key("Chromium"),
            },
            // Edge reads its own key first, then Chromium's and Chrome's.
            Browser::Edge => Location {
                markers: user_data(&["Microsoft", "Edge"]),
                manifest: manifest("edge"),
                registry_key: key("Microsoft\\Edge"),
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
            },
        }
    }
}

/// Whether this process runs with package identity, as the Microsoft Store
/// and sideloaded MSIX builds do.
#[cfg(windows)]
fn has_package_identity() -> bool {
    use windows_sys::Win32::{
        Foundation::ERROR_INSUFFICIENT_BUFFER, Storage::Packaging::Appx::GetCurrentPackagePath,
    };
    let mut length = 0;
    // SAFETY: A size query, without a buffer. Without package identity, it
    // fails with APPMODEL_ERROR_NO_PACKAGE.
    let status = unsafe { GetCurrentPackagePath(&mut length, std::ptr::null_mut()) };
    status == ERROR_INSUFFICIENT_BUFFER
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
        )
    }

    fn table(context: &Context) -> Vec<(Browser, String, Option<String>)> {
        Browser::value_variants()
            .iter()
            .copied()
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
        assert_eq!(roots.len(), 6);
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
