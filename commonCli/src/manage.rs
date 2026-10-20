//! Environment management through MCP. It is answered with metadata only.
use crate::{
    bounds::{MAX_CONTENT, MAX_TEXT, is_variable_name},
    json::wipe_json,
    limits::{MAX_VARIABLE_NAME_LENGTH, MAX_VARIABLES},
};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};
use std::fmt;

/// Bounds the name an MCP client reports for itself, in characters.
pub const MAX_CLIENT_NAME: usize = 128;

/// One public management tool: the arguments the broker accepts, and how
/// the MCP adapter lists it.
pub struct ToolSpec {
    pub name: &'static str,
    pub description: &'static str,
    pub required: &'static [&'static str],
    pub optional: &'static [&'static str],
    pub effect: ToolEffect,
}

/// What a tool may change, as MCP tool annotations describe it.
pub enum ToolEffect {
    ReadOnly,
    /// A destructive write may replace existing data rather than only add
    /// to it. An idempotent write has no further effect when repeated with
    /// the same arguments.
    Write {
        destructive: bool,
        idempotent: bool,
    },
}

/// The closed public tool surface.
pub const TOOL_SPECS: &[ToolSpec] = &[
    ToolSpec {
        name: "authenticate",
        description: "Request access in Keyguard and return permitted account metadata. Approve the request in Keyguard.",
        required: &[],
        optional: &[],
        effect: ToolEffect::ReadOnly,
    },
    ToolSpec {
        name: "list_environments",
        description: "List environment names and identifiers without secret values.",
        required: &[],
        optional: &["account_id"],
        effect: ToolEffect::ReadOnly,
    },
    ToolSpec {
        name: "create_environment",
        description: "Create an empty environment in an approved account.",
        required: &["account_id", "name"],
        optional: &[],
        effect: ToolEffect::Write {
            destructive: false,
            idempotent: false,
        },
    },
    ToolSpec {
        name: "rename_environment",
        description: "Rename an environment after approval in Keyguard.",
        required: &["environment_id", "name"],
        optional: &["account_id"],
        effect: ToolEffect::Write {
            destructive: true,
            idempotent: true,
        },
    },
    ToolSpec {
        name: "list_variables",
        description: "List variable names, whether a value is set, and its source: a value, a reference to another Keyguard item, or unset. Values and referenced items are never returned.",
        required: &["environment_id"],
        optional: &["account_id"],
        effect: ToolEffect::ReadOnly,
    },
    ToolSpec {
        name: "append_variables",
        description: "Append or update variables atomically after approval. Omit value to preserve an existing value or create an unset variable. An empty string is a set value. Variables that reference another Keyguard item can not be overwritten. Values are never returned.",
        required: &["environment_id", "variables"],
        optional: &["account_id"],
        effect: ToolEffect::Write {
            destructive: true,
            idempotent: true,
        },
    },
    ToolSpec {
        name: "create_local_env_file",
        description: "Register a local .env FIFO for an environment on macOS or Linux, with approval in Keyguard. Existing paths are never overwritten. Reading this path can reveal secrets to the reader; MCP never returns them.",
        required: &["environment_id", "path"],
        optional: &["account_id"],
        effect: ToolEffect::Write {
            destructive: false,
            idempotent: true,
        },
    },
    ToolSpec {
        name: "list_local_env_files",
        description: "List local environment file paths and status without file contents.",
        required: &[],
        optional: &["environment_id", "account_id"],
        effect: ToolEffect::ReadOnly,
    },
];

pub fn tool_spec(name: &str) -> Option<&'static ToolSpec> {
    TOOL_SPECS.iter().find(|spec| spec.name == name)
}

/// The JSON schema of a tool's arguments: exactly the arguments that
/// [validate_public_request] accepts.
pub fn input_schema(spec: &ToolSpec) -> Map<String, Value> {
    let string = json!({"type":"string","minLength":1});
    let properties: Map<String, Value> = spec
        .required
        .iter()
        .chain(spec.optional)
        .map(|&name| {
            let schema = match name {
                "account_id" => json!({"type":"string","minLength":1,"description":"Account ID from authenticate or list_environments, or an exact unique account name. Scopes environment selection to this account."}),
                "environment_id" => json!({"type":"string","minLength":1,"description":"Environment ID from list_environments. Include its account_id to distinguish environments in different accounts."}),
                "variables" => json!({"type":"array","minItems":1,"maxItems":MAX_VARIABLES,"items":{"type":"object","properties":{"name":{"type":"string","minLength":1,"maxLength":MAX_VARIABLE_NAME_LENGTH,"pattern":"^[A-Za-z_][A-Za-z0-9_]*$"},"value":{"type":"string"}},"required":["name"],"additionalProperties":false}}),
                _ => string.clone(),
            };
            (name.to_owned(), schema)
        })
        .collect();
    let schema = json!({"type":"object","properties":properties,"required":spec.required,"additionalProperties":false});
    schema.as_object().cloned().unwrap_or_default()
}

