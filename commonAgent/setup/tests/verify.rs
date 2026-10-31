#![cfg(unix)]

use keyguard_agent_setup::verify;
use std::{fs, os::unix::fs::PermissionsExt, path::Path, sync::Mutex};

fn with_client(script: &str, check: impl FnOnce(&Path)) {
    static CLIENT_LOCK: Mutex<()> = Mutex::new(());
    // A concurrent spawn can inherit another fixture's writable descriptor and
    // cause ETXTBSY. Keep both fixture creation and probing under the same lock.
    let _guard = CLIENT_LOCK.lock().unwrap();
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("client");
    fs::write(&path, format!("#!/bin/sh\n{script}\n")).unwrap();
    fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).unwrap();
    check(&path);
}

#[test]
fn ssh_probe_supplies_the_selected_socket() {
    with_client(
        "[ \"$1\" = '-L' ] || exit 1\nprintf '%s' \"$SSH_AUTH_SOCK\"",
        |executable| {
            assert_eq!(
                verify::ssh("/tmp/selected agent.sock", executable).unwrap(),
                "/tmp/selected agent.sock"
            );
        },
    );
}

#[test]
fn gpg_probe_disables_autostart_and_requires_keyinfo() {
    with_client(
        "[ \"$1\" = '--homedir' ] && [ \"$2\" = '/test home' ] && [ \"$3\" = '--no-autostart' ] && [ \"$4\" = 'KEYINFO --list' ] || exit 1\nprintf 'S KEYINFO test\\nOK\\n'",
        |executable| {
            let output = verify::gpg(Path::new("/test home"), executable).unwrap();
            assert!(output.starts_with("S KEYINFO"));
        },
    );
}

#[test]
fn assuan_error_is_not_success_even_when_the_client_exits_zero() {
    with_client("printf 'ERR 1 agent unavailable\\n'", |executable| {
        let error = verify::gpg(Path::new("/test home"), executable).unwrap_err();
        assert_eq!(
            error.to_string(),
            "agent check failed: ERR 1 agent unavailable\n"
        );
    });
}
