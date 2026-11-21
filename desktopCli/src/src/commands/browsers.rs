//! `keyguard browsers`: installs, repairs, reports and removes the native
//! messaging manifests that let browsers start `keyguard-browser-host`, the
//! connector of Keyguard's browser extension. Works for the current user only.
use crate::HELP_TEMPLATE;
use anyhow::Result;
use clap::{Args as ClapArgs, Subcommand};
use serde::Serialize;
use std::path::PathBuf;

mod alias;
mod connector;
mod locations;
mod registry;

use connector::{Report, State};
use locations::{Browser, Context};

#[derive(Debug, ClapArgs)]
pub(crate) struct Args {
    #[command(subcommand)]
    command: Action,
    /// Print the result, or the error, as JSON.
    #[arg(long, global = true)]
    json: bool,
    /// Also allow the development build of the extension. Without it,
    /// a repair removes it.
    #[arg(long, global = true)]
    dev: bool,
}

#[derive(Debug, Subcommand)]
enum Action {
    /// Show which browsers can start Keyguard's connector.
    #[command(
        override_usage = "keyguard browsers status [flags]",
        help_template = HELP_TEMPLATE,
        next_help_heading = "Flags"
    )]
    Status,
    /// Let browsers start Keyguard's connector, or repair it.
    #[command(
        override_usage = "keyguard browsers install [flags]",
        help_template = HELP_TEMPLATE,
        next_help_heading = "Flags"
    )]
    Install {
        /// Only this browser: chrome, chromium, edge, brave, vivaldi or
        /// firefox, or on Linux their Flathub builds, such as
        /// chrome-flatpak. Repeat for more. Defaults to the browsers found.
        #[arg(
            long = "browser",
            value_name = "browser",
            value_enum,
            hide_possible_values = true
        )]
        browsers: Vec<Browser>,
    },
    /// Remove Keyguard's connector from browsers.
    #[command(
        override_usage = "keyguard browsers uninstall [flags]",
        help_template = HELP_TEMPLATE,
        next_help_heading = "Flags"
    )]
    Uninstall {
        /// Only this browser: chrome, chromium, edge, brave, vivaldi or
        /// firefox, or on Linux their Flathub builds, such as
        /// chrome-flatpak. Repeat for more. Defaults to all of them.
        #[arg(
            long = "browser",
            value_name = "browser",
            value_enum,
            hide_possible_values = true
        )]
        browsers: Vec<Browser>,
    },
}

/// A failure the app shows its own text for. Other failures are `failed`,
/// or a code of agent setup's file checks, such as `unsafe_file`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "code", rename_all = "snake_case")]
pub(crate) enum BrowsersError {
    /// The connector next to this executable, or the MSIX package's alias
    /// of it, is missing or can't run.
    HostMissing {
        path: PathBuf,
        /// The file name of the alias, when `path` is the alias: Windows
        /// lists it in its settings of app execution aliases.
        #[serde(skip_serializing_if = "Option::is_none")]
        alias: Option<String>,
    },
    /// `--browser` names a browser that was not found.
    BrowserAbsent { browser: Browser },
    /// Keyguard did not write the file at this path, so it is left alone.
    ForeignFile { path: PathBuf },
    /// The macOS app sandbox can't reach the browsers' files.
    Unsupported,
}

impl std::fmt::Display for BrowsersError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::HostMissing { path, .. } => {
                write!(
                    f,
                    "the connector is missing or can't run: {}",
                    path.display()
                )
            }
            Self::BrowserAbsent { browser } => write!(
                f,
                "{} was not found; open it once, then try again",
                browser.id()
            ),
            Self::ForeignFile { path } => write!(
                f,
                "{} was not written by Keyguard; remove it manually",
                path.display()
            ),
            Self::Unsupported => {
                write!(f, "this build of Keyguard can't install browser connectors")
            }
        }
    }
}

impl std::error::Error for BrowsersError {}

