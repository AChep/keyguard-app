//! Bounded C ABI. Pointers are borrowed only for the duration of execute.
use keyguard_fido2_core as core;
use std::panic::{AssertUnwindSafe, catch_unwind};

#[unsafe(no_mangle)]
pub extern "C" fn keyguard_fido2_abi_version() -> u32 {
    core::ABI_VERSION
}
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_fido2_create() -> u64 {
    catch_unwind(core::create).unwrap_or(0)
}
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_fido2_cancel(id: u64) {
    let _ = catch_unwind(|| core::cancel(id));
}
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_fido2_close(id: u64) {
    let _ = catch_unwind(|| core::close(id));
}

/// Executes a request and copies its result into the caller's buffer.
/// Returns zero for invalid buffers; otherwise returns the number of bytes written.
/// # Safety
/// Input must identify `length` readable bytes and output `capacity` writable bytes.
/// The buffers must not overlap. Neither pointer is retained.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_fido2_execute(
    id: u64,
    input: *const u8,
    length: usize,
    output: *mut u8,
    capacity: usize,
) -> usize {
    if input.is_null()
        || output.is_null()
        || !(core::HEADER_LENGTH..=core::MAX_REQUEST).contains(&length)
        || capacity < core::MAX_RESPONSE
    {
        return 0;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: The caller guarantees readable bytes; null and oversized buffers were rejected.
        core::execute(id, unsafe { std::slice::from_raw_parts(input, length) })
    }))
    .unwrap_or_else(|_| vec![core::Error::Internal as u8]);
    let result = zeroize::Zeroizing::new(result);
    // SAFETY: The output has at least MAX_RESPONSE writable bytes and is disjoint from result.
    unsafe {
        std::ptr::copy_nonoverlapping(result.as_ptr(), output, result.len());
    }
    result.len()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn invalid_buffers_do_not_execute() {
        let mut output = [0; core::MAX_RESPONSE];
        // SAFETY: A null input is explicitly rejected without dereferencing it.
        let written = unsafe {
            keyguard_fido2_execute(0, std::ptr::null(), 4, output.as_mut_ptr(), output.len())
        };
        assert_eq!(written, 0);
        let mut request = [0; core::HEADER_LENGTH];
        request[0] = 1;
        // SAFETY: Both buffers are valid; the advertised capacity is intentionally too small.
        let written = unsafe {
            keyguard_fido2_execute(0, request.as_ptr(), request.len(), output.as_mut_ptr(), 1)
        };
        assert_eq!(written, 0);
    }
}
