//! Installs, repairs, reports and removes the manifests that let browsers
//! start Keyguard's connector.
use super::{
    BrowsersError, alias,
    locations::{Browser, Context, Location, Packaging},
    registry::Registry,
};
use anyhow::{Context as _, Result, anyhow, ensure};
use keyguard_agent_setup::{
    FileLock, check_safe_path, open_safe_file, read_safe_text, read_text_file, wait_for_lock,
    write_safe_file,
};
use keyguard_cli_protocol::{browser::Channel, executable::Contents};
use serde::Serialize;
use serde_json::Value;
use std::{
    cell::OnceCell,
    fs,
    ops::Deref,
    path::{Path, PathBuf},
    time::Duration,
};

/// The Chromium extensions that may start the release connector: the
/// Chrome Web Store listing, which Brave, Vivaldi and Opera install from
/// too, and the Edge Add-ons listing. Each install rewrites the allowlist,
/// so a manifest that allows other extensions needs repair.
const CHROMIUM_ORIGINS: &[&str] = &[
    // Chrome Web Store.
    "chrome-extension://pbgoonibllaljhcpadklliddccmhdehh/",
    // Microsoft Edge Add-ons.
    "chrome-extension://jmiinenokkmkljkgaahelgacgkjemebb/",
];

/// The unpacked development build of `browserExtension/`, whose ID the dev
/// `key` in its `wxt.config.ts` fixes. That key is public, so anyone can load
/// an extension with this ID: only the [`Channel::Dev`] registration allows
/// it, never the release one.
const DEV_EXTENSION_ORIGIN: &str = "chrome-extension://ngjnjkolmoemeghbpilkeiimhacfmkdp/";

/// The Firefox add-on, by its `browser_specific_settings.gecko.id`. Its
/// development build has the same ID, so the host's name is what keeps the
/// channels apart.
const FIREFOX_EXTENSIONS: &[&str] = &["browser@keyguard.dev"];

const DESCRIPTION: &str = "Keyguard browser extension connector";

/// The first lines of each wrapper that Keyguard writes.
const WRAPPER_HEADER: &str = "#!/bin/sh\n\
    # Written by Keyguard: starts its browser extension connector in the Flatpak sandbox.\n";

/// The version of the report's format.
const REPORT_VERSION: u32 = 1;

/// Bounds the wait for another `install` or `uninstall`, which takes a few
/// seconds at most. The app stops a command after 30 seconds, so this one
/// fails before that, with an error that the app can show.
const LOCK_WAIT: Duration = Duration::from_secs(15);

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
    /// The manifest, its key, the wrapper, or a copy of the connector
    /// differs from what `install` writes.
    Stale,
    /// Neither the browser nor a manifest was found.
    BrowserAbsent,
}

/// A command's [`Context`], plus what the command reads at most once.
struct Command<'a> {
    context: &'a Context,
    /// [`Context::roots`].
    roots: Vec<PathBuf>,
    bundled: OnceCell<std::io::Result<Bundled>>,
}

/// The bundled connector, as [`Command::bundled`] read it.
struct Bundled {
    bytes: Vec<u8>,
    contents: Contents,
}

impl<'a> Command<'a> {
    fn new(context: &'a Context) -> Self {
        Self {
            context,
            roots: context.roots(),
            bundled: OnceCell::new(),
        }
    }

    /// The bundled connector, read once a copy of it is compared or written.
    fn bundled(&self) -> Result<&Bundled> {
        self.bundled
            .get_or_init(|| {
                let bytes = fs::read(&self.bundled_host)?;
                Ok(Bundled {
                    contents: Contents::of_bytes(&bytes),
                    bytes,
                })
            })
            .as_ref()
            .map_err(|error| anyhow!("{error}"))
    }
}

impl Deref for Command<'_> {
    type Target = Context;

    fn deref(&self) -> &Context {
        self.context
    }
}

/// Reports each browser, as the registration of the context's channel.
pub(crate) fn status(context: &Context, registry: &dyn Registry) -> Result<Report> {
    report(&Command::new(context), registry)
}

/// [`status`], within a command.
fn report(command: &Command, registry: &dyn Registry) -> Result<Report> {
    let host_missing = missing_host(command).is_some();
    let browsers = command
        .browsers()
        .into_iter()
        .map(|browser| inspect(command, registry, browser, host_missing))
        .collect::<Result<_>>()?;
    Ok(Report {
        version: REPORT_VERSION,
        packaging: command.packaging.id(),
        browsers,
    })
}

