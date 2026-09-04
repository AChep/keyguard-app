//! Reads the golden archives written by the zip4j backed JVM writer, so the
//! Apple targets keep reading archives from another Keyguard install.
//!
//! The fixtures in `tests/fixtures/jvm` are produced by
//! `JvmCompatFixtureGenerator` of the `util/zip` module:
//!
//! ```text
//! ./gradlew :util:zip:desktopTest --tests '*JvmCompatFixtureGenerator*' \
//!     -Pkeyguard.zip.writeFixtures=true
//! ```

use std::{fs, io::Read, path::PathBuf};

use keyguard_zip_core::{ArchiveReader, BridgeError, pack_bridge_error};
use sha2::{Digest, Sha256};
use zip::{CompressionMethod, ZipArchive};

const PASSWORD_TEXT: &str = "correct horse battery staple";

const PASSWORD: &[u8] = PASSWORD_TEXT.as_bytes();

const NAMES: [&str; 3] = ["hello.txt", "nested/dir/data.bin", "empty.txt"];

/// zip4j stamps the current time into every entry, so regenerating the
/// fixtures changes these digests; update them in the same change.
const PLAIN_SHA256: &str = "8372a0f7858d275e424341b0ef19eba55da59e9afa1284860b7a0a9ce27941b1";

const AES_SHA256: &str = "a0f00424daaef082b03ec8ef999a4999a22a9a74933b862dd5bd922817c132bc";

fn contents() -> [Vec<u8>; 3] {
    [
        b"Hello from Keyguard zip fixtures.\n".to_vec(),
        (0..100 * 1024).map(|i| ((i * 7 + 3) % 251) as u8).collect(),
        Vec::new(),
    ]
}

fn fixture_path(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures/jvm")
        .join(name)
}

/// Reads a fixture, checking its digest first.
fn fixture(name: &str, expected_sha256: &str) -> ZipArchive<std::io::Cursor<Vec<u8>>> {
    let path = fixture_path(name);
    let bytes = fs::read(&path).expect("the fixture must exist");

    let digest = Sha256::digest(&bytes);
    let actual = digest.iter().fold(String::new(), |mut hex, byte| {
        use std::fmt::Write;
        let _ = write!(hex, "{byte:02x}");
        hex
    });
    assert_eq!(actual, expected_sha256, "{name} is the committed fixture");

    ZipArchive::new(std::io::Cursor::new(bytes)).expect("the archive must be readable")
}

/// `file_names` does not preserve archive order; this does.
fn names_in_order(archive: &ZipArchive<std::io::Cursor<Vec<u8>>>) -> Vec<String> {
    (0..archive.len())
        .map(|index| {
            archive
                .name_for_index(index)
                .expect("every index must name an entry")
                .to_owned()
        })
        .collect()
}

#[test]
fn the_plain_jvm_archive_is_readable() {
    let mut archive = fixture("plain.zip", PLAIN_SHA256);
    assert_eq!(names_in_order(&archive), NAMES);

    for (name, expected) in NAMES.iter().zip(contents()) {
        let mut entry = archive.by_name(name).expect("the entry must exist");
        assert_eq!(entry.compression(), CompressionMethod::Deflated, "{name}");
        assert!(!entry.encrypted(), "{name}");
        let mut content = Vec::new();
        entry.read_to_end(&mut content).expect("read must work");
        assert_eq!(content, expected, "{name}");
    }
}

#[test]
fn the_encrypted_jvm_archive_decrypts_with_its_password() {
    let mut archive = fixture("aes256.zip", AES_SHA256);
    assert_eq!(names_in_order(&archive), NAMES);

    for (name, expected) in NAMES.iter().zip(contents()) {
        let mut entry = archive
            .by_name_decrypt(name, PASSWORD)
            .expect("the entry must decrypt");
        assert_eq!(entry.compression(), CompressionMethod::Deflated, "{name}");
        assert!(entry.encrypted(), "{name}");
        let mut content = Vec::new();
        entry.read_to_end(&mut content).expect("read must work");
        assert_eq!(content, expected, "{name}");
    }
}

#[test]
fn the_encrypted_jvm_archive_rejects_a_wrong_password() {
    let mut archive = fixture("aes256.zip", AES_SHA256);
    for name in NAMES {
        assert!(
            archive.by_name_decrypt(name, b"wrong password").is_err(),
            "{name} must not decrypt with a wrong password"
        );
        assert!(
            archive.by_name(name).is_err(),
            "{name} must not open without a password"
        );
    }
}

fn reader(name: &str, expected_sha256: &str, password: Option<&str>) -> ArchiveReader {
    // Only for the digest check.
    drop(fixture(name, expected_sha256));
    let path = fixture_path(name);
    ArchiveReader::open(path.to_str().expect("UTF-8 path"), password)
        .expect("the fixture must open through the reader")
}

fn drain(reader: &mut ArchiveReader) -> Vec<(String, Vec<u8>)> {
    let mut entries = Vec::new();
    let mut name_buf = [0_u8; 512];
    while let Some(name_len) = reader.next_entry(&mut name_buf).expect("listing must work") {
        let name = String::from_utf8(name_buf[..name_len].to_vec()).expect("UTF-8 name");
        let mut content = Vec::new();
        let mut chunk = [0_u8; 1024];
        loop {
            let read = reader.read(&mut chunk).expect("reading must work");
            if read == 0 {
                break;
            }
            content.extend_from_slice(&chunk[..read]);
        }
        entries.push((name, content));
    }
    entries
}

fn expected_entries() -> Vec<(String, Vec<u8>)> {
    NAMES
        .iter()
        .map(|name| (*name).to_owned())
        .zip(contents())
        .collect()
}

#[test]
fn the_plain_jvm_archive_streams_through_the_reader() {
    let mut reader = reader("plain.zip", PLAIN_SHA256, None);
    assert_eq!(drain(&mut reader), expected_entries());
    reader.close().expect("close must succeed");
}

#[test]
fn the_encrypted_jvm_archive_streams_through_the_reader() {
    let mut reader = reader("aes256.zip", AES_SHA256, Some(PASSWORD_TEXT));
    assert_eq!(drain(&mut reader), expected_entries());
    reader.close().expect("close must succeed");
}

#[test]
fn the_reader_rejects_a_wrong_or_missing_password_on_the_jvm_archive() {
    let expected = pack_bridge_error(BridgeError::WrongPassword);
    for password in [Some("wrong password"), None] {
        let mut reader = reader("aes256.zip", AES_SHA256, password);
        assert_eq!(
            reader.next_entry(&mut [0_u8; 512]),
            Err(expected),
            "{password:?} must not list an encrypted entry"
        );
        assert_eq!(
            reader.read(&mut [0_u8; 8]),
            Err(pack_bridge_error(BridgeError::InvalidState)),
            "no entry may become current"
        );
    }
}

#[test]
fn a_password_is_ignored_on_the_plain_jvm_archive() {
    let mut reader = reader("plain.zip", PLAIN_SHA256, Some(PASSWORD_TEXT));
    assert_eq!(drain(&mut reader), expected_entries());
}
