//! Messages for the error codes that every command can get.

/// The message for `code`, `None` for a code that only a command knows.
pub(crate) fn message(code: &str) -> Option<&'static str> {
    Some(match code {
        "integration_disabled" => {
            "Command-line access is turned off. Turn it on in Keyguard's Developer settings."
        }
        "user_denied" => "The request was denied in Keyguard.",
        "approval_timeout" | "request_timeout" => "The request was not approved in time.",
        "approval_revoked" => "A change in Keyguard revoked the approval. Try again.",
        "vault_locked" => "Unlock Keyguard and try again.",
        "account_not_found" => "Account not found.",
        "ambiguous_account" => "Several accounts have this name. Use the account ID.",
        "reauthentication_required" => "Confirm your identity in Keyguard and try again.",
        "unverified_cli" => "Keyguard only accepts this request from its own keyguard executable.",
        "unavailable" => "Keyguard is not running, or its CLI agent is stopped.",
        "too_many_connections" => {
            "Keyguard has too many open connections. Close unused MCP clients and try again."
        }
        "unsupported_command" => {
            "This version of Keyguard does not support this command. Update Keyguard and try again."
        }
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use keyguard_cli_protocol::errors::UNSUPPORTED_COMMAND;

    #[test]
    fn a_newer_cli_asks_to_update_keyguard() {
        assert!(
            message(UNSUPPORTED_COMMAND)
                .unwrap()
                .contains("Update Keyguard")
        );
        assert_eq!(message("not_found"), None);
    }
}
