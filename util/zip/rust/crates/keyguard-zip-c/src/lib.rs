//! Stable C ABI for Keyguard native ZIP archive reading and writing.
//!
//! Each function unmarshals its raw arguments, delegates to
//! [`keyguard_zip_core`], and returns the ABI v1 scalar described in
//! `include/keyguard_zip.h`. Panics never cross the boundary.

#![deny(unsafe_op_in_unsafe_fn)]

use std::{panic::AssertUnwindSafe, slice, str};

use keyguard_zip_core::{
    MAX_PATH_BYTES, pack_bridge_internal, pack_bridge_invalid_argument, pack_bridge_panic,
};

/// Runs `body` behind the panic boundary every entry point shares.
///
/// The hook is installed *inside* the boundary: `set_hook` panics on a
/// panicking thread and poisons the `Once`, and outside the boundary that
/// would escape the `extern "C"` frame and abort the process.
fn contained<R>(body: impl FnOnce() -> R) -> Result<R, i64> {
    std::panic::catch_unwind(AssertUnwindSafe(|| {
        // Tests keep the default hook so a caught assertion still prints.
        #[cfg(not(test))]
        keyguard_zip_core::install_redacting_panic_hook();
        body()
    }))
    .map_err(|_| pack_bridge_panic())
}

/// Returned by `keyguard_zip_reader_next_entry` past the last entry. The
/// failure layout keeps its reserved bits clear, so `-1` is never a failure.
const END_OF_ARCHIVE: i64 = -1;

fn unwrap(result: Result<Result<i64, i64>, i64>) -> i64 {
    match result.and_then(std::convert::identity) {
        Ok(value) | Err(value) => value,
    }
}

/// # Safety
///
/// A non-null `pointer` must be valid for `length` readable bytes for the
/// duration of the call.
unsafe fn bytes_from_raw<'a>(pointer: *const u8, length: usize) -> Result<&'a [u8], i64> {
    if length == 0 {
        return Ok(&[]);
    }
    if pointer.is_null() || length > isize::MAX as usize {
        return Err(pack_bridge_invalid_argument());
    }
    // SAFETY: Null and oversized inputs were rejected; the caller contract
    // guarantees `length` readable bytes.
    Ok(unsafe { slice::from_raw_parts(pointer, length) })
}

/// # Safety
///
/// A non-null `pointer` must be valid for `length` writable, unaliased bytes
/// for the duration of the call.
unsafe fn bytes_from_raw_mut<'a>(pointer: *mut u8, length: usize) -> Result<&'a mut [u8], i64> {
    if length == 0 {
        return Ok(&mut []);
    }
    if pointer.is_null() || length > isize::MAX as usize {
        return Err(pack_bridge_invalid_argument());
    }
    // SAFETY: Null and oversized inputs were rejected; the caller contract
    // guarantees `length` writable, unaliased bytes.
    Ok(unsafe { slice::from_raw_parts_mut(pointer, length) })
}

/// # Safety
///
/// As [`bytes_from_raw`].
unsafe fn string_from_raw<'a>(pointer: *const u8, length: usize) -> Result<&'a str, i64> {
    // SAFETY: Forwarded from this function's own contract.
    let bytes = unsafe { bytes_from_raw(pointer, length) }?;
    str::from_utf8(bytes).map_err(|_| pack_bridge_invalid_argument())
}

/// Returns the native function ABI version.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_zip_abi_version() -> u32 {
    contained(|| keyguard_zip_core::ABI_VERSION).unwrap_or(0)
}

