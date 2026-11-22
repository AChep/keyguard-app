use super::*;
use crate::commands::browsers::{
    alias::tests::alias_of, error_json, locations::Os, registry::Memory,
};
use keyguard_cli_protocol::browser::{flatpak, is_chromium_origin, is_firefox_extension_id};
use std::{collections::BTreeMap, ffi::OsString, time::Duration};

const EXTRA_ORIGIN: &str = "chrome-extension://abcdefghijklmnopabcdefghijklmnop/";

struct Fixture {
    _temp: tempfile::TempDir,
    home: PathBuf,
    context: Context,
    registry: Memory,
}

/// A user home and a bundled connector in a temporary directory. Each
/// `{home}` in the values of `env` names the home, and each `{app}` the
/// directory of the connector, which is also the Flatpak app root.
fn fixture(os: Os, env: &[(&str, &str)]) -> Fixture {
    let temp = tempfile::tempdir().unwrap();
    let home = temp.path().join("home");
    let app = temp.path().join("app");
    fs::create_dir(&home).unwrap();
    fs::create_dir(&app).unwrap();
    let env: Vec<(String, OsString)> = env
        .iter()
        .map(|(name, value)| {
            let value = value
                .replace("{home}", home.to_str().unwrap())
                .replace("{app}", app.to_str().unwrap());
            ((*name).to_owned(), value.into())
        })
        .collect();
    let lookup = |name: &str| {
        env.iter()
            .find(|(key, _)| key == name)
            .map(|(_, value)| value.clone())
    };
    let context = Context::new(
        os,
        home.clone(),
        &app.join("keyguard"),
        &lookup,
        false,
        &app,
        None,
    )
    .unwrap();
    fs::write(&context.bundled_host, b"connector v1").unwrap();
    #[cfg(unix)]
    set_mode(&context.bundled_host, 0o755);
    Fixture {
        _temp: temp,
        home,
        context,
        registry: Memory::default(),
    }
}

impl Fixture {
    /// Leaves the traces of a browser's first run.
    fn use_browser(&self, browser: Browser) {
        let marker = &self.context.location(browser).markers[0];
        if browser.is_firefox() || browser.flatpak().is_some() {
            fs::create_dir_all(marker).unwrap();
        } else {
            fs::create_dir_all(marker.parent().unwrap()).unwrap();
            fs::write(marker, "{}").unwrap();
        }
    }

    fn status(&self) -> Report {
        status(&self.context, &self.registry).unwrap()
    }

    fn install(&mut self, browsers: &[Browser]) -> Result<Report> {
        install(&self.context, &mut self.registry, browsers)
    }

    fn uninstall(&mut self, browsers: &[Browser]) -> Result<Report> {
        uninstall(&self.context, &mut self.registry, browsers)
    }

    /// Replaces a string in the manifest's JSON text.
    fn edit_manifest(&self, browser: Browser, from: &str, to: &str) {
        let manifest = self.manifest(browser);
        let text = fs::read_to_string(&manifest).unwrap();
        let (from, to) = (json(from), json(to));
        assert!(text.contains(&from), "{from} in {text}");
        fs::write(&manifest, text.replace(&from, &to)).unwrap();
    }

    fn manifest(&self, browser: Browser) -> PathBuf {
        self.context.location(browser).manifest
    }

    fn read_manifest(&self, browser: Browser) -> Value {
        serde_json::from_slice(&fs::read(self.manifest(browser)).unwrap()).unwrap()
    }

    /// What the manifest starts, as a path: Windows joins paths with `\`.
    fn manifest_host(&self, browser: Browser) -> PathBuf {
        PathBuf::from(self.read_manifest(browser)["path"].as_str().unwrap())
    }

    /// Adds an extension to the manifest's allowlist.
    fn allow(&self, browser: Browser, extension: &str) {
        let mut manifest = self.read_manifest(browser);
        let key = if browser.is_firefox() {
            "allowed_extensions"
        } else {
            "allowed_origins"
        };
        manifest[key].as_array_mut().unwrap().push(extension.into());
        let text = serde_json::to_string_pretty(&manifest).unwrap() + "\n";
        fs::write(self.manifest(browser), text).unwrap();
    }
}

fn entry(report: &Report, browser: Browser) -> &BrowserStatus {
    report
        .browsers
        .iter()
        .find(|it| it.browser == browser)
        .unwrap()
}

fn state(report: &Report, browser: Browser) -> State {
    entry(report, browser).status
}

fn code(error: &anyhow::Error) -> String {
    error_json(error)["code"].as_str().unwrap().to_owned()
}

/// `value` as a JSON string, as manifests hold paths: Windows paths escape
/// their backslashes.
fn json(value: &str) -> String {
    serde_json::to_string(value).unwrap()
}

fn chromium_origins() -> Vec<&'static str> {
    CHROMIUM_ORIGINS.to_vec()
}

#[cfg(unix)]
fn mode(path: &Path) -> u32 {
    use std::os::unix::fs::PermissionsExt;
    fs::metadata(path).unwrap().permissions().mode() & 0o777
}

#[cfg(unix)]
fn set_mode(path: &Path, mode: u32) {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(mode)).unwrap();
}

#[test]
fn install_reports_repairs_and_uninstalls() {
    let mut fixture = fixture(Os::Linux, &[("XDG_CONFIG_HOME", "{home}/.config")]);
    fixture.use_browser(Browser::Chrome);

    let report = fixture.status();
    assert_eq!(report.version, 1);
    assert_eq!(report.packaging, "native");
    for browser in fixture.context.browsers() {
        let expected = match browser {
            Browser::Chrome => State::Missing,
            _ => State::BrowserAbsent,
        };
        assert_eq!(state(&report, browser), expected, "{browser:?}");
    }

    let report = fixture.install(&[]).unwrap();
    assert_eq!(state(&report, Browser::Chrome), State::Installed);
    assert_eq!(state(&report, Browser::Chromium), State::BrowserAbsent);
    // Only a used browser gets a manifest directory.
    assert!(!fixture.home.join(".config/chromium").exists());
    let host = fixture.context.bundled_host.to_str().unwrap();
    assert_eq!(entry(&report, Browser::Chrome).host.as_deref(), Some(host));
    assert_eq!(
        fixture.read_manifest(Browser::Chrome),
        serde_json::json!({
            "name": "com.artemchep.keyguard",
            "description": "Keyguard browser extension connector",
            "path": host,
            "type": "stdio",
            "allowed_origins": chromium_origins(),
        })
    );
    let text = fs::read_to_string(fixture.manifest(Browser::Chrome)).unwrap();
    assert!(
        text.starts_with("{\n  \"name\": \"com.artemchep.keyguard\",\n"),
        "{text}"
    );

    // A manifest that starts another connector, e.g. of a moved app.
    let manifest = fixture.manifest(Browser::Chrome);
    fixture.edit_manifest(Browser::Chrome, host, "/opt/old/keyguard-browser-host");
    let report = fixture.status();
    assert_eq!(state(&report, Browser::Chrome), State::Stale);
    assert_eq!(
        entry(&report, Browser::Chrome).host.as_deref(),
        Some("/opt/old/keyguard-browser-host")
    );
    // Content that differs only in its format is stale too.
    fs::write(&manifest, text.replace('\n', "")).unwrap();
    assert_eq!(state(&fixture.status(), Browser::Chrome), State::Stale);

    let report = fixture.install(&[Browser::Chrome]).unwrap();
    assert_eq!(state(&report, Browser::Chrome), State::Installed);
    assert_eq!(fs::read_to_string(&manifest).unwrap(), text);

    let report = fixture.uninstall(&[]).unwrap();
    assert_eq!(state(&report, Browser::Chrome), State::Missing);
    assert!(!manifest.exists());
    // The browser's directories stay.
    assert!(manifest.parent().unwrap().is_dir());
}

