//! `am broadcast` activation of the Keyguard app's bridge receiver.

use anyhow::Result;
use base64::Engine;
use std::ffi::OsString;
use std::io;
use std::process::{ExitStatus, Stdio};
use tokio::process::Command;
use tokio::time::{timeout_at, Instant};

use crate::secure_transport::{PROTOCOL_VERSION, SESSION_ID_LEN, SESSION_SECRET_LEN};

/// Names an agent's receiver intent and its result; each agent has its own so
/// the app never mistakes one protocol's session for the other's.
#[derive(Debug)]
pub struct BridgeContract {
    /// Intent action handled by the receiver.
    pub action: &'static str,
    /// Integer extra carrying [`PROTOCOL_VERSION`].
    pub extra_protocol_version: &'static str,
    /// Integer extra carrying the loopback proxy port.
    pub extra_proxy_port: &'static str,
    /// Base64 string extra carrying the session ID.
    pub extra_session_id: &'static str,
    /// Base64 string extra carrying the session secret.
    pub extra_session_secret: &'static str,
    /// Prefix of the receiver's result data, followed by the outcome name.
    pub result_prefix: &'static str,
    /// How the helper treats a receiver that handled the broadcast without
    /// acknowledging it.
    pub legacy_acknowledgement: LegacyAcknowledgement,
}

/// How a helper treats a receiver that handled its broadcast without
/// acknowledging it.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum LegacyAcknowledgement {
    /// Older releases handled the broadcast that way: connect anyway.
    Tolerate,
    /// No release handles the broadcast that way: there is no receiver.
    Reject,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum BroadcastOutcome {
    Accepted,
    Deferred,
    Busy,
    Disabled,
    Invalid,
    StartFailed,
    InternalError,
    LegacyNoAcknowledgement,
}

#[derive(Clone, Debug)]
pub struct BroadcastCommandSpec {
    program: OsString,
    fixed_args: Vec<OsString>,
}

impl BroadcastCommandSpec {
    /// Runs `program` with `fixed_args` before the intent arguments.
    pub fn new(program: OsString, fixed_args: Vec<OsString>) -> Self {
        Self {
            program,
            fixed_args,
        }
    }
}

impl Default for BroadcastCommandSpec {
    fn default() -> Self {
        Self {
            program: OsString::from("am"),
            fixed_args: vec![OsString::from("broadcast")],
        }
    }
}

impl BroadcastCommandSpec {
    fn display_program(&self) -> String {
        self.program.to_string_lossy().into_owned()
    }
}

pub struct AndroidBroadcastLauncher {
    spec: BroadcastCommandSpec,
}

impl AndroidBroadcastLauncher {
    pub fn new(spec: BroadcastCommandSpec) -> Self {
        Self { spec }
    }

    pub async fn launch(
        &self,
        contract: &BridgeContract,
        component: &str,
        proxy_port: u16,
        session_id: &[u8; SESSION_ID_LEN],
        session_secret: &[u8; SESSION_SECRET_LEN],
        deadline: Instant,
    ) -> Result<BroadcastOutcome> {
        let session_id_b64 = base64::engine::general_purpose::STANDARD.encode(session_id);
        let session_secret_b64 = base64::engine::general_purpose::STANDARD.encode(session_secret);
        let mut command = Command::new(&self.spec.program);
        command.kill_on_drop(true);
        command.args(&self.spec.fixed_args);
        command
            .arg("-n")
            .arg(component)
            .arg("-a")
            .arg(contract.action)
            .arg("--ei")
            .arg(contract.extra_protocol_version)
            .arg(PROTOCOL_VERSION.to_string())
            .arg("--ei")
            .arg(contract.extra_proxy_port)
            .arg(proxy_port.to_string())
            .arg("--es")
            .arg(contract.extra_session_id)
            .arg(session_id_b64)
            .arg("--es")
            .arg(contract.extra_session_secret)
            .arg(session_secret_b64)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());

        let output = timeout_at(deadline, command.output())
            .await
            .map_err(|_| BroadcastLaunchError::TimedOut {
                program: self.spec.display_program(),
            })?
            .map_err(BroadcastLaunchError::Spawn)?;

