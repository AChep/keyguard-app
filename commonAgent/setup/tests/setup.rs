use keyguard_agent_setup::{
    Agent, AgentKind, Conflict, Environment, Request, Shell, apply, plan, review_and_apply, undo,
};
use std::fs;
use support::put;

mod support;

fn fixture() -> (tempfile::TempDir, Environment) {
    let dir = tempfile::tempdir().unwrap();
    let env = support::environment(dir.path(), "Documents/PowerShell/profile.ps1");
    (dir, env)
}

fn ssh(shells: Vec<Shell>) -> Request {
    Request {
        launcher: None,
        agent: Agent::Ssh {
            socket: if cfg!(windows) {
                r"\\.\pipe\keyguard-agent.sock"
            } else {
                "/tmp/keyguard test/agent.sock"
            }
            .into(),
            host: None,
        },
        shells,
    }
}

#[test]
fn plan_is_read_only_and_apply_is_idempotent() {
    let (_dir, env) = fixture();
    let request = ssh(vec![
        Shell::Bash,
        Shell::Zsh,
        Shell::Fish,
        Shell::Powershell,
    ]);
    let first = plan(&env, request.clone()).unwrap();
    assert!(!env.home.join(".ssh").exists());
    assert!(!env.home.join(".keyguard").exists());
    assert_eq!(first.edits.len(), 6);
    apply(&env, &first).unwrap();
    assert!(plan(&env, request).unwrap().edits.is_empty());
    assert!(
        fs::read_to_string(env.home.join(".bashrc"))
            .unwrap()
            .contains("SSH_CONNECTION")
    );
}

#[test]
fn bash_preserves_the_active_login_profile_and_crlf() {
    let (_dir, env) = fixture();
    put(
        &env,
        ".profile",
        "# existing profile\r\nexport EDITOR=vim\r\n",
    );
    let first = plan(&env, ssh(vec![Shell::Bash])).unwrap();
    assert!(
        first
            .edits
            .iter()
            .any(|edit| edit.path.ends_with(".profile"))
    );
    apply(&env, &first).unwrap();
    assert!(!env.home.join(".bash_profile").exists());
    let text = fs::read_to_string(env.home.join(".profile")).unwrap();
    assert!(text.starts_with("# existing profile\r\nexport EDITOR=vim\r\n"));
    assert!(!text.replace("\r\n", "").contains('\n'));
    apply(&env, &undo(&env, AgentKind::Ssh).unwrap()).unwrap();
    assert_eq!(
        fs::read_to_string(env.home.join(".profile")).unwrap(),
        "# existing profile\r\nexport EDITOR=vim\r\n"
    );
}

#[test]
fn undo_preserves_unrelated_edits_and_other_agent_settings() {
    let (_dir, env) = fixture();
    apply(&env, &plan(&env, ssh(vec![Shell::Zsh])).unwrap()).unwrap();
    let gpg = Request {
        launcher: None,
        agent: Agent::Gpg {
            home: env.home.join(".keyguard/gnupg"),
        },
        shells: vec![Shell::Zsh],
    };
    apply(&env, &plan(&env, gpg).unwrap()).unwrap();
    let path = env.home.join(".zshrc");
    let text = fs::read_to_string(&path).unwrap() + "\nexport EDITOR=vim\n";
    fs::write(&path, text).unwrap();
    apply(&env, &undo(&env, AgentKind::Ssh).unwrap()).unwrap();
    let text = fs::read_to_string(&path).unwrap();
    assert!(!text.contains("SSH_AUTH_SOCK"));
    assert!(text.contains("GNUPGHOME"));
    assert!(text.contains("export EDITOR=vim"));
    apply(&env, &undo(&env, AgentKind::Gpg).unwrap()).unwrap();
    assert!(
        fs::read_to_string(&path)
            .unwrap()
            .contains("export EDITOR=vim")
    );
}

#[test]
fn stale_plan_never_overwrites_new_settings() {
    let (_dir, env) = fixture();
    let reviewed = plan(&env, ssh(vec![Shell::Bash])).unwrap();
    put(&env, ".bashrc", "export SSH_AUTH_SOCK=/another/agent\n");
    assert!(apply(&env, &reviewed).is_err());
    assert!(!env.home.join(".ssh/config").exists());
    assert!(
        fs::read_to_string(env.home.join(".bashrc"))
            .unwrap()
            .contains("/another/agent")
    );
}