#[test]
fn installed_connectors_need_the_bundled_one() {
    let mut fixture = fixture(Os::Linux, &[]);
    fixture.use_browser(Browser::Vivaldi);
    fixture.install(&[]).unwrap();
    fs::remove_file(&fixture.context.bundled_host).unwrap();
    assert_eq!(state(&fixture.status(), Browser::Vivaldi), State::Stale);
    let error = fixture.install(&[]).unwrap_err();
    assert_eq!(code(&error), "host_missing");
    // Removal works without it.
    let report = fixture.uninstall(&[Browser::Vivaldi]).unwrap();
    assert_eq!(state(&report, Browser::Vivaldi), State::Missing);
}

#[cfg(unix)]
#[test]
fn installed_connectors_need_a_bundled_one_that_runs() {
    let mut fixture = fixture(Os::Linux, &[]);
    fixture.use_browser(Browser::Chrome);
    fixture.install(&[]).unwrap();
    set_mode(&fixture.context.bundled_host, 0o644);
    assert_eq!(state(&fixture.status(), Browser::Chrome), State::Stale);
    let error = fixture.install(&[]).unwrap_err();
    assert_eq!(code(&error), "host_missing");
    // The AppImage starts an executable copy instead.
    fixture.context.packaging = Packaging::AppImage;
    let report = fixture.install(&[]).unwrap();
    assert_eq!(state(&report, Browser::Chrome), State::Installed);
    assert_eq!(mode(&fixture.context.bundled_host), 0o644);
}

#[cfg(unix)]
#[test]
fn installs_write_nothing_for_a_bundled_connector_that_cannot_run() {
    let mut fixture = fixture(Os::Linux, &[]);
    fixture.use_browser(Browser::Chrome);
    set_mode(&fixture.context.bundled_host, 0o644);
    let error = fixture.install(&[]).unwrap_err();
    assert_eq!(code(&error), "host_missing");
    assert!(
        !fixture
            .context
            .location(Browser::Chrome)
            .directory()
            .exists()
    );
}

#[test]
fn keyguards_extensions_pass_the_connectors_checks() {
    for origin in CHROMIUM_ORIGINS.iter().chain([&DEV_EXTENSION_ORIGIN]) {
        assert!(is_chromium_origin(origin), "{origin}");
    }
    for id in FIREFOX_EXTENSIONS {
        assert!(is_firefox_extension_id(id), "{id}");
    }
}

#[test]
fn installs_rewrite_the_allowlist() {
    let mut fixture = fixture(Os::Linux, &[]);
    fixture.use_browser(Browser::Brave);
    fixture.use_browser(Browser::Firefox);
    fixture.install(&[]).unwrap();
    for browser in [Browser::Brave, Browser::Firefox] {
        let manifest = fixture.manifest(browser);
        let text = fs::read_to_string(&manifest).unwrap();
        // Another extension, such as one added by hand, needs repair.
        let extension = if browser.is_firefox() {
            "other@example.com"
        } else {
            EXTRA_ORIGIN
        };
        fixture.allow(browser, extension);
        let report = fixture.status();
        assert_eq!(state(&report, browser), State::Stale, "{browser:?}");
        let host = fixture.context.bundled_host.to_str().unwrap();
        assert_eq!(entry(&report, browser).host.as_deref(), Some(host));
        // A repair removes it.
        let report = fixture.install(&[]).unwrap();
        assert_eq!(state(&report, browser), State::Installed, "{browser:?}");
        assert_eq!(fs::read_to_string(&manifest).unwrap(), text);
    }
}

#[test]
fn each_channel_allows_only_its_own_extensions() {
    let mut fixture = fixture(Os::Linux, &[]);
    fixture.use_browser(Browser::Chrome);
    fixture.use_browser(Browser::Firefox);
    fixture.install(&[]).unwrap();
    let allowed =
        |fixture: &Fixture| fixture.read_manifest(Browser::Chrome)["allowed_origins"].clone();
    assert_eq!(allowed(&fixture), serde_json::json!(chromium_origins()));
    let text = fs::read_to_string(fixture.manifest(Browser::Chrome)).unwrap();
    assert!(!text.contains(DEV_EXTENSION_ORIGIN), "{text}");

    fixture.context.channel = Channel::Dev;
    let report = fixture.install(&[]).unwrap();
    assert_eq!(state(&report, Browser::Chrome), State::Installed);
    assert_eq!(allowed(&fixture), serde_json::json!([DEV_EXTENSION_ORIGIN]));
    // Firefox's development build has the same ID: the host's name keeps
    // the channels apart.
    let firefox = fixture.read_manifest(Browser::Firefox);
    assert_eq!(firefox["name"], "com.artemchep.keyguard.dev");
    assert_eq!(
        firefox["allowed_extensions"],
        serde_json::json!(["browser@keyguard.dev"])
    );
    assert_eq!(
        fixture.read_manifest(Browser::Chrome)["name"],
        "com.artemchep.keyguard.dev"
    );
    // The store listing in the dev manifest needs repair.
    fixture.allow(Browser::Chrome, CHROMIUM_ORIGINS[0]);
    assert_eq!(state(&fixture.status(), Browser::Chrome), State::Stale);
    fixture.install(&[]).unwrap();
    assert_eq!(allowed(&fixture), serde_json::json!([DEV_EXTENSION_ORIGIN]));

    // So does the development build in the release manifest.
    fixture.context.channel = Channel::Production;
    assert_eq!(state(&fixture.status(), Browser::Chrome), State::Installed);
    fixture.allow(Browser::Chrome, DEV_EXTENSION_ORIGIN);
    assert_eq!(state(&fixture.status(), Browser::Chrome), State::Stale);
    fixture.install(&[]).unwrap();
    assert_eq!(allowed(&fixture), serde_json::json!(chromium_origins()));
}

/// Every file under `folder`, with what it holds.
fn files(folder: &Path) -> BTreeMap<PathBuf, Vec<u8>> {
    let mut files = BTreeMap::new();
    let mut folders = vec![folder.to_owned()];
    while let Some(folder) = folders.pop() {
        for entry in fs::read_dir(&folder).unwrap() {
            let path = entry.unwrap().path();
            if path.is_dir() {
                folders.push(path);
            } else {
                files.insert(path.clone(), fs::read(&path).unwrap());
            }
        }
    }
    files
}

