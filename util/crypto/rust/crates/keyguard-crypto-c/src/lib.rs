//! Stable C ABI for Keyguard native cryptography.

#![deny(unsafe_op_in_unsafe_fn)]

use std::{
    mem::{align_of, size_of},
    panic::AssertUnwindSafe,
    ptr,
};

use zeroize::Zeroize;

use keyguard_crypto_core as core;
use keyguard_crypto_core::protocol::NativeErrorCode;

/// Transport completed and `output` contains a protobuf `NativeResponse`.
pub const TRANSPORT_OK: i32 = 0;
/// A required pointer was null.
pub const TRANSPORT_INVALID_POINTER: i32 = -1;
/// Panic containment could not encode a protobuf response.
pub const TRANSPORT_PANIC: i32 = -2;
/// An internal bridge failure occurred.
pub const TRANSPORT_INTERNAL: i32 = -3;

const AES_BLOCK_BYTES: usize = 16;
const HMAC_SHA256_BYTES: usize = 32;
const MAX_FAST_SLICE_BYTES: usize = isize::MAX as usize;

/// Rust-owned byte buffer returned across the C boundary.
#[repr(C)]
#[derive(Debug)]
pub struct KeyguardCryptoBuffer {
    /// Buffer start, or null when empty/uninitialized.
    pub ptr: *mut u8,
    /// Initialized byte count.
    pub len: usize,
    /// Rust allocation capacity needed by [`keyguard_crypto_buffer_free`].
    pub capacity: usize,
}

impl KeyguardCryptoBuffer {
    const fn empty() -> Self {
        Self {
            ptr: ptr::null_mut(),
            len: 0,
            capacity: 0,
        }
    }
}

/// Returns the native function ABI version.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_crypto_abi_version() -> u32 {
    std::panic::catch_unwind(AssertUnwindSafe(|| {
        core::install_redacting_panic_hook();
        core::ABI_VERSION
    }))
    .unwrap_or(0)
}

/// Returns the native capability bit mask.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_crypto_capabilities() -> u64 {
    std::panic::catch_unwind(AssertUnwindSafe(|| {
        core::install_redacting_panic_hook();
        core::CAPABILITIES
    }))
    .unwrap_or(0)
}

/// Generates a secure random integer without a protobuf control envelope.
///
/// An `exclusive_upper_bound` of zero requests an unbounded signed integer.
/// Positive values preserve `SecureRandom.nextInt(bound)` semantics without
/// modulo bias. On failure, `output` is cleared to zero.
///
/// # Safety
///
/// `output` must point to writable memory for one `i32` for the duration of
/// the call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_crypto_random_int(
    exclusive_upper_bound: u32,
    output: *mut i32,
) -> i32 {
    if output.is_null() {
        return NativeErrorCode::InvalidArgument as i32;
    }
    // SAFETY: Null was rejected and the caller contract guarantees writable
    // access to one `i32` for the duration of this call.
    unsafe { output.write(0) };
    let result = std::panic::catch_unwind(AssertUnwindSafe(|| {
        core::install_redacting_panic_hook();
        core::fast::random_int(exclusive_upper_bound)
    }));
    match result {
        Ok(Ok(value)) => {
            // SAFETY: The output slot remains valid for the complete call.
            unsafe { output.write(value) };
            NativeErrorCode::Ok as i32
        }
        Ok(Err(code)) if code != NativeErrorCode::Ok => code as i32,
        Ok(Err(_)) => NativeErrorCode::Internal as i32,
        Err(_) => NativeErrorCode::Panic as i32,
    }
}

/// Executes a one-shot protobuf request.
///
/// # Safety
///
/// `output` must point to writable memory for one [`KeyguardCryptoBuffer`].
/// When `input_len` is non-zero, `input_ptr` must reference `input_len`
/// readable bytes for the duration of this call. A successful output must be
/// released exactly once with [`keyguard_crypto_buffer_free`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_crypto_call(
    input_ptr: *const u8,
    input_len: usize,
    output: *mut KeyguardCryptoBuffer,
) -> i32 {
    response_boundary("call", output, || {
        // SAFETY: Forwarded caller contract is validated before dereference.
        unsafe {
            run_with_input_inner(
                "call",
                input_ptr,
                input_len,
                core::MAX_CONTROL_ENVELOPE_BYTES,
                output,
                core::call,
            )
        }
    })
}

/// Opens a streaming operation from a protobuf request.
///
/// # Safety
///
/// Pointer and ownership requirements match [`keyguard_crypto_call`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_crypto_stream_open(
    input_ptr: *const u8,
    input_len: usize,
    output: *mut KeyguardCryptoBuffer,
) -> i32 {
    response_boundary("stream.open", output, || {
        // SAFETY: Forwarded caller contract is validated before dereference.
        unsafe {
            run_with_input_inner(
                "stream.open",
                input_ptr,
                input_len,
                core::MAX_CONTROL_ENVELOPE_BYTES,
                output,
                core::stream_open,
            )
        }
    })
}

