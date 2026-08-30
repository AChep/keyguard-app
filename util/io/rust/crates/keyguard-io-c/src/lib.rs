//! Stable C ABI for Keyguard native filesystem primitives.
//!
//! Every function unmarshals its raw arguments, delegates to
//! [`keyguard_io_core::bridge`], and returns ABI v1 wire values shared with
//! the JNI adapter. Panics never cross the boundary.

#![deny(unsafe_op_in_unsafe_fn)]

use std::{mem::size_of, panic::AssertUnwindSafe, ptr, slice, str};

use keyguard_io_core::{abi, bridge};

/// Runs `body` behind the panic boundary every entry point shares.
///
/// Production builds install the hook *inside* the boundary:
/// `std::panic::set_hook`
/// "panics if called from a panicking thread", and a panic inside
/// `Once::call_once` poisons the `Once` so that every later call panics too.
/// Installed outside the boundary, one such panic would escape the
/// `extern "C"` frame and abort the process on every subsequent bridge call.
fn contained<R>(body: impl FnOnce() -> R) -> Result<R, i64> {
    std::panic::catch_unwind(AssertUnwindSafe(|| {
        // Unit tests keep Rust's default hook so a caught assertion still
        // reports its payload and source location in CI logs.
        #[cfg(not(test))]
        keyguard_io_core::install_redacting_panic_hook();
        body()
    }))
    .map_err(|_| abi::pack_bridge_panic())
}

fn unwrap(result: Result<Result<i64, i64>, i64>) -> i64 {
    match result.and_then(std::convert::identity) {
        Ok(value) | Err(value) => value,
    }
}

/// # Safety
///
/// A non-empty string must be represented by a non-null pointer to its
/// declared number of readable UTF-8 bytes, valid for the duration of the
/// call.
unsafe fn string_from_raw<'a>(pointer: *const u8, length: usize) -> Result<&'a str, i64> {
    if length == 0 {
        return Ok("");
    }
    if pointer.is_null() || length > isize::MAX as usize {
        return Err(abi::pack_bridge_invalid_argument());
    }
    // SAFETY: Null and oversized inputs were rejected; the forwarded FFI
    // contract guarantees `length` readable bytes for the complete call.
    let bytes = unsafe { slice::from_raw_parts(pointer, length) };
    str::from_utf8(bytes).map_err(|_| abi::pack_bridge_invalid_argument())
}

/// # Safety
///
/// A non-empty buffer must be represented by a non-null pointer to its
/// declared number of readable bytes, valid for the duration of the call.
unsafe fn bytes_from_raw<'a>(pointer: *const u8, length: usize) -> Result<&'a [u8], i64> {
    if length == 0 {
        return Ok(&[]);
    }
    if pointer.is_null() || length > isize::MAX as usize {
        return Err(abi::pack_bridge_invalid_argument());
    }
    // SAFETY: Null and oversized inputs were rejected; the forwarded FFI
    // contract guarantees `length` readable bytes for the complete call.
    Ok(unsafe { slice::from_raw_parts(pointer, length) })
}

/// # Safety
///
/// `options` must be non-null and valid for reading the size it declares.
unsafe fn options_from_raw(
    options: *const abi::TxnOptionsWire,
) -> Result<abi::TxnOptionsWire, i64> {
    if options.is_null() {
        return Err(abi::pack_bridge_invalid_argument());
    }
    // SAFETY: Null was rejected and the caller guarantees at least the
    // leading size field is readable for the duration of the call.
    let available = unsafe { ptr::read_unaligned(options.cast::<u32>()) };
    if available != size_of::<abi::TxnOptionsWire>() as u32 {
        return Err(abi::pack_bridge_invalid_argument());
    }
    // SAFETY: The declared size covers the complete trivially-copyable wire
    // record. An unaligned read accepts any caller storage alignment.
    Ok(unsafe { ptr::read_unaligned(options) })
}

/// Returns the native function ABI version.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_io_abi_version() -> u32 {
    contained(|| keyguard_io_core::ABI_VERSION).unwrap_or(0)
}

