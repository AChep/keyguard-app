use anyhow::{Context, Result, ensure};
use clap::{Args as ClapArgs, Subcommand, ValueEnum};
use keyguard_agent_setup::{Agent, AgentKind, Environment, Plan, Request, Shell};
use std::{
    io::{self, Read},
    path::PathBuf,
};

#[derive(Debug, ClapArgs)]
pub struct Args {
    #[command(subcommand)]
    command: Action,
    /// Print errors as JSON on stdout, for the app.
    #[arg(long, global = true, hide = true)]
    json: bool,
}

#[derive(Debug, Subcommand)]
enum Action {
    /// Preview changes and ask before applying them. Run from a host terminal.
    Setup(Options),
    /// Print a read-only JSON plan. Conflicts are reported in the plan.
    Plan(Options),
    /// Apply the exact JSON plan read from stdin; fails if configuration changed.
    Apply,
    /// Preview removal of this agent's settings, or remove them with --apply.
    Undo {
        #[arg(value_enum)]
        agent: Kind,
        #[arg(long)]
        apply: bool,
        /// Show the changes and ask before removing recorded settings.
        #[arg(long, conflicts_with = "apply")]
        review: bool,
    },
    /// Query the selected agent through a real client, without shell profiles.
    Verify {
        #[command(flatten)]
        options: Options,
        /// ssh-add or gpg-connect-agent executable, preferably an absolute path.
        #[arg(long)]
        executable: PathBuf,
    },
}

#[derive(Debug, Clone, Copy, ValueEnum)]
enum Kind {
    Ssh,
    Gpg,
}

impl From<Kind> for AgentKind {
    fn from(kind: Kind) -> Self {
        match kind {
            Kind::Ssh => Self::Ssh,
            Kind::Gpg => Self::Gpg,
        }
    }
}

#[derive(Debug, ClapArgs)]
struct Options {
    #[arg(long, value_enum)]
    agent: Kind,
    /// Exact socket or named pipe reported by Keyguard.
    #[arg(long)]
    socket: Option<String>,
    /// Exact managed GnuPG home reported by Keyguard.
    #[arg(long)]
    gpg_home: Option<PathBuf>,
    /// Restrict SSH configuration to one host name or alias.
    #[arg(long)]
    host: Option<String>,
    /// Configure these interactive shells: bash, zsh, fish, powershell,
    /// windows-powershell. Omit for SSH config only.
    #[arg(long, value_delimiter = ',')]
    shells: Vec<Shell>,
}

impl Options {
    fn request(&self) -> Result<Request> {
        match self.agent {
            Kind::Ssh => ensure!(self.gpg_home.is_none(), "--gpg-home is only used for GPG"),
            Kind::Gpg => ensure!(
                self.socket.is_none() && self.host.is_none(),
                "--socket and --host are only used for SSH"
            ),
        }
        let agent = match self.agent {
            Kind::Ssh => Agent::Ssh {
                socket: self
                    .socket
                    .clone()
                    .context("--socket is required for SSH")?,
                host: self.host.clone(),
            },
            Kind::Gpg => Agent::Gpg {
                home: self
                    .gpg_home
                    .clone()
                    .context("--gpg-home is required for GPG")?,
            },
        };
        Ok(Request {
            agent,
            shells: self.shells.clone(),
            launcher: None,
        })
    }
}

pub fn main(args: Args) -> i32 {
    let json = args.json;
    match execute(args) {
        Ok(()) => 0,
        Err(error) if json => {
            println!("{}", keyguard_agent_setup::error_json(&error));
            1
        }
        Err(error) => {
            eprintln!("{error:#}");
            1
        }
    }
}

fn execute(args: Args) -> Result<()> {
    let environment = Environment::discover()?;
    match args.command {
        Action::Plan(options) => {
            let plan = keyguard_agent_setup::plan(&environment, options.request()?)?;
            println!("{}", serde_json::to_string_pretty(&plan)?);
        }
        Action::Apply => {
            let mut input = String::new();
            io::stdin()
                .take(4 * 1024 * 1024)
                .read_to_string(&mut input)?;
            let plan: Plan =
                serde_json::from_str(&input).context("expected a JSON setup plan on stdin")?;
            keyguard_agent_setup::apply(&environment, &plan)?;
        }
        Action::Setup(options) => {
            let plan = keyguard_agent_setup::plan(&environment, options.request()?)?;
            let undo_command = format!(
                "keyguard agents undo {} --apply",
                plan.request.agent.kind().id()
            );
            keyguard_agent_setup::review_and_apply(&environment, &plan, &undo_command)?;
        }
        Action::Undo {
            agent,
            apply,
            review,
        } => {
            let plan = keyguard_agent_setup::undo(&environment, agent.into())?;
            if review {
                keyguard_agent_setup::review_and_apply(&environment, &plan, "")?;
            } else {
                if apply {
                    keyguard_agent_setup::apply(&environment, &plan)?;
                }
                println!("{}", serde_json::to_string_pretty(&plan)?);
            }
        }
        Action::Verify {
            options,
            executable,
        } => {
            let output = match options.request()?.agent {
                Agent::Ssh { socket, .. } => {
                    keyguard_agent_setup::verify::ssh(&socket, &executable)?
                }
                Agent::Gpg { home } => keyguard_agent_setup::verify::gpg(&home, &executable)?,
            };
            print!("{output}");
        }
    }
    Ok(())
}
