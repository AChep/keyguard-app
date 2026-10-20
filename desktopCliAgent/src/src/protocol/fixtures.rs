//! The encodings both sides of the private channel agree on. `proto/fixtures/`
//! holds them, and the Kotlin tests check the same files: each fixture
//! decodes to its message, and the message encodes back to it.
use super::*;
use keyguard_agent_proto::{
    CallerAuthorization, CallerAuthorizationEvidenceSource, CallerAuthorizationSubject,
    CallerAuthorizationSubjectKind, CallerIdentity,
};
use keyguard_cli_protocol::cli::{CliCommand, RunRequest};
use prost::Message;
use std::fmt::Write;

const REQUESTS: &str = include_str!("../../../proto/fixtures/requests.pbhex");
const RESPONSES: &str = include_str!("../../../proto/fixtures/responses.pbhex");

fn request(name: &'static str, request: request::Request) -> (&'static str, Request) {
    let request = Request {
        request: Some(request),
    };
    (name, request)
}

fn response(name: &'static str, response: response::Response) -> (&'static str, Response) {
    let response = Response {
        response: Some(response),
    };
    (name, response)
}

fn manage(result: manage_response::Result) -> response::Response {
    response::Response::Manage(ManageResponse {
        result: Some(result),
    })
}

fn local_env_file(status: MountStatus) -> LocalEnvFile {
    LocalEnvFile {
        id: "m".into(),
        environment_id: "e".into(),
        path: "/work/.env".into(),
        enabled: true,
        status: status.into(),
        account_id: Some("a".into()),
    }
}

/// A caller whose terminal session the platform verified.
fn verified_caller() -> CallerIdentity {
    CallerIdentity {
        pid: 42,
        process_name: "node".into(),
        executable_path: "/usr/bin/node".into(),
        app_name: "Terminal".into(),
        app_bundle_path: "/Applications/Terminal.app".into(),
        authorization: Some(CallerAuthorization {
            connection_fingerprint: vec![0x22; 32],
            subjects: vec![CallerAuthorizationSubject {
                kind: CallerAuthorizationSubjectKind::TerminalSession.into(),
                evidence_source: CallerAuthorizationEvidenceSource::MacosTerminalSession.into(),
                fingerprint: vec![0x33; 32],
            }],
            authorization_context_fingerprint: Vec::new(),
        }),
        ..CallerIdentity::default()
    }
}

/// A caller that the platform could not verify, such as on Windows.
fn unverified_caller() -> CallerIdentity {
    CallerIdentity {
        pid: 7,
        process_name: "node.exe".into(),
        ..CallerIdentity::default()
    }
}

/// A `keyguard run` request. Its JSON is what `keyguard_cli_protocol` writes,
/// so the fixture pins that too.
fn run(account: Option<&str>) -> request::Request {
    let request = keyguard_cli_protocol::cli::CliRequest::Run(RunRequest {
        environment: "Production".into(),
        account: account.map(Into::into),
        command: vec!["npm".into(), "start".into()],
        working_directory: "/work".into(),
    });
    request::Request::Cli(CliRequest {
        caller: Some(verified_caller()),
        command: "run".into(),
        request: request.request_json().unwrap().to_vec(),
    })
}

/// The answer to `keyguard run`, as the app writes it.
const RUN_RESULT: &[u8] = br#"{"ok":true,"variables":{"TOKEN":"secret","EMPTY":""}}"#;

fn requests() -> Vec<(&'static str, Request)> {
    vec![
        request(
            "authenticate",
            request::Request::Authenticate(AuthenticateRequest {
                token: vec![0x11; 32],
                protocol_revision: PROTOCOL_REVISION,
            }),
        ),
        request(
            "manage",
            request::Request::Manage(ManageRequest {
                caller: Some(verified_caller()),
                connection_id: vec![0x44; 32],
                client_name: "Claude Code".into(),
                tool: "append_variables".into(),
                arguments: [
                    ("account_id".into(), "a".into()),
                    ("environment_id".into(), "e".into()),
                ]
                .into(),
                variables: vec![
                    VariableUpdate {
                        name: "TOKEN".into(),
                        value: Some(b"secret".to_vec()),
                    },
                    VariableUpdate {
                        name: "EMPTY".into(),
                        value: Some(Vec::new()),
                    },
                    VariableUpdate {
                        name: "UNSET".into(),
                        value: None,
                    },
                ],
            }),
        ),
        request(
            "manage_unverified",
            request::Request::Manage(ManageRequest {
                caller: Some(unverified_caller()),
                connection_id: vec![0x44; 32],
                tool: "authenticate".into(),
                ..ManageRequest::default()
            }),
        ),
        request("mounts", request::Request::Mounts(MountsRequest {})),
        request(
            "watch_mounts",
            request::Request::WatchMounts(WatchMountsRequest {
                revision: "r1".into(),
            }),
        ),
        request(
            "mount_status",
            request::Request::MountStatus(MountStatusRequest {
                mount_id: "m".into(),
                status: MountStatus::Ready.into(),
            }),
        ),
        request(
            "resolve_mount",
            request::Request::ResolveMount(ResolveMountRequest {
                mount_id: "m".into(),
                generation: "g".into(),
            }),
        ),
        request("cli_run", run(None)),
        request("cli_run_in_account", run(Some("Work"))),
    ]
}

