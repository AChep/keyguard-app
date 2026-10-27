//! Environment management of an MCP client. Never answered with values.
use crate::{
    app_client::AppClient,
    mounts::Mounts,
    protocol::{
        self, ManageRequest, ManageResponse, MountStatus, VariableSource, VariableUpdate,
        manage_response, request, response,
    },
    transport::Accepted,
};
use anyhow::{Context, Result, ensure};
use keyguard_agent_identity::ConnectionPrincipal;
use keyguard_agent_proto::CallerIdentity;
use keyguard_cli_protocol::{
    errors::{INVALID_RESPONSE, failure, stable_code},
    manage::{PublicRequest, public_result_is_metadata, validate_public_request},
};
use serde_json::{Value, json};
use std::collections::BTreeMap;
use tokio::sync::Mutex;

/// Answers one management request. Returns whether the connection stays open.
pub(super) async fn serve(
    accepted: &mut Accepted,
    app: &AppClient,
    mounts: &Mutex<Mounts>,
    connection_id: &ConnectionPrincipal,
    mut request: PublicRequest,
) -> Result<bool> {
    validate_public_request(&request)?;
    accepted.guard.revalidate().await?;
    let tool = request.tool.clone();
    let private = private_request(&accepted.caller, connection_id, &mut request);
    // One outstanding request per public socket, so unexpected pipelining
    // closes the connection.
    let Some(mut response) = super::ask(
        accepted,
        app,
        request::Request::Manage(private),
        |response| public_response(&tool, response),
    )
    .await?
    else {
        return Ok(false);
    };
    if tool == "create_local_env_file" && response.get("ok") == Some(&Value::Bool(true)) {
        let id = response
            .pointer("/result/id")
            .and_then(Value::as_str)
            .context("invalid_mount_result")?
            .to_owned();
        let mut mounts = mounts.lock().await;
        mounts.refresh().await?;
        if !mounts.is_ready(&id) {
            response = failure("mount_unavailable");
        } else if let Some(result) = response.get_mut("result").and_then(Value::as_object_mut) {
            result.insert("status".into(), json!("ready"));
        }
    }
    ensure!(
        public_result_is_metadata(&response),
        "non_metadata_response"
    );
    accepted.guard.revalidate().await?;
    super::reply(accepted, &response).await?;
    Ok(true)
}

/// The request as the app receives it, with the verified caller of the
/// connection. Expects a [validated][validate_public_request] request, and
/// moves its values out, so that no copy of them is left behind.
fn private_request(
    caller: &CallerIdentity,
    connection_id: &ConnectionPrincipal,
    request: &mut PublicRequest,
) -> ManageRequest {
    let mut arguments = BTreeMap::new();
    let mut variables = Vec::new();
    for (name, value) in &mut request.arguments {
        match value {
            Value::Array(entries) if name == "variables" => {
                variables = entries
                    .iter_mut()
                    .map(|entry| VariableUpdate {
                        name: entry.get_mut("name").map(take_string).unwrap_or_default(),
                        value: entry
                            .get_mut("value")
                            .map(|value| take_string(value).into_bytes()),
                    })
                    .collect();
            }
            value => {
                arguments.insert(name.clone(), take_string(value));
            }
        }
    }
    ManageRequest {
        caller: Some(caller.clone()),
        connection_id: connection_id.as_bytes().to_vec(),
        client_name: request.client.clone().unwrap_or_default(),
        tool: request.tool.clone(),
        arguments,
        variables,
    }
}

fn take_string(value: &mut Value) -> String {
    match value.take() {
        Value::String(value) => value,
        _ => String::new(),
    }
}

/// The answer of the app, as the MCP client receives it: the result of the
/// `tool`, or a stable error code.
fn public_response(tool: &str, response: &response::Response) -> Value {
    let result = match response {
        response::Response::Manage(ManageResponse {
            result: Some(result),
        }) => tool_result(tool, result),
        response::Response::Error(error) => return failure(stable_code(&error.code)),
        _ => None,
    };
    match result {
        Some(result) => json!({"ok":true,"result":result}),
        None => failure(INVALID_RESPONSE),
    }
}

/// The result of the `tool`, `None` if the app answered with the result of
/// another tool.
fn tool_result(tool: &str, result: &manage_response::Result) -> Option<Value> {
    use manage_response::Result as R;
    let result = match (tool, result) {
        ("authenticate", R::Accounts(list)) => json!({
            "accounts": list
                .accounts
                .iter()
                .map(|account| json!({"id":account.id,"name":account.name}))
                .collect::<Vec<_>>(),
        }),
        ("list_environments", R::Environments(list)) => json!({
            "environments": list
                .environments
                .iter()
                .map(|environment| json!({
                    "id":environment.id,
                    "account_id":environment.account_id,
                    "name":environment.name,
                    "variable_count":environment.variable_count,
                    "editable":environment.editable,
                }))
                .collect::<Vec<_>>(),
        }),
        (
            "create_environment" | "rename_environment" | "append_variables",
            R::Environment(environment),
        ) => json!({"id":environment.id,"account_id":environment.account_id}),
        ("list_variables", R::Variables(list)) => json!({
            "variables": list
                .variables
                .iter()
                .map(variable)
                .collect::<Option<Vec<_>>>()?,
        }),
        ("create_local_env_file", R::LocalEnvFile(file)) => local_env_file(file)?,
        ("list_local_env_files", R::LocalEnvFiles(list)) => json!({
            "local_env_files": list
                .local_env_files
                .iter()
                .map(local_env_file)
                .collect::<Option<Vec<_>>>()?,
        }),
        _ => return None,
    };
    Some(result)
}

