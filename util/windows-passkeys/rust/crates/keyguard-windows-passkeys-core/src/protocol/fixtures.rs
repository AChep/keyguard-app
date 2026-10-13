//! The encodings both sides of the bridge agree on. `schema/fixtures/` holds them, and the
//! Kotlin tests check the same files: each fixture decodes to its message, and the message
//! encodes back to the fixture.
use super::{
    Command, CompleteCommand, Descriptor, Empty, Event, Failure, Identity, Limits, NextCommand,
    Reply, Request, Response, SetLockedCommand, Status, SyncCommand, User, UserVerification,
    VerifyCommand, command::Command as C, reply::Outcome as O,
};
use crate::error::Error;
use prost::Message;
use std::fmt::Write;

const COMMANDS: &str = include_str!("../../../../../schema/fixtures/commands.pbhex");
const REPLIES: &str = include_str!("../../../../../schema/fixtures/replies.pbhex");

fn command(name: &'static str, command: C) -> (&'static str, Command) {
    let command = Command {
        command: Some(command),
    };
    (name, command)
}

fn reply(name: &'static str, outcome: O) -> (&'static str, Reply) {
    let reply = Reply {
        outcome: Some(outcome),
    };
    (name, reply)
}

fn identity() -> Identity {
    Identity {
        credential_id: vec![1],
        rp_id: "example.com".into(),
        rp_name: "Example".into(),
        user_id: vec![2],
        user_name: "alice".into(),
        user_display_name: "Alice".into(),
    }
}

fn sign_in() -> Request {
    Request {
        id: 1,
        create: false,
        window: -2,
        rp_id: "example.com".into(),
        client_data_hash: vec![7; 32],
        user_verification: UserVerification::Required.into(),
        credentials: vec![Descriptor {
            id: vec![0x11; 16],
            r#type: "public-key".into(),
        }],
        expires_in_ms: 110_000,
        ..Request::default()
    }
}

fn registration() -> Request {
    Request {
        id: 2,
        create: true,
        window: 42,
        rp_id: "example.com".into(),
        client_data_hash: vec![7; 32],
        user_verification: UserVerification::Preferred.into(),
        user: Some(User {
            id: b"user".to_vec(),
            name: "alice".into(),
            display_name: "Alice".into(),
        }),
        rp_name: Some("Example".into()),
        algorithms: vec![-7, -257],
        discoverable: true,
        expires_in_ms: 110_000,
        ..Request::default()
    }
}

fn complete(id: u64, response: Option<Response>, failure: Failure) -> C {
    C::Complete(CompleteCommand {
        id,
        response,
        failure: failure.into(),
    })
}

fn commands() -> Vec<(&'static str, Command)> {
    let next = |active, timeout_ms| C::Next(NextCommand { active, timeout_ms });
    let assertion = Response {
        authenticator_data: vec![0xaa; 3],
        credential_id: Some(vec![1, 2]),
        signature: Some(vec![3]),
        user_handle: Some(vec![4]),
    };
    let attestation = Response {
        authenticator_data: vec![0xaa; 3],
        credential_id: None,
        signature: None,
        user_handle: None,
    };
    vec![
        command("status", C::Status(Empty {})),
        command("register", C::Register(Empty {})),
        command("unregister", C::Unregister(Empty {})),
        command("clear", C::Clear(Empty {})),
        command("start", C::Start(Empty {})),
        command("stop", C::Stop(Empty {})),
        command("retire", C::Retire(Empty {})),
        command("limits", C::Limits(Empty {})),
        command("next_unbounded", next(None, None)),
        command("next_known", next(Some(7), Some(30_000))),
        // Unlike a proto3 default, a present zero must reach the other side.
        command("next_zero", next(Some(0), Some(0))),
        command(
            "set_locked",
            C::SetLocked(SetLockedCommand { locked: true }),
        ),
        command(
            "set_unlocked",
            C::SetLocked(SetLockedCommand { locked: false }),
        ),
        command(
            "complete_abandoned",
            complete(1, None, Failure::Unspecified),
        ),
        command("complete_not_found", complete(1, None, Failure::NotFound)),
        command(
            "complete_assertion",
            complete(2, Some(assertion), Failure::Unspecified),
        ),
        command(
            "complete_registration",
            complete(3, Some(attestation), Failure::Unspecified),
        ),
        command(
            "verify",
            C::Verify(VerifyCommand {
                id: 1,
                window: -2,
                username: "alice".into(),
                hint: "example.com".into(),
                unlocked: false,
            }),
        ),
        command(
            "verify_unlocked",
            C::Verify(VerifyCommand {
                id: 1,
                window: -2,
                username: "alice".into(),
                hint: "example.com".into(),
                unlocked: true,
            }),
        ),
        command(
            "sync",
            C::Sync(SyncCommand {
                identities: vec![identity()],
            }),
        ),
        command("sync_empty", C::Sync(SyncCommand { identities: vec![] })),
    ]
}

