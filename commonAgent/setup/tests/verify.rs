#![cfg(unix)]

use keyguard_agent_setup::verify;
use std::{fs, os::unix::fs::PermissionsExt};

fn client(script: &str) -> (tempfile::TempDir, std::path::PathBuf) {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("client");
    fs::write(&path, format!("#!/bin/sh\n{script}\n")).unwrap();
    fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).unwrap();
    (dir, path)
}

#[test]
fn ssh_probe_supplies_the_selected_socket() {
    let (_dir, executable) = client("[ \"$1\" = '-L' ] || exit 1\nprintf '%s' \"$SSH_AUTH_SOCK\"");
    assert_eq!(
        verify::ssh("/tmp/selected agent.sock", &executable).unwrap(),
        "/tmp/selected agent.sock"
    );
}

#[test]
fn gpg_probe_disables_autostart_and_requires_keyinfo() {
    let (_dir, executable) = client(
        "[ \"$1\" = '--homedir' ] && [ \"$2\" = '/test home' ] && [ \"$3\" = '--no-autostart' ] && [ \"$4\" = 'KEYINFO --list' ] || exit 1\nprintf 'S KEYINFO test\\nOK\\n'",
    );
    let output = verify::gpg(std::path::Path::new("/test home"), &executable).unwrap();
    assert!(output.starts_with("S KEYINFO"));
}

#[test]
fn assuan_error_is_not_success_even_when_the_client_exits_zero() {
    let (_dir, executable) = client("printf 'ERR 1 agent unavailable\\n'");
    assert!(verify::gpg(std::path::Path::new("/test home"), &executable).is_err());
}
