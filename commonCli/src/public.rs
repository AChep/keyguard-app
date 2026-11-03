//! Frames of the public socket. Management is answered with metadata only; a
//! CLI command may be answered with values, but only to the bundled CLI.
use crate::{cli::CliRequest, json::PrivateJson, manage::PublicRequest};
use anyhow::{Context, Result, bail};
use serde::Serialize;
use serde_json::Value;

pub enum PublicFrame {
    Manage(PublicRequest),
    Cli(CliRequest),
}

#[derive(Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum PublicFrameRef<'a> {
    Manage(&'a PublicRequest),
    Cli(&'a CliRequest),
}

pub enum ParsedFrame {
    Frame(PublicFrame),
    /// A frame of an operation this version does not know, such as a
    /// command of a newer CLI. It is answered, rather than dropped, so that
    /// the CLI can say why.
    Unsupported,
}

/// Parses the operation first, so that an unknown one is told apart from a
/// malformed request of a known one. The frame is parsed once, and its
/// strings are moved into the request rather than copied.
pub fn parse_public_frame(bytes: &[u8]) -> Result<ParsedFrame> {
    let mut frame = PrivateJson(serde_json::from_slice(bytes)?);
    let fields = frame.0.as_object_mut().context("invalid_frame")?;
    let kind = match fields.remove("type") {
        Some(Value::String(kind)) => kind,
        _ => bail!("invalid_frame"),
    };
    let request = match kind.as_str() {
        "manage" => PublicFrame::Manage(serde_json::from_value(frame.0.take())?),
        "cli" => {
            let command = fields
                .get("command")
                .and_then(Value::as_str)
                .context("invalid_frame")?;
            if !CliRequest::COMMANDS.contains(&command) {
                return Ok(ParsedFrame::Unsupported);
            }
            PublicFrame::Cli(serde_json::from_value(frame.0.take())?)
        }
        _ => return Ok(ParsedFrame::Unsupported),
    };
    Ok(ParsedFrame::Frame(request))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cli::RunRequest;
    use serde_json::json;

    fn parse(value: Value) -> Result<ParsedFrame> {
        parse_public_frame(&serde_json::to_vec(&value).unwrap())
    }

    #[test]
    fn frames_are_tagged_and_private_operations_are_not_frames() {
        assert!(matches!(
            parse(json!({"type":"manage","tool":"authenticate","arguments":{}})),
            Ok(ParsedFrame::Frame(PublicFrame::Manage(_)))
        ));
        assert!(matches!(
            parse(
                json!({"type":"cli","command":"run","request":{"environment":"e","command":["env"],"workingDirectory":""}})
            ),
            Ok(ParsedFrame::Frame(PublicFrame::Cli(CliRequest::Run(_))))
        ));
        assert!(matches!(
            parse(
                json!({"type":"cli","command":"run","request":{"environment":"e","account":"a","command":["env"],"workingDirectory":""}})
            ),
            Ok(ParsedFrame::Frame(PublicFrame::Cli(CliRequest::Run(
                RunRequest {
                    account: Some(_),
                    ..
                }
            ))))
        ));
        // Malformed requests of known operations are not answered.
        for value in [
            json!({"tool":"authenticate","arguments":{}}),
            json!({"type":1}),
            json!({"type":"cli","command":["run"]}),
            json!({"type":"manage","tool":"authenticate","arguments":{},"caller":{}}),
            json!({"type":"cli","command":"run","request":{"environment":"e","command":["env"],"workingDirectory":""},"caller":{}}),
            json!({"type":"cli","command":"run","request":{"environment":"e","command":["env"],"workingDirectory":"","caller":{}}}),
        ] {
            assert!(parse(value.clone()).is_err(), "{value}");
        }
    }

    #[test]
    fn unknown_operations_and_commands_are_unsupported() {
        for value in [
            json!({"type":"resolveMount","mountId":"x","generation":"1"}),
            json!({"type":"run","environment":"e","command":["env"],"workingDirectory":""}),
            json!({"type":"whoami"}),
            json!({"type":"cli","command":"read","request":{"reference":"x"}}),
        ] {
            assert!(
                matches!(parse(value.clone()), Ok(ParsedFrame::Unsupported)),
                "{value}"
            );
        }
    }

    #[test]
    fn cli_frames_nest_the_command_and_its_request() {
        let request = CliRequest::Run(RunRequest {
            environment: "e".into(),
            account: None,
            command: vec!["env".into()],
            working_directory: "/".into(),
        });
        assert_eq!(
            serde_json::to_value(PublicFrameRef::Cli(&request)).unwrap(),
            json!({"type":"cli","command":"run","request":{"environment":"e","command":["env"],"workingDirectory":"/"}})
        );
    }
}