/// Creates or truncates a ZIP archive and returns its handle (>= 1), or a
/// packed negative failure.
///
/// # Safety
///
/// Each pointer/length pair must describe readable bytes valid for the
/// duration of the call; a pointer may be null when its length is zero.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_zip_writer_open(
    path_ptr: *const u8,
    path_len: usize,
    password_ptr: *const u8,
    password_len: usize,
) -> i64 {
    unwrap(contained(|| {
        if path_len > MAX_PATH_BYTES {
            return Err(pack_bridge_invalid_argument());
        }
        // SAFETY: Forwarded from this function's own contract.
        let path = unsafe { string_from_raw(path_ptr, path_len) }?;
        // SAFETY: Forwarded from this function's own contract.
        let password = unsafe { string_from_raw(password_ptr, password_len) }?;
        let password = (password_len != 0).then_some(password);
        let handle = keyguard_zip_core::open(path, password)?;
        i64::try_from(handle).map_err(|_| pack_bridge_internal())
    }))
}

/// Starts an archive entry. Returns zero, or a packed negative failure.
///
/// # Safety
///
/// `name_ptr`/`name_len` must describe readable bytes valid for the duration
/// of the call; the pointer may be null when the length is zero.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_zip_writer_begin_entry(
    handle: u64,
    name_ptr: *const u8,
    name_len: usize,
) -> i64 {
    unwrap(contained(|| {
        // SAFETY: Forwarded from this function's own contract.
        let name = unsafe { string_from_raw(name_ptr, name_len) }?;
        keyguard_zip_core::begin_entry(handle, name)?;
        Ok(0)
    }))
}

/// Appends bytes to the current entry. Returns zero, or a packed negative
/// failure.
///
/// # Safety
///
/// `data_ptr`/`data_len` must describe readable bytes valid for the duration
/// of the call; the pointer may be null when the length is zero.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_zip_writer_write(
    handle: u64,
    data_ptr: *const u8,
    data_len: usize,
) -> i64 {
    unwrap(contained(|| {
        // SAFETY: Forwarded from this function's own contract.
        let data = unsafe { bytes_from_raw(data_ptr, data_len) }?;
        keyguard_zip_core::write(handle, data)?;
        Ok(0)
    }))
}

/// Closes the current entry. Returns zero, or a packed negative failure.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_zip_writer_end_entry(handle: u64) -> i64 {
    unwrap(contained(|| {
        keyguard_zip_core::end_entry(handle)?;
        Ok(0)
    }))
}

/// Completes the archive and flushes it to stable storage. Consumes the
/// handle on every result. Returns zero, or a packed negative failure.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_zip_writer_finish(handle: u64) -> i64 {
    unwrap(contained(|| {
        keyguard_zip_core::finish(handle)?;
        Ok(0)
    }))
}

/// Discards the archive and removes its file. Consumes the handle on every
/// result. Returns zero, or a packed negative failure.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_zip_writer_abort(handle: u64) -> i64 {
    unwrap(contained(|| {
        keyguard_zip_core::abort(handle)?;
        Ok(0)
    }))
}

/// Opens an existing ZIP archive for reading and returns its handle (>= 1),
/// or a packed negative failure.
///
/// # Safety
///
/// Each pointer/length pair must describe readable bytes valid for the
/// duration of the call; a pointer may be null when its length is zero.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_zip_reader_open(
    path_ptr: *const u8,
    path_len: usize,
    password_ptr: *const u8,
    password_len: usize,
) -> i64 {
    unwrap(contained(|| {
        if path_len > MAX_PATH_BYTES {
            return Err(pack_bridge_invalid_argument());
        }
        // SAFETY: Forwarded from this function's own contract.
        let path = unsafe { string_from_raw(path_ptr, path_len) }?;
        // SAFETY: Forwarded from this function's own contract.
        let password = unsafe { string_from_raw(password_ptr, password_len) }?;
        let password = (password_len != 0).then_some(password);
        let handle = keyguard_zip_core::reader_open(path, password)?;
        i64::try_from(handle).map_err(|_| pack_bridge_internal())
    }))
}