/// Adds a raw chunk to an existing streaming operation.
///
/// # Safety
///
/// Pointer and ownership requirements match [`keyguard_crypto_call`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_crypto_stream_update(
    handle: u64,
    input_ptr: *const u8,
    input_len: usize,
    output: *mut KeyguardCryptoBuffer,
) -> i32 {
    response_boundary("stream.update", output, || {
        // SAFETY: Forwarded caller contract is validated before dereference.
        unsafe {
            run_with_input_inner(
                "stream.update",
                input_ptr,
                input_len,
                core::MAX_STREAM_CHUNK_BYTES,
                output,
                |input| core::stream_update(handle, input),
            )
        }
    })
}

/// Finalizes and consumes a streaming operation.
///
/// # Safety
///
/// `output` must point to writable memory for one [`KeyguardCryptoBuffer`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_crypto_stream_finish(
    handle: u64,
    output: *mut KeyguardCryptoBuffer,
) -> i32 {
    response_boundary("stream.finish", output, || {
        // SAFETY: `run_without_input_inner` validates `output` before writing it.
        unsafe { run_without_input_inner(output, || core::stream_finish(handle)) }
    })
}

/// Closes a streaming operation.
///
/// # Safety
///
/// `output` must point to writable memory for one [`KeyguardCryptoBuffer`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_crypto_stream_close(
    handle: u64,
    output: *mut KeyguardCryptoBuffer,
) -> i32 {
    response_boundary("stream.close", output, || {
        // SAFETY: `run_without_input_inner` validates `output` before writing it.
        unsafe { run_without_input_inner(output, || core::stream_close(handle)) }
    })
}

/// Encrypts and authenticates a Bitwarden-compatible AES-CBC/HMAC frame
/// without a protobuf control envelope.
///
/// # Safety
///
/// Every non-empty input must reference its declared readable byte count.
/// Both output ranges and `ciphertext_len` must be writable for their declared
/// sizes. Output ranges must not overlap an input or each other. Outputs are
/// cleared on every reported failure. The length slot must not overlap any
/// input or output range.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn keyguard_crypto_aes_cbc_pkcs7_hmac_sha256_encrypt(
    encryption_key_ptr: *const u8,
    encryption_key_len: usize,
    mac_key_ptr: *const u8,
    mac_key_len: usize,
    iv_ptr: *const u8,
    iv_len: usize,
    plaintext_ptr: *const u8,
    plaintext_len: usize,
    ciphertext_ptr: *mut u8,
    ciphertext_capacity: usize,
    mac_ptr: *mut u8,
    mac_capacity: usize,
    ciphertext_len: *mut usize,
) -> i32 {
    let inputs = [
        FastInput::new(encryption_key_ptr, encryption_key_len),
        FastInput::new(mac_key_ptr, mac_key_len),
        FastInput::new(iv_ptr, iv_len),
        FastInput::new(plaintext_ptr, plaintext_len),
    ];
    let outputs = [
        FastOutput::new(ciphertext_ptr, ciphertext_capacity),
        FastOutput::new(mac_ptr, mac_capacity),
    ];
    fast_boundary(&inputs, &outputs, ciphertext_len, || {
        // SAFETY: This export validates all pointers, lengths, and overlaps
        // before constructing slices or delegating to the safe core API.
        unsafe {
            encrypt_fast_inner(
                encryption_key_ptr,
                encryption_key_len,
                mac_key_ptr,
                mac_key_len,
                iv_ptr,
                iv_len,
                plaintext_ptr,
                plaintext_len,
                ciphertext_ptr,
                ciphertext_capacity,
                mac_ptr,
                mac_capacity,
                ciphertext_len,
            )
        }
    })
}

/// Authenticates and decrypts a Bitwarden-compatible AES-CBC/HMAC frame
/// without a protobuf control envelope.
///
/// # Safety
///
/// Every non-empty input must reference its declared readable byte count.
/// `plaintext_ptr` and `plaintext_len` must be writable for their declared
/// sizes, and the output must not overlap an input. The output is cleared on
/// every reported failure and after the returned plaintext length on success.
/// The length slot must not overlap any input or output range.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn keyguard_crypto_aes_cbc_pkcs7_hmac_sha256_decrypt(
    encryption_key_ptr: *const u8,
    encryption_key_len: usize,
    mac_key_ptr: *const u8,
    mac_key_len: usize,
    iv_ptr: *const u8,
    iv_len: usize,
    ciphertext_ptr: *const u8,
    ciphertext_len: usize,
    expected_mac_ptr: *const u8,
    expected_mac_len: usize,
    plaintext_ptr: *mut u8,
    plaintext_capacity: usize,
    plaintext_len: *mut usize,
) -> i32 {
    let inputs = [
        FastInput::new(encryption_key_ptr, encryption_key_len),
        FastInput::new(mac_key_ptr, mac_key_len),
        FastInput::new(iv_ptr, iv_len),
        FastInput::new(ciphertext_ptr, ciphertext_len),
        FastInput::new(expected_mac_ptr, expected_mac_len),
    ];
    let outputs = [FastOutput::new(plaintext_ptr, plaintext_capacity)];
    fast_boundary(&inputs, &outputs, plaintext_len, || {
        // SAFETY: This export validates all pointers, lengths, and overlaps
        // before constructing slices or delegating to the safe core API.
        unsafe {
            decrypt_fast_inner(
                encryption_key_ptr,
                encryption_key_len,
                mac_key_ptr,
                mac_key_len,
                iv_ptr,
                iv_len,
                ciphertext_ptr,
                ciphertext_len,
                expected_mac_ptr,
                expected_mac_len,
                plaintext_ptr,
                plaintext_capacity,
                plaintext_len,
            )
        }
    })
}