#[test]
fn release_and_dev_registrations_leave_each_other_alone() {
    let mut fixture = fixture(
        Os::Linux,
        &[
            ("APPIMAGE", "{home}/Keyguard.AppImage"),
            ("APPDIR", "{app}"),
            ("XDG_DATA_HOME", "{home}/data"),
        ],
    );
    let browsers = [Browser::Chrome, Browser::Firefox, Browser::BraveFlatpak];
    for browser in browsers {
        fixture.use_browser(browser);
    }
    fixture.install(&[]).unwrap();
    let release = files(&fixture.home);
    let release_copy = fixture.context.host_copy().unwrap();
    assert!(release.contains_key(&release_copy));
    // The release registration is not the dev one's to repair.
    fixture.context.channel = Channel::Dev;
    let report = fixture.status();
    for browser in browsers {
        assert_eq!(state(&report, browser), State::Missing, "{browser:?}");
    }

    let report = fixture.install(&[]).unwrap();
    let both = files(&fixture.home);
    for (path, bytes) in &release {
        assert_eq!(both.get(path), Some(bytes), "{path:?}");
    }
    let dev_copy = fixture.context.host_copy().unwrap();
    assert_eq!(
        dev_copy,
        release_copy.with_file_name("keyguard-browser-host-dev")
    );
    for browser in browsers {
        assert_eq!(state(&report, browser), State::Installed, "{browser:?}");
        let location = fixture.context.location(browser);
        assert!(!release.contains_key(&location.manifest), "{browser:?}");
        assert!(both.contains_key(&location.manifest), "{browser:?}");
        let host = location.host_copy.unwrap_or_else(|| dev_copy.clone());
        assert_eq!(fixture.manifest_host(browser), host);
        assert!(!release.contains_key(&host), "{host:?}");
    }
    // Nor is the dev registration the release one's.
    fixture.context.channel = Channel::Production;
    let report = fixture.status();
    for browser in browsers {
        assert_eq!(state(&report, browser), State::Installed, "{browser:?}");
    }

    // Removing one leaves the other, the AppImage's copy included.
    fixture.context.channel = Channel::Dev;
    let report = fixture.uninstall(&[]).unwrap();
    for browser in browsers {
        assert_eq!(state(&report, browser), State::Missing, "{browser:?}");
    }
    assert_eq!(files(&fixture.home), release);
    fixture.install(&[]).unwrap();
    fixture.context.channel = Channel::Production;
    fixture.uninstall(&[]).unwrap();
    assert!(!release_copy.exists());
    assert!(dev_copy.is_file());
    fixture.context.channel = Channel::Dev;
    let report = fixture.status();
    for browser in browsers {
        assert_eq!(state(&report, browser), State::Installed, "{browser:?}");
    }
}

#[test]
fn windows_registers_each_channel_under_its_own_key() {
    let mut fixture = fixture(
        Os::Windows,
        &[
            ("LOCALAPPDATA", "{home}/AppData/Local"),
            ("APPDATA", "{home}/AppData/Roaming"),
        ],
    );
    fixture.use_browser(Browser::Chrome);
    fixture.install(&[]).unwrap();
    let release = fixture.manifest(Browser::Chrome);
    fixture.context.channel = Channel::Dev;
    fixture.install(&[]).unwrap();
    let dev = fixture.manifest(Browser::Chrome);
    assert_ne!(release, dev);
    let key = "Software\\Google\\Chrome\\NativeMessagingHosts\\com.artemchep.keyguard";
    assert_eq!(
        fixture.registry.0,
        [
            (key.to_owned(), release.to_str().unwrap().to_owned()),
            (format!("{key}.dev"), dev.to_str().unwrap().to_owned()),
        ]
        .into()
    );
    fixture.uninstall(&[]).unwrap();
    assert!(release.is_file());
    assert!(!dev.exists());
    assert_eq!(fixture.registry.0.len(), 1);
    assert!(fixture.registry.0.contains_key(key));
}

#[test]
fn explicit_browsers_must_be_found() {
    let mut fixture = fixture(Os::Linux, &[]);
    fixture.use_browser(Browser::Chrome);
    let error = fixture
        .install(&[Browser::Chrome, Browser::Firefox])
        .unwrap_err();
    assert_eq!(
        error_json(&error)["browser"],
        serde_json::json!("firefox"),
        "{error:#}"
    );
    assert_eq!(code(&error), "browser_absent");
    // Nothing was written.
    assert!(!fixture.manifest(Browser::Chrome).exists());
}

#[test]
fn foreign_files_are_left_alone() {
    let mut fixture = fixture(Os::Linux, &[]);
    fixture.use_browser(Browser::Chromium);
    let manifest = fixture.manifest(Browser::Chromium);
    fs::create_dir_all(manifest.parent().unwrap()).unwrap();
    for foreign in ["{\"name\":\"org.example.host\"}", "not JSON"] {
        fs::write(&manifest, foreign).unwrap();
        assert_eq!(state(&fixture.status(), Browser::Chromium), State::Stale);
        let error = fixture.install(&[]).unwrap_err();
        assert_eq!(code(&error), "foreign_file");
        assert_eq!(error_json(&error)["path"], manifest.to_str().unwrap());
        let error = fixture.uninstall(&[]).unwrap_err();
        assert_eq!(code(&error), "foreign_file");
        assert_eq!(fs::read_to_string(&manifest).unwrap(), foreign);
    }
}

#[cfg(unix)]
#[test]
fn links_are_never_followed_for_writes() {
    let mut fixture = fixture(Os::Linux, &[]);
    fixture.use_browser(Browser::Edge);
    let directory = fixture
        .home
        .join(".config/microsoft-edge/NativeMessagingHosts");
    let elsewhere = fixture.home.join("elsewhere");
    fs::create_dir(&elsewhere).unwrap();
    std::os::unix::fs::symlink(&elsewhere, &directory).unwrap();
    let error = fixture.install(&[]).unwrap_err();
    assert_eq!(code(&error), "unsafe_file");
    assert_eq!(fs::read_dir(&elsewhere).unwrap().count(), 0);
    assert_eq!(state(&fixture.status(), Browser::Edge), State::Missing);
}

#[cfg(unix)]
#[test]
fn unsafe_manifests_are_never_current() {
    let mut fixture = fixture(Os::Linux, &[]);
    fixture.use_browser(Browser::Chrome);
    fixture.install(&[]).unwrap();
    let manifest = fixture.manifest(Browser::Chrome);
    set_mode(&manifest, 0o666);
    assert_eq!(state(&fixture.status(), Browser::Chrome), State::Stale);
    let error = fixture.install(&[]).unwrap_err();
    assert_eq!(code(&error), "unsafe_file");

    // A link to a manifest with the same contents.
    set_mode(&manifest, 0o644);
    let elsewhere = fixture.home.join("elsewhere.json");
    fs::rename(&manifest, &elsewhere).unwrap();
    std::os::unix::fs::symlink(&elsewhere, &manifest).unwrap();
    assert_eq!(state(&fixture.status(), Browser::Chrome), State::Stale);
}

#[cfg(unix)]
#[test]
fn broken_manifest_links_need_repair() {
    let mut fixture = fixture(Os::Linux, &[]);
    fixture.use_browser(Browser::Chrome);
    for browser in [Browser::Chrome, Browser::Chromium] {
        let manifest = fixture.manifest(browser);
        fs::create_dir_all(manifest.parent().unwrap()).unwrap();
        std::os::unix::fs::symlink(fixture.home.join("missing.json"), &manifest).unwrap();
    }
    // Like install, which refuses the link, even of a browser not found.
    let report = fixture.status();
    assert_eq!(state(&report, Browser::Chrome), State::Stale);
    assert_eq!(state(&report, Browser::Chromium), State::Stale);
    assert_eq!(entry(&report, Browser::Chrome).host, None);
    let error = fixture.install(&[]).unwrap_err();
    assert_eq!(code(&error), "unsafe_file");
    let manifest = fixture.manifest(Browser::Chrome);
    assert!(fs::symlink_metadata(&manifest).unwrap().is_symlink());
}

