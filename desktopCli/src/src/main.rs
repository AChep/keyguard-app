//! The `keyguard` command-line tool. Each command talks to Keyguard through
//! its CLI agent.
use clap::{
    Arg, ArgAction, Args, Parser, Subcommand,
    builder::{StyledStr, Styles},
};
use std::{fmt::Write, path::PathBuf};

mod client;
mod commands {
    pub(crate) mod mcp;
    pub(crate) mod run;
}
mod errors;

use commands::{mcp, run};

/// Lays out help like the 1Password CLI: the description, a one-line usage,
/// then the sections.
const HELP_TEMPLATE: &str = "\
{about-with-newline}
{usage-heading}  {usage}

{all-args}{after-help}";

const SOCKET_HELP: &str = "Connect to Keyguard at this socket. \
    Can be set as the KEYGUARD_CLI_SOCKET environment variable";

#[derive(Parser)]
#[command(
    name = "keyguard",
    version,
    about = "Keyguard CLI delivers environment variables from your vault to local tools.",
    override_usage = "keyguard [command] [flags]",
    after_help = "Run 'keyguard [command] --help' for more information on a command.",
    help_template = HELP_TEMPLATE,
    next_help_heading = "Flags",
    arg_required_else_help = true,
    disable_help_subcommand = true,
    // The built-in flags ignore the help heading, so define them here.
    disable_help_flag = true,
    disable_version_flag = true,
    arg = Arg::new("help")
        .short('h')
        .long("help")
        .global(true)
        .action(ArgAction::Help)
        .help("Get help for this command"),
    arg = Arg::new("version")
        .short('V')
        .long("version")
        .action(ArgAction::Version)
        .help("Print the version of keyguard"),
)]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

/// How a command reaches Keyguard.
#[derive(Args)]
struct Connection {
    #[arg(long, value_name = "path", help = SOCKET_HELP)]
    socket: Option<PathBuf>,
}

#[derive(Subcommand)]
enum Command {
    /// Get help for any command.
    #[command(
        override_usage = "keyguard help [command]",
        help_template = HELP_TEMPLATE,
        next_help_heading = "Flags"
    )]
    Help {
        #[arg(value_name = "command", hide = true)]
        command: Option<String>,
    },
    /// Run the environment management MCP server over stdio.
    #[command(
        override_usage = "keyguard mcp [flags]",
        help_template = HELP_TEMPLATE,
        next_help_heading = "Flags"
    )]
    Mcp(mcp::Args),
    /// Run a command with the variables of an environment.
    #[command(
        override_usage = "keyguard run --environment <id> [flags] -- <command>...",
        after_help = run_examples(),
        help_template = HELP_TEMPLATE,
        next_help_heading = "Flags"
    )]
    Run(run::Args),
}

fn run_examples() -> StyledStr {
    let header = Styles::styled().get_header().to_owned();
    let mut examples = StyledStr::new();
    let _ = write!(
        examples,
        "{header}Examples:{header:#}

Run npm with the variables of an environment:

    keyguard run --environment <id> -- npm start"
    );
    examples
}

/// Prints the same help as `keyguard [command] --help`, or the error for an
/// unknown command.
fn print_help(command: Option<String>) {
    let args = ["keyguard"]
        .into_iter()
        .chain(command.as_deref())
        .chain(["--help"]);
    if let Err(error) = Cli::try_parse_from(args) {
        error.exit();
    }
}

#[tokio::main]
async fn main() {
    let code = match Cli::parse().command {
        Command::Help { command } => {
            print_help(command);
            0
        }
        Command::Mcp(args) => mcp::main(args).await,
        Command::Run(args) => run::main(args).await,
    };
    std::process::exit(code);
}

#[cfg(test)]
mod tests {
    use super::*;
    use clap::CommandFactory;

    #[test]
    fn command_is_valid() {
        Cli::command().debug_assert();
    }
}
