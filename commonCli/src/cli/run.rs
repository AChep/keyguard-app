//! `keyguard run`: the values of an environment, to start a command with.
use super::{CliCommand, CliRequest};
use crate::{
    bounds::{MAX_CONTENT, MAX_TEXT, is_variable_name},
    limits::MAX_VARIABLES,
};
use anyhow::{Result, ensure};
use serde::{Deserialize, Serialize};
use serde_json::Value;

/// Bounds of a command shown in the approval prompt.
pub const MAX_COMMAND_ARGUMENTS: usize = 256;
pub const MAX_COMMAND_BYTES: usize = 32 * 1024;

/// Asks for the values of an environment to start a command with. The
/// command and directory are display data: the CLI starts the command.
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct RunRequest {
    pub environment: String,
    /// Omitted when unset, so that an older Keyguard accepts the request.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub account: Option<String>,
    pub command: Vec<String>,
    pub working_directory: String,
}

impl From<RunRequest> for CliRequest {
    fn from(request: RunRequest) -> Self {
        Self::Run(request)
    }
}

impl CliCommand for RunRequest {
    const NAME: &'static str = "run";
    const REQUIRES_BUNDLED_CLI: bool = true;

    fn validate(&self) -> Result<()> {
        let is_selector = |value: &str| {
            !value.trim().is_empty()
                && value.len() <= MAX_TEXT
                && !value.chars().any(char::is_control)
        };
        ensure!(
            is_selector(&self.environment) && self.account.as_deref().is_none_or(is_selector),
            "invalid_arguments"
        );
        ensure!(
            (1..=MAX_COMMAND_ARGUMENTS).contains(&self.command.len())
                && !self.command[0].is_empty()
                && self.command.iter().map(String::len).sum::<usize>() <= MAX_COMMAND_BYTES
                && self.command.iter().all(|argument| !argument.contains('\0')),
            "invalid_arguments"
        );
        ensure!(
            self.working_directory.len() <= MAX_TEXT && !self.working_directory.contains('\0'),
            "invalid_arguments"
        );
        Ok(())
    }

    /// A successful run result holds only the values of valid variable names.
    fn is_valid_result(response: &Value) -> bool {
        response.get("ok") == Some(&Value::Bool(true))
            && response
                .as_object()
                .is_some_and(|object| object.keys().all(|key| key == "ok" || key == "variables"))
            && response
                .get("variables")
                .and_then(Value::as_object)
                .is_some_and(|variables| {
                    variables.len() <= MAX_VARIABLES
                        && variables
                            .iter()
                            .map(|(name, value)| name.len() + value.as_str().map_or(0, str::len))
                            .sum::<usize>()
                            <= MAX_CONTENT
                        && variables.iter().all(|(name, value)| {
                            is_variable_name(name)
                                && value.as_str().is_some_and(|v| !v.contains('\0'))
                        })
                })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::limits::MAX_VARIABLE_NAME_LENGTH;
    use serde_json::{Map, json};

    #[test]
    fn run_requests_are_bounded() {
        let request = |environment: &str, command: Vec<String>| RunRequest {
            environment: environment.into(),
            account: None,
            command,
            working_directory: "/".into(),
        };
        let in_account = |account: &str| RunRequest {
            account: Some(account.into()),
            ..request("e", vec!["npm".into()])
        };
        assert!(request("Production", vec!["npm".into()]).validate().is_ok());
        assert!(in_account("Work").validate().is_ok());
        // An older Keyguard rejects unknown fields, so an unset account is omitted.
        assert!(
            serde_json::to_value(request("e", vec!["npm".into()]))
                .unwrap()
                .get("account")
                .is_none()
        );
        for invalid in [
            request("", vec!["npm".into()]),
            request("a\nb", vec!["npm".into()]),
            in_account(" "),
            in_account("a\nb"),
            request("e", vec![]),
            request("e", vec![String::new()]),
            request("e", vec!["a\0b".into()]),
            request("e", vec!["x".into(); MAX_COMMAND_ARGUMENTS + 1]),
            request("e", vec!["x".repeat(MAX_COMMAND_BYTES + 1)]),
        ] {
            assert!(invalid.validate().is_err());
        }
    }

    #[test]
    fn variable_limits_apply_to_results() {
        for (names, accepted) in [
            (vec!["A".repeat(MAX_VARIABLE_NAME_LENGTH)], true),
            (vec!["A".repeat(MAX_VARIABLE_NAME_LENGTH + 1)], false),
            ((0..MAX_VARIABLES).map(|i| format!("V{i}")).collect(), true),
            (
                (0..=MAX_VARIABLES).map(|i| format!("V{i}")).collect(),
                false,
            ),
        ] {
            let variables: Map<String, Value> =
                names.iter().map(|name| (name.clone(), json!(""))).collect();
            assert_eq!(
                RunRequest::is_valid_result(&json!({"ok":true,"variables":variables})),
                accepted
            );
        }
    }

    #[test]
    fn run_results_hold_only_values_of_valid_names() {
        assert!(RunRequest::is_valid_result(
            &json!({"ok":true,"variables":{"TOKEN":"secret","_EMPTY":""}})
        ));
        for invalid in [
            json!({"ok":false,"variables":{}}),
            json!({"ok":true}),
            json!({"ok":true,"variables":{"TOKEN":"x"},"error":"x"}),
            json!({"ok":true,"variables":{"1TOKEN":"x"}}),
            json!({"ok":true,"variables":{"TO-KEN":"x"}}),
            json!({"ok":true,"variables":{"TOKEN":1}}),
            json!({"ok":true,"variables":{"TOKEN":"a\0b"}}),
            json!({"ok":true,"variables":{"TOKEN":"x".repeat(MAX_CONTENT)}}),
        ] {
            assert!(!RunRequest::is_valid_result(&invalid), "{invalid}");
        }
    }
}