#[cfg(unix)]
#[test]
fn unused_files_that_cannot_be_removed_safely_stay() {
    let mut fixture = fixture(Os::Linux, &[("XDG_DATA_HOME", "{home}/data")]);
    fixture.use_browser(Browser::Chrome);
    // A link to a wrapper of the Flatpak build, which no manifest starts.
    let location = fixture.context.location(Browser::Chrome);
    fs::create_dir_all(location.directory()).unwrap();
    let elsewhere = fixture.home.join("wrapper.sh");
    fs::write(&elsewhere, format!("{WRAPPER_HEADER}exec flatpak run x\n")).unwrap();
    let wrapper = fixture.context.wrapper(&location);
    std::os::unix::fs::symlink(&elsewhere, &wrapper).unwrap();
    // A link to an AppImage's copy of the connector, which no manifest
    // starts.
    let copy = fixture.context.host_copy().unwrap();
    fs::create_dir_all(copy.parent().unwrap()).unwrap();
    std::os::unix::fs::symlink(&fixture.context.bundled_host, &copy).unwrap();

    let report = fixture.install(&[]).unwrap();
    assert_eq!(state(&report, Browser::Chrome), State::Installed);
    assert_eq!(
        fixture.manifest_host(Browser::Chrome),
        fixture.context.bundled_host
    );
    for path in [wrapper, copy] {
        assert!(
            fs::symlink_metadata(&path).unwrap().is_symlink(),
            "{path:?}"
        );
    }
    assert!(elsewhere.is_file());
}

#[test]
fn firefox_reads_manifests_from_dot_mozilla() {
    let mut fixture = fixture(Os::Linux, &[("XDG_CONFIG_HOME", "{home}/xdg")]);
    // Firefox 147 and later create new profiles in XDG_CONFIG_HOME.
    fs::create_dir_all(fixture.home.join("xdg/mozilla/firefox")).unwrap();
    let report = fixture.install(&[]).unwrap();
    assert_eq!(state(&report, Browser::Firefox), State::Installed);
    assert_eq!(
        fixture.manifest(Browser::Firefox),
        fixture
            .home
            .join(".mozilla/native-messaging-hosts/com.artemchep.keyguard.json")
    );
    let manifest = fixture.read_manifest(Browser::Firefox);
    assert_eq!(
        manifest["allowed_extensions"],
        serde_json::json!(["browser@keyguard.dev"])
    );
    assert!(manifest.get("allowed_origins").is_none());
    // Firefox's legacy layout is chosen by ~/.mozilla/firefox, not ~/.mozilla.
    assert!(!fixture.home.join(".mozilla/firefox").exists());
}

#[test]
fn flatpak_manifests_start_a_wrapper() {
    let mut fixture = fixture(
        Os::Linux,
        &[
            ("FLATPAK_ID", "com.artemchep.keyguard"),
            (
                "XDG_CONFIG_HOME",
                "{home}/.var/app/com.artemchep.keyguard/config",
            ),
        ],
    );
    // The sandbox sees only the manifest directories that exist on the
    // host, so only they show that a browser is used: not Firefox's profiles.
    let directory = fixture
        .home
        .join(".config")
        .join("google-chrome")
        .join("NativeMessagingHosts");
    fs::create_dir_all(&directory).unwrap();
    fs::create_dir_all(fixture.home.join(".mozilla").join("firefox")).unwrap();
    let report = fixture.status();
    assert_eq!(report.packaging, "flatpak");
    assert_eq!(state(&report, Browser::Chrome), State::Missing);
    assert_eq!(state(&report, Browser::Firefox), State::BrowserAbsent);
    let error = fixture.install(&[Browser::Firefox]).unwrap_err();
    assert_eq!(code(&error), "browser_absent");

    let report = fixture.install(&[]).unwrap();
    assert_eq!(state(&report, Browser::Chrome), State::Installed);
    for browser in fixture.context.browsers() {
        if browser != Browser::Chrome {
            assert_eq!(state(&report, browser), State::BrowserAbsent, "{browser:?}");
            assert!(!fixture.context.location(browser).directory().exists());
        }
    }
    let wrapper = directory.join("com.artemchep.keyguard-host.sh");
    let host = fixture.context.bundled_host.to_str().unwrap().to_owned();
    let script = format!(
        "#!/bin/sh\n\
         # Written by Keyguard: starts its browser extension connector in the Flatpak sandbox.\n\
         exec flatpak run --command='{host}' 'com.artemchep.keyguard' \"$@\"\n"
    );
    assert_eq!(fs::read_to_string(&wrapper).unwrap(), script);
    #[cfg(unix)]
    assert_eq!(mode(&wrapper), 0o755);
    assert_eq!(fixture.manifest_host(Browser::Chrome), wrapper);

    fs::write(
        &wrapper,
        script.replace("com.artemchep.keyguard'", "other'"),
    )
    .unwrap();
    assert_eq!(state(&fixture.status(), Browser::Chrome), State::Stale);
    fixture.install(&[]).unwrap();
    assert_eq!(fs::read_to_string(&wrapper).unwrap(), script);

    fs::write(&wrapper, "#!/bin/sh\nexec something-else\n").unwrap();
    let error = fixture.install(&[]).unwrap_err();
    assert_eq!(code(&error), "foreign_file");
    fs::write(&wrapper, &script).unwrap();

    let report = fixture.uninstall(&[]).unwrap();
    assert_eq!(state(&report, Browser::Chrome), State::Missing);
    assert!(!wrapper.exists());
    assert!(directory.is_dir());
}

#[test]
fn a_native_install_removes_a_flatpak_wrapper() {
    let mut fixture = fixture(Os::Linux, &[]);
    fixture.use_browser(Browser::Chrome);
    let location = fixture.context.location(Browser::Chrome);
    fs::create_dir_all(location.directory()).unwrap();
    let wrapper = fixture.context.wrapper(&location);
    fs::write(&wrapper, format!("{WRAPPER_HEADER}exec flatpak run x\n")).unwrap();
    fixture.install(&[]).unwrap();
    assert!(!wrapper.exists());
}

#[test]
fn appimage_manifests_start_a_copy() {
    let mut fixture = fixture(
        Os::Linux,
        &[
            ("APPIMAGE", "{home}/Keyguard.AppImage"),
            ("APPDIR", "{app}"),
            ("XDG_DATA_HOME", "{home}/data"),
        ],
    );
    fixture.use_browser(Browser::Chromium);
    fixture.use_browser(Browser::Firefox);
    let copy = fixture
        .home
        .join("data")
        .join("keyguard")
        .join("browser-host")
        .join("keyguard-browser-host");

    let report = fixture.install(&[]).unwrap();
    assert_eq!(report.packaging, "appimage");
    assert_eq!(state(&report, Browser::Chromium), State::Installed);
    assert_eq!(fs::read(&copy).unwrap(), b"connector v1");
    #[cfg(unix)]
    assert_eq!(mode(&copy), 0o755);
    for browser in [Browser::Chromium, Browser::Firefox] {
        assert_eq!(fixture.manifest_host(browser), copy);
    }

    // An updated AppImage bundles another connector.
    fs::write(&fixture.context.bundled_host, b"connector v2").unwrap();
    assert_eq!(state(&fixture.status(), Browser::Chromium), State::Stale);
    let report = fixture.install(&[]).unwrap();
    assert_eq!(state(&report, Browser::Firefox), State::Installed);
    assert_eq!(fs::read(&copy).unwrap(), b"connector v2");

    // The copy stays while a manifest starts it.
    fixture.uninstall(&[Browser::Chromium]).unwrap();
    assert!(copy.exists());
    fixture.uninstall(&[Browser::Firefox]).unwrap();
    assert!(!copy.exists());

    // A native build's install removes the AppImage's copy.
    fixture.install(&[]).unwrap();
    assert!(copy.exists());
    fixture.context.packaging = Packaging::Native;
    let report = fixture.install(&[]).unwrap();
    assert_eq!(state(&report, Browser::Firefox), State::Installed);
    assert!(!copy.exists());
}