/// Opens an atomic-write transaction; returns a positive handle or a packed
/// failure.
///
/// # Safety
///
/// `destination_ptr`/`destination_len` must describe readable UTF-8 bytes
/// valid for the duration of the call. `options` must be non-null and valid
/// for reading its declared byte size; the size must equal the ABI v1 options
/// record size.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_io_txn_begin(
    destination_ptr: *const u8,
    destination_len: usize,
    options: *const abi::TxnOptionsWire,
) -> i64 {
    unwrap(contained(|| {
        // SAFETY: The caller contract is forwarded from this function's own.
        let options = unsafe { options_from_raw(options) }?;
        // SAFETY: The caller contract is forwarded from this function's own.
        let destination = unsafe { string_from_raw(destination_ptr, destination_len) }?;
        Ok(bridge::txn_begin(destination, options))
    }))
}

/// Opens and retains an existing absolute directory.
///
/// # Safety
///
/// `directory_ptr`/`directory_len` must describe readable UTF-8 bytes valid
/// for the duration of the call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_io_directory_open(
    directory_ptr: *const u8,
    directory_len: usize,
) -> i64 {
    unwrap(contained(|| {
        // SAFETY: The caller contract is forwarded from this function's own.
        let directory = unsafe { string_from_raw(directory_ptr, directory_len) }?;
        Ok(bridge::directory_open(directory))
    }))
}

/// Closes one retained-directory handle. Transactions already opened from it
/// remain valid.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_io_directory_close(handle: u64) -> i64 {
    unwrap(contained(|| Ok(bridge::directory_close(handle))))
}

/// Opens an atomic-write transaction beneath a retained directory.
///
/// # Safety
///
/// `relative_destination_ptr`/`relative_destination_len` must describe
/// readable UTF-8 bytes valid for the duration of the call. `options` follows
/// the same contract as [`keyguard_io_txn_begin`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_io_txn_begin_at_directory(
    directory_handle: u64,
    relative_destination_ptr: *const u8,
    relative_destination_len: usize,
    options: *const abi::TxnOptionsWire,
) -> i64 {
    unwrap(contained(|| {
        // SAFETY: The caller contract is forwarded from this function's own.
        let options = unsafe { options_from_raw(options) }?;
        // SAFETY: The caller contract is forwarded from this function's own.
        let destination =
            unsafe { string_from_raw(relative_destination_ptr, relative_destination_len) }?;
        Ok(bridge::txn_begin_at_directory(
            directory_handle,
            destination,
            options,
        ))
    }))
}

/// Appends bytes to a transaction; returns the byte count or a packed
/// failure.
///
/// A failed append permanently prevents publication. The handle remains valid
/// for abort, and later writes return the original failure without replaying
/// I/O. Commit consumes the handle, attempts cleanup only, and reports that
/// original write failure.
///
/// # Safety
///
/// `input_ptr`/`input_len` must describe readable bytes valid for the
/// duration of the call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_io_txn_write(
    handle: u64,
    input_ptr: *const u8,
    input_len: usize,
) -> i64 {
    unwrap(contained(|| {
        // SAFETY: The caller contract is forwarded from this function's own.
        let bytes = unsafe { bytes_from_raw(input_ptr, input_len) }?;
        Ok(bridge::txn_write(handle, bytes))
    }))
}

/// Commits a transaction, consuming its handle; returns a packed commit
/// report or failure.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_io_txn_commit(handle: u64) -> i64 {
    unwrap(contained(|| Ok(bridge::txn_commit(handle))))
}

/// Aborts a transaction, consuming its handle; returns zero or a packed
/// failure.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_io_txn_abort(handle: u64) -> i64 {
    unwrap(contained(|| Ok(bridge::txn_abort(handle))))
}

/// Opens private scratch storage; returns a positive handle or a packed
/// failure.
///
/// # Safety
///
/// `directory_ptr`/`directory_len` must describe readable UTF-8 bytes valid
/// for the duration of the call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_io_scratch_open(
    directory_ptr: *const u8,
    directory_len: usize,
) -> i64 {
    unwrap(contained(|| {
        // SAFETY: The caller contract is forwarded from this function's own.
        let directory = unsafe { string_from_raw(directory_ptr, directory_len) }?;
        Ok(bridge::scratch_open(directory))
    }))
}

