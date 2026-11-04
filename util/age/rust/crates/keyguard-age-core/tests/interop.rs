//! The fixture was created with the official Go age v1.2.1 CLI:
//! age -p -o go-age-v1.2.1.zip.age fixture.zip (password: fixture-password).
use keyguard_age_core::{close, create, execute};

struct Session(u64);
impl Session {
    fn new() -> Self {
        let id = create();
        assert_ne!(id, 0);
        Self(id)
    }
    fn call(&self, op: u8, payload: &[u8]) -> Vec<u8> {
        let mut request = vec![op];
        request.extend_from_slice(payload);
        let result = execute(self.0, &request);
        assert_eq!(result.first(), Some(&0), "operation {op}: {result:?}");
        result[1..].to_vec()
    }
}
/// Spools `ciphertext` into the reader session in bounded chunks.
fn spool(session: &Session, ciphertext: &[u8]) {
    let directory = std::env::temp_dir();
    session.call(11, directory.to_str().expect("utf8 path").as_bytes());
    for chunk in ciphertext.chunks(keyguard_age_core::CHUNK) {
        session.call(12, chunk);
    }
}
impl Drop for Session {
    fn drop(&mut self) {
        close(self.0);
    }
}

#[test]
fn reads_reference_go_age_archive() {
    let reader = Session::new();
    spool(&reader, include_bytes!("fixtures/go-age-v1.2.1.zip.age"));
    reader.call(7, b"fixture-password");
    while reader.call(10, &[]) == [0] {}
    assert_eq!(reader.call(8, &[]), b"vault.json");
    assert_eq!(reader.call(9, &[]), b"{\"items\":[]}");
    assert_eq!(reader.call(9, &[]), b"");
    assert_eq!(reader.call(8, &[]), b"attachments/hello.txt");
    assert_eq!(reader.call(9, &[]), b"reference age fixture");
    assert_eq!(reader.call(9, &[]), b"");
    assert_eq!(reader.call(8, &[]), b"");
}

#[test]
fn excessive_scrypt_work_is_rejected_before_derivation() {
    let fixture = include_bytes!("fixtures/go-age-v1.2.1.zip.age");
    let end = fixture
        .windows(2)
        .position(|b| b == b"\n-")
        .expect("header");
    let mut bytes = fixture.to_vec();
    // The scrypt work factor is the final token on its recipient line.
    let line_end = bytes[end + 2..]
        .iter()
        .position(|b| *b == b'\n')
        .expect("recipient")
        + end
        + 2;
    bytes[line_end - 2..line_end].copy_from_slice(b"30");
    let reader = Session::new();
    spool(&reader, &bytes);
    let mut request = vec![7];
    request.extend_from_slice(b"fixture-password");
    assert_eq!(
        execute(reader.0, &request),
        vec![keyguard_age_core::Error::Limit as u8]
    );
}

#[test]
fn writes_archive_for_reference_cli() {
    let writer = Session::new();
    let mut output = Vec::new();
    let drain = |output: &mut Vec<u8>| loop {
        let bytes = writer.call(6, &[]);
        if bytes.is_empty() {
            break;
        }
        output.extend(bytes);
    };
    writer.call(1, b"fixture-password");
    drain(&mut output);
    writer.call(2, b"vault.json");
    drain(&mut output);
    writer.call(3, b"{\"items\":[]}");
    drain(&mut output);
    writer.call(4, &[]);
    drain(&mut output);
    writer.call(5, &[]);
    drain(&mut output);
    assert!(output.starts_with(b"age-encryption.org/v1\n"));
    // Opt-in artifact for running the independent CLI during development.
    if let Ok(path) = std::env::var("KEYGUARD_AGE_INTEROP_OUTPUT") {
        std::fs::write(path, output).expect("write interoperability artifact");
    }
}

#[test]
fn rejects_invalid_password_lengths_without_poisoning_registry() {
    for length in [0, 4097] {
        let reader = Session::new();
        spool(&reader, b"ciphertext");
        let mut request = vec![7];
        request.resize(1 + length, b'p');
        assert_eq!(
            execute(reader.0, &request),
            vec![keyguard_age_core::Error::Invalid as u8]
        );
    }
    let session = Session::new();
    assert_eq!(
        execute(session.0, &[0]),
        vec![keyguard_age_core::Error::Invalid as u8]
    );
}

#[test]
fn reader_requires_a_spool_first() {
    let session = Session::new();
    assert_eq!(
        execute(session.0, b"\x07fixture-password"),
        vec![keyguard_age_core::Error::Invalid as u8]
    );
    let session = Session::new();
    assert_eq!(
        execute(session.0, b"\x0cciphertext"),
        vec![keyguard_age_core::Error::Invalid as u8]
    );
    let session = Session::new();
    assert_eq!(
        execute(session.0, b"\x0b"),
        vec![keyguard_age_core::Error::Invalid as u8],
        "an empty directory is rejected"
    );
}

/// Writes one `entry.json` archive with `create` (op 1 or 13) and `secret`.
fn write_archive(create: u8, secret: &[u8]) -> Vec<u8> {
    let writer = Session::new();
    let mut output = Vec::new();
    let drain = |output: &mut Vec<u8>| loop {
        let bytes = writer.call(6, &[]);
        if bytes.is_empty() {
            break;
        }
        output.extend(bytes);
    };
    writer.call(create, secret);
    writer.call(2, b"entry.json");
    writer.call(3, b"{}");
    writer.call(4, &[]);
    writer.call(5, &[]);
    drain(&mut output);
    output
}

/// Spools `ciphertext` and opens it with `open` (op 7 or 14), returning the raw status.
fn open_archive(ciphertext: &[u8], open: u8, secret: &[u8]) -> (Session, Vec<u8>) {
    let reader = Session::new();
    spool(&reader, ciphertext);
    let mut request = vec![open];
    request.extend_from_slice(secret);
    let status = execute(reader.0, &request);
    (reader, status)
}

fn generate_identity() -> Vec<u8> {
    let identity = Session::new().call(15, &[]);
    assert!(identity.starts_with(b"AGE-SECRET-KEY-1"));
    identity
}

#[test]
fn identity_archives_round_trip() {
    let identity = generate_identity();
    assert_ne!(identity, generate_identity());
    let ciphertext = write_archive(13, &identity);
    assert!(ciphertext.starts_with(b"age-encryption.org/v1\n-> X25519 "));
    let (reader, status) = open_archive(&ciphertext, 14, &identity);
    assert_eq!(status, vec![0]);
    while reader.call(10, &[]) == [0] {}
    assert_eq!(reader.call(8, &[]), b"entry.json");
    assert_eq!(reader.call(9, &[]), b"{}");
}

#[test]
fn identity_and_password_archives_do_not_open_each_other() {
    let password = keyguard_age_core::Error::Password as u8;
    let identity = generate_identity();
    let keyed = write_archive(13, &identity);
    assert_eq!(
        open_archive(&keyed, 14, &generate_identity()).1,
        vec![password]
    );
    assert_eq!(
        open_archive(&keyed, 7, b"fixture-password").1,
        vec![keyguard_age_core::Error::Corrupt as u8],
    );
    let fixture = include_bytes!("fixtures/go-age-v1.2.1.zip.age");
    assert_eq!(open_archive(fixture, 14, &identity).1, vec![password]);
    assert_eq!(
        open_archive(&keyed, 14, b"not an identity").1,
        vec![keyguard_age_core::Error::Invalid as u8],
    );
}