fn replies() -> Vec<(&'static str, Reply)> {
    let status = |name, status: Status| reply(name, O::Status(status.into()));
    let event = |name, event: Event| reply(name, O::Event(event));
    vec![
        // Kotlin names the codes it tells apart.
        reply("error_invalid", O::Error(Error::Invalid.code())),
        reply("error_unsupported", O::Error(Error::Unsupported.code())),
        reply("error_unpackaged", O::Error(Error::Unpackaged.code())),
        reply("error_cancelled", O::Error(Error::Cancelled.code())),
        reply("error_dismissed", O::Error(Error::Dismissed.code())),
        reply("done", O::Done(Empty {})),
        status("status_unsupported", Status::Unsupported),
        status("status_unpackaged", Status::Unpackaged),
        status("status_unavailable", Status::Unavailable),
        status("status_unregistered", Status::Unregistered),
        status("status_disabled", Status::Disabled),
        status("status_enabled", Status::Enabled),
        reply("retired", O::Retired(true)),
        reply("not_retired", O::Retired(false)),
        reply(
            "limits",
            O::Limits(Limits {
                credential_id_bytes: 1023,
                user_id_bytes: 64,
                text_bytes: 4096,
            }),
        ),
        event("event_idle", Event::default()),
        event(
            "event_active",
            Event {
                active_id: Some(1),
                watching: true,
                ..Event::default()
            },
        ),
        event(
            "event_closed",
            Event {
                closed: true,
                ..Event::default()
            },
        ),
        event(
            "event_sign_in",
            Event {
                request: Some(sign_in()),
                active_id: Some(1),
                watching: true,
                ..Event::default()
            },
        ),
        event(
            "event_registration",
            Event {
                request: Some(registration()),
                active_id: Some(2),
                ..Event::default()
            },
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
            (name, from_hex(hex.trim()))
        })
        .collect()
}

fn from_hex(hex: &str) -> Vec<u8> {
    assert!(hex.len().is_multiple_of(2), "odd hex length: {hex}");
    (0..hex.len())
        .step_by(2)
        .map(|index| u8::from_str_radix(&hex[index..index + 2], 16).expect("hex digits"))
        .collect()
}

fn to_hex(bytes: &[u8]) -> String {
    bytes.iter().fold(String::new(), |mut hex, byte| {
        let _ = write!(hex, "{byte:02x}");
        hex
    })
}

/// Checks that the fixtures are exactly the `expected` messages. Reports every mismatch at
/// once, as the fixture lines to review.
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
            let _ = writeln!(mismatches, "{name} {}", to_hex(&encoded));
        }
    }
    assert!(mismatches.is_empty(), "\n{mismatches}");
}

#[test]
fn command_fixtures_match_the_schema() {
    check(COMMANDS, &commands());
}

#[test]
fn reply_fixtures_match_the_schema() {
    check(REPLIES, &replies());
}
