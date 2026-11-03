//! The encodings both sides of the SSH agent's private channel agree on.
//! `proto/fixtures/` holds them, and the Kotlin `SshAgentProtocolTest`
//! checks the same files: each fixture decodes to its message, and the
//! message encodes back to it.
use super::*;
use keyguard_agent_identity::IPC_PROTOCOL_REVISION;
use keyguard_agent_proto::fixtures::{check, verified_caller};

const REQUESTS: &str = include_str!("../../proto/fixtures/ssh_agent_requests.pbhex");
const RESPONSES: &str = include_str!("../../proto/fixtures/ssh_agent_responses.pbhex");

const PUBLIC_KEY: &str = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIA test";

fn request(
    name: &'static str,
    id: u64,
    request: ipc_request::Request,
) -> (&'static str, IpcRequest) {
    let request = IpcRequest {
        id,
        request: Some(request),
    };
    (name, request)
}

fn response(
    name: &'static str,
    id: u64,
    response: ipc_response::Response,
) -> (&'static str, IpcResponse) {
    let response = IpcResponse {
        id,
        response: Some(response),
    };
    (name, response)
}

fn requests() -> Vec<(&'static str, IpcRequest)> {
    vec![
        request(
            "authenticate",
            1,
            ipc_request::Request::Authenticate(AuthenticateRequest {
                token: vec![0x11; 32],
                protocol_revision: IPC_PROTOCOL_REVISION,
            }),
        ),
        // An agent from before the revision: the app reads zero and rejects it.
        request(
            "authenticate_without_revision",
            1,
            ipc_request::Request::Authenticate(AuthenticateRequest {
                token: vec![0x11; 32],
                protocol_revision: 0,
            }),
        ),
        request(
            "list_keys",
            2,
            ipc_request::Request::ListKeys(ListKeysRequest {
                caller: Some(verified_caller()),
            }),
        ),
        request(
            "list_keys_without_caller",
            3,
            ipc_request::Request::ListKeys(ListKeysRequest { caller: None }),
        ),
        request(
            "sign_data",
            4,
            ipc_request::Request::SignData(SignDataRequest {
                public_key: PUBLIC_KEY.into(),
                data: vec![1, 2, 3, 4, 5],
                // SSH_AGENT_RSA_SHA2_512
                flags: 4,
                caller: Some(verified_caller()),
            }),
        ),
        // Kotlin holds the uint32 flags in an Int.
        request(
            "sign_data_all_flags",
            5,
            ipc_request::Request::SignData(SignDataRequest {
                public_key: PUBLIC_KEY.into(),
                data: vec![0xFF],
                flags: u32::MAX,
                caller: None,
            }),
        ),
        // Kotlin holds the uint64 id in a Long. A request without a variant
        // is an error that the app answers.
        (
            "no_request",
            IpcRequest {
                id: u64::MAX,
                request: None,
            },
        ),
    ]
}

fn responses() -> Vec<(&'static str, IpcResponse)> {
    vec![
        response(
            "authenticate",
            1,
            ipc_response::Response::Authenticate(AuthenticateResponse {
                success: true,
                protocol_revision: IPC_PROTOCOL_REVISION,
            }),
        ),
        response(
            "authenticate_rejected",
            1,
            ipc_response::Response::Authenticate(AuthenticateResponse {
                success: false,
                protocol_revision: IPC_PROTOCOL_REVISION,
            }),
        ),
        response(
            "list_keys",
            2,
            ipc_response::Response::ListKeys(ListKeysResponse {
                keys: vec![SshKey {
                    name: "Work".into(),
                    public_key: PUBLIC_KEY.into(),
                    key_type: "ssh-ed25519".into(),
                    fingerprint: "SHA256:abcdef".into(),
                }],
            }),
        ),
        response(
            "list_keys_empty",
            3,
            ipc_response::Response::ListKeys(ListKeysResponse::default()),
        ),
        response(
            "sign_data",
            4,
            ipc_response::Response::SignData(SignDataResponse {
                signature: vec![0x55; 64],
                algorithm: "ssh-ed25519".into(),
            }),
        ),
        response(
            "error_vault_locked",
            5,
            ipc_response::Response::Error(ErrorResponse {
                message: "Vault is locked".into(),
                code: ErrorCode::VaultLocked.into(),
            }),
        ),
        response(
            "error_not_authenticated",
            6,
            ipc_response::Response::Error(ErrorResponse {
                message: "Not authenticated".into(),
                code: ErrorCode::NotAuthenticated.into(),
            }),
        ),
        // Every field at its default: the error is an empty message.
        response(
            "error_unspecified",
            7,
            ipc_response::Response::Error(ErrorResponse::default()),
        ),
    ]
}

#[test]
fn request_fixtures_match_the_schema() {
    check(REQUESTS, &requests());
}

#[test]
fn response_fixtures_match_the_schema() {
    check(RESPONSES, &responses());
}
