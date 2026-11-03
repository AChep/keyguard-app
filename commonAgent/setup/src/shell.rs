use crate::{Agent, Conflict, Environment, Plan, SetupError, Shell, files, planner, quoted};
use anyhow::{Result, ensure};
use std::{
    path::PathBuf,
    time::{Duration, SystemTime},
};

pub(crate) fn configure(env: &Environment, plan: &mut Plan, shell: Shell) -> Result<()> {
    // A global zshenv file can set ZDOTDIR outside the app's environment.
    let resolved = (shell == Shell::Zsh)
        .then(|| zsh_dotdir(env))
        .flatten()
        .map(|zdotdir| Environment {
            zdotdir,
            ..env.clone()
        });
    let env = resolved.as_ref().unwrap_or(env);
    let (variable, value) = match &plan.request.agent {
        Agent::Ssh { socket, .. } => ("SSH_AUTH_SOCK", socket.clone()),
        Agent::Gpg { home } => ("GNUPGHOME", home.to_string_lossy().into_owned()),
    };
    let quoted_value = quote(shell, &value);
    let assignment = match shell {
        Shell::Fish => format!("set -gx {variable} {quoted_value}"),
        Shell::Powershell | Shell::WindowsPowershell => {
            format!("$env:{variable} = {quoted_value}")
        }
        _ => format!("export {variable}={quoted_value}"),
    };
    let assignment = if let Some(launcher) = &plan.request.launcher {
        ensure!(
            matches!(shell, Shell::Bash | Shell::Zsh | Shell::Fish),
            "Termux automatic setup supports Bash, Zsh and Fish"
        );
        let executable = quote(shell, &launcher.to_string_lossy());
        let arguments = match &plan.request.agent {
            Agent::Ssh { socket, .. } => format!("--ensure -s -a {}", quote(shell, socket)),
            Agent::Gpg { home } => {
                format!("-s --homedir {}", quote(shell, &home.to_string_lossy()))
            }
        };
        // Ignore Bourne output and set the environment ourselves; never eval
        // generated shell code, including when the interactive shell is Fish.
        if shell == Shell::Fish {
            format!(
                "if test -x {executable}; and {executable} {arguments} >/dev/null\n  {assignment}\nend"
            )
        } else {
            format!(
                "if [ -x {executable} ] && {executable} {arguments} >/dev/null; then\n  {assignment}\nfi"
            )
        }
    } else {
        assignment
    };
    // A remote login's forwarded agent belongs to that session, not this setup.
    let body = if variable == "SSH_AUTH_SOCK" {
        match shell {
            Shell::Fish => format!(
                "if not set -q SSH_CONNECTION; or not set -q SSH_AUTH_SOCK\n  {assignment}\nend"
            ),
            Shell::Powershell | Shell::WindowsPowershell => format!(
                "if (-not $env:SSH_CONNECTION -or -not $env:SSH_AUTH_SOCK) {{\n  {assignment}\n}}"
            ),
            _ => format!(
                "if [ -z \"${{SSH_CONNECTION:-}}\" ] || [ -z \"${{SSH_AUTH_SOCK:-}}\" ]; then\n  {assignment}\nfi"
            ),
        }
    } else {
        assignment
    };
    let id = plan.request.agent.id();
    let ignore_case = matches!(shell, Shell::Powershell | Shell::WindowsPowershell);
    let targets = paths(env, shell)?;
    // The last assignment wins. Keyguard's block runs after the lines before
    // it, so only lines after it and later startup files can replace it.
    for path in targets.clone() {
        let before = observe(env, plan, shell, &path)?;
        let text = before.as_deref().unwrap_or_default();
        let end = planner::block_in(text, id, &path)?.map_or(text.len(), |(_, end)| end);
        planner::add_overrides(plan, &path, text, end..text.len(), variable, ignore_case);
        planner::add_edit(plan, path, before, &body)?;
        // Windows PowerShell reads a profile without a BOM in the ANSI code page.
        if shell == Shell::WindowsPowershell
            && let Some(Some(after)) = plan.edits.last_mut().map(|edit| edit.after.as_mut())
            && !after.is_ascii()
            && !after.starts_with('\u{feff}')
        {
            after.insert(0, '\u{feff}');
        }
    }
    // A compiled copy that Zsh loads is at least as new as its source, so it
    // was compiled from the text scanned here.
    for path in later_profiles(env, shell, &targets)? {
        let text = files::observe_dependency(plan, &path)?.unwrap_or_default();
        planner::add_overrides(plan, &path, &text, 0..text.len(), variable, ignore_case);
    }
    if shell == Shell::Zsh {
        // Either zshenv file can move ZDOTDIR, so editing one invalidates the review.
        let startup = env.home.join(".zshenv");
        let text = files::observe_dependency(plan, &startup)?;
        if resolved.is_none()
            && env.zdotdir == env.home
            && text.is_some_and(|text| {
                text.lines()
                    .any(|line| !line.trim_start().starts_with('#') && line.contains("ZDOTDIR"))
            })
        {
            plan.conflicts.push(Conflict::Zdotdir { path: startup });
        }
        if env.zdotdir != env.home {
            files::observe_dependency(plan, &env.zdotdir.join(".zshenv"))?;
        }
    }
    Ok(())
}