#[test]
fn appimage_installs_need_their_data_folder() {
    let mut fixture = fixture(
        Os::Linux,
        &[
            ("APPIMAGE", "{home}/Keyguard.AppImage"),
            ("APPDIR", "{app}"),
        ],
    );
    // XDG_DATA_HOME outside the home, such as on a drive that is not
    // mounted.
    let data_home = fixture._temp.path().join("data");
    fixture.context.data_home = data_home.clone();
    fixture.use_browser(Browser::Chromium);
    let error = fixture.install(&[]).unwrap_err();
    assert_eq!(code(&error), "unsafe_file");
    // No manifest starts a copy that is not there.
    assert!(!fixture.manifest(Browser::Chromium).exists());
    assert!(!data_home.exists());
}

#[test]
fn installs_and_uninstalls_wait_for_each_other() {
    let mut fixture = fixture(Os::Linux, &[]);
    fixture.use_browser(Browser::FirefoxFlatpak);
    let manifest = fixture.manifest(Browser::FirefoxFlatpak);
    let lock_file = fixture.context.lock_file();
    // Another process, such as the app, runs a command.
    let lock = || {
        keyguard_agent_setup::wait_for_lock(&lock_file, Duration::ZERO)
            .unwrap()
            .unwrap()
    };
    let other = lock();
    std::thread::scope(|scope| {
        let install = scope.spawn(|| fixture.install(&[]).map(|_| ()));
        std::thread::sleep(Duration::from_millis(500));
        assert!(!install.is_finished());
        assert!(!manifest.exists());
        drop(other);
        install.join().unwrap().unwrap();
    });
    assert!(manifest.exists());
    let other = lock();
    std::thread::scope(|scope| {
        let uninstall = scope.spawn(|| fixture.uninstall(&[]).map(|_| ()));
        std::thread::sleep(Duration::from_millis(500));
        assert!(!uninstall.is_finished());
        assert!(manifest.exists());
        drop(other);
        uninstall.join().unwrap().unwrap();
    });
    assert!(!manifest.exists());
    // Only Keyguard's own folder holds the lock.
    assert_eq!(lock_file, fixture.home.join(".keyguard/browsers.lock"));
}

#[cfg(unix)]
fn appimage_fixture() -> (Fixture, PathBuf) {
    let mut fixture = fixture(
        Os::Linux,
        &[
            ("APPIMAGE", "{home}/Keyguard.AppImage"),
            ("APPDIR", "{app}"),
            ("XDG_DATA_HOME", "{home}/data"),
        ],
    );
    fixture.use_browser(Browser::Chromium);
    fixture.install(&[]).unwrap();
    let copy = fixture.context.host_copy().unwrap();
    (fixture, copy)
}

#[cfg(unix)]
#[test]
fn a_repair_makes_the_appimage_copy_executable() {
    let (mut fixture, copy) = appimage_fixture();
    set_mode(&copy, 0o644);
    assert_eq!(state(&fixture.status(), Browser::Chromium), State::Stale);
    let report = fixture.install(&[]).unwrap();
    assert_eq!(state(&report, Browser::Chromium), State::Installed);
    assert_eq!(mode(&copy), 0o755);
}

#[cfg(unix)]
#[test]
fn unsafe_appimage_copies_are_not_reused() {
    let (mut fixture, copy) = appimage_fixture();
    set_mode(&copy, 0o757);
    assert_eq!(state(&fixture.status(), Browser::Chromium), State::Stale);
    let error = fixture.install(&[]).unwrap_err();
    assert_eq!(code(&error), "unsafe_file");
    assert_eq!(mode(&copy), 0o757);

    // A link to a file with the same contents.
    let elsewhere = fixture.home.join("elsewhere");
    fs::copy(&fixture.context.bundled_host, &elsewhere).unwrap();
    fs::remove_file(&copy).unwrap();
    std::os::unix::fs::symlink(&elsewhere, &copy).unwrap();
    assert_eq!(state(&fixture.status(), Browser::Chromium), State::Stale);
    let error = fixture.install(&[]).unwrap_err();
    assert_eq!(code(&error), "unsafe_file");
    assert!(fs::symlink_metadata(&copy).unwrap().is_symlink());
}

#[test]
fn flatpak_browsers_start_a_copy_in_their_folder() {
    let mut fixture = fixture(Os::Linux, &[]);
    fixture.use_browser(Browser::ChromeFlatpak);
    let directory = fixture
        .home
        .join(".var/app/com.google.Chrome/config/google-chrome/NativeMessagingHosts");
    let copy = directory.join("keyguard-browser-host");
    let report = fixture.status();
    assert_eq!(state(&report, Browser::ChromeFlatpak), State::Missing);
    assert_eq!(state(&report, Browser::Chrome), State::BrowserAbsent);

    let report = fixture.install(&[]).unwrap();
    assert_eq!(state(&report, Browser::ChromeFlatpak), State::Installed);
    assert_eq!(
        fixture.manifest(Browser::ChromeFlatpak),
        directory.join("com.artemchep.keyguard.json")
    );
    assert_eq!(fixture.manifest_host(Browser::ChromeFlatpak), copy);
    assert_eq!(
        entry(&report, Browser::ChromeFlatpak).host.as_deref(),
        copy.to_str()
    );
    assert_eq!(
        fixture.read_manifest(Browser::ChromeFlatpak)["allowed_origins"],
        serde_json::json!(chromium_origins())
    );
    assert_eq!(fs::read(&copy).unwrap(), b"connector v1");
    #[cfg(unix)]
    assert_eq!(mode(&copy), 0o755);
    assert!(!fixture.home.join(".config/google-chrome").exists());

    // An updated Keyguard bundles another connector.
    fs::write(&fixture.context.bundled_host, b"connector v2").unwrap();
    assert_eq!(
        state(&fixture.status(), Browser::ChromeFlatpak),
        State::Stale
    );
    let report = fixture.install(&[]).unwrap();
    assert_eq!(state(&report, Browser::ChromeFlatpak), State::Installed);
    assert_eq!(fs::read(&copy).unwrap(), b"connector v2");
    #[cfg(unix)]
    {
        set_mode(&copy, 0o644);
        assert_eq!(
            state(&fixture.status(), Browser::ChromeFlatpak),
            State::Stale
        );
        fixture.install(&[]).unwrap();
        assert_eq!(mode(&copy), 0o755);
    }

    let report = fixture.uninstall(&[Browser::ChromeFlatpak]).unwrap();
    assert_eq!(state(&report, Browser::ChromeFlatpak), State::Missing);
    assert!(!fixture.manifest(Browser::ChromeFlatpak).exists());
    assert!(!copy.exists());
    // The browser's folders stay.
    assert!(directory.is_dir());
}