/// Advances the reader to its next entry and writes the name into the buffer.
/// Returns the name's byte length, `-1` at the end of the archive, or a packed
/// negative failure.
///
/// # Safety
///
/// `name_ptr`/`name_cap` must describe writable, unaliased bytes valid for the
/// duration of the call; the pointer may be null when the capacity is zero.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_zip_reader_next_entry(
    handle: u64,
    name_ptr: *mut u8,
    name_cap: usize,
) -> i64 {
    unwrap(contained(|| {
        // SAFETY: Forwarded from this function's own contract.
        let name_buf = unsafe { bytes_from_raw_mut(name_ptr, name_cap) }?;
        match keyguard_zip_core::reader_next_entry(handle, name_buf)? {
            Some(name_len) => i64::try_from(name_len).map_err(|_| pack_bridge_internal()),
            None => Ok(END_OF_ARCHIVE),
        }
    }))
}

/// Reads up to `buf_cap` bytes of the current entry. Returns the byte count,
/// zero at the end of the entry, or a packed negative failure.
///
/// # Safety
///
/// `buf_ptr`/`buf_cap` must describe writable, unaliased bytes valid for the
/// duration of the call; the pointer may be null when the capacity is zero.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_zip_reader_read(
    handle: u64,
    buf_ptr: *mut u8,
    buf_cap: usize,
) -> i64 {
    unwrap(contained(|| {
        // SAFETY: Forwarded from this function's own contract.
        let buf = unsafe { bytes_from_raw_mut(buf_ptr, buf_cap) }?;
        let read = keyguard_zip_core::reader_read(handle, buf)?;
        i64::try_from(read).map_err(|_| pack_bridge_internal())
    }))
}

/// Closes the reader, leaving the file in place. Consumes the handle on every
/// result. Returns zero, or a packed negative failure.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_zip_reader_close(handle: u64) -> i64 {
    unwrap(contained(|| {
        keyguard_zip_core::reader_close(handle)?;
        Ok(0)
    }))
}

#[cfg(test)]
mod tests {
    use std::{
        fs,
        io::Read,
        path::{Path, PathBuf},
        ptr,
        sync::atomic::{AtomicU64, Ordering},
    };

    use keyguard_zip_core::{
        BridgeError, MAX_ENTRY_NAME_BYTES, pack_bridge_error, pack_bridge_invalid_handle,
    };
    use zip::{CompressionMethod, ZipArchive};

    use super::*;

    fn temp_path(label: &str) -> PathBuf {
        static COUNTER: AtomicU64 = AtomicU64::new(0);
        std::env::temp_dir().join(format!(
            "keyguard-zip-c-{}-{}-{}.zip",
            label,
            std::process::id(),
            COUNTER.fetch_add(1, Ordering::Relaxed),
        ))
    }

    fn open_archive(path: &Path, password: Option<&str>) -> i64 {
        let path = path.to_str().expect("UTF-8 path");
        let (password_ptr, password_len) = match password {
            Some(password) => (password.as_ptr(), password.len()),
            None => (ptr::null(), 0),
        };
        // SAFETY: Both buffers are live for the duration of the call, and the
        // null password pointer is paired with a zero length.
        unsafe { keyguard_zip_writer_open(path.as_ptr(), path.len(), password_ptr, password_len) }
    }

    fn begin_entry(handle: i64, name: &str) -> i64 {
        // SAFETY: The name buffer is live for the duration of the call.
        unsafe { keyguard_zip_writer_begin_entry(handle as u64, name.as_ptr(), name.len()) }
    }

    fn write(handle: i64, data: &[u8]) -> i64 {
        // SAFETY: The data buffer is live for the duration of the call.
        unsafe { keyguard_zip_writer_write(handle as u64, data.as_ptr(), data.len()) }
    }

    #[test]
    fn the_abi_version_is_pinned_to_one() {
        assert_eq!(keyguard_zip_abi_version(), 1);
    }

