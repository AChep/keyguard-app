//! Streaming ZIP archive reader behind the native bridge. Entries are walked in
//! central-directory order and streamed in caller-sized chunks; nothing is
//! buffered whole.
//!
//! # Why a self-referential cell
//!
//! `zip` 8.6.0 has no owned entry reader: `by_index` returns a `ZipFile` that
//! borrows the archive mutably, and neither `read::stream` (no seek, no
//! per-entry password) nor `by_index_seek` (stored entries only) substitutes.
//! Holding an entry across ABI calls means owning the archive and a borrow of
//! it together, which [`self_cell`] provides without hand-written unsafe drop
//! or move handling.
//!
//! `self_cell`'s builder hands out `&Owner` while `by_index` needs `&mut`, so
//! the owner is an [`UnsafeCell`]. This is sound because the archive is
//! reachable through exactly one path: the cell is private, `borrow_owner` is
//! never called, and the entry is the only live reference until the cell is
//! dropped or unwrapped by `into_owner`, which drops the entry first. The
//! `&Owner` that `with_dependent_mut` hands out is a shared reference to the
//! cell, which is the aliasing the type permits.

use std::{
    cell::UnsafeCell,
    fs::File,
    io::{self, Read, Seek},
    mem,
};

use self_cell::self_cell;
use zeroize::Zeroizing;
use zip::{
    ZipArchive,
    read::{HasZipMetadata, ZipFile},
    result::ZipError,
};

use crate::{
    MAX_ENTRY_NAME_BYTES,
    error::{
        BridgeError, FailureKind, Operation, pack_archive_error, pack_bridge_error, pack_io_error,
        pack_zip_error,
    },
};

/// The entry currently being streamed; `self_cell` needs a named dependent.
struct Entry<'a>(ZipFile<'a, File>);

self_cell!(
    struct EntryCell {
        owner: UnsafeCell<ZipArchive<File>>,

        #[not_covariant]
        dependent: Entry,
    }
);

enum State {
    Idle(ZipArchive<File>),
    Entry(EntryCell),
    /// The archive was moved out and never put back, which only a panic inside
    /// [`ArchiveReader::next_entry`] can cause. Every later call fails.
    Poisoned,
}

/// A ZIP archive being read entry by entry. The reader never removes or writes
/// the file; the caller owns it.
pub struct ArchiveReader {
    state: State,
    password: Option<Zeroizing<String>>,
    next_index: usize,
    len: usize,
}

impl ArchiveReader {
    /// Opens the existing archive at `path`. A nonempty `password` requires
    /// every entry read to use authenticated AES encryption.
    ///
    /// # Errors
    ///
    /// Returns a packed [`Operation::ReaderOpen`] failure.
    pub fn open(path: &str, password: Option<&str>) -> Result<Self, i64> {
        let file =
            File::open(path).map_err(|error| pack_io_error(Operation::ReaderOpen, &error))?;
        let archive =
            ZipArchive::new(file).map_err(|error| pack_zip_error(Operation::ReaderOpen, &error))?;
        Ok(Self {
            len: archive.len(),
            state: State::Idle(archive),
            password: password
                .filter(|password| !password.is_empty())
                .map(|password| Zeroizing::new(password.to_owned())),
            next_index: 0,
        })
    }

