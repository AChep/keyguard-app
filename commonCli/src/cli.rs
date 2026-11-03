//! Commands of the `keyguard` CLI that the agent passes on to Keyguard.
//! Each command states how the agent treats it, so that a new command
//! needs no new dispatch code in the agent.
use crate::json::PrivateJson;
use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use zeroize::Zeroizing;

mod run;

pub use run::{MAX_COMMAND_ARGUMENTS, MAX_COMMAND_BYTES, RunRequest};

/// A request of one CLI command.
pub trait CliCommand: Serialize + Into<CliRequest> {
    /// The `command` of its frame.
    const NAME: &'static str;
    /// Whether its result holds values, so that only the bundled CLI may
    /// receive it.
    const REQUIRES_BUNDLED_CLI: bool;
    fn validate(&self) -> Result<()>;
    /// Whether a response is a successful result of this command.
    fn is_valid_result(response: &Value) -> bool;
}

/// One CLI command. The app receives the same shape, together with the
/// verified caller.
#[derive(Serialize, Deserialize)]
#[serde(
    tag = "command",
    content = "request",
    rename_all = "snake_case",
    deny_unknown_fields
)]
pub enum CliRequest {
    Run(RunRequest),
}

/// How the agent serves a command.
#[derive(Clone, Copy)]
pub struct CliPolicy {
    /// The `command` of its frame.
    pub name: &'static str,
    pub requires_bundled_cli: bool,
    pub is_valid_result: fn(&Value) -> bool,
}

fn policy<C: CliCommand>() -> CliPolicy {
    CliPolicy {
        name: C::NAME,
        requires_bundled_cli: C::REQUIRES_BUNDLED_CLI,
        is_valid_result: C::is_valid_result,
    }
}

impl CliRequest {
    /// The commands this version knows.
    pub const COMMANDS: &'static [&'static str] = &[RunRequest::NAME];

    pub fn policy(&self) -> CliPolicy {
        match self {
            Self::Run(_) => policy::<RunRequest>(),
        }
    }

    pub fn validate(&self) -> Result<()> {
        match self {
            Self::Run(request) => request.validate(),
        }
    }

    /// The request of the command as JSON, without its name: what the app
    /// receives. Serialized from the parsed request, so that it holds only
    /// the fields this version knows.
    pub fn request_json(&self) -> Result<Zeroizing<Vec<u8>>> {
        let mut frame = PrivateJson(serde_json::to_value(self)?);
        let request = PrivateJson(
            frame
                .0
                .get_mut("request")
                .map(Value::take)
                .context("invalid_request")?,
        );
        Ok(Zeroizing::new(serde_json::to_vec(&request.0)?))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn every_command_is_named_as_its_frame() {
        let samples = [CliRequest::Run(RunRequest {
            environment: "e".into(),
            account: None,
            command: vec!["env".into()],
            working_directory: "/".into(),
        })];
        assert_eq!(samples.len(), CliRequest::COMMANDS.len());
        for (sample, name) in samples.iter().zip(CliRequest::COMMANDS) {
            let value = serde_json::to_value(sample).unwrap();
            assert_eq!(value["command"], *name);
            assert!(serde_json::from_value::<CliRequest>(value).is_ok());
        }
    }

    #[test]
    fn the_app_receives_the_request_without_its_name() {
        let request = CliRequest::Run(RunRequest {
            environment: "e".into(),
            account: None,
            command: vec!["env".into()],
            working_directory: "/".into(),
        });
        assert_eq!(request.policy().name, "run");
        let json: Value = serde_json::from_slice(&request.request_json().unwrap()).unwrap();
        assert_eq!(
            json,
            json!({"environment":"e","command":["env"],"workingDirectory":"/"})
        );
        assert!(serde_json::from_value::<RunRequest>(json).is_ok());
    }

    #[test]
    fn requests_reject_unknown_fields() {
        for value in [
            json!({"command":"run","request":{"environment":"e","command":["env"],"workingDirectory":""},"caller":{}}),
            json!({"command":"run","request":{"environment":"e","command":["env"],"workingDirectory":"","caller":{}}}),
            json!({"command":"read","request":{}}),
        ] {
            assert!(serde_json::from_value::<CliRequest>(value).is_err());
        }
    }
}