    #[test]
    fn an_archive_round_trips_through_the_c_abi() {
        let path = temp_path("round-trip");
        let handle = open_archive(&path, None);
        assert!(handle >= 1, "open returned {handle}");
        assert_eq!(begin_entry(handle, "attachments/1/file.bin"), 0);
        assert_eq!(write(handle, b"hello "), 0);
        assert_eq!(write(handle, b"world"), 0);
        // SAFETY: The null pointer is paired with a zero length.
        let empty = unsafe { keyguard_zip_writer_write(handle as u64, ptr::null(), 0) };
        assert_eq!(empty, 0);
        assert_eq!(keyguard_zip_writer_end_entry(handle as u64), 0);
        assert_eq!(keyguard_zip_writer_finish(handle as u64), 0);

        let file = fs::File::open(&path).expect("the archive must exist");
        let mut archive = ZipArchive::new(file).expect("the archive must be readable");
        assert_eq!(archive.len(), 1);
        let mut entry = archive
            .by_name("attachments/1/file.bin")
            .expect("the entry must exist");
        assert_eq!(entry.compression(), CompressionMethod::Deflated);
        assert!(!entry.encrypted());
        let mut content = String::new();
        entry.read_to_string(&mut content).expect("read must work");
        assert_eq!(content, "hello world");
        drop(entry);
        drop(archive);
        fs::remove_file(&path).expect("cleanup must succeed");
    }

    #[test]
    fn an_encrypted_archive_round_trips_through_the_c_abi() {
        let path = temp_path("aes");
        let handle = open_archive(&path, Some("hunter2"));
        assert!(handle >= 1, "open returned {handle}");
        assert_eq!(begin_entry(handle, "secret.txt"), 0);
        assert_eq!(write(handle, b"vault"), 0);
        assert_eq!(keyguard_zip_writer_end_entry(handle as u64), 0);
        assert_eq!(keyguard_zip_writer_finish(handle as u64), 0);

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
        assert!(
            archive.by_name_decrypt("secret.txt", b"wrong").is_err(),
            "a wrong password must not decrypt the entry"
        );
        drop(archive);
        fs::remove_file(&path).expect("cleanup must succeed");
    }

    #[test]
    fn finish_and_abort_consume_the_handle() {
        let path = temp_path("consume");
        let handle = open_archive(&path, None);
        assert_eq!(keyguard_zip_writer_finish(handle as u64), 0);
        assert_eq!(
            keyguard_zip_writer_finish(handle as u64),
            pack_bridge_invalid_handle()
        );
        assert_eq!(
            keyguard_zip_writer_abort(handle as u64),
            pack_bridge_invalid_handle()
        );
        fs::remove_file(&path).expect("cleanup must succeed");

        let path = temp_path("consume-abort");
        let handle = open_archive(&path, None);
        assert_eq!(keyguard_zip_writer_abort(handle as u64), 0);
        assert!(!path.exists(), "abort must remove the file");
        assert_eq!(
            keyguard_zip_writer_abort(handle as u64),
            pack_bridge_invalid_handle()
        );
    }

    #[test]
    fn an_unknown_handle_is_reported_on_every_entry_point() {
        let unknown = u64::MAX;
        let expected = pack_bridge_invalid_handle();
        // SAFETY: Every buffer is live for the duration of its call, and null
        // pointers are paired with zero lengths.
        unsafe {
            assert_eq!(
                keyguard_zip_writer_begin_entry(unknown, "a.txt".as_ptr(), 5),
                expected
            );
            assert_eq!(keyguard_zip_writer_write(unknown, ptr::null(), 0), expected);
        }
        assert_eq!(keyguard_zip_writer_end_entry(unknown), expected);
        assert_eq!(keyguard_zip_writer_finish(unknown), expected);
        assert_eq!(keyguard_zip_writer_abort(unknown), expected);
    }