/// The only management shape accepted on the public socket. No private
/// operation is representable.
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PublicRequest {
    pub tool: String,
    pub arguments: Map<String, Value>,
    /// The name the MCP client reports for itself. It is shown as a hint
    /// next to the verified caller and never identifies the caller.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub client: Option<String>,
}

impl Drop for PublicRequest {
    fn drop(&mut self) {
        for value in self.arguments.values_mut() {
            wipe_json(value);
        }
    }
}

/// Why [validate_public_request] rejected a request. Argument names come
/// from [TOOL_SPECS], never from the request.
#[derive(Debug, PartialEq, Eq)]
pub enum InvalidRequest {
    Client,
    UnknownTool,
    /// The name of the argument is not kept, as the client chose it.
    UnexpectedArgument {
        accepted: Vec<&'static str>,
    },
    MissingArgument(&'static str),
    InvalidArgument(&'static str),
    /// The item at this index of `variables`.
    InvalidVariable(usize),
}

impl InvalidRequest {
    /// What the caller has to change, worded for a person or a model.
    pub fn message(&self) -> String {
        match self {
            Self::Client => "The client name is empty, too long, or has control characters.".into(),
            Self::UnknownTool => "Unknown tool.".into(),
            Self::UnexpectedArgument { accepted } if accepted.is_empty() => {
                "Unexpected argument. This tool takes no arguments.".into()
            }
            Self::UnexpectedArgument { accepted } => format!(
                "Unexpected argument. This tool accepts: {}.",
                accepted.join(", ")
            ),
            Self::MissingArgument(name) => format!("Missing required argument {name}."),
            Self::InvalidArgument("variables") => {
                format!("variables must be an array of 1 to {MAX_VARIABLES} objects.")
            }
            Self::InvalidArgument(name) => format!(
                "{name} must be a non-empty string of at most {MAX_TEXT} bytes, without NUL characters."
            ),
            Self::InvalidVariable(index) => format!(
                "variables[{index}] is invalid. It takes a name of letters, digits, and underscores, not starting with a digit, of at most {MAX_VARIABLE_NAME_LENGTH} characters, and an optional string value of at most {MAX_CONTENT} bytes, without NUL characters."
            ),
        }
    }
}

/// The stable error code.
impl fmt::Display for InvalidRequest {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Client => "invalid_client",
            Self::UnknownTool => "unknown_tool",
            _ => "invalid_arguments",
        })
    }
}

impl std::error::Error for InvalidRequest {}

pub fn validate_public_request(request: &PublicRequest) -> Result<(), InvalidRequest> {
    if let Some(client) = &request.client
        && !is_valid_client_name(client)
    {
        return Err(InvalidRequest::Client);
    }
    let spec = tool_spec(&request.tool).ok_or(InvalidRequest::UnknownTool)?;
    let accepted = || spec.required.iter().chain(spec.optional).copied();
    if !request
        .arguments
        .keys()
        .all(|key| accepted().any(|name| name == key))
    {
        return Err(InvalidRequest::UnexpectedArgument {
            accepted: accepted().collect(),
        });
    }
    if let Some(name) = spec
        .required
        .iter()
        .find(|name| !request.arguments.contains_key(**name))
    {
        return Err(InvalidRequest::MissingArgument(name));
    }
    // Walks the spec rather than the request, so that errors name arguments
    // that Keyguard defines.
    for name in accepted() {
        match (name, request.arguments.get(name)) {
            (_, None) => {}
            ("variables", Some(value)) => validate_variables(value)?,
            (_, Some(value)) => {
                if !value
                    .as_str()
                    .is_some_and(|s| !s.is_empty() && s.len() <= MAX_TEXT && !s.contains('\0'))
                {
                    return Err(InvalidRequest::InvalidArgument(name));
                }
            }
        }
    }
    Ok(())
}

fn validate_variables(value: &Value) -> Result<(), InvalidRequest> {
    let variables = value
        .as_array()
        .filter(|variables| !variables.is_empty() && variables.len() <= MAX_VARIABLES)
        .ok_or(InvalidRequest::InvalidArgument("variables"))?;
    for (index, variable) in variables.iter().enumerate() {
        let valid = variable.as_object().is_some_and(|object| {
            object.keys().all(|key| key == "name" || key == "value")
                && object
                    .get("name")
                    .and_then(Value::as_str)
                    .is_some_and(is_variable_name)
                && object.get("value").is_none_or(|value| {
                    value
                        .as_str()
                        .is_some_and(|s| s.len() <= MAX_CONTENT && !s.contains('\0'))
                })
        });
        if !valid {
            return Err(InvalidRequest::InvalidVariable(index));
        }
    }
    Ok(())
}

