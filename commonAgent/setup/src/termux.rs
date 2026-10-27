//! Interactive configuration, called by the installed Termux helper itself.
use crate::{Agent, AgentKind, Environment, Request, Shell};
use anyhow::{Context, Result, ensure};
use std::path::PathBuf;

/// Reviews and configures `shells` to start this helper and use `agent`.
pub fn setup(agent: Agent, shells: Vec<Shell>) -> Result<()> {
    let helper = helper_name(agent.kind());
    let env = environment(&helper)?;
    let plan = crate::plan(
        &env,
        Request {
            agent,
            shells,
            launcher: env.termux_launcher.clone(),
        },
    )?;
    crate::review_and_apply(&env, &plan, &format!("{helper} --undo-setup"))
}

/// Reviews removal of the configuration created by [`setup`].
pub fn undo(kind: AgentKind) -> Result<()> {
    let env = environment(&helper_name(kind))?;
    let plan = crate::undo(&env, kind)?;
    crate::review_and_apply(&env, &plan, "")
}

/// Termux's installation prefix, such as `/data/data/com.termux/files/usr`.
pub fn prefix() -> Result<PathBuf> {
    std::env::var_os("PREFIX")
        .map(PathBuf::from)
        .filter(|prefix| prefix.is_absolute())
        .context("PREFIX is missing; run this command in Termux")
}

fn helper_name(kind: AgentKind) -> String {
    format!("keyguard-android-{}-agent", kind.id())
}

fn environment(helper: &str) -> Result<Environment> {
    let mut env = Environment::discover()?;
    // termux-exec may start programs through the system linker, so
    // current_exe() can name linker64. Shells start the installed helper.
    let launcher = prefix()?.join("bin").join(helper);
    ensure!(
        launcher.is_file(),
        "install {helper} with pkg before running setup"
    );
    env.termux_launcher = Some(launcher);
    Ok(env)
}