    /// Advances to the next entry, discarding the current one, and copies its
    /// name into `name_buf`. Returns the name's byte length, or `None` at the
    /// end.
    ///
    /// # Errors
    ///
    /// Returns [`BridgeError::NameTooLong`], [`BridgeError::BufferTooSmall`],
    /// [`BridgeError::WrongPassword`], [`BridgeError::EncryptionRequired`],
    /// [`BridgeError::UnsupportedEntry`], or a
    /// packed [`Operation::NextEntry`] failure. No failure advances the reader,
    /// so a call can be retried with a bigger buffer.
    pub fn next_entry(&mut self, name_buf: &mut [u8]) -> Result<Option<usize>, i64> {
        let archive = self.take_archive()?;
        let index = self.next_index;
        if index >= self.len {
            self.state = State::Idle(archive);
            return Ok(None);
        }

        let name_len = match measure_name(&archive, index, name_buf.len()) {
            Ok(name_len) => name_len,
            Err(packed) => {
                self.state = State::Idle(archive);
                return Err(packed);
            }
        };
        let name = archive
            .name_for_index(index)
            .expect("the name was just measured");
        name_buf[..name_len].copy_from_slice(name.as_bytes());

        let password = self.password.as_deref();
        let cell = EntryCell::try_new_or_recover(UnsafeCell::new(archive), |archive| {
            // SAFETY: The cell is private and `borrow_owner` is never called,
            // so the entry built here is the only reference to the archive
            // until the cell is dropped or unwrapped. See the module docs.
            let archive = unsafe { &mut *archive.get() };
            if password.is_some() {
                // Inspect the same metadata used by the decryptor before it can
                // discard the password for plaintext or accept legacy ZipCrypto.
                let entry = archive
                    .by_index_raw(index)
                    .map_err(|error| open_entry_failure(&error))?;
                if !entry.encrypted() || entry.get_metadata().aes_mode.is_none() {
                    return Err(pack_bridge_error(BridgeError::EncryptionRequired));
                }
            }
            open_entry(archive, index, password.map(String::as_str)).map(Entry)
        });

        match cell {
            Ok(cell) => {
                self.state = State::Entry(cell);
                self.next_index = index + 1;
                Ok(Some(name_len))
            }
            Err((archive, packed)) => {
                self.state = State::Idle(archive.into_inner());
                Err(packed)
            }
        }
    }

    /// Reads up to `buf.len()` bytes of the current entry; zero at its end.
    ///
    /// # Errors
    ///
    /// Returns [`BridgeError::InvalidState`] when no entry is current, or a
    /// packed [`Operation::Read`] failure.
    pub fn read(&mut self, buf: &mut [u8]) -> Result<usize, i64> {
        let State::Entry(cell) = &mut self.state else {
            return Err(pack_bridge_error(BridgeError::InvalidState));
        };
        cell.with_dependent_mut(|_, entry| read_entry(&mut entry.0, buf))
    }

    /// Closes the reader, leaving the file in place.
    ///
    /// # Errors
    ///
    /// Never fails; the result keeps the ABI shape uniform.
    pub fn close(self) -> Result<(), i64> {
        drop(self);
        Ok(())
    }

    /// Takes the archive out of the state, dropping any open entry.
    fn take_archive(&mut self) -> Result<ZipArchive<File>, i64> {
        match mem::replace(&mut self.state, State::Poisoned) {
            State::Idle(archive) => Ok(archive),
            State::Entry(cell) => Ok(cell.into_owner().into_inner()),
            State::Poisoned => Err(pack_bridge_error(BridgeError::Internal)),
        }
    }
}

/// Returns the byte length of entry `index`'s name if it fits both the ABI
/// limit and a buffer of `capacity` bytes.
fn measure_name(archive: &ZipArchive<File>, index: usize, capacity: usize) -> Result<usize, i64> {
    let Some(name) = archive.name_for_index(index) else {
        return Err(pack_archive_error(Operation::NextEntry, FailureKind::Other));
    };
    if name.len() > MAX_ENTRY_NAME_BYTES {
        return Err(pack_bridge_error(BridgeError::NameTooLong));
    }
    if name.len() > capacity {
        return Err(pack_bridge_error(BridgeError::BufferTooSmall));
    }
    Ok(name.len())
}

/// Opens entry `index`, decrypting it when a password is given.
pub(crate) fn open_entry<'a, R: Read + Seek>(
    archive: &'a mut ZipArchive<R>,
    index: usize,
    password: Option<&str>,
) -> Result<ZipFile<'a, R>, i64> {
    match password {
        Some(password) => archive.by_index_decrypt(index, password.as_bytes()),
        None => archive.by_index(index),
    }
    .map_err(|error| open_entry_failure(&error))
}

/// Reads up to `buf.len()` bytes of an opened entry; zero at its end.
pub(crate) fn read_entry(entry: &mut impl Read, buf: &mut [u8]) -> Result<usize, i64> {
    if buf.is_empty() {
        return Ok(0);
    }
    loop {
        return match entry.read(buf) {
            Ok(read) => Ok(read),
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            // A CRC-32 or AE-2 HMAC mismatch surfaces as invalid data:
            // an input failure, not an I/O one.
            Err(error) if error.kind() == io::ErrorKind::InvalidData => Err(pack_archive_error(
                Operation::Read,
                FailureKind::InvalidInput,
            )),
            Err(error) => Err(pack_io_error(Operation::Read, &error)),
        };
    }
}

