//! Streaming ZIP archive writer behind the native bridge. Nothing is buffered
//! beyond the `zip` crate's deflate state, so entries of any size stream to
//! disk.

use std::{
    fs::{self, File},
    io::Write,
    path::PathBuf,
};

use zeroize::Zeroizing;
use zip::{
    AesMode, CompressionMethod, ZipWriter,
    write::{FileOptions, SimpleFileOptions},
};

use crate::{
    MAX_ENTRY_NAME_BYTES,
    error::{BridgeError, Operation, pack_bridge_error, pack_io_error, pack_zip_error},
};

/// A ZIP archive being written to a freshly created file. The path is kept
/// only so [`ArchiveWriter::abort`] can remove a half-written archive.
pub struct ArchiveWriter {
    writer: ZipWriter<File>,
    path: PathBuf,
    password: Option<Zeroizing<String>>,
    in_entry: bool,
}

impl ArchiveWriter {
    /// Creates or truncates the archive at `path`. A `password` encrypts every
    /// entry with AES-256 (WinZip AE-2); names and the central directory stay
    /// in the clear, as the format requires.
    ///
    /// # Errors
    ///
    /// Returns a packed [`Operation::Open`] failure.
    pub fn open(path: &str, password: Option<&str>) -> Result<Self, i64> {
        let file = File::create(path).map_err(|error| pack_io_error(Operation::Open, &error))?;
        Ok(Self {
            writer: ZipWriter::new(file),
            path: PathBuf::from(path),
            password: password.map(|password| Zeroizing::new(password.to_owned())),
            in_entry: false,
        })
    }

    /// Starts a deflated, zip64-capable entry named `name`.
    ///
    /// # Errors
    ///
    /// Returns [`BridgeError::InvalidState`], [`BridgeError::NameTooLong`], or
    /// a packed [`Operation::BeginEntry`] failure.
    pub fn begin_entry(&mut self, name: &str) -> Result<(), i64> {
        if self.in_entry {
            return Err(pack_bridge_error(BridgeError::InvalidState));
        }
        if name.len() > MAX_ENTRY_NAME_BYTES {
            return Err(pack_bridge_error(BridgeError::NameTooLong));
        }
        // Entry sizes are unknown until closed; without unconditional zip64
        // fields, streaming past 4 GiB would corrupt the archive.
        let options: SimpleFileOptions = SimpleFileOptions::default()
            .compression_method(CompressionMethod::Deflated)
            .large_file(true);
        let options: FileOptions<'_, ()> = match self.password.as_deref() {
            Some(password) => options.with_aes_encryption(AesMode::Aes256, password),
            None => options,
        };
        self.writer
            .start_file(name, options)
            .map_err(|error| pack_zip_error(Operation::BeginEntry, &error))?;
        self.in_entry = true;
        Ok(())
    }

    /// Appends `bytes` to the open entry.
    ///
    /// # Errors
    ///
    /// Returns [`BridgeError::InvalidState`] or a packed [`Operation::Write`]
    /// failure.
    pub fn write(&mut self, bytes: &[u8]) -> Result<(), i64> {
        if !self.in_entry {
            return Err(pack_bridge_error(BridgeError::InvalidState));
        }
        self.writer
            .write_all(bytes)
            .map_err(|error| pack_io_error(Operation::Write, &error))
    }

    /// Closes the current entry. The `zip` crate finalizes it lazily, so this
    /// only leaves the entry state.
    ///
    /// # Errors
    ///
    /// Returns [`BridgeError::InvalidState`] when no entry is open.
    pub fn end_entry(&mut self) -> Result<(), i64> {
        if !self.in_entry {
            return Err(pack_bridge_error(BridgeError::InvalidState));
        }
        self.in_entry = false;
        Ok(())
    }

    /// Writes the central directory and flushes the archive to stable storage.
    /// The writer is consumed either way; a failed finish leaves the file.
    ///
    /// # Errors
    ///
    /// Returns [`BridgeError::InvalidState`] when an entry is still open, or a
    /// packed [`Operation::Finish`] failure.
    pub fn finish(self) -> Result<(), i64> {
        if self.in_entry {
            return Err(pack_bridge_error(BridgeError::InvalidState));
        }
        let file = self
            .writer
            .finish()
            .map_err(|error| pack_zip_error(Operation::Finish, &error))?;
        file.sync_all()
            .map_err(|error| pack_io_error(Operation::Finish, &error))
    }

    /// Drops the archive and removes the file it was writing. A file that is
    /// already gone is not a failure.
    ///
    /// # Errors
    ///
    /// Returns a packed [`Operation::Abort`] failure when the file cannot be
    /// removed.
    pub fn abort(self) -> Result<(), i64> {
        let Self { writer, path, .. } = self;
        drop(writer);
        match fs::remove_file(&path) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(pack_io_error(Operation::Abort, &error)),
        }
    }
}

