//! Installs, repairs, reports and removes the manifests that let browsers
//! start Keyguard's connector.
use super::{
    BrowsersError,
    locations::{Browser, Context, HOST_NAME, Location, Packaging},
    registry::Registry,
};
use anyhow::{Context as _, Result, anyhow, ensure};
use clap::ValueEnum as _;
use keyguard_agent_setup::{check_safe_path, read_safe_text, write_safe_file};
use serde::Serialize;
use serde_json::Value;
use std::{
    fs,
    path::{Path, PathBuf},
};

/// The Chromium extensions that may start the connector: the Chrome Web
/// Store listing, which Brave, Vivaldi and Opera install from too, and the
/// Edge Add-ons listing. Each install rewrites the allowlist, so a manifest
/// that allows other extensions needs repair.
const CHROMIUM_ORIGINS: &[&str] = &[
    // Chrome Web Store.
    "chrome-extension://pbgoonibllaljhcpadklliddccmhdehh/",
    // Microsoft Edge Add-ons.
    "chrome-extension://jmiinenokkmkljkgaahelgacgkjemebb/",
];

/// The unpacked development build of `browserExtension/`, whose ID the dev
/// `key` in its `wxt.config.ts` fixes. That key is public, so anyone can load
/// an extension with this ID: manifests allow it only while `--dev` asks for
/// it, and repairs without `--dev` remove it.
const DEV_EXTENSION_ORIGIN: &str = "chrome-extension://ngjnjkolmoemeghbpilkeiimhacfmkdp/";

/// The Firefox add-on, by its `browser_specific_settings.gecko.id`.
const FIREFOX_EXTENSIONS: &[&str] = &["browser@keyguard.dev"];

const DESCRIPTION: &str = "Keyguard browser extension connector";

/// The first lines of each wrapper that Keyguard writes.
const WRAPPER_HEADER: &str = "#!/bin/sh\n\
    # Written by Keyguard: starts its browser extension connector in the Flatpak sandbox.\n";

/// Larger files are not manifests that Keyguard wrote.
const MAX_MANIFEST_SIZE: u64 = 1024 * 1024;

/// The version of the report's format.
const REPORT_VERSION: u32 = 1;

#[derive(Debug, Serialize)]
pub(crate) struct Report {
    pub version: u32,
    pub packaging: &'static str,
    pub browsers: Vec<BrowserStatus>,
}