#[test]
fn ssh_configuration_reports_identity_agents_that_take_precedence() {
    for (config, lines) in [
        ("Include ~/.orbstack/ssh/config\n", vec![]),
        ("Match exec touch-something\n", vec![]),
        (
            "# IdentityAgent /old\nHost *\n IdentityAgent /other\n",
            vec![3],
        ),
        ("IdentityAgent=/other\n", vec![1]),
        (
            "Host *\n  identityagent \"${HOME}/.keyguard/ssh-agent.sock\"\n",
            vec![2],
        ),
    ] {
        let (_dir, env) = fixture();
        put(&env, ".ssh/config", config);
        let reviewed = plan(&env, ssh(vec![])).unwrap();
        assert!(reviewed.conflicts.is_empty(), "{:?}", reviewed.conflicts);
        let reported: Vec<_> = reviewed.overrides.iter().map(|item| item.line).collect();
        assert_eq!(reported, lines, "{config}");
        apply(&env, &reviewed).unwrap();
        assert!(
            fs::read_to_string(env.home.join(".ssh/config"))
                .unwrap()
                .starts_with(config)
        );
    }
    // OpenSSH uses the first value, so lines after Keyguard's block lose.
    let (_dir, env) = fixture();
    apply(&env, &plan(&env, ssh(vec![])).unwrap()).unwrap();
    let path = env.home.join(".ssh/config");
    let text = fs::read_to_string(&path).unwrap() + "Host *\n  IdentityAgent /other\n";
    fs::write(&path, text).unwrap();
    assert!(plan(&env, ssh(vec![])).unwrap().overrides.is_empty());
}

#[test]
fn changed_managed_block_requires_manual_resolution_on_undo() {
    let (_dir, env) = fixture();
    apply(&env, &plan(&env, ssh(vec![])).unwrap()).unwrap();
    let path = env.home.join(".ssh/config");
    let text = fs::read_to_string(&path)
        .unwrap()
        .replace("agent.sock", "different.sock");
    fs::write(&path, &text).unwrap();
    assert!(!undo(&env, AgentKind::Ssh).unwrap().conflicts.is_empty());
    assert!(apply(&env, &undo(&env, AgentKind::Ssh).unwrap()).is_err());
    assert_eq!(fs::read_to_string(&path).unwrap(), text);
}

#[cfg(unix)]
#[test]
fn symlinked_dotfiles_are_preserved() {
    let (_dir, env) = fixture();
    put(&env, "dotfiles/bashrc", "# dotfiles\n");
    std::os::unix::fs::symlink(env.home.join("dotfiles/bashrc"), env.home.join(".bashrc")).unwrap();
    assert!(plan(&env, ssh(vec![Shell::Bash])).is_err());
    assert_eq!(
        fs::read_to_string(env.home.join("dotfiles/bashrc")).unwrap(),
        "# dotfiles\n"
    );
}

#[test]
fn rejects_ssh_configuration_injection() {
    let (_dir, env) = fixture();
    for socket in [
        "/tmp/agent\nProxyCommand bad",
        "/tmp/%h.sock",
        "/tmp/$OTHER",
        "/tmp/\"bad",
    ] {
        let request = Request {
            launcher: None,
            agent: Agent::Ssh {
                socket: socket.into(),
                host: None,
            },
            shells: vec![],
        };
        assert!(plan(&env, request).is_err());
    }
}

#[test]
fn future_compiled_zsh_profiles_are_conflicts() {
    let (_dir, env) = fixture();
    let time = std::time::SystemTime::now() + std::time::Duration::from_secs(3600);
    support::put_dated(&env, ".zshrc.zwc", "compiled", time);
    assert!(
        !plan(&env, ssh(vec![Shell::Zsh]))
            .unwrap()
            .conflicts
            .is_empty()
    );
}

