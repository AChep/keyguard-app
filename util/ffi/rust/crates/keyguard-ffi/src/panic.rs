//! The panic boundary every bridge entry point runs behind.

use std::{
    panic::{AssertUnwindSafe, catch_unwind},
    sync::Once,
};

use crate::BRIDGE_PANIC;

/// Which panic hook [`contained`] leaves in place.
///
/// Bridges pick it with `cfg!(test)` in their own crate, where it reflects the
/// bridge's unit tests rather than this crate's.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PanicHook {
    /// Install [`install_redacting_panic_hook`]. Production builds.
    Redact,
    /// Keep Rust's default hook, so a caught test assertion still reports its
    /// payload and source location.
    Keep,
}

static PANIC_HOOK: Once = Once::new();

/// Installs a process-wide panic hook that prints nothing.
///
/// Rust's default hook prints the payload before [`catch_unwind`] runs, and a
/// payload may carry a path, a password or vault data.
pub fn install_redacting_panic_hook() {
    PANIC_HOOK.call_once(|| std::panic::set_hook(Box::new(|_| {})));
}

/// Runs `body` behind a panic boundary; a panic becomes [`BRIDGE_PANIC`].
///
/// The hook is installed *inside* the boundary: `std::panic::set_hook`
/// "panics if called from a panicking thread", and a panic inside
/// `Once::call_once` poisons the `Once` so that every later call panics too.
/// Installed outside the boundary, one such panic would escape the `extern`
/// frame and abort the process on every later bridge call.
///
/// # Errors
///
/// Returns [`BRIDGE_PANIC`] when `body` panics.
pub fn contained<R>(hook: PanicHook, body: impl FnOnce() -> R) -> Result<R, i64> {
    catch_unwind(AssertUnwindSafe(|| {
        if hook == PanicHook::Redact {
            install_redacting_panic_hook();
        }
        body()
    }))
    .map_err(|_| BRIDGE_PANIC)
}

/// Collapses a contained result into the scalar a bridge returns.
#[must_use]
pub fn flatten(result: Result<Result<i64, i64>, i64>) -> i64 {
    match result.and_then(std::convert::identity) {
        Ok(value) | Err(value) => value,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::BRIDGE_INVALID_ARGUMENT;

    #[test]
    fn contained_returns_the_body_result() {
        assert_eq!(contained(PanicHook::Keep, || 7), Ok(7));
    }

    #[test]
    fn contained_panic_uses_the_bridge_panic_failure() {
        assert_eq!(
            contained(PanicHook::Keep, || -> i64 { panic!("boom") }),
            Err(BRIDGE_PANIC),
        );
    }

    #[test]
    fn flatten_keeps_successes_and_failures() {
        assert_eq!(flatten(Ok(Ok(0))), 0);
        assert_eq!(
            flatten(Ok(Err(BRIDGE_INVALID_ARGUMENT))),
            BRIDGE_INVALID_ARGUMENT
        );
        assert_eq!(flatten(Err(BRIDGE_PANIC)), BRIDGE_PANIC);
    }
}
