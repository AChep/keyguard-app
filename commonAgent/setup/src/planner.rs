use crate::{
    Agent, Edit, Environment, Override, Plan, Request, SSH_BOOKMARKS, SetupError, files, shell,
    validate_value,
};
use anyhow::{Context, Result, anyhow, ensure};
use std::{
    ops::Range,
    path::{Component, Path},
};

pub fn plan(environment: &Environment, request: Request) -> Result<Plan> {
    let mut plan = Plan {
        version: crate::PLAN_VERSION,
        undo: false,
        request,
        edits: Vec::new(),
        conflicts: Vec::new(),
        overrides: Vec::new(),
        observed_files: Vec::new(),
    };
    let mut shells = Vec::new();
    if let Some(launcher) = &plan.request.launcher {
        ensure!(
            environment.termux_launcher.as_ref() == Some(launcher),
            "agent startup can only be configured by the installed Termux helper"
        );
        validate_value(&launcher.to_string_lossy())?;
        ensure!(
            launcher.is_absolute(),
            "Termux helper path must be absolute"
        );
        ensure!(
            !plan.request.shells.is_empty(),
            "select a Termux shell to start the agent"
        );
    }
    for shell in &plan.request.shells {
        ensure!(
            !shells.contains(shell),
            "each shell may only be selected once"
        );
        shells.push(*shell);
    }
    let agent = plan.request.agent.clone();
    match &agent {
        Agent::Ssh {
            socket,
            host,
            include,
        } => {
            validate_value(socket)?;
            ensure!(
                Path::new(socket).is_absolute() || socket.starts_with(r"\\.\pipe\"),
                "SSH socket must be absolute"
            );
            // OpenSSH expands percent tokens and environment variables in paths.
            let socket =
                ssh_config_path(environment, socket, &['%', '$', '"', '\n'], "SSH socket")?;
            if let Some(host) = host {
                ensure!(
                    !host.is_empty()
                        && host
                            .bytes()
                            .all(|c| c.is_ascii_alphanumeric() || b".-_".contains(&c)),
                    "enter one host name or alias, without wildcards or SSH options"
                );
            }
            let include = include
                .as_deref()
                .map(|include| include_body(environment, include))
                .transpose()?;
            let path = environment.home.join(".ssh/config");
            let before = files::observe(environment, &mut plan, &path)?;
            let text = before.as_deref().unwrap_or_default();
            if text.starts_with('\u{feff}') {
                return Err(anyhow!(
                    "SSH configuration has a byte order mark; convert it to UTF-8 without a BOM"
                )
                .context(SetupError::UnsafeFile { path }));
            }
            let agent = block_in(text, "ssh", &path)?;
            let bookmarks = block_in(text, SSH_BOOKMARKS, &path)?;
            if let (Some(agent), Some(bookmarks)) = (agent, bookmarks)
                && agent.0 < bookmarks.1
                && bookmarks.0 < agent.1
            {
                return Err(anyhow!("Keyguard's SSH setup blocks overlap")
                    .context(SetupError::ManagedBlock { path }));
            }
            // OpenSSH uses the first IdentityAgent that applies to a host, so
            // only lines before Keyguard's block can take precedence over it.
            // The include block before it is Keyguard's own.
            let end = agent.map_or(text.len(), |(start, _)| start);
            let ranges = match bookmarks {
                Some((start, stop)) if stop <= end => [0..start, stop..end],
                _ => [0..end, end..end],
            };
            for range in ranges {
                add_overrides(&mut plan, &path, text, range, "IdentityAgent", true);
            }
            let body = format!(
                "Host {}\n  IdentityAgent \"{socket}\"",
                host.as_deref().unwrap_or("*")
            );
            // An Include inside a Host or Match block inherits its condition,
            // and the first value wins, so the bookmarks are read before
            // anything else. A block that ended up after one moves back.
            let after = set_block(text, "ssh", Some(&body), false)?;
            let after = match bookmarks {
                Some((start, _)) if is_conditional(&text[..start]) => {
                    remove_block(&after, SSH_BOOKMARKS)?
                }
                _ => after,
            };
            let after = set_block(&after, SSH_BOOKMARKS, include.as_deref(), true)?;
            plan.edits.push(Edit {
                path,
                before,
                after: Some(after),
            });
        }
        Agent::Gpg { home } => {
            validate_value(&home.to_string_lossy())?;
            ensure!(home.is_absolute(), "GnuPG home must be absolute");
            ensure!(
                home != &environment.home.join(".gnupg"),
                "refusing to configure the default GnuPG home"
            );
            ensure!(
                !has_parent_dir(home),
                "GnuPG home must not contain parent directory components"
            );
            if environment.windows
                && let Some(appdata) = std::env::var_os("APPDATA")
            {
                ensure!(
                    !home.to_string_lossy().eq_ignore_ascii_case(
                        &std::path::PathBuf::from(appdata)
                            .join("gnupg")
                            .to_string_lossy()
                    ),
                    "refusing to configure the default Windows GnuPG home"
                );
            }
            ensure!(
                !plan.request.shells.is_empty(),
                "select a shell to configure the GPG terminal environment"
            );
        }
    }
    for shell in plan.request.shells.clone() {
        shell::configure(environment, &mut plan, shell)?;
    }
    files::reconcile(environment, &mut plan)?;
    plan.edits.retain(|edit| edit.before != edit.after);
    plan.observed_files.sort_by(|a, b| a.path.cmp(&b.path));
    Ok(plan)
}

pub(crate) fn marker(id: &str, end: bool) -> String {
    format!(
        "# {} Keyguard {id} agent setup",
        if end { "End" } else { "Begin" }
    )
}

pub(crate) fn block(text: &str, id: &str) -> Result<Option<(usize, usize)>> {
    let begin = marker(id, false);
    let end = marker(id, true);
    let mut start = None;
    let mut range = None;
    let mut offset = 0;
    for line in text.split_inclusive('\n') {
        // A byte order mark belongs to the file, not to the block.
        let bom = if offset == 0 && line.starts_with('\u{feff}') {
            '\u{feff}'.len_utf8()
        } else {
            0
        };
        let value = line[bom..].trim_end_matches(['\r', '\n']);
        if value == begin {
            ensure!(
                start.is_none() && range.is_none(),
                "duplicate or nested Keyguard setup markers"
            );
            start = Some(offset + bom);
        } else if value == end {
            let start = start
                .take()
                .ok_or_else(|| anyhow::anyhow!("unmatched Keyguard setup marker"))?;
            range = Some((start, offset + line.len()));
        }
        offset += line.len();
    }
    ensure!(start.is_none(), "unterminated Keyguard setup block");
    Ok(range)
}

/// [`block`] for the file at `path`, whose markers must be consistent.
pub(crate) fn block_in(text: &str, id: &str, path: &Path) -> Result<Option<(usize, usize)>> {
    block(text, id).with_context(|| SetupError::ManagedBlock {
        path: path.to_owned(),
    })
}

pub(crate) fn remove_block(text: &str, id: &str) -> Result<String> {
    set_block(text, id, None, false)
}

/// Replaces the block `id` in place, or adds it at the start or the end of
/// `text`; `None` removes it. Removing an added block restores `text`, except
/// for the line break added to a last line that had none.
pub(crate) fn set_block(
    text: &str,
    id: &str,
    body: Option<&str>,
    at_start: bool,
) -> Result<String> {
    let newline = if text.contains("\r\n") { "\r\n" } else { "\n" };
    let replacement = body.map_or_else(String::new, |body| {
        format!("{}\n{body}\n{}\n", marker(id, false), marker(id, true)).replace('\n', newline)
    });
    let mut after = text.to_owned();
    match block(text, id)? {
        Some((start, end)) => after.replace_range(start..end, &replacement),
        None if body.is_none() => (),
        None if at_start => after.insert_str(0, &replacement),
        None => {
            if !after.is_empty() && !after.ends_with('\n') {
                after.push_str(newline);
            }
            after.push_str(&replacement);
        }
    }
    Ok(after)
}

/// Reports the lines in `range` that name `setting`, skipping blank lines and
/// comments. A line-based scan over-reports rather than interpreting code.
pub(crate) fn add_overrides(
    plan: &mut Plan,
    path: &Path,
    text: &str,
    range: Range<usize>,
    setting: &str,
    ignore_case: bool,
) {
    let first = text[..range.start].matches('\n').count() + 1;
    for (index, line) in text[range].lines().enumerate() {
        if line.trim_start().starts_with('#') {
            continue;
        }
        if line
            .split(|c: char| !c.is_ascii_alphanumeric() && c != '_')
            .any(|word| word == setting || (ignore_case && word.eq_ignore_ascii_case(setting)))
        {
            plan.overrides.push(Override {
                setting: setting.to_owned(),
                path: path.to_owned(),
                line: first + index,
            });
        }
    }
}

pub(crate) fn add_edit(
    plan: &mut Plan,
    path: std::path::PathBuf,
    before: Option<String>,
    body: &str,
) -> Result<()> {
    let text = before.as_deref().unwrap_or_default();
    let after = set_block(text, plan.request.agent.id(), Some(body), false)?;
    plan.edits.push(Edit {
        path,
        before,
        after: Some(after),
    });
    Ok(())
}

/// The `value` with the `/` separators that OpenSSH accepts on Windows, or
/// an error if it holds one of the `forbidden` characters, which OpenSSH
/// would expand, or a `\` outside of Windows.
fn ssh_config_path(
    environment: &Environment,
    value: &str,
    forbidden: &[char],
    what: &str,
) -> Result<String> {
    ensure!(
        !value.contains(forbidden) && (!value.contains('\\') || environment.windows),
        "{what} contains unsupported configuration metacharacters"
    );
    Ok(if environment.windows {
        value.replace('\\', "/")
    } else {
        value.to_owned()
    })
}

fn has_parent_dir(path: &Path) -> bool {
    path.components().any(|c| matches!(c, Component::ParentDir))
}

/// The `Include` line for Keyguard's SSH bookmarks configuration at `path`.
fn include_body(environment: &Environment, path: &Path) -> Result<String> {
    let text = path.to_string_lossy();
    validate_value(&text)?;
    ensure!(
        path.is_absolute(),
        "SSH bookmarks configuration path must be absolute"
    );
    ensure!(
        !has_parent_dir(path),
        "SSH bookmarks configuration path must not contain parent directory components"
    );
    // OpenSSH expands a leading ~/, which keeps synced dotfiles portable.
    let rendered = match path.strip_prefix(&environment.home) {
        Ok(relative) => {
            let parts: Vec<_> = relative
                .components()
                .map(|c| c.as_os_str().to_string_lossy())
                .collect();
            format!("~/{}", parts.join("/"))
        }
        Err(_) => text.into_owned(),
    };
    // OpenSSH expands globs, percent tokens and environment variables in
    // Include. The ~/ stands for the home, so its name may contain them.
    let rendered = ssh_config_path(
        environment,
        &rendered,
        &['%', '$', '"', '*', '?', '[', ']'],
        "SSH bookmarks configuration path",
    )?;
    // macOS and Windows file systems ignore case by default.
    ensure!(
        !rendered.eq_ignore_ascii_case("~/.ssh/config"),
        "SSH bookmarks configuration must not be ~/.ssh/config itself"
    );
    Ok(format!("Include \"{rendered}\""))
}

/// Whether `text` has a `Host` or `Match` line, which makes the lines
/// after it conditional.
fn is_conditional(text: &str) -> bool {
    text.lines().any(|line| {
        let keyword = line
            .trim_start()
            .split(|c: char| c.is_whitespace() || c == '=')
            .next()
            .unwrap_or_default();
        keyword.eq_ignore_ascii_case("host") || keyword.eq_ignore_ascii_case("match")
    })
}