        if !output.status.success() {
            return Err(BroadcastLaunchError::Exit {
                program: self.spec.display_program(),
                status: output.status,
                stdout: String::from_utf8_lossy(&output.stdout).trim().to_string(),
                stderr: String::from_utf8_lossy(&output.stderr).trim().to_string(),
            }
            .into());
        }

        let stdout = String::from_utf8_lossy(&output.stdout);
        parse_broadcast_outcome(contract, &stdout).map_err(Into::into)
    }
}

fn parse_broadcast_outcome(
    contract: &BridgeContract,
    stdout: &str,
) -> std::result::Result<BroadcastOutcome, BroadcastLaunchError> {
    let completion_line = stdout
        .lines()
        .rev()
        .find(|line| line.contains("Broadcast completed:"));
    let Some(completion_line) = completion_line else {
        return Ok(BroadcastOutcome::LegacyNoAcknowledgement);
    };
    let Some(prefix_index) = completion_line.find(contract.result_prefix) else {
        return Ok(BroadcastOutcome::LegacyNoAcknowledgement);
    };

    let value = &completion_line[prefix_index + contract.result_prefix.len()..];
    let value = value
        .split(|character: char| character == '"' || character == ',' || character.is_whitespace())
        .next()
        .unwrap_or_default();
    let outcome = match value {
        "accepted" => BroadcastOutcome::Accepted,
        "deferred" => BroadcastOutcome::Deferred,
        "busy" => BroadcastOutcome::Busy,
        "disabled" => BroadcastOutcome::Disabled,
        "invalid" => BroadcastOutcome::Invalid,
        "start_failed" => BroadcastOutcome::StartFailed,
        "internal_error" => BroadcastOutcome::InternalError,
        _ => {
            return Err(BroadcastLaunchError::UnrecognizedOutcome {
                value: value.to_string(),
            });
        }
    };
    Ok(outcome)
}

#[derive(Debug)]
pub enum BroadcastLaunchError {
    TimedOut {
        program: String,
    },
    Spawn(io::Error),
    Exit {
        program: String,
        status: ExitStatus,
        stdout: String,
        stderr: String,
    },
    UnrecognizedOutcome {
        value: String,
    },
}

impl std::fmt::Display for BroadcastLaunchError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::TimedOut { program } => {
                write!(f, "Android broadcast timed out while running `{program}`")
            }
            Self::Spawn(err) => write!(f, "Failed to execute Android broadcast command: {err}"),
            Self::Exit {
                program,
                status,
                stdout,
                stderr,
            } => write!(
                f,
                "Android broadcast failed via `{program}` (status {status}): stdout=`{stdout}` stderr=`{stderr}`",
            ),
            Self::UnrecognizedOutcome { value } => {
                write!(f, "Android broadcast returned an unknown Keyguard outcome `{value}`")
            }
        }
    }
}