#[derive(Debug, Serialize)]
pub(crate) struct BrowserStatus {
    pub browser: Browser,
    pub status: State,
    /// Whether the browser was used by this user. Inside Flatpak, whether the
    /// sandbox sees the browser's manifest directory.
    pub detected: bool,
    pub manifest: PathBuf,
    /// Windows: the key whose default value is the manifest's path.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub registry_key: Option<String>,
    /// What the current manifest starts.
    pub host: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum State {
    Installed,
    /// The browser was used, but has no manifest.
    Missing,
    /// The manifest, its key, the wrapper, or the copy of the connector
    /// differs from what `install` writes.
    Stale,
    /// Neither the browser nor a manifest was found.
    BrowserAbsent,
}

/// Reports each browser. With `dev`, a manifest is current only if it allows
/// the development build; without it, only if it doesn't.
pub(crate) fn status(context: &Context, registry: &dyn Registry, dev: bool) -> Result<Report> {
    let browsers = Browser::value_variants()
        .iter()
        .copied()
        .map(|browser| inspect(context, registry, browser, dev))
        .collect::<Result<_>>()?;
    Ok(Report {
        version: REPORT_VERSION,
        packaging: context.packaging.id(),
        browsers,
    })
}

/// Writes the manifests of the `selected` browsers, or of every detected
/// browser, and repairs what differs. With `dev`, Chromium manifests also
/// allow [`DEV_EXTENSION_ORIGIN`].
pub(crate) fn install(
    context: &Context,
    registry: &mut dyn Registry,
    selected: &[Browser],
    dev: bool,
) -> Result<Report> {
    ensure!(
        bundled_host_runs(context),
        BrowsersError::HostMissing {
            path: context.bundled_host.clone()
        }
    );
    let roots = context.roots();
    let browsers = if selected.is_empty() {
        Browser::value_variants()
            .iter()
            .copied()
            .filter(|&browser| is_detected(&context.location(browser)))
            .collect()
    } else {
        for &browser in selected {
            ensure!(
                is_detected(&context.location(browser)),
                BrowsersError::BrowserAbsent { browser }
            );
        }
        selected.to_vec()
    };
    // Check every file before changing any.
    let mut writes: Vec<(Location, String)> = Vec::new();
    for browser in browsers {
        let location = context.location(browser);
        if writes.iter().any(|(it, _)| {
            it.manifest == location.manifest && it.registry_key == location.registry_key
        }) {
            continue;
        }
        ensure_ours_or_missing(&roots, &location.manifest, is_manifest)?;
        if matches!(context.packaging, Packaging::Flatpak { .. }) {
            ensure_ours_or_missing(&roots, &location.wrapper(), is_wrapper)?;
        }
        let manifest = render(browser, &context.host_target(&location), dev)?;
        writes.push((location, manifest));
    }
    if context.packaging == Packaging::AppImage && !writes.is_empty() {
        let copy = context
            .host_copy()
            .context("the AppImage runs only on Linux")?;
        check_safe_path(&roots, &copy)?;
        if !(same_contents(&copy, &context.bundled_host) && is_executable(&copy)) {
            let host = fs::read(&context.bundled_host)?;
            write_safe_file(&roots, &copy, Some(&host), true)?;
        }
    }
    for (location, manifest) in &writes {
        let path = location.wrapper();
        // The manifest starts the wrapper, so the wrapper comes first.
        if let Packaging::Flatpak { app_id } = &context.packaging {
            let script = wrapper(app_id, &context.bundled_host)?;
            if !(holds(&path, &script) && is_executable(&path)) {
                write_safe_file(&roots, &path, Some(script.as_bytes()), true)?;
            }
        }
        if !holds(&location.manifest, manifest) {
            write_safe_file(&roots, &location.manifest, Some(manifest.as_bytes()), false)?;
        }
        if let Some(key) = &location.registry_key {
            let value = manifest_value(location)?;
            if registry.read(key)?.as_deref() != Some(value) {
                registry.write(key, value)?;
            }
        }
        // A wrapper of the Flatpak build, which no manifest starts now.
        if !matches!(context.packaging, Packaging::Flatpak { .. })
            && matches!(read(&path), Found::Text(text) if is_wrapper(&text))
        {
            remove_unused(&roots, &path);
        }
    }
    prune_host_copy(context);
    status(context, registry, dev)
}

/// Removes Keyguard's manifests, wrappers and keys of the `selected`
/// browsers, or of every browser. Never removes a browser's directories.
/// Reports like [`status`].
pub(crate) fn uninstall(
    context: &Context,
    registry: &mut dyn Registry,
    selected: &[Browser],
    dev: bool,
) -> Result<Report> {
    let roots = context.roots();
    let browsers = if selected.is_empty() {
        Browser::value_variants()
    } else {
        selected
    };
    // Check every file before removing any.
    let mut removals: Vec<Location> = Vec::new();
    for &browser in browsers {
        let location = context.location(browser);
        if removals
            .iter()
            .any(|it| it.manifest == location.manifest && it.registry_key == location.registry_key)
        {
            continue;
        }
        if exists(&location.manifest) {
            ensure_ours_or_missing(&roots, &location.manifest, is_manifest)?;
        }
        if exists(&location.wrapper()) {
            ensure_ours_or_missing(&roots, &location.wrapper(), is_wrapper)?;
        }
        removals.push(location);
    }
    for location in &removals {
        // Without its key, the browser no longer looks at the manifest. A key
        // that reads as missing, such as one without a string value, goes too.
        if let Some(key) = &location.registry_key {
            registry.delete(key)?;
        }
        for path in [&location.manifest, &location.wrapper()] {
            if exists(path) {
                write_safe_file(&roots, path, None, false)?;
            }
        }
    }
    prune_host_copy(context);
    status(context, registry, dev)
}

fn inspect(
    context: &Context,
    registry: &dyn Registry,
    browser: Browser,
    dev: bool,
) -> Result<BrowserStatus> {
    let location = context.location(browser);
    let detected = is_detected(&location);
    let manifest = read(&location.manifest);
    let key = match &location.registry_key {
        Some(key) => registry.read(key)?,
        None => None,
    };
    let host = match &manifest {
        Found::Text(text) => manifest_host(text),
        _ => None,
    };
    let status = if manifest == Found::Missing && key.is_none() {
        if detected {
            State::Missing
        } else {
            State::BrowserAbsent
        }
    } else if is_current(context, browser, &location, &manifest, key.as_deref(), dev)? {
        State::Installed
    } else {
        State::Stale
    };
    Ok(BrowserStatus {
        browser,
        status,
        detected,
        registry_key: location
            .registry_key
            .as_ref()
            .map(|key| format!("HKEY_CURRENT_USER\\{key}")),
        manifest: location.manifest,
        host,
    })
}

/// Whether `install` would change nothing.
fn is_current(
    context: &Context,
    browser: Browser,
    location: &Location,
    manifest: &Found,
    key: Option<&str>,
    dev: bool,
) -> Result<bool> {
    let Found::Text(text) = manifest else {
        return Ok(false);
    };
    // Also catches extensions that Keyguard does not allow.
    let expected = render(browser, &context.host_target(location), dev)?;
    if *text != expected {
        return Ok(false);
    }
    if location.registry_key.is_some() && key != Some(manifest_value(location)?) {
        return Ok(false);
    }
    // `install` refuses to replace an unsafe file, even one with the right
    // contents.
    let roots = context.roots();
    let safe = |path: &Path| check_safe_path(&roots, path).is_ok();
    if !(bundled_host_runs(context) && safe(&location.manifest)) {
        return Ok(false);
    }
    Ok(match &context.packaging {
        Packaging::Native => true,
        Packaging::Flatpak { app_id } => {
            let path = location.wrapper();
            holds(&path, &wrapper(app_id, &context.bundled_host)?)
                && is_executable(&path)
                && safe(&path)
        }
        Packaging::AppImage => context.host_copy().is_some_and(|copy| {
            same_contents(&copy, &context.bundled_host) && is_executable(&copy) && safe(&copy)
        }),
    })
}

/// Whether the manifests can start the bundled connector. The AppImage's
/// copy is executable whatever the bundled connector's mode.
fn bundled_host_runs(context: &Context) -> bool {
    match context.packaging {
        Packaging::AppImage => context.bundled_host.is_file(),
        _ => is_executable(&context.bundled_host),
    }
}

fn is_detected(location: &Location) -> bool {
    location.markers.iter().any(|marker| marker.exists())
}

/// The manifest that Keyguard writes. It allows only Keyguard's extensions,
/// plus the development build with `dev`.
fn render(browser: Browser, host: &Path, dev: bool) -> Result<String> {
    #[derive(Serialize)]
    struct Manifest<'a> {
        name: &'a str,
        description: &'a str,
        path: &'a str,
        #[serde(rename = "type")]
        kind: &'a str,
        #[serde(skip_serializing_if = "Option::is_none")]
        allowed_origins: Option<&'a [String]>,
        #[serde(skip_serializing_if = "Option::is_none")]
        allowed_extensions: Option<&'a [String]>,
    }

