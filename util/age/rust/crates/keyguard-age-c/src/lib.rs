//! Bounded C ABI. Pointers are borrowed only for the duration of execute.
use keyguard_age_core as core;
use keyguard_ffi::{PanicHook, contained, execute_into};

/// Unit tests keep Rust's default hook so a caught assertion still reports its payload.
const PANIC_HOOK: PanicHook = if cfg!(test) {
    PanicHook::Keep
} else {
    PanicHook::Redact
};

/// Native bridge entry point.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_age_abi_version() -> u32 {
    core::ABI_VERSION
}
/// Native bridge entry point.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_age_create() -> u64 {
    contained(PANIC_HOOK, core::create).unwrap_or(0)
}
/// Native bridge entry point.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_age_close(id: u64) {
    let _ = contained(PANIC_HOOK, || core::close(id));
}

/// Executes a request and copies its result into the caller's buffer.
/// Returns zero for invalid buffers; otherwise returns the number of bytes written.
/// # Safety
/// Input must identify `length` readable bytes and output `capacity` writable bytes.
/// The buffers must not overlap. Neither pointer is retained.
/// Native bridge entry point.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_age_execute(
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