/// Appends bytes to scratch storage; returns the byte count or a packed
/// failure.
///
/// # Safety
///
/// `input_ptr`/`input_len` must describe readable bytes valid for the
/// duration of the call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_io_scratch_write(
    handle: u64,
    input_ptr: *const u8,
    input_len: usize,
) -> i64 {
    unwrap(contained(|| {
        // SAFETY: The caller contract is forwarded from this function's own.
        let bytes = unsafe { bytes_from_raw(input_ptr, input_len) }?;
        Ok(bridge::scratch_write(handle, bytes))
    }))
}

/// Seals scratch storage for reading; returns zero or a packed failure.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_io_scratch_seal(handle: u64) -> i64 {
    unwrap(contained(|| Ok(bridge::scratch_seal(handle))))
}

/// Returns the scratch length in bytes or a packed failure.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_io_scratch_length(handle: u64) -> i64 {
    unwrap(contained(|| Ok(bridge::scratch_length(handle))))
}

/// Reads scratch bytes at a fixed position; returns the byte count, `-1` at
/// end-of-file, or a packed failure.
///
/// # Safety
///
/// `output_ptr`/`output_len` must describe writable bytes valid for the
/// duration of the call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_io_scratch_read_at(
    handle: u64,
    position: u64,
    output_ptr: *mut u8,
    output_len: usize,
) -> i64 {
    unwrap(contained(|| {
        if output_len == 0 {
            return Ok(bridge::scratch_read_at(handle, position, &mut []));
        }
        if output_ptr.is_null() || output_len > isize::MAX as usize {
            return Err(abi::pack_bridge_invalid_argument());
        }
        // SAFETY: Null and oversized outputs were rejected; the caller
        // contract guarantees `output_len` writable bytes for the call.
        let buffer = unsafe { slice::from_raw_parts_mut(output_ptr, output_len) };
        Ok(bridge::scratch_read_at(handle, position, buffer))
    }))
}

/// Closes scratch storage, consuming its handle; returns zero or a packed
/// failure.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_io_scratch_close(handle: u64) -> i64 {
    unwrap(contained(|| Ok(bridge::scratch_close(handle))))
}