/// Releases a buffer returned by this library and clears the caller's struct.
///
/// Calling this function with a null pointer, or with an already-cleared
/// buffer, is a no-op.
///
/// # Safety
///
/// `buffer` must be null or point to a struct originally initialized by this
/// library. Its fields must not be modified before the first free call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_crypto_buffer_free(buffer: *mut KeyguardCryptoBuffer) {
    let _ = std::panic::catch_unwind(AssertUnwindSafe(|| {
        core::install_redacting_panic_hook();
        if buffer.is_null() {
            return;
        }
        // SAFETY: The caller guarantees writable access to the buffer struct.
        let buffer = unsafe { &mut *buffer };
        if !buffer.ptr.is_null() {
            // SAFETY: Successful exports create this exact Vec allocation and
            // transfer its ptr/len/capacity tuple to the caller unchanged.
            let mut value = unsafe { Vec::from_raw_parts(buffer.ptr, buffer.len, buffer.capacity) };
            value.zeroize();
        }
        *buffer = KeyguardCryptoBuffer::empty();
    }));
}

fn response_boundary(
    operation: &'static str,
    output: *mut KeyguardCryptoBuffer,
    function: impl FnOnce() -> i32,
) -> i32 {
    match std::panic::catch_unwind(AssertUnwindSafe(|| {
        core::install_redacting_panic_hook();
        function()
    })) {
        Ok(status) => status,
        Err(_) => panic_response_boundary(operation, output),
    }
}

#[derive(Clone, Copy)]
struct FastOutput {
    ptr: *mut u8,
    len: usize,
}

impl FastOutput {
    const fn new(ptr: *mut u8, len: usize) -> Self {
        Self { ptr, len }
    }

    fn is_bounded(self) -> bool {
        self.len <= MAX_FAST_SLICE_BYTES
    }

    unsafe fn as_mut_slice<'a>(self) -> &'a mut [u8] {
        if self.len == 0 {
            &mut []
        } else {
            // SAFETY: `is_valid` rejected null, and the caller contract
            // guarantees this pointer is uniquely writable for `len` bytes.
            unsafe { std::slice::from_raw_parts_mut(self.ptr, self.len) }
        }
    }

    unsafe fn clear(self) {
        if !self.ptr.is_null() && self.is_bounded() {
            // SAFETY: The export contract guarantees a writable range of
            // exactly `len` bytes whenever `ptr` is non-null.
            unsafe { ptr::write_bytes(self.ptr, 0, self.len) };
        }
    }
}

fn fast_boundary(
    inputs: &[FastInput],
    outputs: &[FastOutput],
    output_len: *mut usize,
    function: impl FnOnce() -> i32,
) -> i32 {
    if !length_slot_is_separate(output_len, inputs, outputs) {
        let cleared = std::panic::catch_unwind(AssertUnwindSafe(|| {
            // SAFETY: Each export forwards caller-declared output ranges;
            // cleanup checks null and representable bounds before writing.
            unsafe { clear_fast_output_ranges(outputs) };
        }))
        .is_ok();
        return if cleared {
            NativeErrorCode::InvalidArgument as i32
        } else {
            NativeErrorCode::Internal as i32
        };
    }
    let result = std::panic::catch_unwind(AssertUnwindSafe(|| {
        core::install_redacting_panic_hook();
        function()
    }));
    match result {
        Ok(status) if status == NativeErrorCode::Ok as i32 => status,
        Ok(status) => {
            let cleared = std::panic::catch_unwind(AssertUnwindSafe(|| {
                // SAFETY: Each export forwards only caller-declared output
                // ranges; cleanup checks null and representable bounds first.
                unsafe { clear_fast_outputs(outputs, output_len) };
            }))
            .is_ok();
            if cleared {
                status
            } else {
                NativeErrorCode::Internal as i32
            }
        }
        Err(_) => std::panic::catch_unwind(AssertUnwindSafe(|| {
            // SAFETY: Each export forwards only caller-declared output ranges;
            // the cleanup helper checks null and public size bounds first.
            unsafe { clear_fast_outputs(outputs, output_len) };
            NativeErrorCode::Panic as i32
        }))
        .unwrap_or(NativeErrorCode::Internal as i32),
    }
}

