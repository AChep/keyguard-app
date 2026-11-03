//! Queries of the user's GnuPG installation through `gpgconf` and `gpg`.

use anyhow::{Context, Result};
use std::path::{Path, PathBuf};
use std::process::{Command, Output};

/// Resolves the agent socket that GnuPG uses for `home`, creating its socket
/// directory when it lies outside the home.
pub fn gpgconf_agent_socket_path(home: &Path) -> Result<PathBuf> {
    gpgconf_agent_socket_path_with(home, run_gpgconf)
}

/// [`gpgconf_agent_socket_path`] with an injectable `gpgconf` runner.
pub fn gpgconf_agent_socket_path_with<F>(home: &Path, mut run: F) -> Result<PathBuf>
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

fn parse_gpgconf_agent_socket(stdout: &str) -> Option<PathBuf> {
    stdout
        .lines()
        .map(str::trim)
        .find(|line| !line.is_empty())
        .map(|line| line.strip_prefix("agent-socket:").unwrap_or(line))
        .map(PathBuf::from)
        .filter(|path| path.is_absolute())
}

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

fn run_gpgconf(home: &Path, args: &[&str]) -> Result<Output> {
    Command::new("gpgconf")
        .arg("--homedir")
        .arg(home)
        .args(args)
        .output()
        .with_context(|| format!("could not start gpgconf at {}", home.display()))
}

/// Reports the version of the `gpg` on `PATH`, or `None` when it cannot be
/// determined; GnuPG compares it with the agent's `GETINFO version`.
pub fn gpg_version(home: &Path) -> Option<String> {
    let output = Command::new("gpg")
        .arg("--homedir")
        .arg(home)
        .args([
            "--no-options",
            "--no-keyring",
            "--batch",
            "--no-autostart",
            "--with-colons",
            "--list-config",
            "version",
        ])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    parse_gpg_version(&String::from_utf8_lossy(&output.stdout))
}

fn parse_gpg_version(stdout: &str) -> Option<String> {
    let mut versions = stdout
        .lines()
        .filter_map(|line| line.trim_end().strip_prefix("cfg:version:"));
    let version = versions.next()?;
    if versions.next().is_some() {
        return None;
    }
    crate::version::parse_version(version).ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::process::ExitStatusExt;

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

    #[test]
    fn gpgconf_agent_socket_parser_accepts_plain_path() {
        assert_eq!(
            parse_gpgconf_agent_socket("/run/user/1000/gnupg/d.abc/S.gpg-agent\n"),
            Some(PathBuf::from("/run/user/1000/gnupg/d.abc/S.gpg-agent"))
        );
    }

    #[test]
    fn gpgconf_agent_socket_parser_accepts_keyed_path() {
        assert_eq!(
            parse_gpgconf_agent_socket("agent-socket:/run/user/1000/gnupg/S.gpg-agent\n"),
            Some(PathBuf::from("/run/user/1000/gnupg/S.gpg-agent"))
        );
    }

    #[test]
    fn gpg_version_requires_exactly_one_valid_version_line() {
        assert_eq!(
            parse_gpg_version("cfg:version:2.5.24\n").as_deref(),
            Some("2.5.24")
        );
        assert_eq!(parse_gpg_version(""), None);
        assert_eq!(parse_gpg_version("cfg:version:2.5\n"), None);
        assert_eq!(
            parse_gpg_version("cfg:version:2.5.24\ncfg:version:2.4.8\n"),
            None
        );
    }

    fn command_output(exit_code: i32, stdout: &str, stderr: &str) -> Output {
        Output {
            status: std::process::ExitStatus::from_raw(exit_code << 8),
            stdout: stdout.as_bytes().to_vec(),
            stderr: stderr.as_bytes().to_vec(),
        }
    }
}
