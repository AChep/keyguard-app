//! Native ZIP archive reading and writing shared by the Keyguard C bridge.
//!
//! Handle-based streaming API: `open`, then `begin_entry` / `write` /
//! `end_entry` per file, then `finish` or `abort`. The reader mirrors it with
//! `reader_open`, `reader_next_entry` / `reader_read`, `reader_close`. Every
//! failure is an already-packed `i64`; no path, entry name, or password ever
//! travels back out. The panic boundary belongs to each bridge, not here.

pub mod error;
pub mod reader;
pub mod registry;
pub mod writer;

use std::sync::Once;

pub use error::{
    BRIDGE_ERROR_ARCHIVE, BRIDGE_ERROR_BUFFER_TOO_SMALL, BRIDGE_ERROR_INTERNAL,
    BRIDGE_ERROR_INVALID_ARGUMENT, BRIDGE_ERROR_INVALID_HANDLE, BRIDGE_ERROR_INVALID_STATE,
    BRIDGE_ERROR_NAME_TOO_LONG, BRIDGE_ERROR_PANIC, BRIDGE_ERROR_UNSUPPORTED_ENTRY,
    BRIDGE_ERROR_WRONG_PASSWORD, BridgeError, ErrorDomain, FailureKind, Operation,
    pack_archive_error, pack_bridge_error, pack_bridge_internal, pack_bridge_invalid_argument,
    pack_bridge_invalid_handle, pack_bridge_panic, pack_failure, pack_io_error, pack_zip_error,
};
pub use reader::ArchiveReader;
pub use writer::ArchiveWriter;

/// Version of the direct native function ABI.
pub const ABI_VERSION: u32 = 1;

/// Largest accepted entry name, in UTF-8 bytes (the customary path budget,
/// not ZIP's 16-bit limit).
pub const MAX_ENTRY_NAME_BYTES: usize = 4096;

/// Largest accepted destination path, in UTF-8 bytes.
pub const MAX_PATH_BYTES: usize = 4096;

static PANIC_HOOK: Once = Once::new();

/// Installs a process-wide panic hook that prints nothing.
///
/// The default hook prints the payload before `catch_unwind` runs, and a
/// payload may carry a path or a fragment of vault data.
pub fn install_redacting_panic_hook() {
    PANIC_HOOK.call_once(|| std::panic::set_hook(Box::new(|_| {})));
}

/// Creates or truncates an archive at `path` and returns its handle.
///
/// `password` selects AES-256 (WinZip AE-2) for every entry; `None` writes an
/// unencrypted archive.
///
/// # Errors
///
/// Returns a packed [`Operation::Open`] failure.
pub fn open(path: &str, password: Option<&str>) -> Result<u64, i64> {
    let writer = ArchiveWriter::open(path, password)?;
    Ok(registry::insert_writer(writer))
}

/// Starts an entry named `name` in the archive behind `handle`.
///
/// # Errors
///
/// Returns a packed bridge or [`Operation::BeginEntry`] failure.
pub fn begin_entry(handle: u64, name: &str) -> Result<(), i64> {
    registry::with_writer_mut(handle, |writer| writer.begin_entry(name))
}

/// Appends `bytes` to the open entry of the archive behind `handle`.
///
/// # Errors
///
/// Returns a packed bridge or [`Operation::Write`] failure.
pub fn write(handle: u64, bytes: &[u8]) -> Result<(), i64> {
    registry::with_writer_mut(handle, |writer| writer.write(bytes))
}

/// Closes the open entry of the archive behind `handle`.
///
/// # Errors
///
/// Returns a packed bridge failure.
pub fn end_entry(handle: u64) -> Result<(), i64> {
    registry::with_writer_mut(handle, |writer| writer.end_entry())
}

/// Completes the archive behind `handle` and flushes it to stable storage.
/// The handle is consumed whatever the outcome.
///
/// # Errors
///
/// Returns a packed bridge or [`Operation::Finish`] failure.
pub fn finish(handle: u64) -> Result<(), i64> {
    registry::take_writer(handle)?.finish()
}