#[allow(clippy::too_many_arguments)]
unsafe fn encrypt_fast_inner(
    encryption_key_ptr: *const u8,
    encryption_key_len: usize,
    mac_key_ptr: *const u8,
    mac_key_len: usize,
    iv_ptr: *const u8,
    iv_len: usize,
    plaintext_ptr: *const u8,
    plaintext_len: usize,
    ciphertext_ptr: *mut u8,
    ciphertext_capacity: usize,
    mac_ptr: *mut u8,
    mac_capacity: usize,
    ciphertext_len: *mut usize,
) -> i32 {
    if ciphertext_len.is_null() {
        return NativeErrorCode::InvalidArgument as i32;
    }
    // SAFETY: Null was rejected, and the export contract guarantees writable
    // access to the caller-owned length slot.
    unsafe { ciphertext_len.write(0) };

    let Some(expected_ciphertext_len) = encrypted_size(plaintext_len) else {
        // SAFETY: Only bounded, non-null output ranges are cleared.
        unsafe {
            clear_fast_outputs(
                &[
                    FastOutput::new(ciphertext_ptr, ciphertext_capacity),
                    FastOutput::new(mac_ptr, mac_capacity),
                ],
                ciphertext_len,
            )
        };
        return NativeErrorCode::ResourceLimit as i32;
    };
    let inputs = [
        FastInput::new(encryption_key_ptr, encryption_key_len),
        FastInput::new(mac_key_ptr, mac_key_len),
        FastInput::new(iv_ptr, iv_len),
        FastInput::new(plaintext_ptr, plaintext_len),
    ];
    let outputs = [
        FastOutput::new(ciphertext_ptr, ciphertext_capacity),
        FastOutput::new(mac_ptr, mac_capacity),
    ];
    if !inputs.iter().copied().all(FastInput::is_valid)
        || !outputs.iter().copied().all(FastOutput::is_valid)
        || ciphertext_capacity != expected_ciphertext_len
        || mac_capacity != HMAC_SHA256_BYTES
        || ranges_overlap_any(&inputs, &outputs)
        || ranges_overlap(
            ciphertext_ptr.cast_const(),
            ciphertext_capacity,
            mac_ptr.cast_const(),
            mac_capacity,
        )
    {
        // SAFETY: Output ranges were supplied by the caller; clear checks
        // public bounds before dereferencing either pointer.
        unsafe { clear_fast_outputs(&outputs, ciphertext_len) };
        return NativeErrorCode::InvalidArgument as i32;
    }

    // SAFETY: Pointer, length, public bounds, and non-overlap checks above make
    // these borrowed input and unique output slices valid for the call.
    let (encryption_key, mac_key, iv, plaintext, ciphertext_output, mac_output) = unsafe {
        (
            inputs[0].as_slice(),
            inputs[1].as_slice(),
            inputs[2].as_slice(),
            inputs[3].as_slice(),
            std::slice::from_raw_parts_mut(ciphertext_ptr, ciphertext_capacity),
            std::slice::from_raw_parts_mut(mac_ptr, mac_capacity),
        )
    };
    match core::fast::encrypt_into(
        encryption_key,
        mac_key,
        iv,
        plaintext,
        ciphertext_output,
        mac_output,
    ) {
        Ok(actual_len) if actual_len == expected_ciphertext_len => {
            // SAFETY: The length slot remains valid for the duration of this export.
            unsafe { ciphertext_len.write(actual_len) };
            NativeErrorCode::Ok as i32
        }
        Ok(_) => {
            ciphertext_output.zeroize();
            mac_output.zeroize();
            NativeErrorCode::Internal as i32
        }
        Err(code) => {
            ciphertext_output.zeroize();
            mac_output.zeroize();
            code as i32
        }
    }
}

#[allow(clippy::too_many_arguments)]
unsafe fn decrypt_fast_inner(
    encryption_key_ptr: *const u8,
    encryption_key_len: usize,
    mac_key_ptr: *const u8,
    mac_key_len: usize,
    iv_ptr: *const u8,
    iv_len: usize,
    ciphertext_ptr: *const u8,
    ciphertext_len: usize,
    expected_mac_ptr: *const u8,
    expected_mac_len: usize,
    plaintext_ptr: *mut u8,
    plaintext_capacity: usize,
    plaintext_len: *mut usize,
) -> i32 {
    if plaintext_len.is_null() {
        return NativeErrorCode::InvalidArgument as i32;
    }
    // SAFETY: Null was rejected, and the export contract guarantees writable
    // access to the caller-owned length slot.
    unsafe { plaintext_len.write(0) };
    let inputs = [
        FastInput::new(encryption_key_ptr, encryption_key_len),
        FastInput::new(mac_key_ptr, mac_key_len),
        FastInput::new(iv_ptr, iv_len),
        FastInput::new(ciphertext_ptr, ciphertext_len),
        FastInput::new(expected_mac_ptr, expected_mac_len),
    ];
    let outputs = [FastOutput::new(plaintext_ptr, plaintext_capacity)];
    if !inputs.iter().copied().all(FastInput::is_valid)
        || !outputs.iter().copied().all(FastOutput::is_valid)
        || plaintext_capacity != ciphertext_len
        || ranges_overlap_any(&inputs, &outputs)
    {
        // SAFETY: The output range was supplied by the caller; clear checks
        // public bounds before dereferencing it.
        unsafe { clear_fast_outputs(&outputs, plaintext_len) };
        return NativeErrorCode::InvalidArgument as i32;
    }

    // SAFETY: Pointer, length, public bounds, and non-overlap checks above make
    // these borrowed input and unique output slices valid for the call.
    let (encryption_key, mac_key, iv, ciphertext, expected_mac, plaintext_output) = unsafe {
        (
            inputs[0].as_slice(),
            inputs[1].as_slice(),
            inputs[2].as_slice(),
            inputs[3].as_slice(),
            inputs[4].as_slice(),
            outputs[0].as_mut_slice(),
        )
    };
    match core::fast::decrypt_into(
        encryption_key,
        mac_key,
        iv,
        ciphertext,
        expected_mac,
        plaintext_output,
    ) {
        Ok(actual_len) if actual_len <= plaintext_capacity => {
            plaintext_output[actual_len..].zeroize();
            // SAFETY: The length slot remains valid for the duration of this export.
            unsafe { plaintext_len.write(actual_len) };
            NativeErrorCode::Ok as i32
        }
        Ok(_) => {
            plaintext_output.zeroize();
            NativeErrorCode::Internal as i32
        }
        Err(code) => {
            plaintext_output.zeroize();
            code as i32
        }
    }
}