#[test]
fn each_build_of_a_browser_has_its_own_manifest() {
    let mut fixture = fixture(Os::Linux, &[]);
    fixture.use_browser(Browser::Firefox);
    fixture.use_browser(Browser::FirefoxFlatpak);
    let report = fixture.install(&[]).unwrap();
    for browser in [Browser::Firefox, Browser::FirefoxFlatpak] {
        assert_eq!(state(&report, browser), State::Installed, "{browser:?}");
        assert_eq!(
            fixture.read_manifest(browser)["allowed_extensions"],
            serde_json::json!(FIREFOX_EXTENSIONS)
        );
    }
    assert_eq!(
        fixture.manifest(Browser::FirefoxFlatpak),
        fixture.home.join(
            ".var/app/org.mozilla.firefox/.mozilla/native-messaging-hosts/com.artemchep.keyguard.json"
        )
    );
    assert_eq!(
        fixture.manifest_host(Browser::Firefox),
        fixture.context.bundled_host
    );
    let copy = fixture
        .context
        .location(Browser::FirefoxFlatpak)
        .host_copy
        .unwrap();
    assert_eq!(fixture.manifest_host(Browser::FirefoxFlatpak), copy);

    // Removing one build leaves the other.
    let report = fixture.uninstall(&[Browser::Firefox]).unwrap();
    assert_eq!(state(&report, Browser::Firefox), State::Missing);
    assert_eq!(state(&report, Browser::FirefoxFlatpak), State::Installed);
    assert!(copy.is_file());
}

#[test]
fn appimage_copies_the_connector_into_flatpak_browsers() {
    let mut fixture = fixture(
        Os::Linux,
        &[
            ("APPIMAGE", "{home}/Keyguard.AppImage"),
            ("APPDIR", "{app}"),
            ("XDG_DATA_HOME", "{home}/data"),
        ],
    );
    fixture.use_browser(Browser::EdgeFlatpak);
    let shared = fixture.context.host_copy().unwrap();
    let copy = fixture
        .context
        .location(Browser::EdgeFlatpak)
        .host_copy
        .unwrap();
    let report = fixture.install(&[]).unwrap();
    assert_eq!(report.packaging, "appimage");
    assert_eq!(state(&report, Browser::EdgeFlatpak), State::Installed);
    assert_eq!(fixture.manifest_host(Browser::EdgeFlatpak), copy);
    assert_eq!(fs::read(&copy).unwrap(), b"connector v1");
    // No other manifest starts the AppImage's own copy.
    assert!(!shared.exists());

    fixture.use_browser(Browser::Edge);
    fixture.install(&[]).unwrap();
    assert_eq!(fixture.manifest_host(Browser::Edge), shared);
    assert!(shared.is_file());
    fixture.uninstall(&[Browser::Edge]).unwrap();
    assert!(!shared.exists());
    assert!(copy.is_file());
}

#[test]
fn flatpak_builds_set_up_the_flatpak_browsers_they_see() {
    let mut fixture = fixture(
        Os::Linux,
        &[
            ("FLATPAK_ID", "com.artemchep.keyguard"),
            (
                "XDG_CONFIG_HOME",
                "{home}/.var/app/com.artemchep.keyguard/config",
            ),
        ],
    );
    // The sandbox sees the folder of manifests only if it exists, not the
    // browser's folder.
    fs::create_dir_all(flatpak::BRAVE.folder(&fixture.home)).unwrap();
    assert_eq!(
        state(&fixture.status(), Browser::BraveFlatpak),
        State::BrowserAbsent
    );
    let directory = flatpak::BRAVE.manifests(&fixture.home);
    fs::create_dir_all(&directory).unwrap();
    let report = fixture.install(&[]).unwrap();
    assert_eq!(state(&report, Browser::BraveFlatpak), State::Installed);
    assert!(fixture.context.roots().contains(&directory));
    // The browser's sandbox can't run `flatpak run`, so no wrapper.
    let copy = directory.join("keyguard-browser-host");
    assert_eq!(fixture.manifest_host(Browser::BraveFlatpak), copy);
    assert_eq!(fs::read(&copy).unwrap(), b"connector v1");
    assert!(!directory.join("com.artemchep.keyguard-host.sh").exists());

    let report = fixture.uninstall(&[]).unwrap();
    assert_eq!(state(&report, Browser::BraveFlatpak), State::Missing);
    assert!(!copy.exists());
}

#[cfg(unix)]
#[test]
fn unsafe_flatpak_browser_copies_are_not_reused() {
    let mut fixture = fixture(Os::Linux, &[]);
    fixture.use_browser(Browser::ChromiumFlatpak);
    fixture.install(&[]).unwrap();
    let copy = fixture
        .context
        .location(Browser::ChromiumFlatpak)
        .host_copy
        .unwrap();
    // A link to a file with the same contents.
    let elsewhere = fixture.home.join("elsewhere");
    fs::copy(&fixture.context.bundled_host, &elsewhere).unwrap();
    fs::remove_file(&copy).unwrap();
    std::os::unix::fs::symlink(&elsewhere, &copy).unwrap();
    assert_eq!(
        state(&fixture.status(), Browser::ChromiumFlatpak),
        State::Stale
    );
    let error = fixture.install(&[]).unwrap_err();
    assert_eq!(code(&error), "unsafe_file");
    assert!(fs::symlink_metadata(&copy).unwrap().is_symlink());

    // Removal takes the manifest; the link, which can do no harm, stays.
    let report = fixture.uninstall(&[]).unwrap();
    assert_eq!(state(&report, Browser::ChromiumFlatpak), State::Missing);
    assert!(fs::symlink_metadata(&copy).unwrap().is_symlink());
    assert!(elsewhere.is_file());
}

#[test]
fn only_linux_lists_flatpak_browsers() {
    for os in [Os::Mac, Os::Windows] {
        let mut fixture = fixture(
            os,
            &[
                ("LOCALAPPDATA", "{home}/AppData/Local"),
                ("APPDATA", "{home}/AppData/Roaming"),
            ],
        );
        let report = fixture.status();
        assert_eq!(report.browsers.len(), 6, "{os:?}");
        assert!(
            report
                .browsers
                .iter()
                .all(|it| it.browser.flatpak().is_none())
        );
        fs::create_dir_all(flatpak::CHROME.folder(&fixture.home)).unwrap();
        let error = fixture.install(&[Browser::ChromeFlatpak]).unwrap_err();
        assert_eq!(code(&error), "browser_absent");
        // Nor are they removed, as from a home that Linux shares.
        let manifest = fixture.manifest(Browser::FirefoxFlatpak);
        fs::create_dir_all(manifest.parent().unwrap()).unwrap();
        fs::write(&manifest, "{\"name\":\"com.artemchep.keyguard\"}").unwrap();
        let error = fixture
            .uninstall(&[Browser::Firefox, Browser::FirefoxFlatpak])
            .unwrap_err();
        assert_eq!(code(&error), "browser_absent");
        assert_eq!(error_json(&error)["browser"], "firefox-flatpak");
        assert!(manifest.exists());
    }
    assert_eq!(fixture(Os::Linux, &[]).status().browsers.len(), 11);
}

/// A file with the name of the connector in a Flatpak browser's folder that
/// Keyguard didn't put there.
fn foreign_copy(fixture: &Fixture, browser: Browser) -> PathBuf {
    let copy = fixture.context.location(browser).host_copy.unwrap();
    fs::create_dir_all(copy.parent().unwrap()).unwrap();
    fs::write(&copy, b"#!/bin/sh\nexec my-own-host\n").unwrap();
    copy
}