/// Classifies a failure to open an entry. A missing password reads like a
/// wrong one, even though the `zip` crate reports it as unsupported.
pub(crate) fn open_entry_failure(error: &ZipError) -> i64 {
    match error {
        ZipError::InvalidPassword => pack_bridge_error(BridgeError::WrongPassword),
        ZipError::UnsupportedArchive(ZipError::PASSWORD_REQUIRED) => {
            pack_bridge_error(BridgeError::WrongPassword)
        }
        ZipError::UnsupportedArchive(_) | ZipError::CompressionMethodNotSupported(_) => {
            pack_bridge_error(BridgeError::UnsupportedEntry)
        }
        ZipError::Io(error) => pack_io_error(Operation::NextEntry, error),
        _ => pack_archive_error(Operation::NextEntry, FailureKind::Other),
    }
}

#[cfg(test)]
mod tests {
    use std::{fs, io::Write, path::PathBuf};

    use zip::{AesMode, ZipWriter, unstable::write::FileOptionsExt, write::SimpleFileOptions};

    use super::*;
    use crate::{error::pack_failure, writer::ArchiveWriter};

    struct TempArchive {
        path: PathBuf,
    }

    impl TempArchive {
        fn new(label: &str) -> Self {
            use std::sync::atomic::{AtomicU64, Ordering};

            static COUNTER: AtomicU64 = AtomicU64::new(0);
            let unique = format!(
                "keyguard-zip-reader-{}-{}-{}.zip",
                label,
                std::process::id(),
                COUNTER.fetch_add(1, Ordering::Relaxed),
            );
            Self {
                path: std::env::temp_dir().join(unique),
            }
        }

        fn as_str(&self) -> &str {
            self.path.to_str().expect("the temp path must be UTF-8")
        }

        fn write(&self, password: Option<&str>, entries: &[(&str, &[u8])]) {
            let mut writer = ArchiveWriter::open(self.as_str(), password).expect("open must work");
            for (name, body) in entries {
                writer.begin_entry(name).expect("begin must succeed");
                writer.write(body).expect("write must succeed");
                writer.end_entry().expect("end must succeed");
            }
            writer.finish().expect("finish must succeed");
        }

        fn read(&self, password: Option<&str>) -> ArchiveReader {
            ArchiveReader::open(self.as_str(), password).expect("the archive must open")
        }
    }

    impl Drop for TempArchive {
        fn drop(&mut self) {
            let _ = fs::remove_file(&self.path);
        }
    }