    #[test]
    fn a_null_pointer_with_a_non_zero_length_is_an_invalid_argument() {
        let expected = pack_bridge_invalid_argument();
        // SAFETY: Every pointer is deliberately null; the lengths are what the
        // bridge must reject before dereferencing anything.
        unsafe {
            assert_eq!(
                keyguard_zip_writer_open(ptr::null(), 8, ptr::null(), 0),
                expected
            );
            let path = temp_path("null-password");
            let path = path.to_str().expect("UTF-8 path").to_owned();
            assert_eq!(
                keyguard_zip_writer_open(path.as_ptr(), path.len(), ptr::null(), 8),
                expected
            );
            assert_eq!(
                keyguard_zip_writer_begin_entry(1, ptr::null(), 8),
                expected,
                "the argument check must precede the handle lookup"
            );
            assert_eq!(keyguard_zip_writer_write(1, ptr::null(), 8), expected);
        }
    }

    #[test]
    fn invalid_utf8_is_an_invalid_argument() {
        let invalid = [0xff_u8, 0xfe];
        let expected = pack_bridge_invalid_argument();
        // SAFETY: Every buffer is live for the duration of its call.
        unsafe {
            assert_eq!(
                keyguard_zip_writer_open(invalid.as_ptr(), invalid.len(), ptr::null(), 0),
                expected
            );
            let path = temp_path("invalid-utf8-password");
            let path = path.to_str().expect("UTF-8 path").to_owned();
            assert_eq!(
                keyguard_zip_writer_open(
                    path.as_ptr(),
                    path.len(),
                    invalid.as_ptr(),
                    invalid.len()
                ),
                expected
            );
            assert!(!PathBuf::from(&path).exists(), "no file must be created");
            assert_eq!(
                keyguard_zip_writer_begin_entry(1, invalid.as_ptr(), invalid.len()),
                expected
            );
        }
    }

    #[test]
    fn an_oversized_path_is_an_invalid_argument() {
        let path = "a".repeat(MAX_PATH_BYTES + 1);
        // SAFETY: The path buffer is live for the duration of the call.
        let packed = unsafe { keyguard_zip_writer_open(path.as_ptr(), path.len(), ptr::null(), 0) };
        assert_eq!(packed, pack_bridge_invalid_argument());
    }

    #[test]
    fn an_oversized_entry_name_is_reported_as_name_too_long() {
        let path = temp_path("name");
        let handle = open_archive(&path, None);
        let name = "a".repeat(MAX_ENTRY_NAME_BYTES + 1);
        assert_eq!(
            begin_entry(handle, &name),
            pack_bridge_error(BridgeError::NameTooLong)
        );
        assert_eq!(keyguard_zip_writer_abort(handle as u64), 0);
    }

    #[test]
    fn the_state_machine_is_enforced_across_the_c_abi() {
        let path = temp_path("state");
        let handle = open_archive(&path, None);
        let invalid_state = pack_bridge_error(BridgeError::InvalidState);
        assert_eq!(write(handle, b"stray"), invalid_state);
        assert_eq!(keyguard_zip_writer_end_entry(handle as u64), invalid_state);
        assert_eq!(begin_entry(handle, "a.txt"), 0);
        assert_eq!(begin_entry(handle, "b.txt"), invalid_state);
        assert_eq!(keyguard_zip_writer_finish(handle as u64), invalid_state);
        assert_eq!(
            keyguard_zip_writer_end_entry(handle as u64),
            pack_bridge_invalid_handle(),
            "a failed finish still consumes the handle"
        );
        fs::remove_file(&path).expect("cleanup must succeed");
    }

    fn open_reader(path: &Path, password: Option<&str>) -> i64 {
        let path = path.to_str().expect("UTF-8 path");
        let (password_ptr, password_len) = match password {
            Some(password) => (password.as_ptr(), password.len()),
            None => (ptr::null(), 0),
        };
        // SAFETY: Both buffers are live for the duration of the call, and the
        // null password pointer is paired with a zero length.
        unsafe { keyguard_zip_reader_open(path.as_ptr(), path.len(), password_ptr, password_len) }
    }

    fn next_entry(handle: i64, name_buf: &mut [u8]) -> i64 {
        // SAFETY: The buffer is live and exclusively borrowed for the call.
        unsafe {
            keyguard_zip_reader_next_entry(handle as u64, name_buf.as_mut_ptr(), name_buf.len())
        }
    }

