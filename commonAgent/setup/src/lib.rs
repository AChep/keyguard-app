//! Reviewable, reversible configuration of clients of Keyguard's agents.
//!
//! Discovery reads configuration as data. It runs only Zsh's zshenv files, as
//! any Zsh script does, to find ZDOTDIR. It never sources a shell profile,
//! runs `Match exec`, stops another agent, or opens a private key file.

mod files;
mod planner;
mod review;
mod shell;
pub mod termux;
pub mod verify;

use anyhow::{Result, bail, ensure};
use serde::{Deserialize, Serialize, de::IntoDeserializer};
use std::path::PathBuf;

pub use files::{apply, undo};
pub use planner::plan;
pub use review::review_and_apply;

const PLAN_VERSION: u32 = 4;

/// A shell whose interactive startup files we know how to edit.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Shell {
    Bash,
    Zsh,
    Fish,
    Powershell,
    #[serde(rename = "windows-powershell")]
    WindowsPowershell,
}

impl std::str::FromStr for Shell {
    type Err = serde::de::value::Error;

    /// Parses the same names as the plan's JSON.
    fn from_str(value: &str) -> Result<Self, Self::Err> {
        Self::deserialize(value.into_deserializer())
    }
}

/// The agent whose clients are configured, without its endpoint.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AgentKind {
    Ssh,
    Gpg,
}

impl AgentKind {
    pub fn id(self) -> &'static str {
        match self {
            Self::Ssh => "ssh",
            Self::Gpg => "gpg",
        }
    }
}

/// The selected agent. Endpoints always come from the running app.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum Agent {
    Ssh {
        socket: String,
        host: Option<String>,
    },
    Gpg {
        home: PathBuf,
    },
}

impl Agent {
    pub fn kind(&self) -> AgentKind {
        match self {
            Self::Ssh { .. } => AgentKind::Ssh,
            Self::Gpg { .. } => AgentKind::Gpg,
        }
    }

    pub(crate) fn id(&self) -> &'static str {
        self.kind().id()
    }
}

/// Explicit selections, independent of the app's inherited shell environment.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Request {
    pub agent: Agent,
    #[serde(default)]
    pub shells: Vec<Shell>,
    #[serde(default)]
    pub launcher: Option<PathBuf>,
}

/// Host locations. Sandboxed applications must use an externally launched helper.
#[derive(Debug, Clone)]
pub struct Environment {
    pub home: PathBuf,
    /// The Documents folder, which Windows can redirect outside the home.
    /// PowerShell profiles live there.
    pub documents: Option<PathBuf>,
    pub config_home: PathBuf,
    pub zdotdir: PathBuf,
    /// The Zsh to ask for its startup directory. `None` keeps [`Self::zdotdir`].
    pub zsh: Option<PathBuf>,
    pub powershell_profile: Option<PathBuf>,
    pub windows: bool,
    pub termux_launcher: Option<PathBuf>,
}

impl Environment {
    pub fn discover() -> Result<Self> {
        ensure!(
            std::env::var_os("FLATPAK_ID").is_none()
                && std::env::var("container").ok().as_deref() != Some("flatpak")
                && !std::path::Path::new("/.flatpak-info").exists(),
            "run agent setup from a host terminal outside Flatpak"
        );
        ensure!(
            std::env::var_os("APP_SANDBOX_CONTAINER_ID").is_none(),
            "run agent setup from a terminal outside the app sandbox"
        );
        let home =
            dirs::home_dir().ok_or_else(|| anyhow::anyhow!("could not locate the user home"))?;
        let config_home = std::env::var_os("XDG_CONFIG_HOME")
            .map(PathBuf::from)
            .filter(|p| p.is_absolute())
            .unwrap_or_else(|| home.join(".config"));
        let zdotdir = std::env::var_os("ZDOTDIR")
            .map(PathBuf::from)
            .unwrap_or_else(|| home.clone());
        // New terminals start the login shell, and each Zsh build reads its
        // own global zshenv file.
        let zsh = (!cfg!(windows)).then(|| {
            std::env::var_os("SHELL")
                .map(PathBuf::from)
                .filter(|shell| shell.is_absolute() && shell.ends_with("zsh"))
                .unwrap_or_else(|| "zsh".into())
        });
        Ok(Self {
            documents: dirs::document_dir(),
            home,
            config_home,
            zdotdir,
            zsh,
            powershell_profile: None,
            windows: cfg!(windows),
            termux_launcher: None,
        })
    }