fn responses() -> Vec<(&'static str, Response)> {
    vec![
        response(
            "authenticate",
            response::Response::Authenticate(AuthenticateResponse {}),
        ),
        response(
            "error",
            response::Response::Error(ErrorResponse {
                code: "user_denied".into(),
            }),
        ),
        response(
            "manage_accounts",
            manage(manage_response::Result::Accounts(AccountList {
                accounts: vec![Account {
                    id: "a".into(),
                    name: "Personal".into(),
                }],
            })),
        ),
        response(
            "manage_environments",
            manage(manage_response::Result::Environments(EnvironmentList {
                environments: vec![Environment {
                    id: "e".into(),
                    account_id: "a".into(),
                    name: "Production".into(),
                    variable_count: 2,
                    editable: true,
                }],
            })),
        ),
        response(
            "manage_environment",
            manage(manage_response::Result::Environment(EnvironmentId {
                id: "e".into(),
                account_id: "a".into(),
            })),
        ),
        response(
            "manage_variables",
            manage(manage_response::Result::Variables(VariableList {
                variables: vec![
                    Variable {
                        name: "TOKEN".into(),
                        source: VariableSource::Reference.into(),
                        is_set: true,
                        supported: true,
                    },
                    Variable {
                        name: "UNSET".into(),
                        source: VariableSource::Unset.into(),
                        is_set: false,
                        supported: true,
                    },
                ],
            })),
        ),
        response(
            "manage_local_env_file",
            manage(manage_response::Result::LocalEnvFile(local_env_file(
                MountStatus::Ready,
            ))),
        ),
        response(
            "manage_local_env_files",
            manage(manage_response::Result::LocalEnvFiles(LocalEnvFileList {
                local_env_files: vec![local_env_file(MountStatus::Pending)],
            })),
        ),
        response(
            "mounts",
            response::Response::Mounts(MountsResponse {
                revision: "r1".into(),
                mounts: vec![
                    Mount {
                        id: "m".into(),
                        path: "/work/.env".into(),
                        generation: "g".into(),
                        bookmark: None,
                    },
                    Mount {
                        id: "n".into(),
                        path: "/other/.env".into(),
                        generation: "g".into(),
                        bookmark: Some(vec![1, 2, 3]),
                    },
                ],
            }),
        ),
        response(
            "mount_status",
            response::Response::MountStatus(MountStatusResponse {}),
        ),
        response(
            "resolve_mount",
            response::Response::ResolveMount(ResolveMountResponse {
                generation: "g".into(),
                content: b"TOKEN='secret'\n".to_vec(),
            }),
        ),
        response(
            "cli_run",
            response::Response::Cli(CliResponse {
                result: RUN_RESULT.to_vec(),
            }),
        ),
    ]
}

/// The name and bytes of each fixture, in file order.
fn fixtures(text: &str) -> Vec<(&str, Vec<u8>)> {
    text.lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .map(|line| {
            let (name, hex) = line
                .split_once(' ')
                .expect("a fixture is a name and its hex");
            (name, hex::decode(hex.trim()).expect("hex digits"))
        })
        .collect()
}

/// Checks that the fixtures are exactly the `expected` messages. Reports every
/// mismatch at once, as the fixture lines to review.
fn check<M: Message + Default + PartialEq>(text: &str, expected: &[(&str, M)]) {
    let fixtures = fixtures(text);
    let mut mismatches = String::new();
    let names = fixtures.iter().map(|(name, _)| *name);
    if !names.eq(expected.iter().map(|(name, _)| *name)) {
        mismatches.push_str("fixtures and test cases must name the same messages, in order\n");
    }
    for (name, message) in expected {
        let encoded = message.encode_to_vec();
        let fixture = fixtures.iter().find(|(fixture, _)| fixture == name);
        let matches = fixture.is_some_and(|(_, bytes)| {
            *bytes == encoded
                && M::decode(bytes.as_slice()).is_ok_and(|decoded| decoded == *message)
        });
        if !matches {
            let _ = writeln!(mismatches, "{name} {}", hex::encode(&encoded));
        }
    }
    assert!(mismatches.is_empty(), "\n{mismatches}");
}

#[test]
fn request_fixtures_match_the_schema() {
    check(REQUESTS, &requests());
}

#[test]
fn response_fixtures_match_the_schema() {
    check(RESPONSES, &responses());
}

#[test]
fn the_run_fixture_is_a_result_of_the_command() {
    let result = serde_json::from_slice(RUN_RESULT).unwrap();
    assert!(RunRequest::is_valid_result(&result));
}