    fn read(handle: i64, buf: &mut [u8]) -> i64 {
        // SAFETY: The buffer is live and exclusively borrowed for the call.
        unsafe { keyguard_zip_reader_read(handle as u64, buf.as_mut_ptr(), buf.len()) }
    }

    fn write_fixture(label: &str, password: Option<&str>) -> PathBuf {
        let path = temp_path(label);
        let handle = open_archive(&path, password);
        assert!(handle >= 1, "open returned {handle}");
        assert_eq!(begin_entry(handle, "attachments/1/file.bin"), 0);
        assert_eq!(write(handle, b"hello world"), 0);
        assert_eq!(keyguard_zip_writer_end_entry(handle as u64), 0);
        assert_eq!(begin_entry(handle, "empty.bin"), 0);
        assert_eq!(keyguard_zip_writer_end_entry(handle as u64), 0);
        assert_eq!(keyguard_zip_writer_finish(handle as u64), 0);
        path
    }

    #[test]
    fn an_archive_round_trips_through_the_c_reader() {
        for password in [None, Some("hunter2")] {
            let path = write_fixture("reader-round-trip", password);
            let handle = open_reader(&path, password);
            assert!(handle >= 1, "reader open returned {handle}");

            let mut name_buf = [0_u8; 128];
            assert_eq!(next_entry(handle, &mut name_buf), 22);
            assert_eq!(&name_buf[..22], b"attachments/1/file.bin");

            let mut body = [0_u8; 4];
            let mut content = Vec::new();
            loop {
                let read = read(handle, &mut body);
                assert!(read >= 0, "read returned {read}");
                if read == 0 {
                    break;
                }
                content.extend_from_slice(&body[..read as usize]);
            }
            assert_eq!(content, b"hello world");

            assert_eq!(next_entry(handle, &mut name_buf), 9);
            assert_eq!(&name_buf[..9], b"empty.bin");
            assert_eq!(read(handle, &mut body), 0);

            assert_eq!(next_entry(handle, &mut name_buf), -1, "the archive ended");
            assert_eq!(next_entry(handle, &mut name_buf), -1, "the end is stable");
            assert_eq!(keyguard_zip_reader_close(handle as u64), 0);
            assert!(path.exists(), "close must not remove the file");
            fs::remove_file(&path).expect("cleanup must succeed");
        }
    }

    #[test]
    fn close_consumes_the_reader_handle() {
        let path = write_fixture("reader-consume", None);
        let handle = open_reader(&path, None);
        assert_eq!(keyguard_zip_reader_close(handle as u64), 0);
        let expected = pack_bridge_invalid_handle();
        assert_eq!(keyguard_zip_reader_close(handle as u64), expected);
        assert_eq!(next_entry(handle, &mut [0_u8; 8]), expected);
        assert_eq!(read(handle, &mut [0_u8; 8]), expected);
        fs::remove_file(&path).expect("cleanup must succeed");
    }

    #[test]
    fn an_unknown_handle_is_reported_on_every_reader_entry_point() {
        let unknown = u64::MAX;
        let expected = pack_bridge_invalid_handle();
        let mut buf = [0_u8; 8];
        // SAFETY: Every buffer is live and exclusively borrowed for its call.
        unsafe {
            assert_eq!(
                keyguard_zip_reader_next_entry(unknown, buf.as_mut_ptr(), buf.len()),
                expected
            );
            assert_eq!(
                keyguard_zip_reader_read(unknown, buf.as_mut_ptr(), buf.len()),
                expected
            );
        }
        assert_eq!(keyguard_zip_reader_close(unknown), expected);
    }