/// Discards the archive behind `handle` and removes its file. The handle is
/// consumed whatever the outcome.
///
/// # Errors
///
/// Returns a packed bridge or [`Operation::Abort`] failure.
pub fn abort(handle: u64) -> Result<(), i64> {
    registry::take_writer(handle)?.abort()
}

/// Opens the existing archive at `path` for reading and returns its handle.
///
/// # Errors
///
/// Returns a packed [`Operation::ReaderOpen`] failure.
pub fn reader_open(path: &str, password: Option<&str>) -> Result<u64, i64> {
    let reader = ArchiveReader::open(path, password)?;
    Ok(registry::insert_reader(reader))
}

/// Advances the reader behind `handle` to its next entry, copying the name
/// into `name_buf`. Returns the name's byte length, or [`None`] at the end.
///
/// # Errors
///
/// Returns a packed bridge or [`Operation::NextEntry`] failure.
pub fn reader_next_entry(handle: u64, name_buf: &mut [u8]) -> Result<Option<usize>, i64> {
    registry::with_reader_mut(handle, |reader| reader.next_entry(name_buf))
}

/// Reads up to `buf.len()` bytes of the reader's current entry.
///
/// # Errors
///
/// Returns a packed bridge or [`Operation::Read`] failure.
pub fn reader_read(handle: u64, buf: &mut [u8]) -> Result<usize, i64> {
    registry::with_reader_mut(handle, |reader| reader.read(buf))
}

/// Closes the reader behind `handle`, leaving the file in place. The handle
/// is consumed whatever the outcome.
///
/// # Errors
///
/// Returns a packed [`BridgeError::InvalidHandle`].
pub fn reader_close(handle: u64) -> Result<(), i64> {
    registry::take_reader(handle)?.close()
}

#[cfg(test)]
mod tests {
    use std::{fs, io::Read, path::PathBuf};

    use zip::{CompressionMethod, ZipArchive};

    use super::*;

    fn temp_path(label: &str) -> PathBuf {
        use std::sync::atomic::{AtomicU64, Ordering};

        static COUNTER: AtomicU64 = AtomicU64::new(0);
        std::env::temp_dir().join(format!(
            "keyguard-zip-lib-{}-{}-{}.zip",
            label,
            std::process::id(),
            COUNTER.fetch_add(1, Ordering::Relaxed),
        ))
    }

    #[test]
    fn an_archive_round_trips_through_the_handle_api() {
        let path = temp_path("handles");
        let target = path.to_str().expect("UTF-8 path");
        let handle = open(target, None).expect("open must succeed");
        assert!(handle >= 1, "handles must be positive");
        begin_entry(handle, "attachments/1/file.bin").expect("begin must succeed");
        write(handle, b"payload").expect("write must succeed");
        end_entry(handle).expect("end must succeed");
        finish(handle).expect("finish must succeed");

        let file = fs::File::open(&path).expect("the archive must exist");
        let mut archive = ZipArchive::new(file).expect("the archive must be readable");
        let mut entry = archive
            .by_name("attachments/1/file.bin")
            .expect("the entry must exist");
        assert_eq!(entry.compression(), CompressionMethod::Deflated);
        let mut content = String::new();
        entry.read_to_string(&mut content).expect("read must work");
        assert_eq!(content, "payload");
        drop(entry);
        drop(archive);
        fs::remove_file(&path).expect("cleanup must succeed");
    }

    #[test]
    fn handles_increase_and_are_never_reused() {
        let first_path = temp_path("ids-a");
        let second_path = temp_path("ids-b");
        let first = open(first_path.to_str().expect("UTF-8 path"), None).expect("open must work");
        let second = open(second_path.to_str().expect("UTF-8 path"), None).expect("open must work");
        assert!(second > first, "{second} must follow {first}");
        abort(first).expect("abort must succeed");
        abort(second).expect("abort must succeed");

        let third_path = temp_path("ids-c");
        let third = open(third_path.to_str().expect("UTF-8 path"), None).expect("open must work");
        assert!(third > second, "a consumed handle must not be reused");
        abort(third).expect("abort must succeed");
    }