impl std::error::Error for BroadcastLaunchError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Spawn(err) => Some(err),
            _ => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::time::Duration;

    const BROADCAST_RESULT_PREFIX: &str = "keyguard.test-agent.broadcast.v1:";
    const CONTRACT: BridgeContract = BridgeContract {
        action: "com.artemchep.keyguard.action.RUN_TEST_AGENT",
        extra_protocol_version: "com.artemchep.keyguard.extra.TEST_AGENT_PROTOCOL_VERSION",
        extra_proxy_port: "com.artemchep.keyguard.extra.TEST_AGENT_PROXY_PORT",
        extra_session_id: "com.artemchep.keyguard.extra.TEST_AGENT_SESSION_ID",
        extra_session_secret: "com.artemchep.keyguard.extra.TEST_AGENT_SESSION_SECRET",
        result_prefix: BROADCAST_RESULT_PREFIX,
        legacy_acknowledgement: LegacyAcknowledgement::Tolerate,
    };

    #[test]
    fn parses_versioned_broadcast_outcomes() {
        let cases = [
            ("accepted", BroadcastOutcome::Accepted),
            ("deferred", BroadcastOutcome::Deferred),
            ("busy", BroadcastOutcome::Busy),
            ("disabled", BroadcastOutcome::Disabled),
            ("invalid", BroadcastOutcome::Invalid),
            ("start_failed", BroadcastOutcome::StartFailed),
            ("internal_error", BroadcastOutcome::InternalError),
        ];

        for (value, expected) in cases {
            let stdout = format!(
                "Broadcasting: Intent {{ ... }}\nBroadcast completed: result=-1, data=\"{BROADCAST_RESULT_PREFIX}{value}\"\n"
            );
            assert_eq!(
                parse_broadcast_outcome(&CONTRACT, &stdout).unwrap(),
                expected
            );
        }
    }

    #[test]
    fn missing_keyguard_outcome_preserves_legacy_behavior() {
        assert_eq!(
            parse_broadcast_outcome(&CONTRACT, "Broadcast completed: result=0\n").unwrap(),
            BroadcastOutcome::LegacyNoAcknowledgement,
        );
        assert_eq!(
            parse_broadcast_outcome(&CONTRACT, "").unwrap(),
            BroadcastOutcome::LegacyNoAcknowledgement,
        );
    }

    #[test]
    fn unknown_versioned_outcome_is_rejected() {
        let err = parse_broadcast_outcome(
            &CONTRACT,
            &format!("Broadcast completed: result=0, data=\"{BROADCAST_RESULT_PREFIX}future\""),
        )
        .unwrap_err();

        assert!(matches!(
            err,
            BroadcastLaunchError::UnrecognizedOutcome { value } if value == "future"
        ));
    }

    #[tokio::test]
    async fn broadcast_launcher_reports_non_zero_exit() {
        let launcher = AndroidBroadcastLauncher::new(BroadcastCommandSpec::new(
            OsString::from("sh"),
            vec![
                OsString::from("-c"),
                OsString::from("printf 'out'; printf 'err' >&2; exit 7"),
                OsString::from("sh"),
            ],
        ));

        let err = launcher
            .launch(
                &CONTRACT,
                "component",
                1234,
                &[0x11; SESSION_ID_LEN],
                &[0x22; SESSION_SECRET_LEN],
                Instant::now() + Duration::from_secs(1),
            )
            .await
            .unwrap_err();

        let err = err.downcast::<BroadcastLaunchError>().unwrap();
        match err {
            BroadcastLaunchError::Exit {
                status,
                stdout,
                stderr,
                ..
            } => {
                assert_eq!(status.code(), Some(7));
                assert_eq!(stdout, "out");
                assert_eq!(stderr, "err");
            }
            other => panic!("unexpected error: {other:?}"),
        }
    }

    #[tokio::test]
    async fn broadcast_launcher_reports_timeout() {
        let launcher = AndroidBroadcastLauncher::new(BroadcastCommandSpec::new(
            OsString::from("sh"),
            vec![
                OsString::from("-c"),
                OsString::from("sleep 5"),
                OsString::from("sh"),
            ],
        ));

        let err = launcher
            .launch(
                &CONTRACT,
                "component",
                1234,
                &[0x11; SESSION_ID_LEN],
                &[0x22; SESSION_SECRET_LEN],
                Instant::now() + Duration::from_millis(50),
            )
            .await
            .unwrap_err();

        let err = err.downcast::<BroadcastLaunchError>().unwrap();
        assert!(matches!(err, BroadcastLaunchError::TimedOut { .. }));
    }

    #[tokio::test]
    async fn broadcast_launcher_does_not_emit_caller_extras() {
        let launcher = AndroidBroadcastLauncher::new(BroadcastCommandSpec::new(
            OsString::from("sh"),
            vec![
                OsString::from("-c"),
                OsString::from("printf '%s\n' \"$@\"; exit 7"),
                OsString::from("sh"),
            ],
        ));

        let err = tokio::time::timeout(
            Duration::from_secs(1),
            launcher.launch(
                &CONTRACT,
                "component",
                1234,
                &[0x11; SESSION_ID_LEN],
                &[0x22; SESSION_SECRET_LEN],
                Instant::now() + Duration::from_secs(1),
            ),
        )
        .await
        .unwrap()
        .unwrap_err();

        let err = err.downcast::<BroadcastLaunchError>().unwrap();
        let text = match err {
            BroadcastLaunchError::Exit { stdout, .. } => stdout,
            other => panic!("unexpected error: {other:?}"),
        };

        assert!(!text.contains("_CALLER_"));
        assert!(text.contains(CONTRACT.action));
        assert!(text.contains(CONTRACT.extra_protocol_version));
        assert!(text.contains(CONTRACT.extra_session_id));
        assert!(text.contains(CONTRACT.extra_session_secret));
    }
}
