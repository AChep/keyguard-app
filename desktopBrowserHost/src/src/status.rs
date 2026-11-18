//! The status of the host's connection to the app, as the extension sees it.
use keyguard_cli_protocol::errors::{
    INTEGRATION_DISABLED, TOO_MANY_SESSIONS, UNSUPPORTED_COMMAND, UNVERIFIED_HOST, UPDATE_REQUIRED,
    error_code,
};
use serde::{Serialize, Serializer};
use serde_json::Value;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Status {
    /// Connected to the app: a new session starts.
    Ready,
    /// The app is not running, or it closed the connection.
    AppUnavailable,
    /// The app does not accept this host: one of them is out of date.
    UpdateRequired,
    /// The browser integration is off in the app.
    IntegrationDisabled,
    /// The app refused the host: it is not the binary bundled with the app.
    UnverifiedHost,
    /// The app already serves as many browser sessions as it allows.
    TooManySessions,
}

impl Status {
    /// The name of the status, on the wire and in the log.
    pub(crate) fn as_str(self) -> &'static str {
        match self {
            Self::Ready => "ready",
            Self::AppUnavailable => "app_unavailable",
            Self::UpdateRequired => "update_required",
            Self::IntegrationDisabled => "integration_disabled",
            Self::UnverifiedHost => "unverified_host",
            Self::TooManySessions => "too_many_sessions",
        }
    }
}

impl Serialize for Status {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(self.as_str())
    }
}

/// Reads the agent's answer to the `browser` frame: `Ok` once the stream is
/// open, or the status that the refusal stands for.
pub(crate) fn opened(answer: &[u8]) -> Result<(), Status> {
    let Ok(answer) = serde_json::from_slice::<Value>(answer) else {
        return Err(Status::AppUnavailable);
    };
    match answer.get("ok").and_then(Value::as_bool) {
        Some(true) => return Ok(()),
        Some(false) => {}
        // Not an answer of the agent: its error code means nothing.
        None => return Err(Status::AppUnavailable),
    }
    Err(match error_code(&answer) {
        // An app that is older than browser connections, or one that is
        // newer than this host.
        UNSUPPORTED_COMMAND | UPDATE_REQUIRED => Status::UpdateRequired,
        INTEGRATION_DISABLED => Status::IntegrationDisabled,
        UNVERIFIED_HOST => Status::UnverifiedHost,
        TOO_MANY_SESSIONS => Status::TooManySessions,
        // `unavailable`, and anything else.
        _ => Status::AppUnavailable,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn answer(value: Value) -> Result<(), Status> {
        opened(&serde_json::to_vec(&value).unwrap())
    }

    #[test]
    fn only_ok_opens_the_stream() {
        assert_eq!(answer(json!({"ok":true})), Ok(()));
        for value in [
            json!({"ok":false}),
            json!({"ok":"true"}),
            json!({}),
            // A known code, but not in a refusal.
            json!({"error":"update_required"}),
            json!({"ok":"false","error":"update_required"}),
            json!([]),
        ] {
            assert_eq!(
                answer(value.clone()),
                Err(Status::AppUnavailable),
                "{value}"
            );
        }
        assert_eq!(opened(b"not json"), Err(Status::AppUnavailable));
    }

    #[test]
    fn refusals_map_to_statuses() {
        for (code, status) in [
            ("unsupported_command", Status::UpdateRequired),
            ("update_required", Status::UpdateRequired),
            ("integration_disabled", Status::IntegrationDisabled),
            ("unverified_host", Status::UnverifiedHost),
            ("too_many_sessions", Status::TooManySessions),
            ("unavailable", Status::AppUnavailable),
            ("invalid_response", Status::AppUnavailable),
            ("request_failed", Status::AppUnavailable),
            ("Secret /path", Status::AppUnavailable),
        ] {
            assert_eq!(
                answer(json!({"ok":false,"error":code})),
                Err(status),
                "{code}"
            );
        }
    }
}