#[test]
fn a_copy_that_keyguard_did_not_write_is_left_alone() {
    let mut fixture = fixture(Os::Linux, &[]);
    fixture.use_browser(Browser::EdgeFlatpak);
    let copy = foreign_copy(&fixture, Browser::EdgeFlatpak);
    let error = fixture.install(&[]).unwrap_err();
    assert_eq!(code(&error), "foreign_file");
    assert_eq!(error_json(&error)["path"], copy.to_str().unwrap());
    let error = fixture.uninstall(&[Browser::EdgeFlatpak]).unwrap_err();
    assert_eq!(code(&error), "foreign_file");
    assert_eq!(fs::read(&copy).unwrap(), b"#!/bin/sh\nexec my-own-host\n");
    assert!(!fixture.manifest(Browser::EdgeFlatpak).exists());

    // A copy that Keyguard's manifest starts is Keyguard's, even an older
    // one, and one of the bundled connector is too.
    fs::remove_file(&copy).unwrap();
    fixture.install(&[]).unwrap();
    fs::write(&copy, b"connector v0").unwrap();
    let report = fixture.install(&[]).unwrap();
    assert_eq!(state(&report, Browser::EdgeFlatpak), State::Installed);
    assert_eq!(fs::read(&copy).unwrap(), b"connector v1");
    fs::remove_file(fixture.manifest(Browser::EdgeFlatpak)).unwrap();
    fixture.uninstall(&[Browser::EdgeFlatpak]).unwrap();
    assert!(!copy.exists());
}

#[test]
fn changes_to_one_browser_leave_the_copies_of_the_others() {
    let mut fixture = fixture(Os::Linux, &[]);
    fixture.use_browser(Browser::Chrome);
    fixture.use_browser(Browser::FirefoxFlatpak);
    fixture.use_browser(Browser::BraveFlatpak);
    // An install that stopped after its copy, before its manifest.
    let brave = fixture.context.location(Browser::BraveFlatpak);
    let copy = brave.host_copy.clone().unwrap();
    fs::create_dir_all(copy.parent().unwrap()).unwrap();
    fs::copy(&fixture.context.bundled_host, &copy).unwrap();
    // A file of the user's.
    let firefox = foreign_copy(&fixture, Browser::FirefoxFlatpak);

    fixture.install(&[Browser::Chrome]).unwrap();
    fixture.uninstall(&[Browser::Chrome]).unwrap();
    assert!(copy.is_file());
    assert_eq!(
        fs::read(&firefox).unwrap(),
        b"#!/bin/sh\nexec my-own-host\n"
    );
    // A later install reuses it.
    let report = fixture.install(&[Browser::BraveFlatpak]).unwrap();
    assert_eq!(state(&report, Browser::BraveFlatpak), State::Installed);
}

#[cfg(unix)]
#[test]
fn reports_follow_no_link_in_a_flatpak_browser() {
    let mut fixture = fixture(Os::Linux, &[]);
    fixture.use_browser(Browser::FirefoxFlatpak);
    fixture.install(&[]).unwrap();
    // The browser swaps its folder of manifests for a link to a folder of
    // the user's that holds Keyguard's files.
    let location = fixture.context.location(Browser::FirefoxFlatpak);
    let elsewhere = fixture.home.join("elsewhere");
    fs::create_dir(&elsewhere).unwrap();
    for name in ["com.artemchep.keyguard.json", "keyguard-browser-host"] {
        fs::copy(location.directory().join(name), elsewhere.join(name)).unwrap();
    }
    let folder = location.directory().to_owned();
    fs::rename(&folder, folder.with_file_name("moved")).unwrap();
    std::os::unix::fs::symlink(&elsewhere, &folder).unwrap();

    let report = fixture.status();
    let firefox = entry(&report, Browser::FirefoxFlatpak);
    assert_eq!(firefox.status, State::Stale);
    assert_eq!(firefox.host, None);
    assert_eq!(code(&fixture.install(&[]).unwrap_err()), "unsafe_file");
    assert_eq!(code(&fixture.uninstall(&[]).unwrap_err()), "unsafe_file");
    assert_eq!(fs::read_dir(&elsewhere).unwrap().count(), 2);
}

#[test]
fn windows_registers_each_manifest() {
    let mut fixture = fixture(
        Os::Windows,
        &[
            ("LOCALAPPDATA", "{home}/AppData/Local"),
            ("APPDATA", "{home}/AppData/Roaming"),
        ],
    );
    for browser in [Browser::Chrome, Browser::Brave, Browser::Firefox] {
        fixture.use_browser(browser);
    }
    let chrome_key = "Software\\Google\\Chrome\\NativeMessagingHosts\\com.artemchep.keyguard";
    let firefox_key = "Software\\Mozilla\\NativeMessagingHosts\\com.artemchep.keyguard";

    let report = fixture.install(&[]).unwrap();
    for browser in [Browser::Chrome, Browser::Brave, Browser::Firefox] {
        assert_eq!(state(&report, browser), State::Installed, "{browser:?}");
    }
    assert_eq!(state(&report, Browser::Edge), State::BrowserAbsent);
    assert_eq!(
        entry(&report, Browser::Brave).registry_key.as_deref(),
        Some(&*format!("HKEY_CURRENT_USER\\{chrome_key}"))
    );
    // Brave reads Chrome's key.
    assert_eq!(
        fixture.manifest(Browser::Brave),
        fixture.manifest(Browser::Chrome)
    );
    assert_eq!(
        fixture.registry.0,
        [
            (
                chrome_key.to_owned(),
                fixture
                    .manifest(Browser::Chrome)
                    .to_str()
                    .unwrap()
                    .to_owned()
            ),
            (
                firefox_key.to_owned(),
                fixture
                    .manifest(Browser::Firefox)
                    .to_str()
                    .unwrap()
                    .to_owned()
            ),
        ]
        .into()
    );

    fixture.registry.0.remove(chrome_key);
    assert_eq!(state(&fixture.status(), Browser::Chrome), State::Stale);
    fixture.install(&[Browser::Chrome]).unwrap();
    assert_eq!(state(&fixture.status(), Browser::Chrome), State::Installed);

    // A key without a manifest is missing for each browser that reads it,
    // and install repairs it.
    fixture.use_browser(Browser::Vivaldi);
    let shared = [Browser::Chrome, Browser::Brave, Browser::Vivaldi];
    fs::remove_file(fixture.manifest(Browser::Chrome)).unwrap();
    let report = fixture.status();
    for browser in shared {
        assert_eq!(state(&report, browser), State::Missing, "{browser:?}");
    }
    let report = fixture.install(&[]).unwrap();
    for browser in shared {
        assert_eq!(state(&report, browser), State::Installed, "{browser:?}");
    }
    assert!(fixture.manifest(Browser::Chrome).is_file());
    assert_eq!(
        fixture.registry.0[chrome_key],
        fixture.manifest(Browser::Chrome).to_str().unwrap()
    );

    let report = fixture.uninstall(&[Browser::Brave]).unwrap();
    assert_eq!(state(&report, Browser::Chrome), State::Missing);
    assert_eq!(state(&report, Browser::Firefox), State::Installed);
    assert!(!fixture.registry.0.contains_key(chrome_key));
    assert!(!fixture.manifest(Browser::Chrome).exists());

    // Without the browser, such a key is absent, and uninstall removes it.
    let edge_key = "Software\\Microsoft\\Edge\\NativeMessagingHosts\\com.artemchep.keyguard";
    fixture
        .registry
        .0
        .insert(edge_key.into(), "C:\\missing.json".into());
    assert_eq!(
        state(&fixture.status(), Browser::Edge),
        State::BrowserAbsent
    );
    fixture.uninstall(&[]).unwrap();
    assert!(fixture.registry.0.is_empty());
}

