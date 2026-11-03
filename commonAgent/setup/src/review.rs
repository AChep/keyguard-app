//! Interactive review shared by the CLI and the Termux helpers.
use crate::{AgentKind, Environment, Plan};
use anyhow::{Result, ensure};
use std::io::{self, Write};

/// Prints the plan and applies it only after the user confirms.
/// `undo_hint` is shown after a successful configuration.
pub fn review_and_apply(env: &Environment, plan: &Plan, undo_hint: &str) -> Result<()> {
    for edit in &plan.edits {
        println!(
            "\n{}\nBefore:\n{}\nAfter:\n{}",
            edit.path.display(),
            edit.before.as_deref().unwrap_or("(file absent)"),
            edit.after.as_deref().unwrap_or("(remove file)")
        );
    }
    for item in &plan.overrides {
        println!(
            "\nCheck {}:{}: it sets {} and can take precedence over Keyguard's setting.",
            item.path.display(),
            item.line,
            item.setting
        );
    }
    if !plan.undo {
        println!(
            "\n{}",
            match plan.request.agent.kind() {
                AgentKind::Ssh =>
                    "SSH configuration applies to OpenSSH clients. Other clients and ssh-add may also need the optional terminal environment.",
                AgentKind::Gpg =>
                    "Selected terminals will use Keyguard's separate GnuPG home. Existing public keys and trust settings are not copied. Import the public certificate of each key you want to use.",
            }
        );
        println!(
            "\nOpen a new terminal after applying. Existing terminals and graphical applications keep their current environment."
        );
    }
    for conflict in &plan.conflicts {
        eprintln!("\nConflict: {conflict}");
    }
    ensure!(
        plan.conflicts.is_empty(),
        "no changes made; resolve conflicts first"
    );
    // Nothing to confirm. An undo here only drops the record of blocks that are already gone.
    if plan.edits.is_empty() {
        crate::apply(env, plan)?;
        println!(
            "{}",
            if plan.undo {
                "Already removed."
            } else {
                "Already configured."
            }
        );
        return Ok(());
    }
    print!(
        "\n{} [y/N] ",
        if plan.undo {
            "Remove these recorded settings?"
        } else {
            "Apply these changes?"
        }
    );
    io::stdout().flush()?;
    let mut answer = String::new();
    io::stdin().read_line(&mut answer)?;
    if answer.trim().eq_ignore_ascii_case("y") {
        crate::apply(env, plan)?;
        if plan.undo {
            println!("Recorded configuration removed. Open a new terminal.");
        } else {
            println!("Configured. Open a new terminal. {undo_hint}");
        }
    }
    Ok(())
}