    #[test]
    fn a_null_pointer_with_a_non_zero_length_is_rejected_by_the_reader() {
        let expected = pack_bridge_invalid_argument();
        // SAFETY: Every pointer is deliberately null; the lengths are what the
        // bridge must reject before dereferencing anything.
        unsafe {
            assert_eq!(
                keyguard_zip_reader_open(ptr::null(), 8, ptr::null(), 0),
                expected
            );
            let path = temp_path("reader-null-password");
            let path = path.to_str().expect("UTF-8 path").to_owned();
            assert_eq!(
                keyguard_zip_reader_open(path.as_ptr(), path.len(), ptr::null(), 8),
                expected
            );
            assert_eq!(
                keyguard_zip_reader_next_entry(1, ptr::null_mut(), 8),
                expected,
                "the argument check must precede the handle lookup"
            );
            assert_eq!(keyguard_zip_reader_read(1, ptr::null_mut(), 8), expected);
        }
    }

    #[test]
    fn a_null_output_buffer_with_a_zero_length_is_accepted() {
        let path = write_fixture("reader-null-buffer", None);
        let handle = open_reader(&path, None);
        // SAFETY: Every null pointer is paired with a zero length.
        unsafe {
            assert_eq!(
                keyguard_zip_reader_next_entry(handle as u64, ptr::null_mut(), 0),
                pack_bridge_error(BridgeError::BufferTooSmall),
                "no name fits a zero-length buffer"
            );
            let mut name_buf = [0_u8; 64];
            assert_eq!(
                keyguard_zip_reader_next_entry(
                    handle as u64,
                    name_buf.as_mut_ptr(),
                    name_buf.len()
                ),
                22,
                "the failed listing did not consume the entry"
            );
            assert_eq!(
                keyguard_zip_reader_read(handle as u64, ptr::null_mut(), 0),
                0
            );
        }
        assert_eq!(keyguard_zip_reader_close(handle as u64), 0);
        fs::remove_file(&path).expect("cleanup must succeed");
    }

    #[test]
    fn an_oversized_path_is_an_invalid_argument_for_the_reader() {
        let path = "a".repeat(MAX_PATH_BYTES + 1);
        // SAFETY: The path buffer is live for the duration of the call.
        let packed = unsafe { keyguard_zip_reader_open(path.as_ptr(), path.len(), ptr::null(), 0) };
        assert_eq!(packed, pack_bridge_invalid_argument());
    }

    #[test]
    fn reading_outside_an_entry_is_an_invalid_state_across_the_c_abi() {
        let path = write_fixture("reader-state", None);
        let handle = open_reader(&path, None);
        assert_eq!(
            read(handle, &mut [0_u8; 8]),
            pack_bridge_error(BridgeError::InvalidState)
        );
        assert_eq!(keyguard_zip_reader_close(handle as u64), 0);
        fs::remove_file(&path).expect("cleanup must succeed");
    }

    #[test]
    fn a_wrong_password_is_reported_across_the_c_abi() {
        let path = write_fixture("reader-password", Some("hunter2"));
        let expected = pack_bridge_error(BridgeError::WrongPassword);

        let handle = open_reader(&path, Some("wrong"));
        assert_eq!(next_entry(handle, &mut [0_u8; 64]), expected);
        assert_eq!(keyguard_zip_reader_close(handle as u64), 0);

        let handle = open_reader(&path, None);
        assert_eq!(next_entry(handle, &mut [0_u8; 64]), expected);
        assert_eq!(keyguard_zip_reader_close(handle as u64), 0);
        fs::remove_file(&path).expect("cleanup must succeed");
    }

    #[test]
    fn a_missing_archive_does_not_open_for_reading() {
        let path = temp_path("reader-missing");
        let packed = open_reader(&path, None);
        assert!(packed < 0, "a missing archive must not open: {packed}");
        assert_ne!(packed, -1, "a failure must not collide with the end marker");
    }

    #[test]
    fn a_contained_panic_uses_the_bridge_panic_failure() {
        assert_eq!(
            unwrap(contained(|| -> Result<i64, i64> { panic!("boom") })),
            pack_bridge_panic()
        );
    }
}
