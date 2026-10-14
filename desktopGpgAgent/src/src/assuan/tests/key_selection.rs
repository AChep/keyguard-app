//! Key selection and lifetime through the command dispatcher and protobuf IPC.

use crate::assuan::*;
use crate::ipc::client::{read_frame, write_frame};
use crate::ipc::messages::{
    ipc_request::Request, ipc_response::Response, ErrorCode, ErrorResponse, GpgKey, IpcRequest,
    IpcResponse, ListKeysResponse, PkdecryptResponse, SignHashResponse,
};
use prost::Message;
use std::collections::VecDeque;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::task::JoinHandle;

const KEY_A: &str = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
const KEY_B: &str = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
const SETHASH: &str = "SETHASH 8 0000000000000000000000000000000000000000000000000000000000000000";
const SETHASH_PSS: &str =
    "SETHASH --pss 8 0000000000000000000000000000000000000000000000000000000000000000";
const DECRYPT: &str = "PKDECRYPT\nD ciphertext\nEND\n";

struct CommandSession {
    state: SessionState,
    ipc_client: IpcClient,
    backend: JoinHandle<Vec<Request>>,
}

impl CommandSession {
    fn new(mut respond: impl FnMut(&Request) -> Response + Send + 'static) -> Self {
        let (ipc_stream, mut peer) = tokio::io::duplex(4096);
        let backend = tokio::spawn(async move {
            let mut requests = Vec::new();
            while let Ok(bytes) = read_frame(&mut peer).await {
                let request = IpcRequest::decode(bytes.as_slice()).unwrap();
                let payload = request.request.unwrap();
                let response = IpcResponse {
                    id: request.id,
                    response: Some(respond(&payload)),
                }
                .encode_to_vec();
                requests.push(payload);
                write_frame(&mut peer, &response).await.unwrap();
            }
            requests
        });
        Self {
            state: SessionState::default(),
            ipc_client: IpcClient::from_test_stream(ipc_stream),
            backend,
        }
    }

    async fn command(&mut self, input: &str) -> String {
        let (command, inquiry) = input.split_once('\n').unwrap_or((input, ""));
        let mut reader = DeadlineReader::new(
            BufReader::new(inquiry.as_bytes()),
            AssuanTimeouts::default(),
        );
        let mut output = Vec::new();
        let should_close = tokio::time::timeout(
            Duration::from_secs(5),
            handle_command(
                parse_command(command),
                &mut self.state,
                &self.ipc_client,
                &CallerGuard::default(),
                None,
                "test",
                &mut reader,
                &mut output,
            ),
        )
        .await
        .expect("command must not hang awaiting IPC")
        .unwrap();
        assert!(!should_close);
        String::from_utf8(output).unwrap()
    }

    async fn command_ok(&mut self, input: &str) {
        assert_eq!(self.command(input).await, "OK\n", "{input}");
    }

    /// Selects `KEY_A` as the primary key and `KEY_B` as the secondary key.
    async fn select(&mut self, alias: &str, another_alias: &str) {
        self.command_ok(&format!("{alias} {KEY_A}")).await;
        self.command_ok(&format!("{another_alias} --another {KEY_B}"))
            .await;
    }

    async fn requests(self) -> Vec<Request> {
        drop(self.ipc_client);
        self.backend.await.unwrap()
    }

    async fn assert_no_selection(&mut self) {
        assert_eq!(
            self.command("PKSIGN").await,
            "ERR 17 missing key selection\n"
        );
        assert!(self
            .command(DECRYPT)
            .await
            .ends_with("ERR 17 missing key selection\n"));
    }

    fn assert_selections(&self, primary: &str, secondary: &str) {
        assert_eq!(self.state.keygrip.as_deref(), Some(primary));
        assert_eq!(self.state.keygrip_another.as_deref(), Some(secondary));
    }

    fn assert_digest_cleared(&self) {
        assert!(self.state.hash.is_none());
        assert!(self.state.hash_algorithm.is_none());
        assert!(!self.state.hash_pss);
    }
}

/// Keygrips used by signing and decryption requests, in order.
fn used_keygrips(requests: &[Request]) -> Vec<&str> {
    requests
        .iter()
        .filter_map(|request| match request {
            Request::SignHash(request) => Some(request.keygrip.as_str()),
            Request::Pkdecrypt(request) => Some(request.keygrip.as_str()),
            _ => None,
        })
        .collect()
}

fn ipc_error(code: ErrorCode) -> Response {
    Response::Error(ErrorResponse {
        code: code.into(),
        message: String::new(),
    })
}

fn listed_keys() -> Response {
    Response::ListKeys(ListKeysResponse {
        keys: [KEY_A, KEY_B]
            .into_iter()
            .map(|keygrip| GpgKey {
                keygrip: keygrip.to_string(),
                can_sign: true,
                can_decrypt: true,
                ..Default::default()
            })
            .collect(),
    })
}