/// A manifest that `install` writes, with what its file and the Flatpak
/// build's wrapper next to it hold now.
struct Write {
    location: Location,
    manifest: String,
    current: Option<String>,
    wrapper: Option<String>,
}

/// Writes the manifests of the `selected` browsers, or of every detected
/// browser, and repairs what differs.
pub(crate) fn install(
    context: &Context,
    registry: &mut dyn Registry,
    selected: &[Browser],
) -> Result<Report> {
    let _lock = lock(context)?;
    if let Some(error) = missing_host(context) {
        return Err(error.into());
    }
    let command = Command::new(context);
    let roots = &command.roots;
    let browsers = if selected.is_empty() {
        context
            .browsers()
            .into_iter()
            .filter(|&browser| is_detected(&context.location(browser)))
            .collect()
    } else {
        ensure_of_this_os(context, selected)?;
        for &browser in selected {
            ensure!(
                is_detected(&context.location(browser)),
                BrowsersError::BrowserAbsent { browser }
            );
        }
        selected.to_vec()
    };
    // Check every file before changing any.
    let mut writes: Vec<Write> = Vec::new();
    for browser in browsers {
        let location = context.location(browser);
        if writes.iter().any(|it| {
            it.location.manifest == location.manifest
                && it.location.registry_key == location.registry_key
        }) {
            continue;
        }
        let current = ensure_ours_or_missing(roots, &location.manifest, |text| {
            is_manifest(context.channel, text)
        })?;
        let host = context.host_target(&location);
        let wrapper = if host == context.wrapper(&location) {
            ensure_ours_or_missing(roots, &host, is_wrapper)?
        } else {
            None
        };
        if let Some(copy) = &location.host_copy {
            check_safe_path(roots, copy)?;
            ensure!(
                !exists(copy) || owns_copy(&command, current.as_deref(), copy),
                BrowsersError::ForeignFile { path: copy.clone() }
            );
        }
        let manifest = render(context.channel, browser, &host)?;
        writes.push(Write {
            location,
            manifest,
            current,
            wrapper,
        });
    }
    // The other browsers' manifests start the AppImage's copy.
    if context.packaging == Packaging::AppImage
        && writes.iter().any(|it| it.location.host_copy.is_none())
    {
        let copy = context
            .host_copy()
            .context("the AppImage runs only on Linux")?;
        copy_host(&command, &copy)?;
    }
    for write in &writes {
        let location = &write.location;
        let path = context.wrapper(location);
        let host = context.host_target(location);
        // The manifest starts the copy or the wrapper, so it comes first.
        if let Some(copy) = &location.host_copy {
            copy_host(&command, copy)?;
        } else if let Packaging::Flatpak { app_id } = &context.packaging {
            let script = wrapper(app_id, &context.bundled_host)?;
            if !(write.wrapper.as_ref() == Some(&script) && is_executable(&path)) {
                write_safe_file(roots, &path, Some(script.as_bytes()), true)?;
            }
        }
        if write.current.as_ref() != Some(&write.manifest) {
            write_safe_file(
                roots,
                &location.manifest,
                Some(write.manifest.as_bytes()),
                false,
            )?;
        }
        if let Some(key) = &location.registry_key {
            let value = manifest_value(location)?;
            if registry.read(key)?.as_deref() != Some(value) {
                registry.write(key, value)?;
            }
        }
        // A wrapper of the Flatpak build, which no manifest starts now.
        if host != path && matches!(read(&command, &path), Found::Text(text) if is_wrapper(&text)) {
            remove_unused(roots, &path);
        }
    }
    prune_appimage_copy(&command);
    report(&command, registry)
}

