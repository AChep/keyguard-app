//! A command of the `keyguard` CLI, served as its policy says. The agent
//! passes every command on alike: a new command needs no code here.
use crate::{
    app_client::AppClient,
    protocol::{self, CliResponse, response},
    transport::Accepted,
};
use anyhow::Result;
use keyguard_agent_proto::CallerIdentity;
use keyguard_cli_protocol::{
    cli::CliRequest,
    errors::{INVALID_RESPONSE, UNVERIFIED_CLI, error_code, failure, stable_code},
    frame::write_frame,
    json::PrivateJson,
};
use serde_json::Value;
use zeroize::{Zeroize, Zeroizing};

/// Answers one command, then closes the connection.
pub(super) async fn serve(
    mut accepted: Accepted,
    app: AppClient,
    request: CliRequest,
) -> Result<()> {
    request.validate()?;
    let policy = request.policy();
    accepted.guard.revalidate().await?;
    if policy.requires_bundled_cli && !accepted.bundled_cli {
        return super::reply(&mut accepted, &failure(UNVERIFIED_CLI)).await;
    }
    let caller = std::mem::take(&mut accepted.caller);
    let request = protocol::request::Request::Cli(private_request(caller, &request)?);
    let Some(response) = super::ask(&mut accepted, &app, request, public_response).await? else {
        return Ok(());
    };
    let response = PrivateJson(response);
    let failed = if (policy.is_valid_result)(&response.0) {
        None
    } else if response.0.get("ok") == Some(&Value::Bool(true)) {
        Some(failure(INVALID_RESPONSE))
    } else {
        Some(failure(error_code(&response.0)))
    };
    // The client receives exactly the result that the policy checked.
    let bytes = Zeroizing::new(serde_json::to_vec(failed.as_ref().unwrap_or(&response.0))?);
    write_frame(&mut *accepted.stream, &bytes).await
}

/// The command as the app receives it: its validated request, with the
/// verified caller.
fn private_request(caller: CallerIdentity, request: &CliRequest) -> Result<protocol::CliRequest> {
    let mut json = request.request_json()?;
    Ok(protocol::CliRequest {
        caller: Some(caller),
        command: request.policy().name.to_owned(),
        // Moved, so that no copy of the values is left behind.
        request: std::mem::take(&mut *json),
    })
}

/// The answer of the app, as the CLI receives it. The command's policy
/// then checks that it is a result of the command.
fn public_response(response: &mut response::Response) -> Value {
    match response {
        response::Response::Cli(CliResponse { result }) => {
            let parsed = serde_json::from_slice(result);
            result.zeroize();
            parsed.unwrap_or_else(|_| failure(INVALID_RESPONSE))
        }
        response::Response::Error(error) => failure(stable_code(&error.code)),
        _ => failure(INVALID_RESPONSE),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use keyguard_cli_protocol::cli::RunRequest;
    use serde_json::json;

    fn cli(result: &[u8]) -> response::Response {
        response::Response::Cli(CliResponse {
            result: result.to_vec(),
        })
    }

    #[test]
    fn the_app_receives_the_named_validated_request() {
        let request = CliRequest::Run(RunRequest {
            environment: "e".into(),
            account: Some("a".into()),
            command: vec!["env".into()],
            working_directory: "/".into(),
        });
        let private = private_request(CallerIdentity::default(), &request).unwrap();
        assert_eq!(private.command, "run");
        assert!(private.caller.is_some());
        assert_eq!(
            serde_json::from_slice::<Value>(&private.request).unwrap(),
            json!({"environment":"e","account":"a","command":["env"],"workingDirectory":"/"})
        );
    }

    #[test]
    fn results_are_passed_on_as_the_app_wrote_them() {
        let mut response = cli(br#"{"ok":true,"variables":{"TOKEN":"secret","EMPTY":""}}"#);
        assert_eq!(
            public_response(&mut response),
            json!({"ok":true,"variables":{"TOKEN":"secret","EMPTY":""}})
        );
        // The bytes are wiped once parsed.
        let response::Response::Cli(CliResponse { result }) = response else {
            unreachable!()
        };
        assert!(result.iter().all(|byte| *byte == 0));
    }

    #[test]
    fn malformed_and_foreign_results_are_invalid() {
        for mut response in [
            cli(b"not json"),
            cli(&[0xff]),
            cli(b""),
            response::Response::MountStatus(protocol::MountStatusResponse {}),
        ] {
            assert_eq!(public_response(&mut response), failure(INVALID_RESPONSE));
        }
    }

    #[test]
    fn only_stable_error_codes_are_passed_on() {
        let error =
            |code: &str| response::Response::Error(protocol::ErrorResponse { code: code.into() });
        assert_eq!(
            public_response(&mut error("user_denied")),
            failure("user_denied")
        );
        assert_eq!(
            public_response(&mut error("Secret /path")),
            failure("request_failed")
        );
    }
}