    #[test]
    fn every_call_on_an_unknown_handle_is_an_invalid_handle() {
        let unknown = u64::MAX;
        let expected = pack_bridge_invalid_handle();
        assert_eq!(begin_entry(unknown, "a.txt"), Err(expected));
        assert_eq!(write(unknown, b"a"), Err(expected));
        assert_eq!(end_entry(unknown), Err(expected));
        assert_eq!(finish(unknown), Err(expected));
        assert_eq!(abort(unknown), Err(expected));
        assert_eq!(reader_next_entry(unknown, &mut [0_u8; 64]), Err(expected));
        assert_eq!(reader_read(unknown, &mut [0_u8; 64]), Err(expected));
        assert_eq!(reader_close(unknown), Err(expected));
    }

    #[test]
    fn an_archive_round_trips_through_the_reader_handle_api() {
        let path = temp_path("reader");
        let target = path.to_str().expect("UTF-8 path");
        let handle = open(target, Some("hunter2")).expect("open must succeed");
        begin_entry(handle, "attachments/1/file.bin").expect("begin must succeed");
        write(handle, b"payload").expect("write must succeed");
        end_entry(handle).expect("end must succeed");
        begin_entry(handle, "empty.bin").expect("begin must succeed");
        end_entry(handle).expect("end must succeed");
        finish(handle).expect("finish must succeed");

        let reader = reader_open(target, Some("hunter2")).expect("the archive must open");
        assert!(reader >= 1, "handles must be positive");
        assert!(reader > handle, "readers share the writers' counter");

        let mut name_buf = [0_u8; 128];
        assert_eq!(reader_next_entry(reader, &mut name_buf), Ok(Some(22)));
        assert_eq!(&name_buf[..22], b"attachments/1/file.bin");
        let mut body = [0_u8; 32];
        let read = reader_read(reader, &mut body).expect("read must succeed");
        assert_eq!(&body[..read], b"payload");
        assert_eq!(reader_read(reader, &mut body), Ok(0));

        assert_eq!(reader_next_entry(reader, &mut name_buf), Ok(Some(9)));
        assert_eq!(&name_buf[..9], b"empty.bin");
        assert_eq!(reader_read(reader, &mut body), Ok(0));

        assert_eq!(reader_next_entry(reader, &mut name_buf), Ok(None));
        reader_close(reader).expect("close must succeed");
        fs::remove_file(&path).expect("cleanup must succeed");
    }

    #[test]
    fn close_consumes_the_reader_handle() {
        let path = temp_path("reader-consume");
        let target = path.to_str().expect("UTF-8 path");
        let handle = open(target, None).expect("open must succeed");
        finish(handle).expect("finish must succeed");

        let reader = reader_open(target, None).expect("the archive must open");
        reader_close(reader).expect("close must succeed");
        let expected = pack_bridge_invalid_handle();
        assert_eq!(reader_close(reader), Err(expected));
        assert_eq!(reader_read(reader, &mut [0_u8; 8]), Err(expected));
        assert_eq!(reader_next_entry(reader, &mut [0_u8; 8]), Err(expected));
        fs::remove_file(&path).expect("cleanup must succeed");
    }

    #[test]
    fn a_writer_handle_is_never_mistaken_for_a_reader_handle() {
        let path = temp_path("kinds");
        let target = path.to_str().expect("UTF-8 path");
        let writer = open(target, None).expect("open must succeed");
        finish(writer).expect("finish must succeed");

        let writer = open(&format!("{target}.2"), None).expect("open must succeed");
        let reader = reader_open(target, None).expect("the archive must open");
        let expected = pack_bridge_invalid_handle();

        assert_eq!(reader_read(writer, &mut [0_u8; 8]), Err(expected));
        assert_eq!(reader_next_entry(writer, &mut [0_u8; 8]), Err(expected));
        assert_eq!(reader_close(writer), Err(expected));
        assert_eq!(begin_entry(reader, "a.txt"), Err(expected));
        assert_eq!(write(reader, b"a"), Err(expected));
        assert_eq!(end_entry(reader), Err(expected));
        assert_eq!(finish(reader), Err(expected));
        assert_eq!(abort(reader), Err(expected));

        // Neither handle was consumed by the rejected calls.
        reader_close(reader).expect("close must succeed");
        abort(writer).expect("abort must succeed");
        fs::remove_file(&path).expect("cleanup must succeed");
    }