    let path = host
        .to_str()
        .ok_or_else(|| anyhow!("the connector's path is not UTF-8: {}", host.display()))?;
    let firefox = browser.is_firefox();
    let allowed: Vec<String> = if firefox {
        FIREFOX_EXTENSIONS.iter().map(|&id| id.to_owned()).collect()
    } else {
        CHROMIUM_ORIGINS
            .iter()
            .copied()
            .chain(dev.then_some(DEV_EXTENSION_ORIGIN))
            .map(str::to_owned)
            .collect()
    };
    let manifest = Manifest {
        name: HOST_NAME,
        description: DESCRIPTION,
        path,
        kind: "stdio",
        allowed_origins: (!firefox).then_some(&allowed[..]),
        allowed_extensions: firefox.then_some(&allowed[..]),
    };
    Ok(serde_json::to_string_pretty(&manifest)? + "\n")
}

/// Runs the bundled connector of the Flatpak app `app_id`, whose path in the
/// sandbox is `host`.
fn wrapper(app_id: &str, host: &Path) -> Result<String> {
    let host = host
        .to_str()
        .filter(|host| !host.contains('\'') && !host.chars().any(char::is_control))
        .ok_or_else(|| anyhow!("cannot quote the connector's path: {}", host.display()))?;
    Ok(format!(
        "{WRAPPER_HEADER}exec flatpak run --command='{host}' '{app_id}' \"$@\"\n"
    ))
}

