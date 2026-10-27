//! Helpers shared by the integration tests: an isolated environment rooted at a
//! temporary home, and startup files written into it.

#![allow(dead_code, reason = "each test binary uses some of the helpers")]

use keyguard_agent_setup::Environment;
use std::{fs, path::Path, time::SystemTime};

/// An environment whose directories all live under `home`. The PowerShell
/// profile is relative to `home` and no Zsh is set, so no shell probe runs.
pub fn environment(home: &Path, powershell_profile: &str) -> Environment {
    Environment {
        config_home: home.join(".config"),
        zdotdir: home.to_owned(),
        zsh: None,
        powershell_profile: Some(home.join(powershell_profile)),
        documents: None,
        home: home.to_owned(),
        windows: cfg!(windows),
        termux_launcher: None,
    }
}

/// Writes `text` to `relative` under the home, creating parent directories.
pub fn put(env: &Environment, relative: &str, text: &str) {
    let path = env.home.join(relative);
    fs::create_dir_all(path.parent().unwrap()).unwrap();
    fs::write(path, text).unwrap();
}

/// [`put`], with the file's modification time set to `time`.
pub fn put_dated(env: &Environment, relative: &str, text: &str, time: SystemTime) {
    put(env, relative, text);
    fs::File::options()
        .write(true)
        .open(env.home.join(relative))
        .unwrap()
        .set_modified(time)
        .unwrap();
}