/// Removes Keyguard's manifests, wrappers, copies of the connector and keys
/// of the `selected` browsers, or of every browser. Never removes a
/// browser's directories. Reports like [`status`].
pub(crate) fn uninstall(
    context: &Context,
    registry: &mut dyn Registry,
    selected: &[Browser],
) -> Result<Report> {
    let _lock = lock(context)?;
    let command = Command::new(context);
    let roots = &command.roots;
    let browsers = if selected.is_empty() {
        context.browsers()
    } else {
        ensure_of_this_os(context, selected)?;
        selected.to_vec()
    };
    // Check every file before removing any.
    let mut removals: Vec<(Location, Option<PathBuf>)> = Vec::new();
    for browser in browsers {
        let location = context.location(browser);
        if removals.iter().any(|(it, _)| {
            it.manifest == location.manifest && it.registry_key == location.registry_key
        }) {
            continue;
        }
        let manifest = if exists(&location.manifest) {
            ensure_ours_or_missing(roots, &location.manifest, |text| {
                is_manifest(context.channel, text)
            })?
        } else {
            None
        };
        let wrapper = context.wrapper(&location);
        if exists(&wrapper) {
            ensure_ours_or_missing(roots, &wrapper, is_wrapper)?;
        }
        // A link, which can do no harm without a manifest, stays. Only the
        // browsers that this removal covers lose their copies.
        let copy = location
            .host_copy
            .clone()
            .filter(|copy| exists(copy) && check_safe_path(roots, copy).is_ok());
        if let Some(copy) = &copy {
            ensure!(
                owns_copy(&command, manifest.as_deref(), copy),
                BrowsersError::ForeignFile { path: copy.clone() }
            );
        }
        removals.push((location, copy));
    }
    for (location, copy) in &removals {
        // Without its key, the browser no longer looks at the manifest. A key
        // that reads as missing, such as one without a string value, goes too.
        if let Some(key) = &location.registry_key {
            registry.delete(key)?;
        }
        // The manifest goes before what it starts.
        for path in [&location.manifest, &context.wrapper(location)]
            .into_iter()
            .chain(copy)
        {
            if exists(path) {
                write_safe_file(roots, path, None, false)?;
            }
        }
    }
    prune_appimage_copy(&command);
    report(&command, registry)
}

/// Waits until no other `install` or `uninstall` runs, such as the app's
/// and one from a terminal. Without the lock, one could remove a copy of the
/// connector that the other just wrote, before the other writes the
/// manifest that starts it. Fails after [`LOCK_WAIT`].
fn lock(context: &Context) -> Result<FileLock> {
    wait_for_lock(&context.lock_file(), LOCK_WAIT)?
        .ok_or_else(|| anyhow!("another `keyguard browsers` command is running; try again"))
}

/// Fails for a browser that `--browser` names, but that this OS doesn't
/// have, as `install` does for a browser that it didn't find.
fn ensure_of_this_os(context: &Context, selected: &[Browser]) -> Result<()> {
    let browsers = context.browsers();
    for &browser in selected {
        ensure!(
            browsers.contains(&browser),
            BrowsersError::BrowserAbsent { browser }
        );
    }
    Ok(())
}

/// Whether Keyguard put the file at a Flatpak browser's `copy` there: its
/// manifest next to it, which holds `manifest`, starts it, or it is a copy
/// of the bundled connector. The browser, or the user, may have put another
/// file with its name there.
fn owns_copy(command: &Command, manifest: Option<&str>, copy: &Path) -> bool {
    starts(command.channel, manifest, copy) || is_copy(command, copy)
}

/// Whether Keyguard's manifest of `channel`, which holds `manifest`, starts
/// `path`.
fn starts(channel: Channel, manifest: Option<&str>, path: &Path) -> bool {
    manifest.is_some_and(|text| manifest_host(channel, text).as_deref() == path.to_str())
}