/// Asks Zsh for the directory it reads `.zshrc` from. Only zshenv files run,
/// as for any Zsh script; `None` when Zsh is missing or gives no answer.
fn zsh_dotdir(env: &Environment) -> Option<PathBuf> {
    let mut command = std::process::Command::new(env.zsh.as_ref()?);
    // A NUL separates the answer from anything the zshenv files print.
    command
        .args(["-c", r#"print -rn -- $'\0'"${ZDOTDIR:-$HOME}""#])
        .env("HOME", &env.home);
    if env.zdotdir == env.home {
        command.env_remove("ZDOTDIR");
    } else {
        command.env("ZDOTDIR", &env.zdotdir);
    }
    let output = crate::verify::run(command).ok()?;
    let path = PathBuf::from(output.rsplit_once('\0')?.1);
    path.is_absolute().then_some(path)
}

fn quote(shell: Shell, value: &str) -> String {
    match shell {
        Shell::Fish => format!("'{}'", value.replace('\\', "\\\\").replace('\'', "\\'")),
        Shell::Powershell | Shell::WindowsPowershell => {
            // PowerShell also ends single-quoted strings at typographic quotes.
            let mut text = String::from("'");
            for c in value.chars() {
                text.push(c);
                if matches!(c, '\'' | '\u{2018}' | '\u{2019}' | '\u{201A}' | '\u{201B}') {
                    text.push(c);
                }
            }
            text.push('\'');
            text
        }
        _ => quoted(value),
    }
}

fn observe(
    env: &Environment,
    plan: &mut Plan,
    shell: Shell,
    path: &std::path::Path,
) -> Result<Option<String>> {
    check_compiled(plan, shell, path);
    files::observe(env, plan, path)
}

/// The compiled copy that Zsh loads in place of a missing `path`, or of a
/// `path` modified in the same second or earlier.
pub(crate) fn compiled(path: &std::path::Path) -> PathBuf {
    PathBuf::from(format!("{}.zwc", path.display()))
}

/// Setup rewrites `path`, so only a compiled copy from this second or the
/// future still wins over it.
fn check_compiled(plan: &mut Plan, shell: Shell, path: &std::path::Path) {
    if shell == Shell::Zsh
        && std::fs::metadata(compiled(path))
            .and_then(|meta| meta.modified())
            .is_ok_and(|time| time + Duration::from_secs(1) > SystemTime::now())
    {
        plan.conflicts.push(Conflict::CompiledStartup {
            path: path.to_owned(),
        });
    }
}

/// Startup files that run after the edited ones.
fn later_profiles(env: &Environment, shell: Shell, targets: &[PathBuf]) -> Result<Vec<PathBuf>> {
    let directory = match shell {
        Shell::Bash => return Ok(Vec::new()),
        // Login shells read .zlogin after .zshrc.
        Shell::Zsh => return Ok(vec![env.zdotdir.join(".zlogin")]),
        Shell::Fish => env.config_home.join("fish/conf.d"),
        Shell::Powershell | Shell::WindowsPowershell => targets[0]
            .parent()
            .ok_or_else(|| anyhow::anyhow!("PowerShell profile has no parent"))?
            .to_path_buf(),
    };
    files::check_path(env, &directory)?;
    let mut result = Vec::new();
    if directory.exists() {
        for entry in std::fs::read_dir(directory)? {
            let path = entry?.path();
            let name = path.file_name().unwrap_or_default().to_string_lossy();
            // Fish sorts snippets in its own order, so every other visible
            // snippet is listed. Host profiles run after profile.ps1.
            let later = if shell == Shell::Fish {
                name.ends_with(".fish") && !name.starts_with('.')
            } else {
                name.to_ascii_lowercase().ends_with("_profile.ps1")
            };
            if later && !targets.contains(&path) {
                result.push(path);
            }
        }
    }
    result.sort();
    if shell == Shell::Fish {
        result.push(env.config_home.join("fish/config.fish"));
    }
    Ok(result)
}

fn paths(env: &Environment, shell: Shell) -> Result<Vec<PathBuf>> {
    Ok(match shell {
        Shell::Bash => {
            let login = [".bash_profile", ".bash_login", ".profile"]
                .iter()
                .map(|name| env.home.join(name))
                .find(|path| path.exists())
                .unwrap_or_else(|| env.home.join(".bash_profile"));
            // Both contexts are explicit. Reassigning the same value is harmless
            // when the existing login profile also sources .bashrc.
            vec![env.home.join(".bashrc"), login]
        }
        Shell::Zsh => vec![env.zdotdir.join(".zshrc")],
        Shell::Fish => vec![env.config_home.join("fish/conf.d/keyguard-agents.fish")],
        Shell::Powershell | Shell::WindowsPowershell => {
            let path = match &env.powershell_profile {
                Some(path) => path.clone(),
                None => powershell_profile(shell)?,
            };
            ensure!(
                path.extension()
                    .is_some_and(|ext| ext.eq_ignore_ascii_case("ps1")),
                "PowerShell profile must be a .ps1 file"
            );
            vec![path]
        }
    })
}

fn powershell_profile(shell: Shell) -> Result<PathBuf> {
    let executable = if shell == Shell::WindowsPowershell {
        "powershell"
    } else {
        "pwsh"
    };
    let mut command = std::process::Command::new(executable);
    command.args([
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-Command",
        "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false); $PROFILE.CurrentUserAllHosts",
    ]);
    if let Ok(output) = crate::verify::run(command) {
        let path = PathBuf::from(output.trim());
        if path.is_absolute() {
            return Ok(path);
        }
    }
    anyhow::bail!(SetupError::NoPowershell)
}