#[cfg(test)]
mod tests {
    use std::io::Read;

    use zip::ZipArchive;

    use super::*;
    use crate::error::{ErrorDomain, FailureKind, pack_failure};

    struct TempArchive {
        path: PathBuf,
    }

    impl TempArchive {
        fn new(label: &str) -> Self {
            use std::sync::atomic::{AtomicU64, Ordering};

            static COUNTER: AtomicU64 = AtomicU64::new(0);
            let unique = format!(
                "keyguard-zip-{}-{}-{}.zip",
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

        fn open(&self) -> ZipArchive<File> {
            let file = File::open(&self.path).expect("the archive must exist");
            ZipArchive::new(file).expect("the archive must be readable")
        }
    }

    impl Drop for TempArchive {
        fn drop(&mut self) {
            let _ = fs::remove_file(&self.path);
        }
    }

    fn invalid_state() -> i64 {
        pack_bridge_error(BridgeError::InvalidState)
    }

    #[test]
    fn an_unencrypted_archive_round_trips_its_entry() {
        let temp = TempArchive::new("plain");
        let mut writer = ArchiveWriter::open(temp.as_str(), None).expect("open must succeed");
        writer.begin_entry("notes.txt").expect("begin must succeed");
        writer.write(b"hello ").expect("write must succeed");
        writer.write(b"world").expect("write must succeed");
        writer.end_entry().expect("end must succeed");
        writer.finish().expect("finish must succeed");

        let mut archive = temp.open();
        assert_eq!(archive.len(), 1);
        let mut entry = archive.by_name("notes.txt").expect("the entry must exist");
        assert_eq!(entry.compression(), CompressionMethod::Deflated);
        assert!(!entry.encrypted());
        let mut content = String::new();
        entry.read_to_string(&mut content).expect("read must work");
        assert_eq!(content, "hello world");
    }

    #[test]
    fn an_encrypted_archive_needs_its_password() {
        let temp = TempArchive::new("aes");
        let mut writer =
            ArchiveWriter::open(temp.as_str(), Some("correct horse")).expect("open must succeed");
        writer.begin_entry("secret.txt").expect("begin must work");
        writer.write(b"vault").expect("write must succeed");
        writer.end_entry().expect("end must succeed");
        writer.finish().expect("finish must succeed");

        let mut archive = temp.open();
        {
            let entry = archive
                .by_name_decrypt("secret.txt", b"correct horse")
                .expect("the entry must decrypt");
            assert_eq!(entry.compression(), CompressionMethod::Deflated);
            assert!(entry.encrypted());
        }
        let mut entry = archive
            .by_name_decrypt("secret.txt", b"correct horse")
            .expect("the entry must decrypt");
        let mut content = String::new();
        entry.read_to_string(&mut content).expect("read must work");
        assert_eq!(content, "vault");
        drop(entry);

        assert!(
            archive.by_name_decrypt("secret.txt", b"wrong").is_err(),
            "a wrong password must not decrypt the entry"
        );
        assert!(
            archive.by_name("secret.txt").is_err(),
            "an encrypted entry must not open without a password"
        );
    }

    #[test]
    fn an_empty_entry_round_trips_with_no_bytes() {
        let temp = TempArchive::new("empty");
        let mut writer = ArchiveWriter::open(temp.as_str(), None).expect("open must succeed");
        writer.begin_entry("empty.bin").expect("begin must succeed");
        writer.end_entry().expect("end must succeed");
        writer.finish().expect("finish must succeed");

        let mut archive = temp.open();
        let mut entry = archive.by_name("empty.bin").expect("the entry must exist");
        let mut content = Vec::new();
        entry.read_to_end(&mut content).expect("read must work");
        assert!(content.is_empty());
    }

    #[test]
    fn nested_entry_names_keep_their_separators_and_contents() {
        let temp = TempArchive::new("nested");
        let names = [
            "attachments/1/file.bin",
            "attachments/2/file.bin",
            "manifest.json",
        ];
        let mut writer = ArchiveWriter::open(temp.as_str(), None).expect("open must succeed");
        for (index, name) in names.iter().enumerate() {
            writer.begin_entry(name).expect("begin must succeed");
            writer
                .write(format!("body {index}").as_bytes())
                .expect("write must succeed");
            writer.end_entry().expect("end must succeed");
        }
        writer.finish().expect("finish must succeed");

        let mut archive = temp.open();
        assert_eq!(archive.len(), names.len());
        for (index, name) in names.iter().enumerate() {
            let mut entry = archive.by_name(name).expect("the entry must exist");
            let mut content = String::new();
            entry.read_to_string(&mut content).expect("read must work");
            assert_eq!(content, format!("body {index}"));
        }
    }

    #[test]
    fn writing_outside_an_entry_is_an_invalid_state() {
        let temp = TempArchive::new("state-write");
        let mut writer = ArchiveWriter::open(temp.as_str(), None).expect("open must succeed");
        assert_eq!(writer.write(b"stray"), Err(invalid_state()));
        assert_eq!(writer.end_entry(), Err(invalid_state()));
        writer.begin_entry("a.txt").expect("begin must succeed");
        assert_eq!(writer.begin_entry("b.txt"), Err(invalid_state()));
        assert_eq!(writer.finish(), Err(invalid_state()));
    }

    #[test]
    fn an_oversized_entry_name_is_rejected_at_the_byte_limit() {
        let temp = TempArchive::new("name");
        let mut writer = ArchiveWriter::open(temp.as_str(), None).expect("open must succeed");
        let rejected = "a".repeat(MAX_ENTRY_NAME_BYTES + 1);
        assert_eq!(
            writer.begin_entry(&rejected),
            Err(pack_bridge_error(BridgeError::NameTooLong))
        );
        let accepted = "a".repeat(MAX_ENTRY_NAME_BYTES);
        writer
            .begin_entry(&accepted)
            .expect("a name at the limit must be accepted");
        writer.end_entry().expect("end must succeed");
        writer.finish().expect("finish must succeed");
    }

    #[test]
    fn opening_inside_a_missing_directory_reports_the_open_operation() {
        let missing = std::env::temp_dir().join("keyguard-zip-missing-dir/archive.zip");
        let packed = ArchiveWriter::open(missing.to_str().expect("UTF-8 path"), None)
            .err()
            .expect("open must fail");
        assert_eq!(
            packed,
            pack_failure(
                Operation::Open,
                FailureKind::NotFound,
                ErrorDomain::PosixErrno,
                2,
            )
        );
    }

    #[test]
    fn abort_removes_the_partially_written_archive() {
        let temp = TempArchive::new("abort");
        let mut writer = ArchiveWriter::open(temp.as_str(), None).expect("open must succeed");
        writer.begin_entry("partial.bin").expect("begin must work");
        writer.write(&[0_u8; 1024]).expect("write must succeed");
        assert!(temp.path.exists(), "the file must exist before the abort");
        writer.abort().expect("abort must succeed");
        assert!(!temp.path.exists(), "the file must be gone after the abort");
    }

    #[test]
    fn abort_tolerates_a_file_that_is_already_gone() {
        let temp = TempArchive::new("abort-missing");
        let writer = ArchiveWriter::open(temp.as_str(), None).expect("open must succeed");
        fs::remove_file(&temp.path).expect("the file must be removable");
        writer.abort().expect("a missing file must not fail");
    }
}
