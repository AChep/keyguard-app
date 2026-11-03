//! Golden fixtures: the bytes that both the Rust and the Kotlin side of a
//! private channel check. A fixture file holds one `<name> <hex>` per line,
//! and `#` starts a comment line.

use crate::{
    CallerAuthorization, CallerAuthorizationEvidenceSource, CallerAuthorizationSubject,
    CallerAuthorizationSubjectKind, CallerIdentity,
};
use prost::Message;
use std::fmt::Write;

/// The name and bytes of each fixture, in file order.
pub fn fixtures(text: &str) -> Vec<(&str, Vec<u8>)> {
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

/// Checks that the fixtures are exactly the `expected` bytes, in order.
/// Reports every mismatch at once, as the fixture lines to review.
pub fn check_bytes(text: &str, expected: &[(&str, Vec<u8>)]) {
    let fixtures = fixtures(text);
    let mut mismatches = String::new();
    let names = fixtures.iter().map(|(name, _)| *name);
    if !names.eq(expected.iter().map(|(name, _)| *name)) {
        mismatches.push_str("fixtures and test cases must name the same values, in order\n");
    }
    for (name, bytes) in expected {
        let fixture = fixtures.iter().find(|(fixture, _)| fixture == name);
        if !fixture.is_some_and(|(_, fixture)| fixture == bytes) {
            let _ = writeln!(mismatches, "{name} {}", hex::encode(bytes));
        }
    }
    assert!(mismatches.is_empty(), "\n{mismatches}");
}

/// Checks that the fixtures are exactly the encodings of the `expected`
/// messages, and that each decodes back to its message.
pub fn check<M: Message + Default + PartialEq>(text: &str, expected: &[(&str, M)]) {
    let encoded = expected
        .iter()
        .map(|(name, message)| (*name, message.encode_to_vec()))
        .collect::<Vec<_>>();
    check_bytes(text, &encoded);
    for ((name, message), (_, bytes)) in expected.iter().zip(&encoded) {
        let decoded = M::decode(bytes.as_slice()).expect("a fixture decodes");
        assert!(
            decoded == *message,
            "{name} must decode back to its message"
        );
    }
}

/// A caller whose terminal session the platform verified.
pub fn verified_caller() -> CallerIdentity {
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