#[test]
fn windows_pipe_uses_forward_slashes_in_ssh_configuration() {
    let (_dir, mut env) = fixture();
    env.windows = true;
    let request = Request {
        launcher: None,
        agent: Agent::Ssh {
            socket: r"\\.\pipe\keyguard-ssh-agent".into(),
            host: Some("github.com".into()),
        },
        shells: vec![],
    };
    let plan = plan(&env, request).unwrap();
    assert!(
        plan.edits[0]
            .after
            .as_ref()
            .unwrap()
            .contains("IdentityAgent \"//./pipe/keyguard-ssh-agent\"")
    );
}

#[test]
fn changing_shell_selection_removes_only_retired_managed_settings() {
    let (_dir, env) = fixture();
    put(&env, ".bashrc", "export EDITOR=vim\n");
    apply(&env, &plan(&env, ssh(vec![Shell::Bash])).unwrap()).unwrap();
    let changed = plan(&env, ssh(vec![Shell::Zsh])).unwrap();
    apply(&env, &changed).unwrap();
    assert_eq!(
        fs::read_to_string(env.home.join(".bashrc")).unwrap(),
        "export EDITOR=vim\n"
    );
    assert!(!env.home.join(".bash_profile").exists());
    assert!(
        fs::read_to_string(env.home.join(".zshrc"))
            .unwrap()
            .contains("SSH_AUTH_SOCK")
    );
    apply(&env, &undo(&env, AgentKind::Ssh).unwrap()).unwrap();
    assert!(!env.home.join(".zshrc").exists());
}

#[test]
fn undo_requires_the_exact_reviewed_files() {
    let (_dir, env) = fixture();
    apply(&env, &plan(&env, ssh(vec![])).unwrap()).unwrap();
    let reviewed = undo(&env, AgentKind::Ssh).unwrap();
    let path = env.home.join(".ssh/config");
    let text = fs::read_to_string(&path).unwrap() + "\nHost personal\n  User me\n";
    fs::write(&path, &text).unwrap();
    assert!(apply(&env, &reviewed).is_err());
    assert_eq!(fs::read_to_string(&path).unwrap(), text);
    apply(&env, &undo(&env, AgentKind::Ssh).unwrap()).unwrap();
    assert_eq!(
        fs::read_to_string(&path).unwrap(),
        "\nHost personal\n  User me\n"
    );
}

#[test]
fn custom_zdotdir_requires_explicit_discovery() {
    let (_dir, mut env) = fixture();
    put(&env, ".zshenv", "ZDOTDIR=$HOME/custom-zsh\n");
    assert!(
        !plan(&env, ssh(vec![Shell::Zsh]))
            .unwrap()
            .conflicts
            .is_empty()
    );
    env.zdotdir = env.home.join("custom-zsh");
    let plan = plan(&env, ssh(vec![Shell::Zsh])).unwrap();
    assert!(plan.conflicts.is_empty());
    assert!(
        plan.edits
            .iter()
            .any(|edit| edit.path == env.zdotdir.join(".zshrc"))
    );
}

#[cfg(unix)]
#[test]
fn bash_keeps_forwarded_socket_and_quotes_values() {
    let (_dir, env) = fixture();
    let mut request = ssh(vec![Shell::Bash]);
    request.agent = Agent::Ssh {
        socket: "/tmp/keyguard's agent.sock".into(),
        host: None,
    };
    apply(&env, &plan(&env, request).unwrap()).unwrap();
    let probe = |forwarded: bool| {
        let mut command = std::process::Command::new("bash");
        command
            .args([
                "--noprofile",
                "--norc",
                "-c",
                ". \"$1\"; printf '%s' \"$SSH_AUTH_SOCK\"",
                "test",
            ])
            .arg(env.home.join(".bashrc"));
        command
            .env_remove("BASH_ENV")
            .env_remove("SSH_CONNECTION")
            .env_remove("SSH_AUTH_SOCK");
        if forwarded {
            command
                .env("SSH_CONNECTION", "forwarded")
                .env("SSH_AUTH_SOCK", "/forwarded");
        }
        let output = command.output().unwrap();
        assert!(output.status.success());
        String::from_utf8(output.stdout).unwrap()
    };
    assert_eq!(probe(false), "/tmp/keyguard's agent.sock");
    assert_eq!(probe(true), "/forwarded");
}

