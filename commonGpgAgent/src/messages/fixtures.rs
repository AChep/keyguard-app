//! The encodings both sides of the GPG agent's private channel agree on.
//! `proto/fixtures/` holds them, and the Kotlin `GpgAgentProtocolTest`
//! checks the same files: each fixture decodes to its message, and the
//! message encodes back to it.
use super::*;
use keyguard_agent_identity::IPC_PROTOCOL_REVISION;
use keyguard_agent_proto::fixtures::{check, verified_caller};

const REQUESTS: &str = include_str!("../../proto/fixtures/gpg_agent_requests.pbhex");
const RESPONSES: &str = include_str!("../../proto/fixtures/gpg_agent_responses.pbhex");

const KEYGRIP: &str = "0123456789ABCDEF0123456789ABCDEF01234567";

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

fn pkdecrypt(unwrap_ecdh: bool) -> ipc_request::Request {
    ipc_request::Request::Pkdecrypt(PkdecryptRequest {
        keygrip: KEYGRIP.into(),
        ciphertext: b"(7:enc-val(4:ecdh(1:s1:x)(1:e1:y)))".to_vec(),
        caller: Some(verified_caller()),
        unwrap_ecdh,
    })
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
        request(
            "list_keys",
            2,
            ipc_request::Request::ListKeys(ListKeysRequest {
                caller: Some(verified_caller()),
            }),
        ),
        request(
            "sign_hash",
            3,
            ipc_request::Request::SignHash(SignHashRequest {
                keygrip: KEYGRIP.into(),
                hash_algorithm: "sha256".into(),
                hash: vec![0x66; 32],
                caller: Some(verified_caller()),
            }),
        ),
        // `PKDECRYPT --kem=PGP`: the agent unwraps the session key.
        request("pkdecrypt", 4, pkdecrypt(true)),
        // Plain `PKDECRYPT`: gpg unwraps the shared secret itself.
        request("pkdecrypt_legacy", 5, pkdecrypt(false)),
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
                keys: vec![
                    GpgKey {
                        name: "Work <work@example.com>".into(),
                        keygrip: KEYGRIP.into(),
                        fingerprint: "AAAABBBBCCCCDDDDEEEEFFFF0000111122223333".into(),
                        algorithm: "ed25519".into(),
                        can_sign: true,
                        can_decrypt: false,
                    },
                    GpgKey {
                        name: "Work <work@example.com>".into(),
                        keygrip: "76543210FEDCBA9876543210FEDCBA9876543210".into(),
                        fingerprint: "3333222211110000FFFFEEEEDDDDCCCCBBBBAAAA".into(),
                        algorithm: "cv25519".into(),
                        can_sign: false,
                        can_decrypt: true,
                    },
                ],
            }),
        ),
        response(
            "sign_hash",
            3,
            ipc_response::Response::SignHash(SignHashResponse {
                sexp: "(sig-val(eddsa(r #01#)(s #02#)))".into(),
            }),
        ),
        response(
            "pkdecrypt",
            4,
            ipc_response::Response::Pkdecrypt(PkdecryptResponse {
                value_sexp: "(value #0303#)".into(),
            }),
        ),
        response(
            "error_unsupported",
            5,
            ipc_response::Response::Error(ErrorResponse {
                message: "Unsupported".into(),
                code: ErrorCode::Unsupported.into(),
            }),
        ),
        response(
            "error_user_denied",
            6,
            ipc_response::Response::Error(ErrorResponse {
                message: "User denied".into(),
                code: ErrorCode::UserDenied.into(),
            }),
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