    #[test]
    fn the_reader_reports_a_missing_archive() {
        let missing = std::env::temp_dir().join("keyguard-zip-lib-missing.zip");
        assert!(
            reader_open(missing.to_str().expect("UTF-8 path"), None).is_err(),
            "a missing archive must not open"
        );
    }

    #[test]
    fn finish_and_abort_consume_the_handle() {
        let path = temp_path("consume");
        let handle = open(path.to_str().expect("UTF-8 path"), None).expect("open must succeed");
        finish(handle).expect("finish must succeed");
        assert_eq!(finish(handle), Err(pack_bridge_invalid_handle()));
        assert_eq!(abort(handle), Err(pack_bridge_invalid_handle()));
        fs::remove_file(&path).expect("cleanup must succeed");

        let path = temp_path("consume-abort");
        let handle = open(path.to_str().expect("UTF-8 path"), None).expect("open must succeed");
        abort(handle).expect("abort must succeed");
        assert!(!path.exists(), "abort must remove the file");
        assert_eq!(abort(handle), Err(pack_bridge_invalid_handle()));
    }

    #[test]
    fn an_encrypted_archive_is_written_through_the_handle_api() {
        let path = temp_path("aes");
        let handle =
            open(path.to_str().expect("UTF-8 path"), Some("hunter2")).expect("open must succeed");
        begin_entry(handle, "secret.txt").expect("begin must succeed");
        write(handle, b"vault").expect("write must succeed");
        end_entry(handle).expect("end must succeed");
        finish(handle).expect("finish must succeed");

        let file = fs::File::open(&path).expect("the archive must exist");
        let mut archive = ZipArchive::new(file).expect("the archive must be readable");
        let mut entry = archive
            .by_name_decrypt("secret.txt", b"hunter2")
            .expect("the entry must decrypt");
        assert!(entry.encrypted());
        let mut content = String::new();
        entry.read_to_string(&mut content).expect("read must work");
        assert_eq!(content, "vault");
        drop(entry);
        drop(archive);
        fs::remove_file(&path).expect("cleanup must succeed");
    }

    #[test]
    fn the_state_machine_rejects_calls_outside_an_entry() {
        let path = temp_path("state");
        let handle = open(path.to_str().expect("UTF-8 path"), None).expect("open must succeed");
        let invalid_state = pack_bridge_error(BridgeError::InvalidState);
        assert_eq!(write(handle, b"stray"), Err(invalid_state));
        assert_eq!(end_entry(handle), Err(invalid_state));
        begin_entry(handle, "a.txt").expect("begin must succeed");
        assert_eq!(begin_entry(handle, "b.txt"), Err(invalid_state));
        assert_eq!(finish(handle), Err(invalid_state));
        // A failed finish still consumed the handle.
        assert_eq!(end_entry(handle), Err(pack_bridge_invalid_handle()));
        fs::remove_file(&path).expect("cleanup must succeed");
    }

    #[test]
    fn an_oversized_entry_name_is_reported_as_name_too_long() {
        let path = temp_path("name");
        let handle = open(path.to_str().expect("UTF-8 path"), None).expect("open must succeed");
        let name = "a".repeat(MAX_ENTRY_NAME_BYTES + 1);
        assert_eq!(
            begin_entry(handle, &name),
            Err(pack_bridge_error(BridgeError::NameTooLong))
        );
        abort(handle).expect("abort must succeed");
    }
}
