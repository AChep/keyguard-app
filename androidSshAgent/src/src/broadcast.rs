use anyhow::Result;
use base64::Engine;
use std::ffi::OsString;
use std::io;
use std::process::{ExitStatus, Stdio};
use tokio::process::Command;
use tokio::time::{timeout_at, Instant};

use crate::secure_transport::{PROTOCOL_VERSION, SESSION_ID_LEN, SESSION_SECRET_LEN};

const ACTION_RUN_ANDROID_SSH_AGENT: &str = "com.artemchep.keyguard.action.RUN_ANDROID_SSH_AGENT";

const EXTRA_PROTOCOL_VERSION: &str = "com.artemchep.keyguard.extra.SSH_AGENT_PROTOCOL_VERSION";
const EXTRA_PROXY_PORT: &str = "com.artemchep.keyguard.extra.SSH_AGENT_PROXY_PORT";
const EXTRA_SESSION_ID: &str = "com.artemchep.keyguard.extra.SSH_AGENT_SESSION_ID";
const EXTRA_SESSION_SECRET: &str = "com.artemchep.keyguard.extra.SSH_AGENT_SESSION_SECRET";

pub(crate) const DEFAULT_COMPONENT: &str =
    "com.artemchep.keyguard/com.artemchep.keyguard.android.sshagent.SshAgentReceiver";

#[derive(Clone, Debug)]
pub(crate) struct BroadcastCommandSpec {
    program: OsString,
    fixed_args: Vec<OsString>,
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

pub(crate) struct AndroidBroadcastLauncher {
    spec: BroadcastCommandSpec,
}

impl AndroidBroadcastLauncher {
    pub(crate) fn new(spec: BroadcastCommandSpec) -> Self {
        Self { spec }
    }

    pub(crate) async fn launch(
        &self,
        component: &str,
        proxy_port: u16,
        session_id: &[u8; SESSION_ID_LEN],
        session_secret: &[u8; SESSION_SECRET_LEN],
        deadline: Instant,
    ) -> Result<()> {
        let session_id_b64 = base64::engine::general_purpose::STANDARD.encode(session_id);
        let session_secret_b64 = base64::engine::general_purpose::STANDARD.encode(session_secret);
        let mut command = Command::new(&self.spec.program);
        command.kill_on_drop(true);
        command.args(&self.spec.fixed_args);
        command
            .arg("-n")
            .arg(component)
            .arg("-a")
            .arg(ACTION_RUN_ANDROID_SSH_AGENT)
            .arg("--ei")
            .arg(EXTRA_PROTOCOL_VERSION)
            .arg(PROTOCOL_VERSION.to_string())
            .arg("--ei")
            .arg(EXTRA_PROXY_PORT)
            .arg(proxy_port.to_string())
            .arg("--es")
            .arg(EXTRA_SESSION_ID)
            .arg(session_id_b64)
            .arg("--es")
            .arg(EXTRA_SESSION_SECRET)
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

        Ok(())
    }
}

#[derive(Debug)]
pub(crate) enum BroadcastLaunchError {
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

    #[tokio::test]
    async fn broadcast_launcher_reports_non_zero_exit() {
        let launcher = AndroidBroadcastLauncher::new(BroadcastCommandSpec {
            program: OsString::from("sh"),
            fixed_args: vec![
                OsString::from("-c"),
                OsString::from("printf 'out'; printf 'err' >&2; exit 7"),
                OsString::from("sh"),
            ],
        });

        let err = launcher
            .launch(
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
        let launcher = AndroidBroadcastLauncher::new(BroadcastCommandSpec {
            program: OsString::from("sh"),
            fixed_args: vec![
                OsString::from("-c"),
                OsString::from("sleep 5"),
                OsString::from("sh"),
            ],
        });

        let err = launcher
            .launch(
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
        let launcher = AndroidBroadcastLauncher::new(BroadcastCommandSpec {
            program: OsString::from("sh"),
            fixed_args: vec![
                OsString::from("-c"),
                OsString::from("printf '%s\n' \"$@\"; exit 7"),
                OsString::from("sh"),
            ],
        });

        let err = tokio::time::timeout(
            Duration::from_secs(1),
            launcher.launch(
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

        assert!(!text.contains("SSH_AGENT_CALLER_"));
        assert!(text.contains("com.artemchep.keyguard.extra.SSH_AGENT_PROTOCOL_VERSION"));
        assert!(text.contains("com.artemchep.keyguard.extra.SSH_AGENT_SESSION_ID"));
        assert!(text.contains("com.artemchep.keyguard.extra.SSH_AGENT_SESSION_SECRET"));
    }
}