/// Sweeps a directory for orphaned temporary artifacts.
///
/// # Safety
///
/// `directory_ptr`/`directory_len` must describe readable UTF-8 bytes valid
/// for the duration of the call. `report` must point to writable
/// [`abi::SweepReportWire`] storage whose `size` field was initialized to the
/// available byte size.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_io_sweep_orphans(
    directory_ptr: *const u8,
    directory_len: usize,
    older_than_ms: u64,
    role_mask: u32,
    report: *mut abi::SweepReportWire,
) -> i64 {
    unwrap(contained(|| {
        if report.is_null() {
            return Err(abi::pack_bridge_invalid_argument());
        }
        // SAFETY: Null was rejected and the caller contract requires the
        // leading size field to be initialized and readable. Read unaligned to
        // match `options_from_raw`, which deliberately accepts any caller
        // storage alignment — an aligned read here would be undefined behavior
        // for a report staged in a `char[]` or a packed buffer, and neither the
        // header nor this function's contract demands alignment.
        let available = unsafe { ptr::read_unaligned(report.cast::<u32>()) };
        if available < size_of::<abi::SweepReportWire>() as u32 {
            return Err(abi::pack_bridge_invalid_argument());
        }
        if role_mask & !bridge::SWEEP_ROLE_MASK_ALL != 0 {
            return Err(abi::pack_bridge_invalid_argument());
        }
        // SAFETY: The caller contract is forwarded from this function's own.
        let directory = unsafe { string_from_raw(directory_ptr, directory_len) }?;
        match bridge::sweep(directory, older_than_ms, role_mask) {
            Ok(core_report) => {
                // SAFETY: The caller declared enough writable storage for the
                // complete v1 report and ownership does not cross the ABI. The
                // write is unaligned for the same reason as the size read above.
                unsafe {
                    ptr::write_unaligned(report, abi::SweepReportWire::from_report(core_report));
                }
                Ok(0)
            }
            Err(packed_failure) => Ok(packed_failure),
        }
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reports_abi_version_one() {
        assert_eq!(keyguard_io_abi_version(), 1);
    }

    #[test]
    fn scalar_boundary_preserves_the_cleanup_incomplete_bit() {
        let packed = abi::pack_bridge_panic() | (1_i64 << 56);
        assert_eq!(unwrap(contained(|| Ok(packed))), packed);
    }

    #[test]
    fn scalar_boundary_preserves_publication_unknown_operation_bits() {
        let packed = 0x0600_0000_0000_01F6_i64;
        assert_eq!(unwrap(contained(|| Ok(packed))), packed);
    }

    #[test]
    fn invalid_utf8_is_rejected_without_dereferencing_other_inputs() {
        let invalid = [0xff_u8];
        let options = valid_options();
        // SAFETY: The pointer refers to its declared initialized array.
        let packed = unsafe { keyguard_io_txn_begin(invalid.as_ptr(), invalid.len(), &options) };
        assert_eq!(packed, abi::pack_bridge_invalid_argument());
    }

    #[test]
    fn null_options_are_rejected() {
        // SAFETY: Null is deliberately rejected before dereference.
        let packed = unsafe { keyguard_io_txn_begin(ptr::null(), 0, ptr::null()) };
        assert_eq!(packed, abi::pack_bridge_invalid_argument());
    }

    #[test]
    fn null_non_empty_input_is_rejected() {
        // SAFETY: This deliberately-invalid pointer/length pair is rejected
        // before dereference by the boundary.
        let packed = unsafe { keyguard_io_txn_write(1, ptr::null(), 1) };
        assert_eq!(packed, abi::pack_bridge_invalid_argument());
    }

    #[test]
    fn sweep_requires_a_sized_output_report() {
        // SAFETY: The null report is deliberately rejected before any write.
        let packed =
            unsafe { keyguard_io_sweep_orphans(ptr::null(), 0, u64::MAX, 0, ptr::null_mut()) };
        assert_eq!(packed, abi::pack_bridge_invalid_argument());

        let mut report = abi::SweepReportWire {
            size: 0,
            version: 0,
            status: 0,
            first_failure_kind: 0,
            first_failure_domain: 0,
            first_failure_raw_code: 0,
            entries_seen: 0,
            candidate_names: 0,
            removed: 0,
            skipped_young: 0,
            skipped_busy: 0,
            skipped_unsafe: 0,
            skipped_changed: 0,
            inspection_failed: 0,
            removal_failed: 0,
        };
        // SAFETY: The output points to valid storage; its deliberately-small
        // size is rejected before any filesystem access or write.
        let packed = unsafe { keyguard_io_sweep_orphans(ptr::null(), 0, u64::MAX, 0, &mut report) };
        assert_eq!(packed, abi::pack_bridge_invalid_argument());

        report.size = size_of::<abi::SweepReportWire>() as u32;
        // SAFETY: The output points to valid sized storage. The unknown role
        // bit is rejected before the empty path can reach the filesystem.
        let packed =
            unsafe { keyguard_io_sweep_orphans(ptr::null(), 0, u64::MAX, 0x8, &mut report) };
        assert_eq!(packed, abi::pack_bridge_invalid_argument());
    }

    #[test]
    fn full_transaction_round_trip_through_the_c_abi() {
        let mut nonce = [0_u8; 8];
        getrandom::fill(&mut nonce).expect("test nonce generation must succeed");
        let name: String = nonce.iter().map(|byte| format!("{byte:02x}")).collect();
        let directory = std::env::temp_dir().join(format!("keyguard-io-c-test-{name}"));
        let destination = directory.join("vault.bin");
        let destination_str = destination.to_str().expect("test path must be UTF-8");
        let options = valid_options();

        // SAFETY: The pointer refers to the live UTF-8 path buffer.
        let handle = unsafe {
            keyguard_io_txn_begin(destination_str.as_ptr(), destination_str.len(), &options)
        };
        assert!(handle > 0, "txn_begin failed: {handle:#x}");

        let payload = b"c-abi payload";
        // SAFETY: The pointer refers to the live payload buffer.
        let written =
            unsafe { keyguard_io_txn_write(handle as u64, payload.as_ptr(), payload.len()) };
        assert_eq!(written, payload.len() as i64);

        let report = keyguard_io_txn_commit(handle as u64);
        assert!(report >= 0, "commit failed: {report:#x}");
        assert_eq!(
            std::fs::read(&destination).expect("destination must be readable"),
            payload
        );
        let _ = std::fs::remove_dir_all(&directory);
    }

    #[test]
    fn retained_directory_round_trip_matches_the_scalar_c_abi() {
        let mut nonce = [0_u8; 8];
        getrandom::fill(&mut nonce).expect("test nonce generation must succeed");
        let name: String = nonce.iter().map(|byte| format!("{byte:02x}")).collect();
        let directory = std::env::temp_dir().join(format!("keyguard-io-c-directory-{name}"));
        std::fs::create_dir_all(&directory).expect("test root must be created");
        let directory_str = directory.to_str().expect("test path must be UTF-8");

        // SAFETY: The pointer refers to the live UTF-8 path buffer.
        let directory_handle =
            unsafe { keyguard_io_directory_open(directory_str.as_ptr(), directory_str.len()) };
        assert!(directory_handle > 0);
        let relative = "nested/vault.bin";
        let options = abi::TxnOptionsWire {
            existing_parent_links: 0,
            ..valid_options()
        };
        // SAFETY: Both input pointers refer to live initialized buffers.
        let txn_handle = unsafe {
            keyguard_io_txn_begin_at_directory(
                directory_handle as u64,
                relative.as_ptr(),
                relative.len(),
                &options,
            )
        };
        assert!(txn_handle > 0, "relative begin failed: {txn_handle:#x}");
        assert_eq!(keyguard_io_directory_close(directory_handle as u64), 0);

        let payload = b"retained c payload";
        // SAFETY: The pointer refers to the live payload buffer.
        let written =
            unsafe { keyguard_io_txn_write(txn_handle as u64, payload.as_ptr(), payload.len()) };
        assert_eq!(written, payload.len() as i64);
        assert!(keyguard_io_txn_commit(txn_handle as u64) >= 0);
        assert_eq!(
            std::fs::read(directory.join(relative)).expect("destination must be readable"),
            payload
        );
        assert!(keyguard_io_directory_close(directory_handle as u64) < 0);

        std::fs::remove_dir_all(directory).expect("test root must be removed");
    }

    #[test]
    fn retained_directory_relative_validation_precedes_unknown_handle_lookup() {
        let invalid = "../escape";
        let options = abi::TxnOptionsWire {
            existing_parent_links: 0,
            ..valid_options()
        };
        // SAFETY: The pointer refers to the live UTF-8 buffer and options
        // record.
        let packed = unsafe {
            keyguard_io_txn_begin_at_directory(u64::MAX, invalid.as_ptr(), invalid.len(), &options)
        };
        assert_eq!(packed, abi::pack_bridge_invalid_argument());
    }

    fn valid_options() -> abi::TxnOptionsWire {
        abi::TxnOptionsWire {
            size: size_of::<abi::TxnOptionsWire>() as u32,
            version: abi::TXN_OPTIONS_WIRE_VERSION,
            publication: 0,
            file_permissions: 0,
            parent_creation: 1,
            directory_permissions: 0,
            existing_parent_links: 1,
            preferred_sync_level: 2,
            minimum_sync_level: 1,
            sync_policy_mode: 1,
            flags: 0,
            reserved: [0; 5],
        }
    }

    #[test]
    fn contained_panic_uses_the_bridge_panic_failure() {
        assert_eq!(
            unwrap(contained(|| -> Result<i64, i64> { panic!("boom") })),
            abi::pack_bridge_panic()
        );
    }
}