    fn drain(reader: &mut ArchiveReader) -> Vec<(String, Vec<u8>)> {
        let mut entries = Vec::new();
        let mut name_buf = [0_u8; 512];
        while let Some(name_len) = reader.next_entry(&mut name_buf).expect("listing must work") {
            let name = String::from_utf8(name_buf[..name_len].to_vec()).expect("UTF-8 name");
            let mut content = Vec::new();
            let mut chunk = [0_u8; 64];
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

    /// A payload deflate cannot shrink, so a corrupted entry stays corrupted.
    fn incompressible(len: usize) -> Vec<u8> {
        let mut state = 0x2545_F491_4F6C_DD1D_u64;
        (0..len)
            .map(|_| {
                state ^= state << 13;
                state ^= state >> 7;
                state ^= state << 17;
                (state >> 33) as u8
            })
            .collect()
    }

    #[test]
    fn a_plain_archive_round_trips_every_entry_in_order() {
        let temp = TempArchive::new("plain");
        temp.write(
            None,
            &[
                ("hello.txt", b"hello world"),
                ("nested/dir/data.bin", &[7_u8; 1024]),
                ("empty.bin", b""),
            ],
        );

        let mut reader = temp.read(None);
        let entries = drain(&mut reader);
        assert_eq!(
            entries,
            vec![
                ("hello.txt".to_owned(), b"hello world".to_vec()),
                ("nested/dir/data.bin".to_owned(), vec![7_u8; 1024]),
                ("empty.bin".to_owned(), Vec::new()),
            ]
        );
        let mut name_buf = [0_u8; 512];
        assert_eq!(reader.next_entry(&mut name_buf), Ok(None));
        reader.close().expect("close must succeed");
    }

    #[test]
    fn an_encrypted_archive_round_trips_with_its_password() {
        let temp = TempArchive::new("aes");
        temp.write(
            Some("correct horse"),
            &[("secret.txt", b"vault"), ("empty.bin", b"")],
        );

        let mut reader = temp.read(Some("correct horse"));
        let entries = drain(&mut reader);
        assert_eq!(
            entries,
            vec![
                ("secret.txt".to_owned(), b"vault".to_vec()),
                ("empty.bin".to_owned(), Vec::new()),
            ]
        );
    }

    #[test]
    fn a_large_entry_streams_in_small_chunks() {
        const SIZE: usize = 300 * 1024;
        let payload = incompressible(SIZE);
        let temp = TempArchive::new("large");
        temp.write(Some("hunter2"), &[("big.bin", &payload)]);

        let mut reader = temp.read(Some("hunter2"));
        let mut name_buf = [0_u8; 64];
        assert_eq!(reader.next_entry(&mut name_buf), Ok(Some(7)));
        assert_eq!(&name_buf[..7], b"big.bin");

        let mut content = Vec::with_capacity(SIZE);
        let mut chunk = [0_u8; 4096];
        let mut calls = 0_usize;
        loop {
            let read = reader.read(&mut chunk).expect("reading must work");
            if read == 0 {
                break;
            }
            calls += 1;
            content.extend_from_slice(&chunk[..read]);
        }
        assert_eq!(content, payload);
        assert!(calls > 1, "a 300 KiB entry must not arrive in one chunk");
    }

    #[test]
    fn a_wrong_password_is_reported_when_the_entry_opens() {
        let temp = TempArchive::new("wrong-password");
        temp.write(Some("correct horse"), &[("secret.txt", b"vault")]);

        let mut reader = temp.read(Some("battery staple"));
        let mut name_buf = [0_u8; 64];
        assert_eq!(
            reader.next_entry(&mut name_buf),
            Err(pack_bridge_error(BridgeError::WrongPassword))
        );
        assert_eq!(
            reader.read(&mut [0_u8; 8]),
            Err(pack_bridge_error(BridgeError::InvalidState))
        );
    }

    #[test]
    fn a_missing_password_is_reported_like_a_wrong_one() {
        let temp = TempArchive::new("no-password");
        temp.write(Some("correct horse"), &[("secret.txt", b"vault")]);

        let mut reader = temp.read(None);
        let mut name_buf = [0_u8; 64];
        assert_eq!(
            reader.next_entry(&mut name_buf),
            Err(pack_bridge_error(BridgeError::WrongPassword))
        );
    }

    #[test]
    fn a_password_on_a_plain_archive_requires_encryption() {
        let temp = TempArchive::new("spurious-password");
        temp.write(None, &[("hello.txt", b"hello world")]);

        let mut reader = temp.read(Some("unused"));
        assert_encryption_rejected(&mut reader);
    }

    fn assert_encryption_rejected(reader: &mut ArchiveReader) {
        for _ in 0..2 {
            assert_eq!(
                reader.next_entry(&mut [0_u8; 64]),
                Err(pack_bridge_error(BridgeError::EncryptionRequired))
            );
            assert_eq!(
                reader.read(&mut [0_u8; 8]),
                Err(pack_bridge_error(BridgeError::InvalidState))
            );
        }
    }

    #[test]
    fn an_empty_password_keeps_plain_archives_readable() {
        let temp = TempArchive::new("empty-password");
        temp.write(None, &[("hello.txt", b"hello world")]);
        assert_eq!(
            drain(&mut temp.read(Some(""))),
            vec![("hello.txt".to_owned(), b"hello world".to_vec())]
        );
    }

    #[test]
    fn a_password_rejects_zipcrypto_even_with_the_correct_password() {
        let temp = TempArchive::new("zipcrypto");
        let mut writer = ZipWriter::new(File::create(&temp.path).expect("create"));
        let options = SimpleFileOptions::default()
            .with_deprecated_encryption(b"password")
            .expect("ZipCrypto options");
        writer.start_file("index.json", options).expect("entry");
        writer.write_all(b"{}").expect("write");
        writer.finish().expect("finish");
        assert_encryption_rejected(&mut temp.read(Some("password")));
    }

    #[test]
    fn mixed_archives_cannot_skip_an_unencrypted_entry() {
        for encrypted_first in [false, true] {
            let temp = TempArchive::new("mixed");
            let mut writer = ZipWriter::new(File::create(&temp.path).expect("create"));
            for (index, encrypted) in [encrypted_first, !encrypted_first].into_iter().enumerate() {
                let options = SimpleFileOptions::default();
                let options = if encrypted {
                    options.with_aes_encryption(AesMode::Aes256, "password")
                } else {
                    options
                };
                writer
                    .start_file(format!("{index}.json"), options)
                    .expect("entry");
                writer.write_all(b"{}").expect("write");
            }
            writer.finish().expect("finish");
            let mut reader = temp.read(Some("password"));
            if encrypted_first {
                assert_eq!(reader.next_entry(&mut [0_u8; 64]), Ok(Some(6)));
                let mut bytes = [0_u8; 8];
                assert_eq!(reader.read(&mut bytes), Ok(2));
                assert_eq!(reader.read(&mut bytes), Ok(0));
            }
            assert_encryption_rejected(&mut reader);
        }
    }

    #[test]
    fn a_short_name_buffer_leaves_the_reader_where_it_was() {
        let temp = TempArchive::new("short-buffer");
        temp.write(None, &[("hello.txt", b"first"), ("b.txt", b"second")]);

        let mut reader = temp.read(None);
        let mut small = [0_u8; 4];
        assert_eq!(
            reader.next_entry(&mut small),
            Err(pack_bridge_error(BridgeError::BufferTooSmall))
        );
        assert_eq!(
            reader.next_entry(&mut small),
            Err(pack_bridge_error(BridgeError::BufferTooSmall)),
            "a failed listing must not consume the entry"
        );
        let mut name_buf = [0_u8; 64];
        assert_eq!(reader.next_entry(&mut name_buf), Ok(Some(9)));
        assert_eq!(&name_buf[..9], b"hello.txt");
        let mut body = [0_u8; 16];
        let read = reader.read(&mut body).expect("reading must work");
        assert_eq!(&body[..read], b"first");
    }

    #[test]
    fn an_empty_name_buffer_is_a_buffer_too_small() {
        let temp = TempArchive::new("empty-buffer");
        temp.write(None, &[("a.txt", b"body")]);

        let mut reader = temp.read(None);
        assert_eq!(
            reader.next_entry(&mut []),
            Err(pack_bridge_error(BridgeError::BufferTooSmall))
        );
    }

    #[test]
    fn an_oversized_entry_name_is_reported_as_name_too_long() {
        use zip::{ZipWriter, write::SimpleFileOptions};

        let temp = TempArchive::new("long-name");
        let file = File::create(&temp.path).expect("the file must be creatable");
        let mut writer = ZipWriter::new(file);
        let name = "a".repeat(MAX_ENTRY_NAME_BYTES + 1);
        writer
            .start_file(&name, SimpleFileOptions::default())
            .expect("a long name must be writable directly");
        writer.finish().expect("finish must succeed");

        let mut reader = temp.read(None);
        let mut name_buf = vec![0_u8; MAX_ENTRY_NAME_BYTES * 2];
        assert_eq!(
            reader.next_entry(&mut name_buf),
            Err(pack_bridge_error(BridgeError::NameTooLong)),
            "the limit must be checked before the caller's capacity"
        );
    }

    #[test]
    fn reading_without_a_current_entry_is_an_invalid_state() {
        let temp = TempArchive::new("no-entry");
        temp.write(None, &[("a.txt", b"body")]);

        let mut reader = temp.read(None);
        assert_eq!(
            reader.read(&mut [0_u8; 8]),
            Err(pack_bridge_error(BridgeError::InvalidState))
        );
        let mut name_buf = [0_u8; 64];
        assert_eq!(reader.next_entry(&mut name_buf), Ok(Some(5)));
        assert_eq!(reader.read(&mut []), Ok(0));
        assert_eq!(reader.next_entry(&mut name_buf), Ok(None));
        assert_eq!(
            reader.read(&mut [0_u8; 8]),
            Err(pack_bridge_error(BridgeError::InvalidState))
        );
    }

    #[test]
    fn an_unread_entry_is_skipped_rather_than_read() {
        let temp = TempArchive::new("skip");
        let payload = incompressible(64 * 1024);
        temp.write(None, &[("big.bin", &payload), ("small.txt", b"tail")]);

        let mut reader = temp.read(None);
        let mut name_buf = [0_u8; 64];
        assert_eq!(reader.next_entry(&mut name_buf), Ok(Some(7)));
        let read = reader.read(&mut [0_u8; 8]).expect("reading must work");
        assert!(read > 0, "the first entry must produce bytes");
        assert_eq!(reader.next_entry(&mut name_buf), Ok(Some(9)));
        assert_eq!(&name_buf[..9], b"small.txt");
        let mut body = [0_u8; 16];
        let read = reader.read(&mut body).expect("reading must work");
        assert_eq!(&body[..read], b"tail");
    }

    #[test]
    fn a_corrupted_encrypted_entry_fails_the_integrity_check() {
        use std::io::{Seek, SeekFrom, Write};

        let temp = TempArchive::new("corrupt");
        temp.write(Some("hunter2"), &[("secret.bin", &incompressible(1024))]);

        // The last data byte of an AE-2 entry belongs to its HMAC, so flipping
        // it fails only the integrity check at the end of the entry.
        let (data_start, compressed_size) = {
            let file = File::open(&temp.path).expect("the archive must exist");
            let mut archive = ZipArchive::new(file).expect("the archive must be readable");
            let entry = archive
                .by_index_decrypt(0, b"hunter2")
                .expect("the entry must decrypt");
            (
                entry.data_start().expect("the entry must be located"),
                entry.compressed_size(),
            )
        };
        let last = data_start + compressed_size - 1;
        let mut file = fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(&temp.path)
            .expect("the archive must be writable");
        file.seek(SeekFrom::Start(last)).expect("seek must work");
        let mut byte = [0_u8; 1];
        file.read_exact(&mut byte).expect("read must work");
        file.seek(SeekFrom::Start(last)).expect("seek must work");
        file.write_all(&[byte[0] ^ 0xff]).expect("write must work");
        file.sync_all().expect("sync must work");
        drop(file);

        let mut reader = temp.read(Some("hunter2"));
        let mut name_buf = [0_u8; 64];
        assert_eq!(reader.next_entry(&mut name_buf), Ok(Some(10)));

        let mut chunk = [0_u8; 4096];
        let failure = loop {
            match reader.read(&mut chunk) {
                Ok(0) => panic!("the corrupted entry must not read cleanly"),
                Ok(_) => continue,
                Err(packed) => break packed,
            }
        };
        assert_eq!(
            failure,
            pack_failure(
                Operation::Read,
                FailureKind::InvalidInput,
                crate::error::ErrorDomain::Bridge,
                crate::error::BRIDGE_ERROR_ARCHIVE,
            )
        );
    }

    #[test]
    #[cfg(any(unix, windows))]
    fn opening_a_missing_archive_reports_the_reader_open_operation() {
        let missing = TempArchive::new("missing");
        let packed = ArchiveReader::open(missing.as_str(), None)
            .err()
            .expect("open must fail");
        #[cfg(unix)]
        let domain = crate::error::ErrorDomain::PosixErrno;
        #[cfg(windows)]
        let domain = crate::error::ErrorDomain::Win32LastError;
        assert_eq!(
            packed,
            pack_failure(Operation::ReaderOpen, FailureKind::NotFound, domain, 2)
        );
    }

    #[test]
    fn opening_a_file_that_is_not_an_archive_reports_a_structural_failure() {
        let temp = TempArchive::new("not-a-zip");
        fs::write(&temp.path, b"definitely not a zip archive").expect("the file must be writable");
        let packed = ArchiveReader::open(temp.as_str(), None)
            .err()
            .expect("open must fail");
        assert_eq!(packed, pack_bridge_error(BridgeError::Archive));
    }
}