    pub(crate) fn state_dir(&self) -> PathBuf {
        self.home.join(".keyguard/agent-setup")
    }
}

/// An exact file change. `None` represents a missing file, not an empty file.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Edit {
    pub path: PathBuf,
    pub before: Option<String>,
    pub after: Option<String>,
}

/// A line that names the setting and can take precedence over Keyguard's
/// block. Its value is never interpreted; the user reviews it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Override {
    pub setting: String,
    pub path: PathBuf,
    pub line: usize,
}

/// An issue that blocks applying until the user resolves it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "code", rename_all = "snake_case")]
pub enum Conflict {
    /// `.zshenv` sets ZDOTDIR, so Zsh may read another `.zshrc`.
    Zdotdir { path: PathBuf },
    /// A compiled copy dated now or later, which Zsh loads instead of the edited file.
    CompiledStartup { path: PathBuf },
    /// Keyguard's block changed since it was applied, so undo cannot remove it.
    ChangedBlock { path: PathBuf },
    /// Keyguard's block has no undo record, e.g. in dotfiles from another computer.
    UnrecordedBlock { path: PathBuf },
}

impl std::fmt::Display for Conflict {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Zdotdir { path } => write!(
                f,
                "{} sets ZDOTDIR; run setup from that Zsh terminal with ZDOTDIR exported, or configure the actual startup directory manually",
                path.display()
            ),
            Self::CompiledStartup { path } => write!(
                f,
                "{} has a compiled copy dated in the future; delete or recompile it",
                path.display()
            ),
            Self::ChangedBlock { path } => write!(
                f,
                "Keyguard's block was changed in {}; remove it manually or restore the recorded block",
                path.display()
            ),
            Self::UnrecordedBlock { path } => write!(
                f,
                "Keyguard's block in {} has no undo record; remove it manually",
                path.display()
            ),
        }
    }
}

/// A failure the user can act on. The app shows its own text for each code;
/// any other failure is reported as `failed`, with only the English detail.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "code", rename_all = "snake_case")]
pub enum SetupError {
    /// A reviewed file changed, so the review must be refreshed.
    Changed,
    /// Another setup operation holds the lock.
    Busy,
    /// A link, another user's file, a file others can write, or not UTF-8 text.
    UnsafeFile {
        path: PathBuf,
    },
    /// Keyguard's markers or undo record for this file are inconsistent.
    ManagedBlock {
        path: PathBuf,
    },
    NoPowershell,
    /// The plan comes from another version of Keyguard.
    UpdateRequired,
}

impl std::fmt::Display for SetupError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Changed => write!(f, "configuration changed after review; refresh the review"),
            Self::Busy => write!(f, "another agent setup operation is running"),
            Self::UnsafeFile { path } => write!(f, "cannot safely edit {}", path.display()),
            Self::ManagedBlock { path } => write!(
                f,
                "Keyguard's settings in {} do not match its records",
                path.display()
            ),
            Self::NoPowershell => write!(
                f,
                "could not locate a PowerShell profile; install PowerShell or configure it manually"
            ),
            Self::UpdateRequired => write!(f, "unsupported setup plan version; update Keyguard"),
        }
    }
}

impl std::error::Error for SetupError {}

/// The error for the app: its [`SetupError`] code, or `failed`, plus the
/// full English description as `detail`.
pub fn error_json(error: &anyhow::Error) -> serde_json::Value {
    let mut value = error
        .downcast_ref::<SetupError>()
        .and_then(|error| serde_json::to_value(error).ok())
        .unwrap_or_else(|| serde_json::json!({ "code": "failed" }));
    value["detail"] = format!("{error:#}").into();
    value
}

/// A read dependency, including files which will not be edited.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ObservedFile {
    pub path: PathBuf,
    /// None means absent, distinct from the digest of an empty file.
    pub sha256: Option<String>,
}

/// A plan is read-only until explicitly passed to [`apply`].
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Plan {
    pub version: u32,
    #[serde(default)]
    pub undo: bool,
    pub request: Request,
    pub edits: Vec<Edit>,
    pub conflicts: Vec<Conflict>,
    #[serde(default)]
    pub overrides: Vec<Override>,
    #[serde(default)]
    pub observed_files: Vec<ObservedFile>,
}

fn validate_value(value: &str) -> Result<()> {
    if value.is_empty() || value.chars().any(char::is_control) {
        bail!("configuration values must be nonempty and contain no control characters");
    }
    Ok(())
}

fn quoted(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\"'\"'"))
}