fn inspect(
    command: &Command,
    registry: &dyn Registry,
    browser: Browser,
    host_missing: bool,
) -> Result<BrowserStatus> {
    let location = command.location(browser);
    let detected = is_detected(&location);
    let manifest = read(command, &location.manifest);
    let key = match &location.registry_key {
        Some(key) => registry.read(key)?,
        None => None,
    };
    let host = match &manifest {
        Found::Text(text) => manifest_host(command.channel, text),
        _ => None,
    };
    let status = if manifest == Found::Missing && key.is_none() {
        if detected {
            State::Missing
        } else {
            State::BrowserAbsent
        }
    } else if is_current(
        command,
        browser,
        &location,
        &manifest,
        key.as_deref(),
        host_missing,
    )? {
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

/// Whether `install` would change nothing. `host_missing`: the manifests
/// can't start the connector.
fn is_current(
    command: &Command,
    browser: Browser,
    location: &Location,
    manifest: &Found,
    key: Option<&str>,
    host_missing: bool,
) -> Result<bool> {
    let Found::Text(text) = manifest else {
        return Ok(false);
    };
    // Also catches extensions that Keyguard does not allow.
    let expected = render(command.channel, browser, &command.host_target(location))?;
    if *text != expected {
        return Ok(false);
    }
    if location.registry_key.is_some() && key != Some(manifest_value(location)?) {
        return Ok(false);
    }
    // `install` refuses to replace an unsafe file, even one with the right
    // contents. A copy passes the same checks as it opens.
    let safe = |path: &Path| check_safe_path(&command.roots, path).is_ok();
    if host_missing || !safe(&location.manifest) {
        return Ok(false);
    }
    if let Some(copy) = &location.host_copy {
        return Ok(is_copy(command, copy));
    }
    Ok(match &command.packaging {
        Packaging::Native | Packaging::Msix { .. } => true,
        Packaging::Flatpak { app_id } => {
            let path = command.wrapper(location);
            holds(command, &path, &wrapper(app_id, &command.bundled_host)?)
                && is_executable(&path)
                && safe(&path)
        }
        Packaging::AppImage => command
            .host_copy()
            .is_some_and(|copy| is_copy(command, &copy)),
    })
}

/// Whether `copy` is an executable copy of the bundled connector. It opens
/// after the checks of a file to write, without following links.
fn is_copy(command: &Command, copy: &Path) -> bool {
    let Ok(Some(file)) = open_safe_file(&command.roots, copy) else {
        return false;
    };
    file.metadata()
        .is_ok_and(|metadata| metadata.is_file() && mode_is_executable(&metadata))
        && command
            .bundled()
            .is_ok_and(|bundled| bundled.contents.matches(&file))
}

/// Writes a copy of the bundled connector to `copy`, unless it is one. The
/// caller checks that a file there is Keyguard's; the write checks the path.
fn copy_host(command: &Command, copy: &Path) -> Result<()> {
    if !is_copy(command, copy) {
        let bundled = command.bundled()?;
        write_safe_file(&command.roots, copy, Some(&bundled.bytes), true)?;
    }
    Ok(())
}

/// The connector that the manifests can't start, if any: the bundled one,
/// or the MSIX package's alias of it.
fn missing_host(context: &Context) -> Option<BrowsersError> {
    if !bundled_host_runs(context) {
        return Some(BrowsersError::HostMissing {
            path: context.bundled_host.clone(),
            alias: None,
        });
    }
    // Windows creates the alias, but the user can turn it off, and the
    // package of another build of Keyguard may own it.
    let Packaging::Msix { family } = &context.packaging else {
        return None;
    };
    let path = context.host_alias()?;
    let owned = (context.read_alias)(&path)
        .is_ok_and(|data| alias::is_owned(&data, family.as_deref(), &context.bundled_host));
    (!owned).then(|| BrowsersError::HostMissing {
        alias: path
            .file_name()
            .map(|name| name.to_string_lossy().into_owned()),
        path,
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

/// The manifest that Keyguard writes for `channel`. It allows only the
/// extensions of that channel.
fn render(channel: Channel, browser: Browser, host: &Path) -> Result<String> {
    #[derive(Serialize)]
    struct Manifest<'a> {
        name: &'a str,
        description: &'a str,
        path: &'a str,
        #[serde(rename = "type")]
        kind: &'a str,
        #[serde(skip_serializing_if = "Option::is_none")]
        allowed_origins: Option<&'a [&'a str]>,
        #[serde(skip_serializing_if = "Option::is_none")]
        allowed_extensions: Option<&'a [&'a str]>,
    }

    let path = host
        .to_str()
        .ok_or_else(|| anyhow!("the connector's path is not UTF-8: {}", host.display()))?;
    let firefox = browser.is_firefox();
    let allowed: &[&str] = match (channel, firefox) {
        (_, true) => FIREFOX_EXTENSIONS,
        (Channel::Production, false) => CHROMIUM_ORIGINS,
        (Channel::Dev, false) => &[DEV_EXTENSION_ORIGIN],
    };
    let manifest = Manifest {
        name: channel.host_name(),
        description: DESCRIPTION,
        path,
        kind: "stdio",
        allowed_origins: (!firefox).then_some(allowed),
        allowed_extensions: firefox.then_some(allowed),
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

/// The JSON of a manifest that Keyguard wrote for `channel`: it names the
/// channel's host.
fn parse_manifest(channel: Channel, text: &str) -> Option<Value> {
    let value: Value = serde_json::from_str(text).ok()?;
    (value.get("name")?.as_str()? == channel.host_name()).then_some(value)
}

fn is_manifest(channel: Channel, text: &str) -> bool {
    parse_manifest(channel, text).is_some()
}

/// What a manifest that Keyguard wrote for `channel` starts.
fn manifest_host(channel: Channel, text: &str) -> Option<String> {
    let value = parse_manifest(channel, text)?;
    value.get("path")?.as_str().map(str::to_owned)
}

/// Fails unless the file at `path`, after the safety checks of a file to
/// write, is missing or one that Keyguard wrote. Returns what it holds.
fn ensure_ours_or_missing(
    roots: &[PathBuf],
    path: &Path,
    is_ours: impl Fn(&str) -> bool,
) -> Result<Option<String>> {
    let text = read_safe_text(roots, path)?;
    if let Some(text) = &text {
        ensure!(
            is_ours(text),
            BrowsersError::ForeignFile {
                path: path.to_owned()
            }
        );
    }
    Ok(text)
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

/// Deletes the AppImage's copy of the connector, in Keyguard's own folder,
/// once no manifest starts it. Only this channel's copy and manifests count.
fn prune_appimage_copy(command: &Command) {
    // The sandbox's XDG_DATA_HOME is not the host's.
    if matches!(command.packaging, Packaging::Flatpak { .. }) {
        return;
    }
    let Some(copy) = command.host_copy() else {
        return;
    };
    if !exists(&copy) {
        return;
    }
    let used = command.browsers().into_iter().any(|browser| {
        matches!(read(command, &command.location(browser).manifest), Found::Text(text)
            if starts(command.channel, Some(&text), &copy))
    });
    if !used {
        remove_unused(&command.roots, &copy);
    }
}

/// Removes a file that no manifest starts. Best effort: the file can't do
/// harm, so one that can't be removed safely, such as a link, stays.
fn remove_unused(roots: &[PathBuf], path: &Path) {
    let _ = write_safe_file(roots, path, None, false);
}

/// A file as `status` sees it. Reports only read, so they follow links, but
/// a file that `install` would refuse is never current. A broken link is
/// [`Found::Other`], not [`Found::Missing`]: `install` refuses it too. In a
/// Flatpak browser's folder, which the browser can change, they follow none.
#[derive(Debug, PartialEq, Eq)]
enum Found {
    Missing,
    Text(String),
    /// Not a small UTF-8 file, a broken link, or unreadable.
    Other,
}

fn read(command: &Command, path: &Path) -> Found {
    if command.in_flatpak_browser(path) {
        return match read_safe_text(&command.roots, path) {
            Ok(None) => Found::Missing,
            Ok(Some(text)) => Found::Text(text),
            Err(_) => Found::Other,
        };
    }
    if matches!(fs::symlink_metadata(path), Err(error) if error.kind() == std::io::ErrorKind::NotFound)
    {
        return Found::Missing;
    }
    match fs::metadata(path) {
        // Only a regular file opens: a FIFO would wait for a writer.
        Ok(metadata) if metadata.is_file() => fs::File::open(path)
            .ok()
            .and_then(|file| read_text_file(file).ok())
            .map_or(Found::Other, Found::Text),
        _ => Found::Other,
    }
}

/// Whether `path` holds exactly `expected`.
fn holds(command: &Command, path: &Path, expected: &str) -> bool {
    matches!(read(command, path), Found::Text(text) if text == expected)
}

/// Whether anything, even a broken link, is at `path`.
fn exists(path: &Path) -> bool {
    fs::symlink_metadata(path).is_ok()
}

/// Whether `path` is a file that its owner, group or others may run.
fn is_executable(path: &Path) -> bool {
    fs::metadata(path).is_ok_and(|metadata| metadata.is_file() && mode_is_executable(&metadata))
}

fn mode_is_executable(metadata: &fs::Metadata) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        metadata.permissions().mode() & 0o111 != 0
    }
    #[cfg(not(unix))]
    {
        let _ = metadata;
        true
    }
}

#[cfg(test)]
mod tests;