#[derive(Clone, Copy)]
struct FastInput {
    ptr: *const u8,
    len: usize,
}

impl FastInput {
    const fn new(ptr: *const u8, len: usize) -> Self {
        Self { ptr, len }
    }

    fn is_valid(self) -> bool {
        self.len <= MAX_FAST_SLICE_BYTES && (self.len == 0 || !self.ptr.is_null())
    }

    unsafe fn as_slice<'a>(self) -> &'a [u8] {
        if self.len == 0 {
            &[]
        } else {
            // SAFETY: `is_valid` rejected null, and the caller contract
            // guarantees this pointer is readable for exactly `len` bytes.
            unsafe { std::slice::from_raw_parts(self.ptr, self.len) }
        }
    }
}

impl FastOutput {
    fn is_valid(self) -> bool {
        self.is_bounded() && (self.len == 0 || !self.ptr.is_null())
    }
}

fn encrypted_size(plaintext_len: usize) -> Option<usize> {
    if plaintext_len > MAX_FAST_SLICE_BYTES {
        return None;
    }
    plaintext_len
        .checked_div(AES_BLOCK_BYTES)?
        .checked_add(1)?
        .checked_mul(AES_BLOCK_BYTES)
        .filter(|length| *length <= MAX_FAST_SLICE_BYTES)
}

fn ranges_overlap_any(inputs: &[FastInput], outputs: &[FastOutput]) -> bool {
    inputs.iter().any(|input| {
        outputs
            .iter()
            .any(|output| ranges_overlap(input.ptr, input.len, output.ptr.cast_const(), output.len))
    })
}

fn ranges_overlap(left: *const u8, left_len: usize, right: *const u8, right_len: usize) -> bool {
    if left_len == 0 || right_len == 0 {
        return false;
    }
    let left_start = left.addr();
    let right_start = right.addr();
    let Some(left_end) = left_start.checked_add(left_len) else {
        return true;
    };
    let Some(right_end) = right_start.checked_add(right_len) else {
        return true;
    };
    left_start < right_end && right_start < left_end
}

fn length_slot_is_separate(
    output_len: *mut usize,
    inputs: &[FastInput],
    outputs: &[FastOutput],
) -> bool {
    if output_len.is_null() || !output_len.addr().is_multiple_of(align_of::<usize>()) {
        return false;
    }
    let slot = output_len.cast::<u8>().cast_const();
    let slot_size = size_of::<usize>();
    inputs
        .iter()
        .all(|input| !ranges_overlap(slot, slot_size, input.ptr, input.len))
        && outputs
            .iter()
            .all(|output| !ranges_overlap(slot, slot_size, output.ptr.cast_const(), output.len))
}

unsafe fn clear_fast_output_ranges(outputs: &[FastOutput]) {
    for output in outputs {
        // SAFETY: The caller contract guarantees the declared range is
        // writable; `FastOutput::clear` additionally checks null and bounds.
        unsafe { output.clear() };
    }
}

unsafe fn clear_fast_outputs(outputs: &[FastOutput], output_len: *mut usize) {
    // SAFETY: The caller contract and fast-boundary validation guarantee the
    // declared output ranges are writable and separate from the length slot.
    unsafe { clear_fast_output_ranges(outputs) };
    if !output_len.is_null() {
        // SAFETY: Null was rejected and the export contract guarantees a
        // writable caller-owned length slot.
        unsafe { output_len.write(0) };
    }
}

