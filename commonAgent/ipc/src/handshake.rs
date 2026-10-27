//! The first request of every private connection.
use crate::launch::AuthToken;
use anyhow::{Result, bail};
use keyguard_agent_identity::IPC_PROTOCOL_REVISION;
use keyguard_agent_proto::{AuthenticateRequest, AuthenticateResponse};
use zeroize::Zeroize;

/// The request that authenticates the agent with its launch `token`, at the
/// agent's protocol revision. Wipe its token once it is sent.
pub fn authenticate_request(token: &AuthToken) -> AuthenticateRequest {
    AuthenticateRequest {
        token: token.as_bytes().to_vec(),
        protocol_revision: IPC_PROTOCOL_REVISION,
    }
}

/// Zeroes the token of `request`, once it is sent.
pub fn wipe(request: &mut AuthenticateRequest) {
    request.token.zeroize();
}

/// Checks the app's answer to [`authenticate_request`]. A revision mismatch
/// is reported before a rejected token, so that a stale agent says so.
///
/// # Errors
///
/// Fails when the revisions differ or the app rejected the token.
pub fn check_authenticate_response(response: &AuthenticateResponse) -> Result<()> {
    if response.protocol_revision != IPC_PROTOCOL_REVISION {
        bail!(
            "IPC protocol revision mismatch: expected {}, received {}",
            IPC_PROTOCOL_REVISION,
            response.protocol_revision,
        );
    }
    if !response.success {
        bail!("Authentication failed: server rejected the token");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_request_carries_the_token_and_the_revision() {
        let request = authenticate_request(&AuthToken::from_bytes([7; 32]));
        assert_eq!(request.token, vec![7; 32]);
        assert_eq!(request.protocol_revision, IPC_PROTOCOL_REVISION);
    }

    #[test]
    fn an_accepted_token_at_the_same_revision_authenticates() {
        check_authenticate_response(&AuthenticateResponse {
            success: true,
            protocol_revision: IPC_PROTOCOL_REVISION,
        })
        .unwrap();
    }

    #[test]
    fn a_revision_mismatch_is_reported_first() {
        for success in [true, false] {
            let error = check_authenticate_response(&AuthenticateResponse {
                success,
                protocol_revision: IPC_PROTOCOL_REVISION + 1,
            })
            .unwrap_err()
            .to_string();
            assert!(error.contains("protocol revision mismatch"), "{error}");
        }
    }

    #[test]
    fn a_rejected_token_fails() {
        let error = check_authenticate_response(&AuthenticateResponse {
            success: false,
            protocol_revision: IPC_PROTOCOL_REVISION,
        })
        .unwrap_err()
        .to_string();
        assert!(error.contains("rejected the token"), "{error}");
    }
}
