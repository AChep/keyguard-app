//! `keyguard mcp`: the official MCP SDK transport with a closed,
//! management-only tool surface.
use crate::{Connection, errors};
use anyhow::{Result, ensure};
use keyguard_cli_protocol::{
    errors::{UNSUPPORTED_COMMAND, error_code},
    frame::{read_frame, write_frame},
    manage::{
        InvalidRequest, PublicRequest, TOOL_SPECS, ToolEffect, ToolSpec, input_schema,
        public_result_is_metadata, sanitize_client_name, tool_spec, validate_public_request,
    },
    public::PublicFrameRef,
    socket::client_socket,
    transport::{self, BoxStream},
};
use rmcp::{
    ErrorData, RoleServer, ServerHandler, ServiceExt,
    model::{
        CallToolRequestParams, CallToolResponse, CallToolResult, ContentBlock, Implementation,
        ListToolsResult, PaginatedRequestParams, ServerCapabilities, ServerConfig, Tool,
        ToolAnnotations,
    },
    service::RequestContext,
};
use serde_json::{Value, json};
use std::{path::PathBuf, sync::Arc, time::Duration};
use tokio::sync::Mutex;
use zeroize::Zeroizing;

#[derive(clap::Args)]
pub(crate) struct Args {
    #[command(flatten)]
    connection: Connection,
}

/// Serves MCP until the client closes standard input.
pub(crate) async fn main(args: Args) -> i32 {
    let result = async {
        let socket = client_socket(args.connection.socket)?;
        run(socket).await
    }
    .await;
    if result.is_err() {
        eprintln!("Keyguard MCP stopped. Open Keyguard and check the MCP configuration.");
        return 1;
    }
    0
}

#[derive(Clone)]
struct Bridge {
    socket: PathBuf,
    stream: Arc<Mutex<Option<BoxStream>>>,
}

impl Bridge {
    async fn request(&self, request: &PublicRequest) -> Result<Value> {
        let mut slot = self.stream.lock().await;
        // Taking ownership means cancellation drops the connection rather than
        // leaving a half-read frame for the next request. The app revokes its grant.
        let mut stream = match slot.take() {
            Some(stream) => stream,
            None => transport::connect(&self.socket, None).await?,
        };
        let bytes = Zeroizing::new(serde_json::to_vec(&PublicFrameRef::Manage(request))?);
        write_frame(&mut *stream, &bytes).await?;
        let response: Value = serde_json::from_slice(&read_frame(&mut *stream).await?)?;
        ensure!(public_result_is_metadata(&response), "invalid_response");
        // The broker closes a connection it refuses or does not support.
        let code = error_code(&response);
        if code != "too_many_connections" && code != UNSUPPORTED_COMMAND {
            *slot = Some(stream);
        }
        Ok(response)
    }
}

fn tool(spec: &ToolSpec) -> Tool {
    let annotations = ToolAnnotations::new().open_world(false);
    // The destructive and idempotent hints only apply to tools that write.
    let annotations = match spec.effect {
        ToolEffect::ReadOnly => annotations.read_only(true),
        ToolEffect::Write {
            destructive,
            idempotent,
        } => annotations
            .read_only(false)
            .destructive(destructive)
            .idempotent(idempotent),
    };
    Tool::new(spec.name, spec.description, input_schema(spec)).with_annotations(annotations)
}

impl ServerHandler for Bridge {
    fn get_info(&self) -> ServerConfig {
        ServerConfig::new(ServerCapabilities::builder().enable_tools().build())
            .with_server_info(Implementation::new("keyguard-environments", env!("CARGO_PKG_VERSION")))
            .with_instructions("Manage Keyguard environments. Call authenticate first and approve access in Keyguard. Secret values are never returned. To use existing values, run a local command with keyguard run on Windows, Linux, or macOS, or create a user-approved local .env file on Linux and macOS. No shell execution, exports, resources, or deletion tools are provided.")
    }

    async fn list_tools(
        &self,
        _request: Option<PaginatedRequestParams>,
        _context: RequestContext<RoleServer>,
    ) -> Result<ListToolsResult, ErrorData> {
        Ok(ListToolsResult::with_all_items(
            TOOL_SPECS.iter().map(tool).collect(),
        ))
    }

    fn get_tool(&self, name: &str) -> Option<Tool> {
        tool_spec(name).map(tool)
    }