/// A Windows fixture of the MSIX build, whose package Windows installed
/// with the connector's alias.
fn msix_fixture() -> (Fixture, PathBuf) {
    let mut fixture = fixture(
        Os::Windows,
        &[
            ("LOCALAPPDATA", "{home}/AppData/Local"),
            ("APPDATA", "{home}/AppData/Roaming"),
        ],
    );
    fixture.context.packaging = Packaging::Msix {
        family: Some(PACKAGE_FAMILY.into()),
    };
    // The alias's file holds its reparse data.
    fixture.context.read_alias = |path| fs::read(path);
    let alias = fixture.context.host_alias().unwrap();
    fs::create_dir_all(alias.parent().unwrap()).unwrap();
    let host = &fixture.context.bundled_host;
    fs::write(&alias, alias_of(PACKAGE_FAMILY, host)).unwrap();
    (fixture, alias)
}

const PACKAGE_FAMILY: &str = "Keyguard_abcdefghjkmnp";

#[test]
fn msix_manifests_start_the_execution_alias() {
    let (mut fixture, alias) = msix_fixture();
    assert_eq!(
        alias,
        fixture
            .home
            .join("AppData/Local/Microsoft/WindowsApps/keyguard-browser-host.exe")
    );
    fixture.use_browser(Browser::Edge);
    let report = fixture.install(&[]).unwrap();
    assert_eq!(report.packaging, "msix");
    assert_eq!(
        serde_json::to_value(&report).unwrap()["packaging"],
        serde_json::json!("msix")
    );
    assert_eq!(state(&report, Browser::Edge), State::Installed);
    assert_eq!(fixture.manifest_host(Browser::Edge), alias);
    assert_eq!(
        entry(&report, Browser::Edge).host.as_deref(),
        alias.to_str()
    );
    let key = "Software\\Microsoft\\Edge\\NativeMessagingHosts\\com.artemchep.keyguard";
    assert_eq!(
        fixture.registry.0.get(key).map(String::as_str),
        fixture.manifest(Browser::Edge).to_str()
    );

    // The bundled connector is still checked.
    fs::remove_file(&fixture.context.bundled_host).unwrap();
    assert_eq!(state(&fixture.status(), Browser::Edge), State::Stale);
    let error = fixture.install(&[]).unwrap_err();
    assert_eq!(code(&error), "host_missing");
    assert_eq!(
        error_json(&error)["path"],
        fixture.context.bundled_host.to_str().unwrap()
    );
    assert_eq!(error_json(&error).get("alias"), None);
}

#[test]
fn msix_manifests_need_the_execution_alias() {
    let (mut fixture, alias) = msix_fixture();
    fixture.use_browser(Browser::Firefox);
    fixture.install(&[]).unwrap();
    // E.g. turned off in the settings of Windows.
    fs::remove_file(&alias).unwrap();
    let report = fixture.status();
    assert_eq!(state(&report, Browser::Firefox), State::Stale);
    assert_eq!(
        entry(&report, Browser::Firefox).host.as_deref(),
        alias.to_str()
    );
    let manifest = fs::read_to_string(fixture.manifest(Browser::Firefox)).unwrap();
    let error = fixture.install(&[]).unwrap_err();
    assert_eq!(code(&error), "host_missing");
    assert_eq!(error_json(&error)["path"], alias.to_str().unwrap());
    assert_eq!(error_json(&error)["alias"], "keyguard-browser-host.exe");
    assert_eq!(
        fs::read_to_string(fixture.manifest(Browser::Firefox)).unwrap(),
        manifest
    );
    // Removal works without it.
    let report = fixture.uninstall(&[]).unwrap();
    assert_eq!(state(&report, Browser::Firefox), State::Missing);
    assert!(fixture.registry.0.is_empty());

    // An alias is a reparse point, not a file of the package.
    let host = &fixture.context.bundled_host;
    fs::write(&alias, alias_of(PACKAGE_FAMILY, host)).unwrap();
    let report = fixture.install(&[]).unwrap();
    assert_eq!(state(&report, Browser::Firefox), State::Installed);
}

#[test]
fn msix_manifests_need_this_packages_alias() {
    let (mut fixture, alias) = msix_fixture();
    fixture.use_browser(Browser::Chrome);
    fixture.install(&[]).unwrap();
    // The other build's package, such as the sideloaded one, a file that is
    // no alias, or the alias of an older version of the package.
    let host = &fixture.context.bundled_host;
    let older = fixture
        .home
        .join("WindowsApps/Keyguard_1.0.0.0_x64__abcdefghjkmnp/host.exe");
    for other in [
        alias_of("KeyguardSideload_abcdefghjkmnp", host),
        b"MZ".to_vec(),
        alias_of(PACKAGE_FAMILY, &older),
    ] {
        fs::write(&alias, other).unwrap();
        assert_eq!(state(&fixture.status(), Browser::Chrome), State::Stale);
        let error = fixture.install(&[]).unwrap_err();
        assert_eq!(code(&error), "host_missing");
        assert_eq!(error_json(&error)["path"], alias.to_str().unwrap());
    }
}

#[test]
fn another_build_repairs_the_manifests_of_a_removed_package() {
    let (mut fixture, alias) = msix_fixture();
    fixture.use_browser(Browser::Chrome);
    fixture.install(&[]).unwrap();
    // Removing the package runs no code of Keyguard: its alias goes, its
    // manifests and keys stay.
    fs::remove_file(&alias).unwrap();
    fixture.context.packaging = Packaging::Native;
    let report = fixture.status();
    // The app repairs a stale manifest that Keyguard wrote.
    let chrome = entry(&report, Browser::Chrome);
    assert_eq!(chrome.status, State::Stale);
    assert_eq!(chrome.host.as_deref(), alias.to_str());
    let report = fixture.install(&[]).unwrap();
    assert_eq!(state(&report, Browser::Chrome), State::Installed);
    assert_eq!(
        fixture.manifest_host(Browser::Chrome),
        fixture.context.bundled_host
    );
}

#[test]
fn macos_brave_shares_chrome_manifest() {
    let mut fixture = fixture(Os::Mac, &[]);
    fixture.use_browser(Browser::Brave);
    let report = fixture.install(&[]).unwrap();
    assert_eq!(state(&report, Browser::Brave), State::Installed);
    assert!(
        fixture
            .home
            .join("Library/Application Support/Google/Chrome/NativeMessagingHosts/com.artemchep.keyguard.json")
            .is_file()
    );
    let chrome = entry(&report, Browser::Chrome);
    assert!(!chrome.detected);
    assert_eq!(chrome.status, State::Installed);
}

#[test]
fn reports_serialize_for_the_app() {
    let mut fixture = fixture(Os::Linux, &[]);
    fixture.use_browser(Browser::Chrome);
    let report = fixture.install(&[]).unwrap();
    let json = serde_json::to_value(&report).unwrap();
    assert_eq!(json["version"], 1);
    assert_eq!(json["packaging"], "native");
    assert_eq!(
        json["browsers"][0],
        serde_json::json!({
            "browser": "chrome",
            "status": "installed",
            "detected": true,
            "manifest": fixture.manifest(Browser::Chrome).to_str().unwrap(),
            "host": fixture.context.bundled_host.to_str().unwrap(),
        })
    );
    assert_eq!(
        json["browsers"][5],
        serde_json::json!({
            "browser": "firefox",
            "status": "browser_absent",
            "detected": false,
            "manifest": fixture.manifest(Browser::Firefox).to_str().unwrap(),
            "host": null,
        })
    );
    assert_eq!(json["browsers"][6]["browser"], "chrome-flatpak");
    assert_eq!(json["browsers"][10]["browser"], "firefox-flatpak");
    assert_eq!(json["browsers"].as_array().unwrap().len(), 11);
}
