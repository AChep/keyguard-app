//! Run only synthetic startup files, with user profiles disabled. These tests
//! check that the generated block wins over an earlier user declaration.
use keyguard_agent_setup::{Agent, Request, Shell, apply, plan};
use std::{fs, process::Command};

mod support;

#[test]
fn generated_blocks_set_the_endpoint_in_installed_shells() {
    let required = std::env::var("KEYGUARD_REQUIRED_SHELLS")
        .unwrap_or_else(|_| if cfg!(windows) { "powershell" } else { "bash" }.into());
    for (executable, shell) in [
        ("bash", Shell::Bash),
        ("zsh", Shell::Zsh),
        ("fish", Shell::Fish),
        ("pwsh", Shell::Powershell),
        ("powershell", Shell::WindowsPowershell),
    ] {
        let ps = matches!(shell, Shell::Powershell | Shell::WindowsPowershell);
        if cfg!(windows) && !ps {
            continue;
        }
        // A no-profile version probe cannot run any user startup configuration.
        let args: &[&str] = if ps {
            &[
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "$PSVersionTable.PSVersion.ToString()",
            ]
        } else if shell == Shell::Fish {
            &["--no-config", "--version"]
        } else if shell == Shell::Zsh {
            &["-f", "--version"]
        } else {
            &["--noprofile", "--norc", "--version"]
        };
        if Command::new(executable).args(args).output().is_err() {
            assert!(
                !required.split(',').any(|name| name == executable),
                "Required test shell missing: {executable}"
            );
            eprintln!("Skipping unavailable shell {executable}");
            continue;
        }
        let dir = tempfile::tempdir().unwrap();
        let home = dir.path().to_owned();
        let endpoint = home.join("Keyguard with spaces/gnupg");
        let value = endpoint.to_string_lossy();
        let env = support::environment(&home, "profile.ps1");
        let request = Request {
            agent: Agent::Gpg {
                home: endpoint.clone(),
            },
            shells: vec![shell],
            launcher: None,
        };
        let (relative, assignment) = match shell {
            Shell::Bash => (".bashrc", "export GNUPGHOME=/other"),
            Shell::Zsh => (".zshrc", "export GNUPGHOME=/other"),
            Shell::Fish => (
                ".config/fish/conf.d/keyguard-agents.fish",
                "set -gx GNUPGHOME /other",
            ),
            _ => ("profile.ps1", "$env:GNUPGHOME = '/other'"),
        };
        let profile = home.join(relative);
        fs::create_dir_all(profile.parent().unwrap()).unwrap();
        let bom = if shell == Shell::WindowsPowershell {
            "\u{feff}"
        } else {
            ""
        };
        fs::write(&profile, format!("{bom}{assignment}\n")).unwrap();
        let reviewed = plan(&env, request).unwrap();
        assert!(
            reviewed.conflicts.is_empty(),
            "{executable}: {:?}",
            reviewed.conflicts
        );
        assert!(reviewed.overrides.is_empty(), "{executable}");
        apply(&env, &reviewed).unwrap();
        let mut command = Command::new(executable);
        command
            .env_remove("BASH_ENV")
            .env_remove("ENV")
            .env_remove("GNUPGHOME")
            .env("HOME", &home)
            .env("ZDOTDIR", &home)
            .env("XDG_CONFIG_HOME", &env.config_home);
        if ps {
            let script = format!(
                "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false); . '{}'; [Console]::Write($env:GNUPGHOME)",
                profile.to_string_lossy().replace('\'', "''")
            );
            command.args([
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                &script,
            ]);
        } else if shell == Shell::Fish {
            command
                .args([
                    "--no-config",
                    "-c",
                    "source $argv[1]; command printenv GNUPGHOME",
                ])
                .arg(&profile);
        } else {
            if shell == Shell::Bash {
                command.args(["--noprofile", "--norc"]);
            } else {
                command.arg("-f");
            }
            command
                .args(["-c", ". \"$1\"; command printenv GNUPGHOME", "fixture"])
                .arg(&profile);
        }
        let output = command.output().unwrap();
        assert!(
            output.status.success(),
            "{executable}: {}",
            String::from_utf8_lossy(&output.stderr)
        );
        assert_eq!(
            String::from_utf8(output.stdout)
                .unwrap()
                .trim_end_matches(['\r', '\n']),
            value,
            "{executable}"
        );
    }
}

#[cfg(unix)]
#[test]
fn zsh_profile_follows_zdotdir_from_zshenv() {
    let required = std::env::var("KEYGUARD_REQUIRED_SHELLS").unwrap_or_default();
    if Command::new("zsh")
        .args(["-f", "--version"])
        .output()
        .is_err()
    {
        assert!(
            !required.split(',').any(|name| name == "zsh"),
            "Required test shell missing: zsh"
        );
        eprintln!("Skipping unavailable shell zsh");
        return;
    }
    let dir = tempfile::tempdir().unwrap();
    let mut env = support::environment(dir.path(), "profile.ps1");
    env.zsh = Some("zsh".into());
    // The app's environment has no ZDOTDIR; only Zsh's startup sets it.
    support::put(&env, ".zshenv", "echo output\nZDOTDIR=$HOME/.config/zsh\n");
    let request = Request {
        agent: Agent::Gpg {
            home: env.home.join("Keyguard/gnupg"),
        },
        shells: vec![Shell::Zsh],
        launcher: None,
    };
    let reviewed = plan(&env, request).unwrap();
    assert!(reviewed.conflicts.is_empty(), "{:?}", reviewed.conflicts);
    let edited: Vec<_> = reviewed.edits.iter().map(|edit| &edit.path).collect();
    assert_eq!(edited, [&env.home.join(".config/zsh/.zshrc")]);
}