#[test]
fn interrupted_apply_can_be_recovered_by_undo() {
    let (_dir, env) = fixture();
    let reviewed = plan(&env, ssh(vec![Shell::Bash])).unwrap();
    apply(&env, &reviewed).unwrap();
    // A persisted journal with only the first write is the state after a crash.
    for edit in reviewed.edits.iter().skip(1) {
        fs::remove_file(&edit.path).unwrap();
    }
    let recovery = undo(&env, AgentKind::Ssh).unwrap();
    assert!(recovery.conflicts.is_empty());
    apply(&env, &recovery).unwrap();
    assert!(!env.home.join(".ssh/config").exists());
    assert!(!env.home.join(".keyguard/agent-setup/ssh.json").exists());
}

#[cfg(unix)]
#[test]
fn interrupted_apply_keeps_replaced_and_retired_blocks_recorded() {
    use std::os::unix::fs::PermissionsExt;
    let (_dir, env) = fixture();
    apply(
        &env,
        &plan(&env, ssh(vec![Shell::Bash, Shell::Zsh])).unwrap(),
    )
    .unwrap();
    let mut moved = ssh(vec![Shell::Bash]);
    if let Agent::Ssh { socket, .. } = &mut moved.agent {
        *socket = socket.replace("agent", "moved");
    }
    let reviewed = plan(&env, moved.clone()).unwrap();
    // The first write fails after the journal is saved, so .ssh/config keeps
    // its old block and .zshrc keeps the block of a deselected shell.
    let config = env.home.join(".ssh/config");
    assert_eq!(reviewed.edits[0].path, config);
    let dir = env.home.join(".ssh");
    fs::set_permissions(&dir, fs::Permissions::from_mode(0o500)).unwrap();
    let result = apply(&env, &reviewed);
    fs::set_permissions(&dir, fs::Permissions::from_mode(0o700)).unwrap();
    assert!(result.is_err());

    let recovery = undo(&env, AgentKind::Ssh).unwrap();
    assert!(recovery.conflicts.is_empty());
    for path in [&config, &env.home.join(".zshrc")] {
        assert!(recovery.edits.iter().any(|edit| &edit.path == path));
    }
    apply(&env, &plan(&env, moved).unwrap()).unwrap();
    apply(&env, &undo(&env, AgentKind::Ssh).unwrap()).unwrap();
    for name in [".ssh/config", ".bashrc", ".bash_profile", ".zshrc"] {
        assert!(!env.home.join(name).exists(), "{name}");
    }
}

#[test]
fn blocks_without_an_undo_record_are_conflicts() {
    let (_dir, env) = fixture();
    apply(&env, &plan(&env, ssh(vec![Shell::Zsh])).unwrap()).unwrap();
    // Dotfiles copied from another computer, without its undo record.
    fs::remove_file(env.home.join(".keyguard/agent-setup/ssh.json")).unwrap();
    let reviewed = plan(&env, ssh(vec![Shell::Zsh])).unwrap();
    assert!(reviewed.edits.is_empty());
    assert_eq!(
        reviewed.conflicts,
        [
            Conflict::UnrecordedBlock {
                path: env.home.join(".ssh/config")
            },
            Conflict::UnrecordedBlock {
                path: env.home.join(".zshrc")
            },
        ]
    );
    assert!(apply(&env, &reviewed).is_err());
    assert!(!env.home.join(".keyguard/agent-setup/ssh.json").exists());
}

#[test]
fn termux_launches_without_evaluating_helper_output() {
    let (_dir, mut env) = fixture();
    let launcher = env.home.join("bin/keyguard-android-ssh-agent");
    env.termux_launcher = Some(launcher.clone());
    let mut request = ssh(vec![Shell::Bash, Shell::Fish]);
    request.launcher = Some(launcher);
    let plan = plan(&env, request).unwrap();
    assert!(plan.conflicts.is_empty());
    let bash = plan
        .edits
        .iter()
        .find(|edit| edit.path.ends_with(".bashrc"))
        .unwrap()
        .after
        .as_ref()
        .unwrap();
    assert!(bash.contains("--ensure -s -a"));
    assert!(!bash.contains("eval"));
    let fish = plan
        .edits
        .iter()
        .find(|edit| edit.path.ends_with("keyguard-agents.fish"))
        .unwrap()
        .after
        .as_ref()
        .unwrap();
    assert!(fish.contains("if test -x"));
    assert!(fish.contains("set -gx SSH_AUTH_SOCK"));
}