fn panic_response_boundary(operation: &'static str, output: *mut KeyguardCryptoBuffer) -> i32 {
    std::panic::catch_unwind(AssertUnwindSafe(|| {
        if output.is_null() {
            return TRANSPORT_INVALID_POINTER;
        }
        // SAFETY: Null was rejected; the caller's export contract guarantees
        // writable access to a single buffer struct.
        unsafe { output.write(KeyguardCryptoBuffer::empty()) };
        let response = core::panic_response(operation);
        // SAFETY: `output` is valid and initialized to the empty state.
        unsafe { write_response(response, output) }
    }))
    .unwrap_or(TRANSPORT_PANIC)
}

unsafe fn run_with_input_inner(
    operation: &'static str,
    input_ptr: *const u8,
    input_len: usize,
    maximum_input_len: usize,
    output: *mut KeyguardCryptoBuffer,
    function: impl FnOnce(&[u8]) -> Vec<u8>,
) -> i32 {
    if output.is_null() || (input_len != 0 && input_ptr.is_null()) {
        return TRANSPORT_INVALID_POINTER;
    }
    // SAFETY: Null was rejected and the caller contract guarantees writable
    // access to one buffer struct.
    unsafe { output.write(KeyguardCryptoBuffer::empty()) };
    if input_len > maximum_input_len || input_len > isize::MAX as usize {
        // SAFETY: `output` is valid and initialized to the empty state.
        return unsafe { write_response(core::resource_limit_response(operation), output) };
    }
    let input = if input_len == 0 {
        &[]
    } else {
        // SAFETY: Null was rejected and the caller contract guarantees a
        // readable allocation of `input_len` bytes for this call.
        unsafe { std::slice::from_raw_parts(input_ptr, input_len) }
    };
    let response = function(input);
    // SAFETY: `output` is valid and initialized to the empty state.
    unsafe { write_response(response, output) }
}

unsafe fn run_without_input_inner(
    output: *mut KeyguardCryptoBuffer,
    function: impl FnOnce() -> Vec<u8>,
) -> i32 {
    if output.is_null() {
        return TRANSPORT_INVALID_POINTER;
    }
    // SAFETY: Null was rejected and the caller contract guarantees writable
    // access to one buffer struct.
    unsafe { output.write(KeyguardCryptoBuffer::empty()) };
    let response = function();
    // SAFETY: `output` is valid and initialized to the empty state.
    unsafe { write_response(response, output) }
}

unsafe fn write_response(response: Vec<u8>, output: *mut KeyguardCryptoBuffer) -> i32 {
    if response.is_empty() {
        return TRANSPORT_INTERNAL;
    }
    // SAFETY: The caller guarantees a valid writable output struct.
    unsafe { transfer_vec(response, output) };
    TRANSPORT_OK
}

unsafe fn transfer_vec(mut value: Vec<u8>, output: *mut KeyguardCryptoBuffer) {
    let buffer = KeyguardCryptoBuffer {
        ptr: value.as_mut_ptr(),
        len: value.len(),
        capacity: value.capacity(),
    };
    std::mem::forget(value);
    // SAFETY: The caller guarantees a valid writable output struct.
    unsafe { output.write(buffer) };
}

#[cfg(test)]
mod tests {
    use super::*;
    use keyguard_crypto_core::protocol::{NativeErrorCode, NativeResponse};
    use prost::Message;

    #[test]
    fn c_abi_reports_version_and_capabilities() {
        assert_eq!(keyguard_crypto_abi_version(), 1);
        assert_eq!(keyguard_crypto_capabilities(), core::CAPABILITIES);
    }

    #[test]
    fn random_int_c_abi_preserves_bounds_and_clears_failures() {
        let mut output = i32::MIN;
        // SAFETY: `output` is writable for the complete call.
        let status = unsafe { keyguard_crypto_random_int(1, &mut output) };
        assert_eq!(status, NativeErrorCode::Ok as i32);
        assert_eq!(output, 0);

        output = i32::MIN;
        // SAFETY: `output` is writable; the oversized bound exercises typed failure.
        let status = unsafe { keyguard_crypto_random_int(i32::MAX as u32 + 1, &mut output) };
        assert_eq!(status, NativeErrorCode::InvalidArgument as i32);
        assert_eq!(output, 0);

        // SAFETY: The deliberately null output exercises boundary validation.
        let status = unsafe { keyguard_crypto_random_int(1, ptr::null_mut()) };
        assert_eq!(status, NativeErrorCode::InvalidArgument as i32);
    }

