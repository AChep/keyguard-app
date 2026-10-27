use keyguard_agent_setup::{
    Agent, AgentKind, Environment, Override, Request, SetupError, Shell, apply, plan, undo,
};
use std::{
    fs,
    time::{Duration, SystemTime},
};
use support::{put, put_dated};

mod support;

fn fixture(shell: Shell) -> (tempfile::TempDir, Environment, Request) {
    let dir = tempfile::tempdir().unwrap();
    let env = support::environment(dir.path(), "PowerShell/profile.ps1");
    let request = Request {
        agent: Agent::Gpg {
            home: env.home.join("Keyguard/gnupg"),
        },
        shells: vec![shell],
        launcher: None,
    };
    (dir, env, request)
}

fn reported(overrides: &[Override]) -> Vec<(String, usize)> {
    overrides
        .iter()
        .map(|item| {
            assert_eq!(item.setting, "GNUPGHOME");
            let name = item.path.file_name().unwrap().to_string_lossy();
            (name.into_owned(), item.line)
        })
        .collect()
}

#[test]
fn manual_settings_before_the_block_are_kept_and_never_block() {
    let (_dir, env, request) = fixture(Shell::Bash);
    // Ubuntu's default .bashrc and .profile, with the in-app manual snippet.
    let bashrc = concat!(
        "case $- in\n",
        "    *i*) ;;\n",
        "      *) return;;\n",
        "esac\n",
        "if [ -f ~/.bash_aliases ]; then\n",
        "    . ~/.bash_aliases\n",
        "fi\n",
        "case \"${XDG_DATA_HOME:-}\" in\n",
        "  /*) GNUPGHOME=\"$XDG_DATA_HOME/keyguard/gnupg\" ;;\n",
        "  *) GNUPGHOME=\"$HOME/.local/share/keyguard/gnupg\" ;;\n",
        "esac\n",
        "GNUPGHOME=\"$(printf '%s' \"$GNUPGHOME\" | tr -s '/')\"\n",
        "export GNUPGHOME\n",
    );
    let profile = concat!(
        "if [ -n \"$BASH_VERSION\" ]; then\n",
        "    if [ -f \"$HOME/.bashrc\" ]; then\n",
        "\t. \"$HOME/.bashrc\"\n",
        "    fi\n",
        "fi\n",
    );
    put(&env, ".bashrc", bashrc);
    put(&env, ".profile", profile);
    let reviewed = plan(&env, request).unwrap();
    assert!(reviewed.conflicts.is_empty(), "{:?}", reviewed.conflicts);
    assert!(reviewed.overrides.is_empty(), "{:?}", reviewed.overrides);
    assert_eq!(reviewed.edits.len(), 2);
    apply(&env, &reviewed).unwrap();
    apply(&env, &undo(&env, AgentKind::Gpg).unwrap()).unwrap();
    assert_eq!(
        fs::read_to_string(env.home.join(".bashrc")).unwrap(),
        bashrc
    );
    assert_eq!(
        fs::read_to_string(env.home.join(".profile")).unwrap(),
        profile
    );
}

#[test]
fn lines_after_the_block_are_reported_with_their_line_numbers() {
    let (_dir, env, request) = fixture(Shell::Zsh);
    put(&env, ".zshrc", "export EDITOR=vim\n");
    apply(&env, &plan(&env, request.clone()).unwrap()).unwrap();
    let path = env.home.join(".zshrc");
    let managed = fs::read_to_string(&path).unwrap();
    let lines = managed.lines().count();
    fs::write(
        &path,
        managed + "# GNUPGHOME is set above\n\nexport GNUPGHOME=/other\n",
    )
    .unwrap();
    let reviewed = plan(&env, request).unwrap();
    assert!(reviewed.conflicts.is_empty(), "{:?}", reviewed.conflicts);
    assert!(reviewed.edits.is_empty());
    assert_eq!(
        reported(&reviewed.overrides),
        [(".zshrc".into(), lines + 3)]
    );
    // A reported line is for review; it does not block Apply.
    apply(&env, &reviewed).unwrap();
}

#[test]
fn only_startup_files_that_run_later_are_reported() {
    let (_dir, env, request) = fixture(Shell::Zsh);
    for name in [".zshenv", ".zprofile", ".zlogin"] {
        put(&env, name, "export GNUPGHOME=/other\n");
    }
    let reviewed = plan(&env, request).unwrap();
    assert!(reviewed.conflicts.is_empty(), "{:?}", reviewed.conflicts);
    assert_eq!(reported(&reviewed.overrides), [(".zlogin".into(), 1)]);

    let (_dir, env, request) = fixture(Shell::Fish);
    for name in [
        "conf.d/00-early.fish",
        "conf.d/zz-late.fish",
        "conf.d/.hidden.fish",
        "conf.d/manual.FISH",
        "config.fish",
    ] {
        put(
            &env,
            &format!(".config/fish/{name}"),
            "set -gx GNUPGHOME /other\n",
        );
    }
    let reviewed = plan(&env, request).unwrap();
    assert_eq!(
        reported(&reviewed.overrides),
        [
            ("00-early.fish".into(), 1),
            ("zz-late.fish".into(), 1),
            ("config.fish".into(), 1),
        ]
    );

    for shell in [Shell::Powershell, Shell::WindowsPowershell] {
        let (_dir, env, request) = fixture(shell);
        put(
            &env,
            "PowerShell/Microsoft.PowerShell_profile.ps1",
            "$env:gnupghome = 'other'\n",
        );
        put(&env, "PowerShell/helper.ps1", "$env:GNUPGHOME = 'other'\n");
        let reviewed = plan(&env, request).unwrap();
        assert_eq!(
            reported(&reviewed.overrides),
            [("Microsoft.PowerShell_profile.ps1".into(), 1)]
        );
    }
}