    async fn call_tool(
        &self,
        request: CallToolRequestParams,
        context: RequestContext<RoleServer>,
    ) -> Result<CallToolResponse, ErrorData> {
        // Prefer the display title over the programmatic name. Either is
        // reported by the client itself, so Keyguard only shows it as a hint.
        let client = context.peer.peer_info().and_then(|info| {
            let client_info = &info.client_info;
            sanitize_client_name(client_info.title.as_deref().unwrap_or(&client_info.name))
        });
        let request = PublicRequest {
            tool: request.name.into_owned(),
            arguments: request.arguments.unwrap_or_default(),
            client,
        };
        match validate_public_request(&request) {
            Ok(()) => {}
            // MCP reports an unknown tool as a protocol error, and arguments
            // as a tool error that the model can read and correct.
            Err(InvalidRequest::UnknownTool) => {
                return Err(ErrorData::invalid_params("Unknown tool.", None));
            }
            Err(error) => return Ok(tool_error(&error.to_string(), error.message())),
        }
        let response = tokio::select! {
            _ = context.ct.cancelled() => return Ok(CallToolResult::error(vec![ContentBlock::text("Request cancelled.")]).into()),
            response = tokio::time::timeout(Duration::from_secs(75), self.request(&request)) => response,
        };
        match response {
            Ok(Ok(mut response)) if response.get("ok") == Some(&Value::Bool(true)) => {
                let result = response
                    .get_mut("result")
                    .map(Value::take)
                    .unwrap_or_else(|| json!({}));
                Ok(CallToolResult::structured(result).into())
            }
            Ok(Ok(response)) => {
                let code = error_code(&response);
                Ok(tool_error(code, message(code)))
            }
            Ok(Err(_)) => Ok(tool_error("unavailable", message("unavailable"))),
            Err(_) => Ok(tool_error("request_timeout", message("request_timeout"))),
        }
    }
}

/// A failed tool call: a stable code to branch on, and a message to act on.
fn tool_error(code: &str, message: String) -> CallToolResponse {
    CallToolResult::structured_error(json!({"error":code,"message":message})).into()
}

/// The message for an error `code`, worded for an MCP client.
fn message(code: &str) -> String {
    match code {
        "integration_disabled" => {
            "MCP access is turned off. Turn it on in Keyguard's Developer settings under Environments > MCP."
        }
        "unavailable" => {
            "Keyguard is not running, or its CLI agent is stopped. Open Keyguard, then call authenticate again."
        }
        "authorization_expired" => "Access expired. Call authenticate again.",
        "account_not_found" => "Account not found. Call authenticate to get account IDs.",
        "not_found" => "Account or environment not found. Call list_environments to get IDs.",
        "ambiguous_environment" => {
            "Environments in several accounts have this ID. Pass account_id."
        }
        "permission_denied" => "You don't have permission to access this environment.",
        "invalid_name" => "The name is blank or too long.",
        "variable_is_reference" => {
            "A variable references another Keyguard item and can not be overwritten. Leave it out."
        }
        "unsupported_environment" => "This environment contains unsupported data.",
        "invalid_environment" => {
            "This environment exceeds the variable count or name length limit. Edit it in Keyguard."
        }
        "environment_too_large" => "The environment is too large.",
        "unsupported_platform" => "This Keyguard build does not support local .env files.",
        "invalid_path" => "The path must be an absolute path to a file, without '..'.",
        "path_exists" => "The path already exists. Choose a path that does not exist.",
        "path_conflict" => "The path is registered for another environment.",
        "mount_limit" => "Too many local .env files. Remove one in Keyguard.",
        "directory_access_required" => {
            "Keyguard can not access this folder. Allow access to it in Keyguard, then try again."
        }
        "mount_unavailable" => "Keyguard could not create the local .env file.",
        code => {
            return errors::message(code).map_or_else(
                || format!("Keyguard could not complete the request ({code})."),
                str::to_owned,
            );
        }
    }
    .to_owned()
}

/// Serve MCP on standard input/output. Diagnostics never use standard output.
async fn run(socket: PathBuf) -> Result<()> {
    let bridge = Bridge {
        socket,
        stream: Arc::new(Mutex::new(None)),
    };
    let service = bridge.serve(rmcp::transport::stdio()).await?;
    service.waiting().await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn initialization_explains_cross_platform_value_delivery() {
        let bridge = Bridge {
            socket: PathBuf::new(),
            stream: Arc::new(Mutex::new(None)),
        };
        let instructions = bridge.get_info().instructions.unwrap();
        assert!(instructions.contains("keyguard run"));
        assert!(instructions.contains("Linux and macOS"));
        assert!(instructions.contains("Secret values are never returned"));
        assert!(instructions.contains("No shell execution"));
    }

    #[test]
    fn disabled_integration_points_to_the_mcp_setting() {
        let CallToolResponse::Complete(response) =
            tool_error("integration_disabled", message("integration_disabled"))
        else {
            panic!("expected a completed error response");
        };
        let value = serde_json::to_value(response).unwrap();
        assert_eq!(value["structuredContent"]["error"], "integration_disabled");
        let diagnostic = value["structuredContent"]["message"].as_str().unwrap();
        assert!(diagnostic.contains("MCP"), "{diagnostic}");
        assert!(diagnostic.contains("Developer"), "{diagnostic}");
    }

    #[test]
    fn every_error_code_gets_a_message() {
        assert!(message("not_found").contains("list_environments"));
        assert_eq!(
            message("vault_locked"),
            errors::message("vault_locked").unwrap()
        );
        assert!(message("invalid_request").contains("(invalid_request)"));
    }
}
