//! Stable error codes. The app sends codes, never arbitrary parser or error text.
use serde_json::{Value, json};

/// The agent or the app does not know the command: the CLI is newer.
pub const UNSUPPORTED_COMMAND: &str = "unsupported_command";
/// The command answers with values, but the caller is not the bundled CLI.
pub const UNVERIFIED_CLI: &str = "unverified_cli";
/// The app answered with a result of the wrong shape.
pub const INVALID_RESPONSE: &str = "invalid_response";
/// The app could not be reached, or closed the request before it answered.
pub const UNAVAILABLE: &str = "unavailable";
/// The browser extension integration is turned off in the app.
pub const INTEGRATION_DISABLED: &str = "integration_disabled";
/// A browser frame, but the caller is not the bundled browser connector.
pub const UNVERIFIED_HOST: &str = "unverified_host";
/// The app already serves as many browser extension streams as it allows.
pub const TOO_MANY_SESSIONS: &str = "too_many_sessions";
/// The app does not accept the browser connector's `hostRevision`.
pub const UPDATE_REQUIRED: &str = "update_required";

pub fn failure(code: &str) -> Value {
    json!({"ok":false,"error":code})
}

pub fn error_code(response: &Value) -> &str {
    response
        .get("error")
        .and_then(Value::as_str)
        .map_or(REQUEST_FAILED, stable_code)
}

/// The `code` if it is a stable error code, `request_failed` otherwise.
pub fn stable_code(code: &str) -> &str {
    let stable = !code.is_empty()
        && code.len() < 80
        && code.chars().all(|c| c.is_ascii_lowercase() || c == '_');
    if stable { code } else { REQUEST_FAILED }
}

const REQUEST_FAILED: &str = "request_failed";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_stable_codes_are_passed_on() {
        assert_eq!(
            error_code(&json!({"ok":false,"error":"user_denied"})),
            "user_denied"
        );
        assert_eq!(
            error_code(&json!({"ok":false,"error":"Secret /path"})),
            "request_failed"
        );
        assert_eq!(error_code(&json!({"ok":false})), "request_failed");
        assert_eq!(
            error_code(&failure(UNSUPPORTED_COMMAND)),
            UNSUPPORTED_COMMAND
        );
    }
}