fn variable(variable: &protocol::Variable) -> Option<Value> {
    let source = match VariableSource::try_from(variable.source).ok()? {
        VariableSource::Value => "value",
        VariableSource::Reference => "reference",
        VariableSource::Unset => "unset",
        VariableSource::Unsupported => "unsupported",
        VariableSource::Unspecified => return None,
    };
    Some(json!({
        "name":variable.name,
        "source":source,
        "is_set":variable.is_set,
        "supported":variable.supported,
    }))
}

fn local_env_file(file: &protocol::LocalEnvFile) -> Option<Value> {
    let status = match MountStatus::try_from(file.status).ok()? {
        MountStatus::Pending => "pending",
        MountStatus::Ready => "ready",
        MountStatus::Error => "error",
        MountStatus::Unspecified => return None,
    };
    Some(json!({
        "id":file.id,
        "environment_id":file.environment_id,
        "account_id":file.account_id,
        "path":file.path,
        "enabled":file.enabled,
        "status":status,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::{Account, AccountList, EnvironmentId, Variable, VariableList};

    fn manage(result: manage_response::Result) -> response::Response {
        response::Response::Manage(ManageResponse {
            result: Some(result),
        })
    }

    #[test]
    fn requests_carry_the_arguments_and_the_values_to_append() {
        let mut request: PublicRequest = serde_json::from_value(json!({
            "tool":"append_variables",
            "arguments":{"environment_id":"env","account_id":"account","variables":[{"name":"TOKEN","value":"secret"},{"name":"UNSET"}]},
            "client":"Claude Code",
        }))
        .unwrap();
        validate_public_request(&request).unwrap();
        let caller = CallerIdentity {
            pid: 42,
            ..CallerIdentity::default()
        };
        let private = private_request(
            &caller,
            &ConnectionPrincipal::from_bytes([7; 32]),
            &mut request,
        );
        assert_eq!(private.caller, Some(caller));
        assert_eq!(private.connection_id, vec![7; 32]);
        assert_eq!(private.client_name, "Claude Code");
        assert_eq!(private.tool, "append_variables");
        assert_eq!(
            private.arguments,
            BTreeMap::from([
                ("environment_id".into(), "env".into()),
                ("account_id".into(), "account".into()),
            ])
        );
        assert_eq!(
            private.variables,
            vec![
                VariableUpdate {
                    name: "TOKEN".into(),
                    value: Some(b"secret".to_vec()),
                },
                VariableUpdate {
                    name: "UNSET".into(),
                    value: None,
                },
            ]
        );
    }

    #[test]
    fn results_keep_the_public_shape_of_their_tool() {
        let accounts = manage(manage_response::Result::Accounts(AccountList {
            accounts: vec![Account {
                id: "a".into(),
                name: "Personal".into(),
            }],
        }));
        assert_eq!(
            public_response("authenticate", &accounts),
            json!({"ok":true,"result":{"accounts":[{"id":"a","name":"Personal"}]}})
        );
        let variables = manage(manage_response::Result::Variables(VariableList {
            variables: vec![Variable {
                name: "TOKEN".into(),
                source: VariableSource::Reference.into(),
                is_set: true,
                supported: true,
            }],
        }));
        assert_eq!(
            public_response("list_variables", &variables),
            json!({"ok":true,"result":{"variables":[{"name":"TOKEN","source":"reference","is_set":true,"supported":true}]}})
        );
    }

    #[test]
    fn environment_and_mount_results_keep_account_scope() {
        let environment = manage(manage_response::Result::Environment(EnvironmentId {
            id: "shared".into(),
            account_id: "account".into(),
        }));
        for tool in [
            "create_environment",
            "rename_environment",
            "append_variables",
        ] {
            assert_eq!(
                public_response(tool, &environment),
                json!({"ok":true,"result":{"id":"shared","account_id":"account"}}),
            );
        }
        for account_id in [Some("account".to_owned()), None] {
            let file = protocol::LocalEnvFile {
                id: "mount".into(),
                environment_id: "shared".into(),
                path: "/work/.env".into(),
                enabled: true,
                status: MountStatus::Pending.into(),
                account_id: account_id.clone(),
            };
            let expected = json!({"id":"mount","environment_id":"shared","account_id":account_id,
                "path":"/work/.env","enabled":true,"status":"pending"});
            assert_eq!(
                public_response(
                    "create_local_env_file",
                    &manage(manage_response::Result::LocalEnvFile(file.clone()))
                ),
                json!({"ok":true,"result":expected}),
            );
            assert_eq!(
                public_response(
                    "list_local_env_files",
                    &manage(manage_response::Result::LocalEnvFiles(
                        protocol::LocalEnvFileList {
                            local_env_files: vec![file],
                        }
                    ))
                ),
                json!({"ok":true,"result":{"local_env_files":[expected]}}),
            );
        }
    }

    #[test]
    fn results_of_another_tool_or_an_unknown_kind_are_invalid() {
        let environment = manage(manage_response::Result::Environment(EnvironmentId {
            id: "e".into(),
            account_id: "a".into(),
        }));
        assert_eq!(
            public_response("authenticate", &environment),
            failure(INVALID_RESPONSE)
        );
        let unknown = manage(manage_response::Result::Variables(VariableList {
            variables: vec![Variable {
                name: "TOKEN".into(),
                source: 99,
                ..Variable::default()
            }],
        }));
        assert_eq!(
            public_response("list_variables", &unknown),
            failure(INVALID_RESPONSE)
        );
        assert_eq!(
            public_response(
                "authenticate",
                &response::Response::Error(protocol::ErrorResponse {
                    code: "user_denied".into(),
                }),
            ),
            failure("user_denied")
        );
    }
}