#[test]
fn reviewed_plan_requires_unchanged_read_dependencies() {
    for (path, content) in [
        (".zshrc", "# edited\n"),
        (".zlogin", "# new file\n"),
        (".zshenv", "ZDOTDIR=/another\n"),
    ] {
        let (_dir, env, request) = fixture(Shell::Zsh);
        apply(&env, &plan(&env, request.clone()).unwrap()).unwrap();
        let reviewed = plan(&env, request).unwrap();
        assert!(reviewed.edits.is_empty());
        let file = env.home.join(path);
        let text = fs::read_to_string(&file).unwrap_or_default() + content;
        fs::write(&file, text).unwrap();
        assert!(apply(&env, &reviewed).is_err(), "{path}");
    }
}

#[test]
fn ssh_shell_settings_are_reported_but_not_adopted() {
    let (_dir, env, mut request) = fixture(Shell::Zsh);
    request.agent = Agent::Ssh {
        socket: env.home.join("agent.sock").to_string_lossy().into_owned(),
        host: None,
    };
    put(&env, ".zlogin", "export SSH_AUTH_SOCK=/other\n");
    let reviewed = plan(&env, request).unwrap();
    assert_eq!(reviewed.edits.len(), 2);
    assert_eq!(reviewed.overrides[0].setting, "SSH_AUTH_SOCK");
    apply(&env, &reviewed).unwrap();
    apply(&env, &undo(&env, AgentKind::Ssh).unwrap()).unwrap();
    assert_eq!(
        fs::read_to_string(env.home.join(".zlogin")).unwrap(),
        "export SSH_AUTH_SOCK=/other\n"
    );
}

#[test]
fn old_plan_requires_an_update_but_existing_journals_still_undo() {
    let (_dir, env, request) = fixture(Shell::Zsh);
    let reviewed = plan(&env, request).unwrap();
    let mut old = serde_json::to_value(&reviewed).unwrap();
    old["version"] = 3.into();
    let old = serde_json::from_value(old).unwrap();
    assert_eq!(
        apply(&env, &old).unwrap_err().downcast_ref(),
        Some(&SetupError::UpdateRequired)
    );
    assert!(!env.home.join(".keyguard").exists());
    apply(&env, &reviewed).unwrap();
    apply(&env, &undo(&env, AgentKind::Gpg).unwrap()).unwrap();
    assert!(!env.home.join(".zshrc").exists());
}

#[test]
fn only_compiled_copies_newer_than_the_edit_are_conflicts() {
    let hour = Duration::from_secs(3600);
    // Zsh loads a compiled copy unless its source is at least a second newer.
    // Setup rewrites .zshrc, and .zlogin is only read.
    for (name, time, conflict) in [
        (".zshrc.zwc", SystemTime::now() - hour, false),
        (".zshrc.zwc", SystemTime::now() + hour, true),
        (".zlogin.zwc", SystemTime::now() + hour, false),
    ] {
        let (_dir, env, request) = fixture(Shell::Zsh);
        put(&env, ".zlogin", "# login\n");
        put_dated(&env, name, "compiled", time);
        assert_eq!(
            plan(&env, request).unwrap().conflicts.is_empty(),
            !conflict,
            "{name}"
        );
    }
}

#[test]
fn undo_keeps_a_created_zsh_profile_that_has_a_compiled_copy() {
    let (_dir, env, request) = fixture(Shell::Zsh);
    apply(&env, &plan(&env, request).unwrap()).unwrap();
    put_dated(
        &env,
        ".zshrc.zwc",
        "compiled",
        SystemTime::now() - Duration::from_secs(3600),
    );
    apply(&env, &undo(&env, AgentKind::Gpg).unwrap()).unwrap();
    assert_eq!(fs::read_to_string(env.home.join(".zshrc")).unwrap(), "");
}

#[cfg(unix)]
#[test]
fn only_private_groups_may_write_configuration() {
    use std::os::unix::fs::{MetadataExt, PermissionsExt};
    let (_dir, env, request) = fixture(Shell::Zsh);
    put(&env, ".zshrc", "export EDITOR=vim\n");
    let path = env.home.join(".zshrc");
    let group = fs::metadata(&path).unwrap().gid();
    // SAFETY: getpwuid returns static storage or null; this test reads it once.
    let private = unsafe {
        let user = libc::getpwuid(libc::geteuid());
        let entry = libc::getgrgid(group);
        !user.is_null()
            && !entry.is_null()
            && (*user).pw_gid == group
            && std::ffi::CStr::from_ptr((*user).pw_name)
                == std::ffi::CStr::from_ptr((*entry).gr_name)
    };
    fs::set_permissions(&path, fs::Permissions::from_mode(0o664)).unwrap();
    assert_eq!(plan(&env, request.clone()).is_ok(), private);
    fs::set_permissions(&path, fs::Permissions::from_mode(0o646)).unwrap();
    assert!(plan(&env, request).is_err());
}
