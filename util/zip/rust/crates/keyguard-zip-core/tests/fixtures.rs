//! Generator of the golden archives `RustCompatFixtureTest` reads back on the
//! JVM. The archives are checked in, so this test is `#[ignore]`d:
//!
//! ```text
//! cargo test -p keyguard-zip-core -- --ignored write_jvm_compat_fixtures
//! ```
//!
//! Regenerating changes the SHA-256 constants pinned in
//! `RustCompatFixtureTest.kt`; update them in the same change.

use std::{fs, path::PathBuf};

const PASSWORD: &str = "correct horse battery staple";

const BINARY_ENTRY_SIZE: usize = 100 * 1024;

fn fixture_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../src/desktopTest/resources/compat/rust")
}

fn binary_payload() -> Vec<u8> {
    (0..BINARY_ENTRY_SIZE)
        .map(|index| ((index * 7 + 3) % 251) as u8)
        .collect()
}

fn write_fixture(path: &str, password: Option<&str>) {
    let handle = keyguard_zip_core::open(path, password).expect("open must succeed");

    keyguard_zip_core::begin_entry(handle, "hello.txt").expect("begin must succeed");
    keyguard_zip_core::write(handle, b"Hello from Keyguard zip fixtures.\n")
        .expect("write must succeed");
    keyguard_zip_core::end_entry(handle).expect("end must succeed");

    keyguard_zip_core::begin_entry(handle, "nested/dir/data.bin").expect("begin must succeed");
    keyguard_zip_core::write(handle, &binary_payload()).expect("write must succeed");
    keyguard_zip_core::end_entry(handle).expect("end must succeed");

    keyguard_zip_core::begin_entry(handle, "empty.txt").expect("begin must succeed");
    keyguard_zip_core::end_entry(handle).expect("end must succeed");

    keyguard_zip_core::finish(handle).expect("finish must succeed");
}

#[test]
#[ignore = "regenerates checked-in golden fixtures; run it explicitly"]
fn write_jvm_compat_fixtures() {
    let dir = fixture_dir();
    fs::create_dir_all(&dir).expect("the fixture directory must exist");

    let plain = dir.join("plain.zip");
    write_fixture(plain.to_str().expect("UTF-8 path"), None);

    let encrypted = dir.join("aes256.zip");
    write_fixture(encrypted.to_str().expect("UTF-8 path"), Some(PASSWORD));
}
