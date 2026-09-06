//! Platform-specific configuration for the GPG agent socket.

#[cfg(any(target_os = "linux", target_os = "macos"))]
use anyhow::{Context, Result};
#[cfg(any(target_os = "linux", target_os = "macos"))]
use std::path::{Path, PathBuf};
#[cfg(any(target_os = "linux", target_os = "macos"))]
use std::process::{Command, Output};

#[cfg(any(target_os = "linux", target_os = "macos"))]
pub(crate) fn linux_fallback_gpg_agent_socket_path(uid: libc::uid_t) -> PathBuf {
    managed_gpg_agent_socket_path(&linux_fallback_gpg_home_path(uid))
}

/// Single source of truth for the socket file name inside a managed GnuPG
/// home. The socket-serving layer re-derives this path to decide whether a
/// configured socket lives in a Keyguard-managed directory.
#[cfg(any(target_os = "linux", target_os = "macos"))]
pub(crate) fn managed_gpg_agent_socket_path(gpg_home: &Path) -> PathBuf {
    gpg_home.join("S.gpg-agent")
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn linux_fallback_gpg_home_path(uid: libc::uid_t) -> PathBuf {
    PathBuf::from(format!("/tmp/keyguard-{uid}/gnupg"))
}

#[cfg(target_os = "macos")]
pub(crate) fn macos_managed_gpg_home_path(home: &Path) -> PathBuf {
    // Match macosManagedGpgHomePath in GpgManagedHome.kt. External GnuPG tools
    // need this home's public keyrings and configuration outside Group Containers:
    // macOS 27 blocks other developers' container access by default, without prompting.
    // Apple, System Integrity Protection (161835690):
    // https://developer.apple.com/documentation/macos-release-notes/macos-27-release-notes
    home.join(".keyguard").join("gnupg")
}

#[cfg(target_os = "linux")]
const FLATPAK_APP_ID_FALLBACK: &str = "com.artemchep.keyguard";

#[cfg(target_os = "linux")]
pub(crate) fn linux_managed_gpg_home_path() -> Result<PathBuf> {
    if std::env::var("container").ok().as_deref() == Some("flatpak") {
        let home = dirs::home_dir().context("could not determine the Linux home directory")?;
        return Ok(flatpak_gpg_home_path(
            std::env::var("XDG_DATA_HOME").ok().as_deref(),
            &home,
            std::env::var("FLATPAK_ID").ok().as_deref(),
        ));
    }

    let home = dirs::home_dir().context("could not determine the Linux home directory")?;
    Ok(linux_persistent_gpg_home_path(
        &home,
        std::env::var_os("XDG_DATA_HOME").as_deref().map(Path::new),
    ))
}

#[cfg(any(target_os = "linux", all(test, unix)))]
fn linux_persistent_gpg_home_path(home: &Path, xdg_data_home: Option<&Path>) -> PathBuf {
    use std::ffi::OsString;
    use std::os::unix::ffi::OsStringExt;

    let path = xdg_data_home
        .filter(|directory| directory.is_absolute())
        .map(Path::to_path_buf)
        .unwrap_or_else(|| home.join(".local").join("share"))
        .join("keyguard")
        .join("gnupg");

    // Match JVM Path and the Linux setup command without resolving symlinks
    // or removing `.`/`..`: GnuPG hashes the lexical home passed to gpgconf.
    // Work on Unix bytes so non-UTF-8 directory names remain unchanged.
    let mut bytes = path.into_os_string().into_vec();
    bytes.dedup_by(|a, b| *a == b'/' && *b == b'/');
    PathBuf::from(OsString::from_vec(bytes))
}

#[cfg(target_os = "linux")]
pub(crate) fn flatpak_gpg_home_path(
    xdg_data_home: Option<&str>,
    home: &Path,
    flatpak_id: Option<&str>,
) -> PathBuf {
    if let Some(xdg_data_home) = xdg_data_home.filter(|dir| !dir.trim().is_empty()) {
        return PathBuf::from(xdg_data_home).join("gnupg");
    }
    let app_id = flatpak_id
        .filter(|id| !id.trim().is_empty())
        .unwrap_or(FLATPAK_APP_ID_FALLBACK);
    home.join(".var")
        .join("app")
        .join(app_id)
        .join("data")
        .join("gnupg")
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn gpgconf_agent_socket_path(home: &Path) -> Result<PathBuf> {
    gpgconf_agent_socket_path_with(home, run_gpgconf)
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn gpgconf_agent_socket_path_with<F>(home: &Path, mut run: F) -> Result<PathBuf>
where
    F: FnMut(&Path, &[&str]) -> Result<Output>,
{
    const LIST_SOCKET_ARGS: &[&str] = &["--list-dirs", "agent-socket"];

    let output = run(home, LIST_SOCKET_ARGS).with_context(|| {
        format!(
            "failed to run gpgconf --list-dirs agent-socket for GnuPG home {}",
            home.display()
        )
    })?;
    if !output.status.success() {
        anyhow::bail!(
            "gpgconf --list-dirs agent-socket exited with {} for GnuPG home {}; stdout: {}; stderr: {}",
            output.status,
            home.display(),
            String::from_utf8_lossy(&output.stdout).trim(),
            String::from_utf8_lossy(&output.stderr).trim()
        );
    }

    let stdout = String::from_utf8(output.stdout).with_context(|| {
        format!(
            "gpgconf --list-dirs agent-socket returned non-UTF-8 stdout for GnuPG home {}",
            home.display()
        )
    })?;
    let socket = parse_gpgconf_agent_socket(&stdout).with_context(|| {
        format!(
            "gpgconf --list-dirs agent-socket did not report an absolute agent socket for GnuPG home {}; stdout: {}",
            home.display(),
            stdout.trim()
        )
    })?;
    prepare_gpgconf_socket_directory_with(home, &socket, &mut run)?;
    Ok(socket)
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn parse_gpgconf_agent_socket(stdout: &str) -> Option<PathBuf> {
    stdout
        .lines()
        .map(str::trim)
        .find(|line| !line.is_empty())
        .map(|line| line.strip_prefix("agent-socket:").unwrap_or(line))
        .map(PathBuf::from)
        .filter(|path| path.is_absolute())
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn prepare_gpgconf_socket_directory_with<F>(home: &Path, socket: &Path, run: &mut F) -> Result<()>
where
    F: FnMut(&Path, &[&str]) -> Result<Output>,
{
    if socket.starts_with(home) {
        return Ok(());
    }

    const CREATE_SOCKET_DIR_ARGS: &[&str] = &["--create-socketdir"];
    let output = run(home, CREATE_SOCKET_DIR_ARGS).with_context(|| {
        format!(
            "failed to run gpgconf --create-socketdir for GnuPG home {}",
            home.display()
        )
    })?;
    if !output.status.success() {
        anyhow::bail!(
            "gpgconf --create-socketdir exited with {} for GnuPG home {}; stdout: {}; stderr: {}",
            output.status,
            home.display(),
            String::from_utf8_lossy(&output.stdout).trim(),
            String::from_utf8_lossy(&output.stderr).trim()
        );
    }
    Ok(())
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn run_gpgconf(home: &Path, args: &[&str]) -> Result<Output> {
    Command::new("gpgconf")
        .arg("--homedir")
        .arg(home)
        .args(args)
        .output()
        .with_context(|| format!("could not start gpgconf at {}", home.display()))
}

#[cfg(unix)]
fn resolve_default_gpg_agent_socket_path<F>(
    gpg_home: PathBuf,
    resolve_with_gpgconf: F,
) -> Result<PathBuf>
where
    F: FnOnce(&Path) -> Result<PathBuf>,
{
    resolve_with_gpgconf(&gpg_home).with_context(|| {
        format!(
            "failed to resolve GnuPG's standard agent socket for managed home {}; ensure gpgconf \
             is on PATH and can prepare the per-user runtime socket directory with \
             gpgconf --create-socketdir",
            gpg_home.display()
        )
    })
}

/// Returns the default path for the GPG agent socket.
///
/// # Errors
///
/// Returns an error when the home directory cannot be resolved, `gpgconf`
/// cannot report an absolute standard agent socket, or its external socket
/// directory cannot be prepared.
#[cfg(unix)]
pub fn default_gpg_agent_socket_path() -> Result<PathBuf> {
    #[cfg(target_os = "macos")]
    {
        let home = dirs::home_dir().context("could not determine the macOS home directory")?;
        let gpg_home = macos_managed_gpg_home_path(&home);
        resolve_default_gpg_agent_socket_path(gpg_home, gpgconf_agent_socket_path)
    }

    #[cfg(target_os = "linux")]
    {
        let gpg_home = linux_managed_gpg_home_path()?;
        resolve_default_gpg_agent_socket_path(gpg_home, gpgconf_agent_socket_path)
    }
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;
    use std::ffi::OsStr;
    use std::os::unix::ffi::OsStrExt;
    use std::os::unix::process::ExitStatusExt;

    #[test]
    fn default_policy_returns_the_injected_absolute_gpgconf_socket() {
        let gpg_home = PathBuf::from("/Users/alice/.keyguard/gnupg");
        let expected_socket = PathBuf::from("/private/tmp/gnupg/d.abc/S.gpg-agent");

        let socket = resolve_default_gpg_agent_socket_path(gpg_home.clone(), |resolved_home| {
            assert_eq!(resolved_home, gpg_home);
            Ok(expected_socket.clone())
        })
        .expect("injected gpgconf resolution should succeed");

        assert_eq!(socket, expected_socket);
        assert!(socket.is_absolute());
    }

    #[test]
    fn macos_default_policy_propagates_gpgconf_failure() {
        let gpg_home = PathBuf::from("/Users/alice/.keyguard/gnupg");

        let error = resolve_default_gpg_agent_socket_path(gpg_home, |_| {
            anyhow::bail!("gpgconf executable is unavailable")
        })
        .expect_err("macOS policy must fail closed");

        let diagnostic = format!("{error:#}");
        assert!(diagnostic.contains("failed to resolve GnuPG's standard agent socket"));
        assert!(diagnostic.contains("gpgconf executable is unavailable"));
    }

    #[test]
    fn linux_default_policy_propagates_gpgconf_failure_without_fallback() {
        let gpg_home = PathBuf::from("/home/alice/.local/share/keyguard/gnupg");

        let error = resolve_default_gpg_agent_socket_path(gpg_home.clone(), |_| {
            anyhow::bail!("gpgconf executable is unavailable")
        })
        .expect_err("Linux policy must fail closed");

        let diagnostic = format!("{error:#}");
        assert!(diagnostic.contains("failed to resolve GnuPG's standard agent socket"));
        assert!(diagnostic.contains(gpg_home.to_str().expect("UTF-8 test path")));
        assert!(diagnostic.contains("gpgconf --create-socketdir"));
        assert!(diagnostic.contains("gpgconf executable is unavailable"));
        assert!(!diagnostic.contains("fallback"));
    }

    #[test]
    fn external_gpgconf_socket_requires_create_socketdir_success() {
        let gpg_home = PathBuf::from("/Users/alice/.keyguard/gnupg");
        let external_socket = "/private/tmp/gnupg/d.abc/S.gpg-agent";
        let mut invocation_count = 0;

        let error = gpgconf_agent_socket_path_with(&gpg_home, |_, args| {
            invocation_count += 1;
            if args == ["--list-dirs", "agent-socket"] {
                Ok(command_output(0, external_socket, ""))
            } else if args == ["--create-socketdir"] {
                Ok(command_output(7, "", "socket directory denied"))
            } else {
                panic!("unexpected gpgconf arguments: {args:?}");
            }
        })
        .expect_err("external socket directory preparation must fail closed");

        let diagnostic = format!("{error:#}");
        assert_eq!(invocation_count, 2);
        assert!(diagnostic.contains("gpgconf --create-socketdir exited"));
        assert!(diagnostic.contains("socket directory denied"));
    }

    #[test]
    fn gpgconf_list_failure_preserves_home_status_and_output() {
        let gpg_home = PathBuf::from("/Users/alice/.keyguard/gnupg");

        let error = gpgconf_agent_socket_path_with(&gpg_home, |_, args| {
            assert_eq!(args, ["--list-dirs", "agent-socket"]);
            Ok(command_output(2, "partial stdout", "gpgconf failed"))
        })
        .expect_err("list-dirs failure must be reported");

        let diagnostic = format!("{error:#}");
        assert!(diagnostic.contains("gpgconf --list-dirs agent-socket exited"));
        assert!(diagnostic.contains(gpg_home.to_str().expect("UTF-8 test path")));
        assert!(diagnostic.contains("partial stdout"));
        assert!(diagnostic.contains("gpgconf failed"));
    }

    #[test]
    fn gpgconf_start_failure_has_invocation_and_home_context() {
        let gpg_home = PathBuf::from("/Users/alice/.keyguard/gnupg");

        let error = gpgconf_agent_socket_path_with(&gpg_home, |_, _| {
            Err(anyhow::anyhow!("permission denied while starting gpgconf"))
        })
        .expect_err("start failure must be contextualized");

        let diagnostic = format!("{error:#}");
        assert!(diagnostic.contains("failed to run gpgconf --list-dirs agent-socket"));
        assert!(diagnostic.contains(gpg_home.to_str().expect("UTF-8 test path")));
        assert!(diagnostic.contains("permission denied while starting gpgconf"));
    }

    fn command_output(exit_code: i32, stdout: &str, stderr: &str) -> Output {
        Output {
            status: std::process::ExitStatus::from_raw(exit_code << 8),
            stdout: stdout.as_bytes().to_vec(),
            stderr: stderr.as_bytes().to_vec(),
        }
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn macos_managed_home_uses_keyguard_directory() {
        let home = Path::new("/Users/alice");
        let gpg_home = macos_managed_gpg_home_path(home);
        assert_eq!(gpg_home, PathBuf::from("/Users/alice/.keyguard/gnupg"));
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn macos_dev_fallback_path_matches_layout() {
        // SAFETY: `getuid` takes no arguments, has no preconditions, and only
        // returns the real user ID of the calling process.
        let uid = unsafe { libc::getuid() };
        assert_eq!(
            linux_fallback_gpg_agent_socket_path(uid),
            PathBuf::from(format!("/tmp/keyguard-{uid}/gnupg/S.gpg-agent"))
        );
    }

    #[test]
    fn linux_path_uses_absolute_xdg_data_directory() {
        for (directory, expected) in [
            ("/data", "/data/keyguard/gnupg"),
            ("/data/", "/data/keyguard/gnupg"),
            ("/data///", "/data/keyguard/gnupg"),
            (
                "/data//space dir/ключі///",
                "/data/space dir/ключі/keyguard/gnupg",
            ),
            (
                "/data//./linked/../directory/",
                "/data/./linked/../directory/keyguard/gnupg",
            ),
            (
                r"///data//bookkeeper\\keys///",
                r"/data/bookkeeper\\keys/keyguard/gnupg",
            ),
            ("/", "/keyguard/gnupg"),
            ("//", "/keyguard/gnupg"),
        ] {
            let path = linux_persistent_gpg_home_path(
                Path::new("/home/alice"),
                Some(Path::new(directory)),
            );

            // Path equality ignores repeated separators and interior `.`;
            // GnuPG's socket-directory hash uses the actual argument bytes.
            assert_eq!(
                path.as_os_str(),
                OsStr::new(expected),
                "XDG_DATA_HOME={directory}"
            );
        }
    }

    #[test]
    fn linux_path_uses_default_data_directory_for_unset_empty_or_relative_xdg() {
        for directory in [
            None,
            Some(""),
            Some("  "),
            Some("relative/data"),
            Some("~/data"),
        ] {
            assert_eq!(
                linux_persistent_gpg_home_path(
                    Path::new("/home//alice///"),
                    directory.map(Path::new)
                )
                .as_os_str(),
                OsStr::new("/home/alice/.local/share/keyguard/gnupg")
            );
        }
    }

    #[test]
    fn linux_path_preserves_non_utf8_directory_bytes() {
        let path = linux_persistent_gpg_home_path(
            Path::new("/home/alice"),
            Some(Path::new(OsStr::from_bytes(b"/data/\xff//keys///"))),
        );

        assert_eq!(
            path.as_os_str().as_bytes(),
            b"/data/\xff/keys/keyguard/gnupg"
        );
    }

    #[test]
    fn linux_default_passes_the_same_lexical_home_to_both_gpgconf_commands() {
        let home = linux_persistent_gpg_home_path(
            Path::new("/home/alice"),
            Some(Path::new("/data//./linked/../directory///")),
        );
        let mut invocations = Vec::new();
        let socket = resolve_default_gpg_agent_socket_path(home, |home| {
            gpgconf_agent_socket_path_with(home, |home, args| {
                assert_eq!(
                    home.as_os_str(),
                    OsStr::new("/data/./linked/../directory/keyguard/gnupg"),
                );
                invocations.push(args.iter().map(|arg| arg.to_string()).collect::<Vec<_>>());
                Ok(command_output(
                    0,
                    "/run/user/1000/gnupg/d.test/S.gpg-agent\n",
                    "",
                ))
            })
        })
        .expect("resolve external socket");

        assert_eq!(socket, Path::new("/run/user/1000/gnupg/d.test/S.gpg-agent"));
        assert_eq!(
            invocations,
            [
                vec!["--list-dirs", "agent-socket"],
                vec!["--create-socketdir"]
            ],
        );
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn flatpak_path_uses_persistent_app_data_dir() {
        let path = flatpak_gpg_home_path(
            None,
            Path::new("/home/alice"),
            Some("com.artemchep.keyguard"),
        );
        assert_eq!(
            path,
            PathBuf::from("/home/alice/.var/app/com.artemchep.keyguard/data/gnupg")
        );
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn flatpak_path_prefers_xdg_data_home() {
        let path = flatpak_gpg_home_path(
            Some("/home/alice/.var/app/com.artemchep.keyguard/data"),
            Path::new("/home/alice"),
            Some("com.example.Other"),
        );
        assert_eq!(
            path,
            PathBuf::from("/home/alice/.var/app/com.artemchep.keyguard/data/gnupg")
        );
    }

    #[cfg(any(target_os = "linux", target_os = "macos"))]
    #[test]
    fn gpgconf_agent_socket_parser_accepts_plain_path() {
        assert_eq!(
            parse_gpgconf_agent_socket("/run/user/1000/gnupg/d.abc/S.gpg-agent\n"),
            Some(PathBuf::from("/run/user/1000/gnupg/d.abc/S.gpg-agent"))
        );
    }

    #[cfg(any(target_os = "linux", target_os = "macos"))]
    #[test]
    fn gpgconf_agent_socket_parser_accepts_keyed_path() {
        assert_eq!(
            parse_gpgconf_agent_socket("agent-socket:/run/user/1000/gnupg/S.gpg-agent\n"),
            Some(PathBuf::from("/run/user/1000/gnupg/S.gpg-agent"))
        );
    }
}
