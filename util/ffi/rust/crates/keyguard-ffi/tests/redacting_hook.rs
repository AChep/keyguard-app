//! The redacting hook is process-wide, so it gets its own test binary.

use keyguard_ffi::{BRIDGE_PANIC, PanicHook, contained};

#[test]
fn repeated_panics_stay_contained_behind_the_redacting_hook() {
    for _ in 0..2 {
        assert_eq!(
            contained(PanicHook::Redact, || -> i64 { panic!("secret") }),
            Err(BRIDGE_PANIC),
        );
    }
    assert_eq!(contained(PanicHook::Redact, || 7), Ok(7));
}
