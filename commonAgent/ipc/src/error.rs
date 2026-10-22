//! The errors that the app answers SSH and GPG agent requests with.
use keyguard_agent_proto::{ErrorCode, ErrorResponse};
use std::fmt;

/// Typed error reported by the Keyguard app.
#[derive(Clone, Debug)]
pub struct IpcError {
    code: ErrorCode,
    message: String,
}

impl IpcError {
    /// Creates an error with the app's protocol code and free-form message.
    pub fn new(code: ErrorCode, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }

    /// The protocol error code.
    pub fn code(&self) -> ErrorCode {
        self.code
    }

    /// The app's free-form message. It may contain vault data, so never log it.
    pub fn message(&self) -> &str {
        &self.message
    }
}

impl From<&ErrorResponse> for IpcError {
    /// An unknown code, as from a newer app, is [`ErrorCode::Unspecified`].
    fn from(error: &ErrorResponse) -> Self {
        let code = ErrorCode::try_from(error.code).unwrap_or(ErrorCode::Unspecified);
        Self::new(code, error.message.clone())
    }
}

impl fmt::Display for IpcError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "IPC error ({:?}): {}", self.code, self.message)
    }
}

impl std::error::Error for IpcError {}

#[cfg(test)]
mod tests {
    use super::*;
    use anyhow::Error;

    #[test]
    fn ipc_error_preserves_code_for_downcast() {
        let error = Error::new(IpcError::from(&ErrorResponse {
            message: "denied".to_string(),
            code: ErrorCode::UserDenied as i32,
        }));

        let ipc_error = error.downcast_ref::<IpcError>().expect("typed IPC error");
        assert_eq!(ipc_error.code(), ErrorCode::UserDenied);
        assert_eq!(ipc_error.message(), "denied");
    }

    #[test]
    fn ipc_error_defaults_unknown_code_to_unspecified() {
        let error = IpcError::from(&ErrorResponse {
            message: "unknown".to_string(),
            code: i32::MAX,
        });

        assert_eq!(error.code(), ErrorCode::Unspecified);
    }

    #[test]
    fn ipc_error_survives_anyhow_context() {
        let error = Error::new(IpcError::from(&ErrorResponse {
            message: "auth failed".to_string(),
            code: ErrorCode::AuthFailed as i32,
        }))
        .context("authentication failed");

        let ipc_error = error
            .downcast_ref::<IpcError>()
            .expect("context should preserve typed IPC error");
        assert_eq!(ipc_error.code(), ErrorCode::AuthFailed);
    }
}