#[test]
fn reports_later_shell_profile_overrides() {
    let (_dir, env) = fixture();
    for (shell, path, text) in [
        (
            Shell::Fish,
            ".config/fish/conf.d/zz-agent.fish",
            "set -gx SSH_AUTH_SOCK /other\n",
        ),
        (Shell::Zsh, ".zlogin", "export SSH_AUTH_SOCK=/other\n"),
        (
            Shell::Powershell,
            "Documents/PowerShell/Microsoft.PowerShell_profile.ps1",
            "$env:SSH_AUTH_SOCK = 'other'\n",
        ),
    ] {
        put(&env, path, text);
        let reviewed = plan(&env, ssh(vec![shell])).unwrap();
        assert!(reviewed.conflicts.is_empty(), "{:?}", reviewed.conflicts);
        assert_eq!(reviewed.overrides.len(), 1, "{path}");
        assert!(reviewed.overrides[0].path.ends_with(path));
    }
}

#[test]
fn powershell_profile_may_live_in_a_redirected_documents_folder() {
    let (_dir, mut env) = fixture();
    let outside = tempfile::tempdir().unwrap();
    let profile = outside.path().join("PowerShell/profile.ps1");
    env.powershell_profile = Some(profile.clone());
    assert!(plan(&env, ssh(vec![Shell::Powershell])).is_err());
    env.documents = Some(outside.path().to_owned());
    let reviewed = plan(&env, ssh(vec![Shell::Powershell])).unwrap();
    assert!(reviewed.edits.iter().any(|edit| edit.path == profile));
    apply(&env, &reviewed).unwrap();
    assert!(
        fs::read_to_string(&profile)
            .unwrap()
            .contains("SSH_AUTH_SOCK")
    );
}

#[cfg(unix)]
#[test]
fn linked_startup_files_are_read_but_not_edited() {
    let (_dir, env) = fixture();
    put(
        &env,
        "dotfiles/agent.fish",
        "set -gx SSH_AUTH_SOCK /other\n",
    );
    put(&env, "dotfiles/zshenv", "export EDITOR=vim\n");
    fs::create_dir_all(env.home.join(".config/fish/conf.d")).unwrap();
    let link = env.home.join(".config/fish/conf.d/agent.fish");
    std::os::unix::fs::symlink(env.home.join("dotfiles/agent.fish"), &link).unwrap();
    std::os::unix::fs::symlink(env.home.join("dotfiles/zshenv"), env.home.join(".zshenv")).unwrap();
    let reviewed = plan(&env, ssh(vec![Shell::Fish, Shell::Zsh])).unwrap();
    assert!(reviewed.conflicts.is_empty(), "{:?}", reviewed.conflicts);
    assert_eq!(reviewed.overrides.len(), 1);
    assert_eq!(reviewed.overrides[0].path, link);
    apply(&env, &reviewed).unwrap();
    assert_eq!(
        fs::read_to_string(&link).unwrap(),
        "set -gx SSH_AUTH_SOCK /other\n"
    );
}

#[test]
fn windows_powershell_profile_has_a_bom_only_for_non_ascii_settings() {
    let (_dir, env) = fixture();
    let profile = env.powershell_profile.clone().unwrap();
    let gpg = |user: &str| Request {
        launcher: None,
        agent: Agent::Gpg {
            home: env.home.join(user).join("gnupg"),
        },
        shells: vec![Shell::WindowsPowershell],
    };
    apply(&env, &plan(&env, gpg("Juergen")).unwrap()).unwrap();
    assert!(
        !fs::read_to_string(&profile)
            .unwrap()
            .starts_with('\u{feff}')
    );
    apply(&env, &plan(&env, gpg("Jürgen")).unwrap()).unwrap();
    assert!(
        fs::read_to_string(&profile)
            .unwrap()
            .starts_with("\u{feff}# Begin Keyguard gpg agent setup")
    );
    assert!(plan(&env, gpg("Jürgen")).unwrap().edits.is_empty());
    apply(&env, &undo(&env, AgentKind::Gpg).unwrap()).unwrap();
    assert!(!profile.exists());
}