fn successful_response(request: &Request) -> Response {
    match request {
        Request::ListKeys(_) => listed_keys(),
        Request::SignHash(_) => Response::SignHash(SignHashResponse {
            sexp: "(sig-val(rsa(s #01#)))".to_string(),
        }),
        Request::Pkdecrypt(_) => Response::Pkdecrypt(PkdecryptResponse {
            value_sexp: "(value #41#)".to_string(),
        }),
        other => panic!("unexpected request: {other:?}"),
    }
}

#[tokio::test]
async fn either_key_selection_command_supports_signing_and_decryption() {
    for alias in ["SIGKEY", "SETKEY"] {
        let mut session = CommandSession::new(successful_response);
        session.select(alias, alias).await;

        // Repeated operations use the same selection, even when switching
        // between signing and decryption.
        for _ in 0..2 {
            session.command_ok(SETHASH).await;
            assert_eq!(
                session.command("PKSIGN").await,
                "D (sig-val(rsa(s #01#)))\nOK\n"
            );
            session.assert_selections(KEY_A, KEY_B);
            session.assert_digest_cleared();
            assert!(session
                .command(DECRYPT)
                .await
                .ends_with("D (5:value1:A)\nOK\n"));
            session.assert_selections(KEY_A, KEY_B);
        }

        let requests = session.requests().await;
        assert_eq!(requests.len(), 8);
        for round in requests.as_chunks::<4>().0 {
            assert!(matches!(&round[0], Request::ListKeys(_)));
            assert!(matches!(&round[1], Request::SignHash(request)
                if request.keygrip == KEY_A && request.hash == vec![0; 32]));
            assert!(matches!(&round[2], Request::ListKeys(_)));
            assert!(matches!(&round[3], Request::Pkdecrypt(request)
                if request.keygrip == KEY_A && request.ciphertext == b"ciphertext"));
        }
    }
}

#[tokio::test]
async fn changing_alias_replaces_the_key_used_by_both_operations() {
    for (first, second) in [("SIGKEY", "SETKEY"), ("SETKEY", "SIGKEY")] {
        let mut session = CommandSession::new(successful_response);
        session.command_ok(&format!("{first} {KEY_A}")).await;
        session
            .command_ok(&format!("{second} {}", KEY_B.to_ascii_lowercase()))
            .await;
        session.command_ok(SETHASH).await;
        assert!(session.command("PKSIGN").await.ends_with("\nOK\n"));
        assert!(session.command(DECRYPT).await.ends_with("\nOK\n"));

        let requests = session.requests().await;
        assert_eq!(requests.len(), 4);
        assert_eq!(used_keygrips(&requests), [KEY_B, KEY_B]);
    }
}

#[tokio::test]
async fn secondary_selection_is_shared_but_independent_of_primary_selection() {
    for (first, second) in [("SIGKEY", "SETKEY"), ("SETKEY", "SIGKEY")] {
        let mut session = CommandSession::new(|_| panic!("selection must not call IPC"));
        session
            .command_ok(&format!("{first} --another {KEY_A}"))
            .await;
        assert!(session.state.keygrip.is_none());
        assert_eq!(session.state.keygrip_another.as_deref(), Some(KEY_A));
        session.assert_no_selection().await;
        assert_eq!(session.state.keygrip_another.as_deref(), Some(KEY_A));

        session
            .command_ok(&format!("{second} --another {KEY_B}"))
            .await;
        session.command_ok(&format!("{first} {KEY_A}")).await;
        session.assert_selections(KEY_A, KEY_B);
        session
            .command_ok(&format!("{first} --another {KEY_A}"))
            .await;
        session.command_ok(&format!("{second} {KEY_B}")).await;
        session.assert_selections(KEY_B, KEY_A);
        assert!(session.requests().await.is_empty());
    }
}

#[tokio::test]
async fn invalid_key_selection_preserves_both_slots() {
    let mut session = CommandSession::new(|_| panic!("selection must not call IPC"));
    session.select("SIGKEY", "SETKEY").await;
    for alias in ["SIGKEY", "SETKEY"] {
        for option in ["", "--another"] {
            for keygrip in ["", "too-short", &"G".repeat(40)] {
                assert_eq!(
                    session
                        .command(&format!("{alias} {option} {keygrip}"))
                        .await,
                    "ERR 280 invalid keygrip\n",
                );
                session.assert_selections(KEY_A, KEY_B);
            }
        }
    }
    assert!(session.requests().await.is_empty());
}