    #[test]
    fn fast_c_abi_round_trips_and_reports_authenticated_failures() {
        let encryption_key: Vec<u8> = (0_u8..32).collect();
        let mac_key: Vec<u8> = (32_u8..64).collect();
        let iv: Vec<u8> = (64_u8..80).collect();
        let plaintext = b"fast C ABI Bitwarden frame";
        let mut ciphertext = vec![0xaa; 32];
        let mut mac = vec![0xaa; HMAC_SHA256_BYTES];
        let mut ciphertext_len = usize::MAX;

        // SAFETY: All input and output allocations are disjoint and valid for
        // the declared lengths for the complete call.
        let status = unsafe {
            keyguard_crypto_aes_cbc_pkcs7_hmac_sha256_encrypt(
                encryption_key.as_ptr(),
                encryption_key.len(),
                mac_key.as_ptr(),
                mac_key.len(),
                iv.as_ptr(),
                iv.len(),
                plaintext.as_ptr(),
                plaintext.len(),
                ciphertext.as_mut_ptr(),
                ciphertext.len(),
                mac.as_mut_ptr(),
                mac.len(),
                &mut ciphertext_len,
            )
        };
        assert_eq!(status, NativeErrorCode::Ok as i32);
        assert_eq!(ciphertext_len, ciphertext.len());

        let mut decrypted = vec![0xaa; ciphertext.len()];
        let mut plaintext_len = usize::MAX;
        // SAFETY: All input and output allocations are disjoint and valid for
        // the declared lengths for the complete call.
        let status = unsafe {
            keyguard_crypto_aes_cbc_pkcs7_hmac_sha256_decrypt(
                encryption_key.as_ptr(),
                encryption_key.len(),
                mac_key.as_ptr(),
                mac_key.len(),
                iv.as_ptr(),
                iv.len(),
                ciphertext.as_ptr(),
                ciphertext.len(),
                mac.as_ptr(),
                mac.len(),
                decrypted.as_mut_ptr(),
                decrypted.len(),
                &mut plaintext_len,
            )
        };
        assert_eq!(status, NativeErrorCode::Ok as i32);
        assert_eq!(plaintext_len, plaintext.len());
        assert_eq!(&decrypted[..plaintext_len], plaintext);
        assert!(decrypted[plaintext_len..].iter().all(|byte| *byte == 0));

        decrypted.fill(0xaa);
        plaintext_len = usize::MAX;
        // A short expected tag must still reach the core authentication check,
        // not be rejected as an argument shape at the bridge.
        // SAFETY: Pointer and output contracts match the preceding valid call.
        let status = unsafe {
            keyguard_crypto_aes_cbc_pkcs7_hmac_sha256_decrypt(
                encryption_key.as_ptr(),
                encryption_key.len(),
                mac_key.as_ptr(),
                mac_key.len(),
                iv.as_ptr(),
                iv.len(),
                ciphertext.as_ptr(),
                ciphertext.len(),
                mac.as_ptr(),
                mac.len() - 1,
                decrypted.as_mut_ptr(),
                decrypted.len(),
                &mut plaintext_len,
            )
        };
        assert_eq!(status, NativeErrorCode::AuthenticationFailed as i32);
        assert_eq!(plaintext_len, 0);
        assert!(decrypted.iter().all(|byte| *byte == 0));
    }

    #[test]
    fn fast_c_abi_accepts_null_pointers_for_zero_length_slices() {
        let encryption_key = [0x11_u8; 32];
        let mac_key = [0x22_u8; 32];
        let iv = [0x33_u8; AES_BLOCK_BYTES];
        let mut plaintext_len = usize::MAX;

        // SAFETY: Every non-empty range is valid. The C contract permits null
        // pointers for zero-length ciphertext, MAC, and output ranges.
        let status = unsafe {
            keyguard_crypto_aes_cbc_pkcs7_hmac_sha256_decrypt(
                encryption_key.as_ptr(),
                encryption_key.len(),
                mac_key.as_ptr(),
                mac_key.len(),
                iv.as_ptr(),
                iv.len(),
                ptr::null(),
                0,
                ptr::null(),
                0,
                ptr::null_mut(),
                0,
                &mut plaintext_len,
            )
        };
        assert_eq!(status, NativeErrorCode::AuthenticationFailed as i32);
        assert_eq!(plaintext_len, 0);
    }

    #[test]
    fn fast_c_boundary_clears_outputs_after_panic() {
        let mut output = [0xaa_u8; 32];
        let mut output_len = usize::MAX;
        let outputs = [FastOutput::new(output.as_mut_ptr(), output.len())];
        let status = fast_boundary(&[], &outputs, &mut output_len, || -> i32 {
            panic!("test-only panic payload must not cross fast FFI")
        });
        assert_eq!(status, NativeErrorCode::Panic as i32);
        assert_eq!(output_len, 0);
        assert!(output.iter().all(|byte| *byte == 0));
    }

    #[test]
    fn fast_c_abi_clears_outputs_when_length_slot_is_null() {
        let encryption_key = [0_u8; 32];
        let mac_key = [1_u8; 32];
        let iv = [2_u8; AES_BLOCK_BYTES];
        let plaintext = [3_u8; 1];
        let mut ciphertext = [0xaa_u8; AES_BLOCK_BYTES];
        let mut mac = [0xaa_u8; HMAC_SHA256_BYTES];

        // SAFETY: Every non-null range is valid and disjoint. The deliberately
        // null length slot exercises validation at the fast boundary.
        let status = unsafe {
            keyguard_crypto_aes_cbc_pkcs7_hmac_sha256_encrypt(
                encryption_key.as_ptr(),
                encryption_key.len(),
                mac_key.as_ptr(),
                mac_key.len(),
                iv.as_ptr(),
                iv.len(),
                plaintext.as_ptr(),
                plaintext.len(),
                ciphertext.as_mut_ptr(),
                ciphertext.len(),
                mac.as_mut_ptr(),
                mac.len(),
                ptr::null_mut(),
            )
        };
        assert_eq!(status, NativeErrorCode::InvalidArgument as i32);
        assert!(ciphertext.iter().all(|byte| *byte == 0));
        assert!(mac.iter().all(|byte| *byte == 0));
    }