pub fn is_valid_client_name(name: &str) -> bool {
    !name.trim().is_empty()
        && name.chars().count() <= MAX_CLIENT_NAME
        && !name.chars().any(char::is_control)
}

/// Bounds the name an MCP client reports for itself, dropping a name
/// that can not be shown as is.
pub fn sanitize_client_name(name: &str) -> Option<String> {
    let name: String = name
        .chars()
        .filter(|c| !c.is_control())
        .take(MAX_CLIENT_NAME)
        .collect();
    let name = name.trim();
    is_valid_client_name(name).then(|| name.to_owned())
}

/// Last-line defense against accidentally exposing a private response on public IPC.
pub fn public_result_is_metadata(value: &Value) -> bool {
    match value {
        Value::Object(map) => map.iter().all(|(key, value)| {
            !matches!(
                key.as_str(),
                "value" | "content" | "bookmark" | "token" | "password" | "secret"
            ) && public_result_is_metadata(value)
        }),
        Value::Array(values) => values.iter().all(public_result_is_metadata),
        _ => true,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn private_operations_and_spoofed_callers_are_not_public_requests() {
        for value in [
            json!({"type":"resolveMount","mountId":"x"}),
            json!({"tool":"resolveMount","arguments":{}}),
            json!({"tool":"authenticate","arguments":{},"caller":{"pid":1}}),
        ] {
            let request = serde_json::from_value::<PublicRequest>(value);
            assert!(request.is_err() || validate_public_request(&request.unwrap()).is_err());
        }
    }

    #[test]
    fn client_names_are_bounded_and_printable() {
        let request: PublicRequest = serde_json::from_value(
            json!({"tool":"authenticate","arguments":{},"client":"Claude Code"}),
        )
        .unwrap();
        assert!(validate_public_request(&request).is_ok());
        for client in [
            json!(""),
            json!("a\u{0007}b"),
            json!("x".repeat(MAX_CLIENT_NAME + 1)),
        ] {
            let request: PublicRequest = serde_json::from_value(
                json!({"tool":"authenticate","arguments":{},"client":client}),
            )
            .unwrap();
            assert!(validate_public_request(&request).is_err());
        }
        assert_eq!(
            sanitize_client_name(" Code\u{0007} "),
            Some("Code".to_owned())
        );
        assert_eq!(sanitize_client_name("\u{0007}"), None);
        assert_eq!(
            sanitize_client_name(&"x".repeat(MAX_CLIENT_NAME * 2)).map(|s| s.len()),
            Some(MAX_CLIENT_NAME),
        );
    }

    #[test]
    fn append_preserves_omitted_and_empty_values_but_rejects_null() {
        for variables in [
            json!([{"name":"TOKEN"}]),
            json!([{"name":"TOKEN","value":""}]),
        ] {
            let request = serde_json::from_value(json!({"tool":"append_variables","arguments":{"environment_id":"e","variables":variables}})).unwrap();
            assert!(validate_public_request(&request).is_ok());
        }
        let request = serde_json::from_value(json!({"tool":"append_variables","arguments":{"environment_id":"e","variables":[{"name":"TOKEN","value":null}]}})).unwrap();
        assert!(validate_public_request(&request).is_err());
    }

    #[test]
    fn public_response_rejects_nested_secret_fields() {
        assert!(!public_result_is_metadata(
            &json!({"variables":[{"name":"A","value":"secret"}]})
        ));
        assert!(public_result_is_metadata(
            &json!({"variables":[{"name":"A","hasValue":true}]})
        ));
    }

    #[test]
    fn variable_limits_apply_to_requests() {
        for (names, accepted) in [
            (vec!["A".repeat(MAX_VARIABLE_NAME_LENGTH)], true),
            (vec!["A".repeat(MAX_VARIABLE_NAME_LENGTH + 1)], false),
            ((0..MAX_VARIABLES).map(|i| format!("V{i}")).collect(), true),
            (
                (0..=MAX_VARIABLES).map(|i| format!("V{i}")).collect(),
                false,
            ),
        ] {
            let request: PublicRequest = serde_json::from_value(json!({
                "tool":"append_variables",
                "arguments":{
                    "environment_id":"environment",
                    "variables":names.iter().map(|name| json!({"name":name,"value":""})).collect::<Vec<_>>()
                }
            })).unwrap();
            assert_eq!(validate_public_request(&request).is_ok(), accepted);
        }
    }

    #[test]
    fn environment_tools_accept_optional_account_scope() {
        for (tool, arguments) in [
            (
                "rename_environment",
                json!({"environment_id":"shared","name":"New"}),
            ),
            ("list_variables", json!({"environment_id":"shared"})),
            (
                "append_variables",
                json!({"environment_id":"shared","variables":[{"name":"A"}]}),
            ),
            (
                "create_local_env_file",
                json!({"environment_id":"shared","path":"/work/.env"}),
            ),
            ("list_local_env_files", json!({"environment_id":"shared"})),
            ("list_local_env_files", json!({})),
        ] {
            let mut request: PublicRequest = serde_json::from_value(json!({
                "tool":tool,"arguments":arguments,
            }))
            .unwrap();
            assert!(validate_public_request(&request).is_ok(), "{tool}");
            request
                .arguments
                .insert("account_id".into(), json!("account"));
            assert!(validate_public_request(&request).is_ok(), "{tool}");
            for invalid in [json!(""), json!(null), json!(42)] {
                request.arguments.insert("account_id".into(), invalid);
                assert!(validate_public_request(&request).is_err(), "{tool}");
            }
        }
    }

    #[test]
    fn schemas_expose_only_management_and_no_raw_read_tool() {
        assert_eq!(TOOL_SPECS.len(), 8);
        for spec in TOOL_SPECS {
            assert_eq!(
                input_schema(spec).get("additionalProperties"),
                Some(&Value::Bool(false))
            );
            assert!(
                !spec.name.contains("resolve")
                    && !spec.name.contains("export")
                    && !spec.name.contains("delete")
            );
        }
    }

    #[test]
    fn schemas_list_exactly_the_validated_arguments() {
        let sample = |name: &str| match name {
            "variables" => json!([{"name":"A"}]),
            _ => json!("x"),
        };
        for spec in TOOL_SPECS {
            let schema = input_schema(spec);
            let validate = |names: &[&str]| {
                validate_public_request(&PublicRequest {
                    tool: spec.name.into(),
                    arguments: names
                        .iter()
                        .map(|name| ((*name).to_owned(), sample(name)))
                        .collect(),
                    client: None,
                })
            };
            let listed: Vec<&str> = schema["properties"]
                .as_object()
                .unwrap()
                .keys()
                .map(String::as_str)
                .collect();
            let required: Vec<&str> = schema["required"]
                .as_array()
                .unwrap()
                .iter()
                .map(|name| name.as_str().unwrap())
                .collect();
            assert!(validate(&listed).is_ok(), "{}", spec.name);
            assert!(validate(&required).is_ok(), "{}", spec.name);
            for missing in &required {
                let rest: Vec<&str> = required
                    .iter()
                    .copied()
                    .filter(|name| name != missing)
                    .collect();
                assert!(validate(&rest).is_err(), "{} without {missing}", spec.name);
            }
            let unlisted = [listed.as_slice(), &["extra"]].concat();
            assert!(validate(&unlisted).is_err(), "{}", spec.name);
        }
    }

    #[test]
    fn rejections_name_the_failed_rule() {
        let validate = |tool: &str, arguments: Value| {
            let request: PublicRequest =
                serde_json::from_value(json!({"tool":tool,"arguments":arguments})).unwrap();
            validate_public_request(&request)
        };
        assert_eq!(
            validate("missing", json!({})),
            Err(InvalidRequest::UnknownTool)
        );
        assert_eq!(
            validate("list_variables", json!({})),
            Err(InvalidRequest::MissingArgument("environment_id"))
        );
        assert_eq!(
            validate("list_variables", json!({"environment_id":""})),
            Err(InvalidRequest::InvalidArgument("environment_id"))
        );
        assert_eq!(
            validate(
                "append_variables",
                json!({"environment_id":"e","variables":[]})
            ),
            Err(InvalidRequest::InvalidArgument("variables"))
        );
        assert_eq!(
            validate(
                "append_variables",
                json!({"environment_id":"e","variables":[{"name":"A"},{"name":"1A"}]})
            ),
            Err(InvalidRequest::InvalidVariable(1))
        );
        let unexpected = validate(
            "list_variables",
            json!({"environment_id":"e","SECRET_KEY":"x"}),
        )
        .unwrap_err();
        assert_eq!(unexpected.to_string(), "invalid_arguments");
        assert!(!unexpected.message().contains("SECRET_KEY"));
        assert!(unexpected.message().contains("environment_id, account_id"));
    }
}