pub(crate) fn main(args: Args) -> i32 {
    let json = args.json;
    match execute(args.command, args.dev) {
        Ok(report) if json => match serde_json::to_string_pretty(&report) {
            Ok(text) => {
                println!("{text}");
                0
            }
            Err(error) => fail(&error.into(), json),
        },
        Ok(report) => {
            print!("{}", table(&report));
            0
        }
        Err(error) => fail(&error, json),
    }
}

fn fail(error: &anyhow::Error, json: bool) -> i32 {
    if json {
        println!("{}", error_json(error));
    } else {
        eprintln!("{error:#}");
    }
    1
}

fn execute(action: Action, dev: bool) -> Result<Report> {
    let context = Context::discover()?;
    let mut registry = registry::current_user();
    match action {
        Action::Status => connector::status(&context, &registry, dev),
        Action::Install { browsers } => connector::install(&context, &mut registry, &browsers, dev),
        Action::Uninstall { browsers } => {
            connector::uninstall(&context, &mut registry, &browsers, dev)
        }
    }
}

/// The error for the app: its code, plus the English description as `detail`.
fn error_json(error: &anyhow::Error) -> serde_json::Value {
    match error
        .downcast_ref::<BrowsersError>()
        .and_then(|it| serde_json::to_value(it).ok())
    {
        Some(mut value) => {
            value["detail"] = format!("{error:#}").into();
            value
        }
        None => keyguard_agent_setup::error_json(error),
    }
}

/// One line per browser: its ID, its state, and its manifest.
fn table(report: &Report) -> String {
    report
        .browsers
        .iter()
        .map(|browser| {
            let state = match browser.status {
                State::Installed => "installed",
                State::Missing => "not installed",
                State::Stale => "needs repair",
                State::BrowserAbsent => "not found",
            };
            let manifest = match browser.status {
                State::BrowserAbsent => String::new(),
                _ => browser.manifest.display().to_string(),
            };
            format!("{:<17}{state:<15}{manifest}", browser.browser.id())
                .trim_end()
                .to_owned()
                + "\n"
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use clap::Parser;

    #[derive(Parser)]
    struct Cli {
        #[command(flatten)]
        args: Args,
    }

    #[test]
    fn errors_have_codes_for_the_app() {
        let error = anyhow::Error::new(BrowsersError::BrowserAbsent {
            browser: Browser::Firefox,
        });
        assert_eq!(
            error_json(&error),
            serde_json::json!({
                "code": "browser_absent",
                "browser": "firefox",
                "detail": "firefox was not found; open it once, then try again",
            })
        );
        let error = anyhow::anyhow!("disk full");
        assert_eq!(
            error_json(&error),
            serde_json::json!({"code": "failed", "detail": "disk full"})
        );
    }

    #[test]
    fn browsers_repeat() {
        let cli = Cli::parse_from([
            "browsers",
            "install",
            "--browser",
            "chrome",
            "--browser",
            "firefox",
            "--json",
            "--dev",
        ]);
        assert!(cli.args.json);
        assert!(cli.args.dev);
        let Action::Install { browsers } = cli.args.command else {
            panic!("expected install");
        };
        assert_eq!(browsers, [Browser::Chrome, Browser::Firefox]);
        let cli = Cli::parse_from(["browsers", "uninstall", "--browser", "firefox-flatpak"]);
        let Action::Uninstall { browsers } = cli.args.command else {
            panic!("expected uninstall");
        };
        assert_eq!(browsers, [Browser::FirefoxFlatpak]);
        assert!(Cli::try_parse_from(["browsers", "uninstall", "--browser", "safari"]).is_err());
        // Each install rewrites the allowlist, so no flag adds to it.
        assert!(
            Cli::try_parse_from([
                "browsers",
                "install",
                "--allow-origin",
                "chrome-extension://abcdefghijklmnopabcdefghijklmnop/",
            ])
            .is_err()
        );
    }
}