    #[test]
    fn fast_c_abi_rejects_length_slot_overlapping_output() {
        let encryption_key = [0_u8; 32];
        let mac_key = [1_u8; 32];
        let iv = [2_u8; AES_BLOCK_BYTES];
        let plaintext = [3_u8; 1];
        let mut ciphertext = [0xaa_u8; AES_BLOCK_BYTES];
        let mut mac = [0xaa_u8; HMAC_SHA256_BYTES];
        let overlapping_length = ciphertext.as_mut_ptr().cast::<usize>();

        // SAFETY: Every byte range is valid. The deliberately overlapping
        // length slot exercises fail-closed C boundary validation.
        let status = unsafe {
            keyguard_crypto_aes_cbc_pkcs7_hmac_sha256_encrypt(
                encryption_key.as_ptr(),
                encryption_key.len(),
                mac_key.as_ptr(),
                mac_key.len(),
                iv.as_ptr(),
                iv.len(),
                plaintext.as_ptr(),
                plaintext.len(),
                ciphertext.as_mut_ptr(),
                ciphertext.len(),
                mac.as_mut_ptr(),
                mac.len(),
                overlapping_length,
            )
        };
        assert_eq!(status, NativeErrorCode::InvalidArgument as i32);
        assert!(ciphertext.iter().all(|byte| *byte == 0));
        assert!(mac.iter().all(|byte| *byte == 0));
    }

    #[test]
    fn c_abi_rejects_null_pointers() {
        let mut output = KeyguardCryptoBuffer::empty();
        // SAFETY: Output is valid; the deliberately invalid input exercises validation.
        let status = unsafe { keyguard_crypto_call(ptr::null(), 1, &mut output) };
        assert_eq!(status, TRANSPORT_INVALID_POINTER);
    }

    #[test]
    fn c_abi_returns_and_frees_typed_response() {
        let input = [0xff_u8];
        let mut output = KeyguardCryptoBuffer::empty();
        // SAFETY: Input and output allocations remain valid for the call.
        let status = unsafe { keyguard_crypto_call(input.as_ptr(), input.len(), &mut output) };
        assert_eq!(status, TRANSPORT_OK);
        // SAFETY: The returned ptr/len are owned by this buffer until free.
        let bytes = unsafe { std::slice::from_raw_parts(output.ptr, output.len) };
        let response = NativeResponse::decode(bytes).expect("response must decode");
        assert_eq!(
            response.status.map(|status| status.code),
            Some(NativeErrorCode::InvalidRequest as i32)
        );
        // SAFETY: Buffer came from this library and has not yet been freed.
        unsafe { keyguard_crypto_buffer_free(&mut output) };
        assert!(output.ptr.is_null());
        assert_eq!(output.len, 0);
        // SAFETY: Freeing a cleared buffer is explicitly a no-op.
        unsafe { keyguard_crypto_buffer_free(&mut output) };
    }

    #[test]
    fn oversized_input_is_rejected_before_pointer_dereference() {
        let input = ptr::NonNull::<u8>::dangling().as_ptr();
        let mut output = KeyguardCryptoBuffer::empty();
        // SAFETY: The deliberately dangling input is never dereferenced because
        // its declared length exceeds the public envelope limit.
        let status = unsafe {
            keyguard_crypto_call(input, core::MAX_CONTROL_ENVELOPE_BYTES + 1, &mut output)
        };
        assert_eq!(status, TRANSPORT_OK);
        // SAFETY: The returned ptr/len are owned by this buffer until free.
        let bytes = unsafe { std::slice::from_raw_parts(output.ptr, output.len) };
        let response = NativeResponse::decode(bytes).expect("response must decode");
        assert_eq!(
            response.status.map(|status| status.code),
            Some(NativeErrorCode::ResourceLimit as i32)
        );
        // SAFETY: Buffer came from this library and has not yet been freed.
        unsafe { keyguard_crypto_buffer_free(&mut output) };
    }

    #[test]
    fn boundary_contains_panics_as_typed_response() {
        let mut output = KeyguardCryptoBuffer::empty();
        let status = response_boundary("test_panic", &mut output, || -> i32 {
            panic!("test-only panic payload must not cross FFI")
        });
        assert_eq!(status, TRANSPORT_OK);
        // SAFETY: The returned ptr/len are owned by this buffer until free.
        let bytes = unsafe { std::slice::from_raw_parts(output.ptr, output.len) };
        let response = NativeResponse::decode(bytes).expect("panic response must decode");
        assert_eq!(
            response.status.map(|status| status.code),
            Some(NativeErrorCode::Panic as i32)
        );
        // SAFETY: Buffer came from this library and has not yet been freed.
        unsafe { keyguard_crypto_buffer_free(&mut output) };
    }
}