#[tokio::test]
async fn failed_operations_preserve_both_selections_for_retry() {
    let locked = ipc_error(ErrorCode::VaultLocked);
    let denied = ipc_error(ErrorCode::UserDenied);
    let no_keys = Response::ListKeys(ListKeysResponse { keys: Vec::new() });
    let bad_decryption = Response::Pkdecrypt(PkdecryptResponse {
        value_sexp: "invalid".to_string(),
    });
    let cases = [
        ("PKSIGN", vec![locked.clone()], "ERR 99 vault locked\n"),
        ("PKSIGN", vec![no_keys.clone()], "ERR 17 no secret key\n"),
        (
            "PKSIGN",
            vec![listed_keys(), denied.clone()],
            "ERR 99 canceled\n",
        ),
        (DECRYPT, vec![locked], "ERR 99 vault locked\n"),
        (DECRYPT, vec![no_keys], "ERR 17 no secret key\n"),
        (DECRYPT, vec![listed_keys(), denied], "ERR 99 canceled\n"),
        (
            DECRYPT,
            vec![listed_keys(), bad_decryption],
            "ERR 83 invalid S-expression\n",
        ),
        ("PKDECRYPT\nCAN\n", vec![], "ERR 277 canceled\n"),
        ("PKDECRYPT\nNOP\n", vec![], "ERR 274 unexpected command\n"),
        ("PKDECRYPT\n", vec![], "ERR 79 bad ciphertext\n"),
        (
            "PKDECRYPT --kem=invalid",
            vec![],
            "ERR 280 invalid PKDECRYPT\n",
        ),
    ];
    for (command, responses, error) in cases {
        let mut responses = VecDeque::from(responses);
        let mut session = CommandSession::new(move |request| {
            responses
                .pop_front()
                .unwrap_or_else(|| successful_response(request))
        });
        session.select("SIGKEY", "SETKEY").await;
        session.command_ok(SETHASH).await;

        let response = session.command(command).await;
        assert!(response.ends_with(error), "{command}: {response}");
        session.assert_selections(KEY_A, KEY_B);

        session.command_ok(SETHASH).await;
        assert!(session.command("PKSIGN").await.ends_with("\nOK\n"));
        assert!(session.command(DECRYPT).await.ends_with("\nOK\n"));
        session.assert_selections(KEY_A, KEY_B);
        let requests = session.requests().await;
        assert!(used_keygrips(&requests).ends_with(&[KEY_A, KEY_A]));
    }
}

#[tokio::test]
async fn signing_validation_errors_clear_only_the_digest() {
    for (hash_command, error) in [
        (None, "ERR 5 invalid digest algorithm\n"),
        (Some(SETHASH_PSS), "ERR 60 not supported\n"),
    ] {
        let mut session = CommandSession::new(successful_response);
        session.select("SETKEY", "SIGKEY").await;
        if let Some(command) = hash_command {
            session.command_ok(command).await;
        }
        assert_eq!(session.command("PKSIGN").await, error);
        session.assert_selections(KEY_A, KEY_B);
        session.assert_digest_cleared();
        assert_eq!(session.requests().await.len(), 1);
    }
}

#[tokio::test]
async fn reset_clears_both_selections_and_digest() {
    let mut session = CommandSession::new(successful_response);
    session.select("SIGKEY", "SETKEY").await;
    session.command_ok(SETHASH_PSS).await;
    session.command_ok("RESET").await;
    assert!(session.state.keygrip.is_none());
    assert!(session.state.keygrip_another.is_none());
    session.assert_digest_cleared();
    session.assert_no_selection().await;

    session.command_ok(&format!("SETKEY {KEY_B}")).await;
    assert_eq!(
        session.command("PKSIGN").await,
        "ERR 5 invalid digest algorithm\n"
    );
    assert_eq!(session.requests().await.len(), 1);
}

#[tokio::test]
async fn key_selection_does_not_leak_to_a_new_connection() {
    let (ipc_stream, _ipc_peer) = tokio::io::duplex(1);
    let ipc_client = IpcClient::from_test_stream(ipc_stream);
    for (commands, expected_errors) in [
        (
            format!("SIGKEY {KEY_A}\nSETKEY --another {KEY_B}\n{SETHASH}\nBYE\n"),
            0,
        ),
        (format!("PKSIGN\n{DECRYPT}BYE\n"), 2),
    ] {
        let (mut client, server) = tokio::io::duplex(4096);
        let client_task = async {
            client.write_all(commands.as_bytes()).await.unwrap();
            let mut output = String::new();
            client.read_to_string(&mut output).await.unwrap();
            assert_eq!(
                output.matches("ERR 17 missing key selection\n").count(),
                expected_errors
            );
            assert!(output.ends_with("OK closing connection\n"));
        };
        tokio::time::timeout(Duration::from_secs(5), async {
            let (result, ()) = tokio::join!(
                serve_connection(server, ipc_client.clone(), None, "test".to_string()),
                client_task,
            );
            result.unwrap();
        })
        .await
        .expect("connection must not reach IPC without a key selection");
    }
}