fn is_wrapper(text: &str) -> bool {
    text.starts_with(WRAPPER_HEADER)
}

/// The JSON of a manifest that Keyguard wrote: it names Keyguard's host.
fn parse_manifest(text: &str) -> Option<Value> {
    let value: Value = serde_json::from_str(text).ok()?;
    (value.get("name")?.as_str()? == HOST_NAME).then_some(value)
}

fn is_manifest(text: &str) -> bool {
    parse_manifest(text).is_some()
}

/// What a manifest that Keyguard wrote starts.
fn manifest_host(text: &str) -> Option<String> {
    let value = parse_manifest(text)?;
    value.get("path")?.as_str().map(str::to_owned)
}

/// Fails unless the file at `path`, after the safety checks of a file to
/// write, is missing or one that Keyguard wrote.
fn ensure_ours_or_missing(roots: &[PathBuf], path: &Path, is_ours: fn(&str) -> bool) -> Result<()> {
    if let Some(text) = read_safe_text(roots, path)? {
        ensure!(
            is_ours(&text),
            BrowsersError::ForeignFile {
                path: path.to_owned()
            }
        );
    }
    Ok(())
}

/// The manifest's path, as its registry key holds it.
fn manifest_value(location: &Location) -> Result<&str> {
    location.manifest.to_str().ok_or_else(|| {
        anyhow!(
            "the manifest's path is not UTF-8: {}",
            location.manifest.display()
        )
    })
}

/// Deletes the AppImage's copy of the connector once no manifest starts it.
fn prune_host_copy(context: &Context) {
    // The sandbox's XDG_DATA_HOME is not the host's.
    if matches!(context.packaging, Packaging::Flatpak { .. }) {
        return;
    }
    let Some(copy) = context.host_copy() else {
        return;
    };
    if !exists(&copy) {
        return;
    }
    let used = Browser::value_variants().iter().copied().any(|browser| {
        let location = context.location(browser);
        matches!(read(&location.manifest), Found::Text(text)
            if manifest_host(&text).as_deref() == copy.to_str())
    });
    if !used {
        remove_unused(&context.roots(), &copy);
    }
}

/// Removes a file that no manifest starts. Best effort: the file can't do
/// harm, so one that can't be removed safely, such as a link, stays.
fn remove_unused(roots: &[PathBuf], path: &Path) {
    let _ = write_safe_file(roots, path, None, false);
}

/// A file as `status` sees it. Reports only read, so they follow links, but
/// a file that `install` would refuse is never current. A broken link is
/// [`Found::Other`], not [`Found::Missing`]: `install` refuses it too.
#[derive(Debug, PartialEq, Eq)]
enum Found {
    Missing,
    Text(String),
    /// Not a small UTF-8 file, a broken link, or unreadable.
    Other,
}

fn read(path: &Path) -> Found {
    if matches!(fs::symlink_metadata(path), Err(error) if error.kind() == std::io::ErrorKind::NotFound)
    {
        return Found::Missing;
    }
    match fs::metadata(path) {
        Ok(metadata) if metadata.is_file() && metadata.len() <= MAX_MANIFEST_SIZE => fs::read(path)
            .ok()
            .and_then(|bytes| String::from_utf8(bytes).ok())
            .map_or(Found::Other, Found::Text),
        _ => Found::Other,
    }
}

/// Whether `path` holds exactly `expected`.
fn holds(path: &Path, expected: &str) -> bool {
    matches!(read(path), Found::Text(text) if text == expected)
}

/// Whether anything, even a broken link, is at `path`.
fn exists(path: &Path) -> bool {
    fs::symlink_metadata(path).is_ok()
}

fn same_contents(a: &Path, b: &Path) -> bool {
    match (fs::metadata(a), fs::metadata(b)) {
        (Ok(a_metadata), Ok(b_metadata))
            if a_metadata.is_file() && b_metadata.len() == a_metadata.len() =>
        {
            matches!((fs::read(a), fs::read(b)), (Ok(a), Ok(b)) if a == b)
        }
        _ => false,
    }
}

/// Whether `path` is a file that its owner, group or others may run.
fn is_executable(path: &Path) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::metadata(path)
            .is_ok_and(|metadata| metadata.is_file() && metadata.permissions().mode() & 0o111 != 0)
    }
    #[cfg(not(unix))]
    {
        path.is_file()
    }
}

#[cfg(test)]
mod tests;
