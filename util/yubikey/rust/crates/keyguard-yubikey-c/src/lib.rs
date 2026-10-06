//! Bounded C ABI. Pointers are borrowed only for the duration of execute.
use keyguard_ffi::{PanicHook, contained, execute_into};
use keyguard_yubikey_core as core;

/// Unit tests keep Rust's default hook so a caught assertion still reports its payload.
const PANIC_HOOK: PanicHook = if cfg!(test) {
    PanicHook::Keep
} else {
    PanicHook::Redact
};

#[unsafe(no_mangle)]
pub extern "C" fn keyguard_yubikey_abi_version() -> u32 {
    core::ABI_VERSION
}
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_yubikey_create() -> u64 {
    contained(PANIC_HOOK, core::create).unwrap_or(0)
}
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_yubikey_cancel(id: u64) {
    let _ = contained(PANIC_HOOK, || core::cancel(id));
}
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_yubikey_close(id: u64) {
    let _ = contained(PANIC_HOOK, || core::close(id));
}

/// Executes a request and copies its result into the caller's buffer.
/// Returns zero for invalid buffers; otherwise returns the number of bytes written.
/// # Safety
/// Input must identify `length` readable bytes and output `capacity` writable bytes.
/// The buffers must not overlap. Neither pointer is retained.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_yubikey_execute(
    id: u64,
    input: *const u8,
    length: usize,
    output: *mut u8,
    capacity: usize,
) -> usize {
    // SAFETY: Forwarded from this function's own contract.
    unsafe {
        execute_into::<core::Error>(
            PANIC_HOOK,
            core::LIMITS,
            input,
            length,
            output,
            capacity,
            |request| core::execute(id, request),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn invalid_buffers_do_not_execute() {
        let mut output = [0; core::MAX_RESPONSE];
        // SAFETY: A null input is explicitly rejected without dereferencing it.
        let written = unsafe {
            keyguard_yubikey_execute(0, std::ptr::null(), 4, output.as_mut_ptr(), output.len())
        };
        assert_eq!(written, 0);
        let request = [1, 2, 0, 0];
        // SAFETY: Both buffers are valid; the advertised capacity is intentionally too small.
        let written = unsafe {
            keyguard_yubikey_execute(0, request.as_ptr(), request.len(), output.as_mut_ptr(), 1)
        };
        assert_eq!(written, 0);
    }
}
