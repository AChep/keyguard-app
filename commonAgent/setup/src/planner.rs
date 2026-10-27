use crate::{
    Agent, Edit, Environment, Override, Plan, Request, SetupError, files, shell, validate_value,
};
use anyhow::{Context, Result, anyhow, ensure};
use std::{ops::Range, path::Path};

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
        Agent::Ssh { socket, host } => {
            validate_value(socket)?;
            ensure!(
                Path::new(socket).is_absolute() || socket.starts_with(r"\\.\pipe\"),
                "SSH socket must be absolute"
            );
            // OpenSSH expands percent tokens and environment variables in paths.
            ensure!(
                !socket.contains(['%', '$', '"', '\n'])
                    && (!socket.contains('\\') || environment.windows),
                "SSH socket contains unsupported configuration metacharacters"
            );
            if let Some(host) = host {
                ensure!(
                    !host.is_empty()
                        && host
                            .bytes()
                            .all(|c| c.is_ascii_alphanumeric() || b".-_".contains(&c)),
                    "enter one host name or alias, without wildcards or SSH options"
                );
            }
            let path = environment.home.join(".ssh/config");
            let before = files::observe(environment, &mut plan, &path)?;
            let text = before.as_deref().unwrap_or_default();
            if text.starts_with('\u{feff}') {
                return Err(anyhow!(
                    "SSH configuration has a byte order mark; convert it to UTF-8 without a BOM"
                )
                .context(SetupError::UnsafeFile { path }));
            }
            // OpenSSH uses the first IdentityAgent that applies to a host, so
            // only lines before Keyguard's block can take precedence over it.
            let end = block_in(text, "ssh", &path)?.map_or(text.len(), |(start, _)| start);
            add_overrides(&mut plan, &path, text, 0..end, "IdentityAgent", true);
            let socket = if environment.windows {
                socket.replace('\\', "/")
            } else {
                socket.clone()
            };
            let body = format!(
                "Host {}\n  IdentityAgent \"{socket}\"",
                host.as_deref().unwrap_or("*")
            );
            add_edit(&mut plan, path, before, &body)?;
        }
        Agent::Gpg { home } => {
            validate_value(&home.to_string_lossy())?;
            ensure!(home.is_absolute(), "GnuPG home must be absolute");
            ensure!(
                home != &environment.home.join(".gnupg"),
                "refusing to configure the default GnuPG home"
            );
            ensure!(
                !home
                    .components()
                    .any(|c| matches!(c, std::path::Component::ParentDir)),
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
    let mut result = text.to_owned();
    if let Some((start, end)) = block(text, id)? {
        result.replace_range(start..end, "");
    }
    Ok(result)
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
    let newline = if text.contains("\r\n") { "\r\n" } else { "\n" };
    let id = plan.request.agent.id();
    let replacement =
        format!("{}\n{body}\n{}\n", marker(id, false), marker(id, true)).replace('\n', newline);
    let mut after = text.to_owned();
    if let Some((start, end)) = block(text, id)? {
        after.replace_range(start..end, &replacement);
    } else {
        if !after.is_empty() && !after.ends_with('\n') {
            after.push_str(newline);
        }
        after.push_str(&replacement);
    }
    plan.edits.push(Edit {
        path,
        before,
        after: Some(after),
    });
    Ok(())
}