#[test]
fn powershell_doubles_typographic_single_quotes() {
    let (_dir, env) = fixture();
    let request = Request {
        launcher: None,
        agent: Agent::Gpg {
            home: env.home.join("O’Brien 'gnupg'"),
        },
        shells: vec![Shell::Powershell],
    };
    let reviewed = plan(&env, request).unwrap();
    let after = reviewed.edits[0].after.as_deref().unwrap();
    assert!(after.contains("O’’Brien ''gnupg''"), "{after}");
}

#[test]
fn undo_after_changed_setup_keeps_later_edits_and_other_agents() {
    let (_dir, env) = fixture();
    apply(&env, &plan(&env, ssh(vec![Shell::Fish])).unwrap()).unwrap();
    let gpg = Request {
        launcher: None,
        agent: Agent::Gpg {
            home: env.home.join(".keyguard/gnupg"),
        },
        shells: vec![Shell::Fish],
    };
    apply(&env, &plan(&env, gpg).unwrap()).unwrap();
    let config = env.home.join(".ssh/config");
    let text = fs::read_to_string(&config).unwrap() + "\nHost work\n  User me\n";
    fs::write(&config, text).unwrap();
    // Both files were created by the first setup and change again here.
    let mut moved = ssh(vec![Shell::Fish]);
    if let Agent::Ssh { socket, .. } = &mut moved.agent {
        *socket = socket.replace("agent", "moved");
    }
    apply(&env, &plan(&env, moved).unwrap()).unwrap();
    apply(&env, &undo(&env, AgentKind::Ssh).unwrap()).unwrap();
    assert_eq!(
        fs::read_to_string(&config).unwrap(),
        "\nHost work\n  User me\n"
    );
    let fish =
        fs::read_to_string(env.home.join(".config/fish/conf.d/keyguard-agents.fish")).unwrap();
    assert!(fish.contains("GNUPGHOME"));
    assert!(!fish.contains("SSH_AUTH_SOCK"));
}

#[test]
fn setup_writes_a_recorded_block_again_when_it_is_missing() {
    let (_dir, env) = fixture();
    put(&env, ".zshrc", "export EDITOR=vim\n");
    apply(&env, &plan(&env, ssh(vec![Shell::Zsh])).unwrap()).unwrap();
    // Removed by hand, and never written by an interrupted apply.
    put(&env, ".zshrc", "export EDITOR=vim\n");
    fs::remove_file(env.home.join(".ssh/config")).unwrap();
    apply(&env, &plan(&env, ssh(vec![Shell::Zsh])).unwrap()).unwrap();
    assert!(
        fs::read_to_string(env.home.join(".zshrc"))
            .unwrap()
            .contains("SSH_AUTH_SOCK")
    );
    apply(&env, &undo(&env, AgentKind::Ssh).unwrap()).unwrap();
    assert_eq!(
        fs::read_to_string(env.home.join(".zshrc")).unwrap(),
        "export EDITOR=vim\n"
    );
    assert!(!env.home.join(".ssh/config").exists());
}

#[test]
fn review_drops_the_record_without_asking_when_blocks_are_already_gone() {
    let (_dir, env) = fixture();
    put(&env, ".zshrc", "export EDITOR=vim\n");
    apply(&env, &plan(&env, ssh(vec![Shell::Zsh])).unwrap()).unwrap();
    put(&env, ".zshrc", "export EDITOR=vim\n");
    fs::remove_file(env.home.join(".ssh/config")).unwrap();
    let reviewed = undo(&env, AgentKind::Ssh).unwrap();
    assert!(reviewed.edits.is_empty());
    // A prompt would read no answer here and keep the record.
    review_and_apply(&env, &reviewed, "").unwrap();
    assert!(undo(&env, AgentKind::Ssh).is_err());
    assert_eq!(
        fs::read_to_string(env.home.join(".zshrc")).unwrap(),
        "export EDITOR=vim\n"
    );
}
