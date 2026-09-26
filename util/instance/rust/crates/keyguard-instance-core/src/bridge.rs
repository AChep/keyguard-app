//! Panic-contained scalar bridge shared by JNI and C adapters.

use crate::{Acquisition, Error, Failure, Instance, Result};
use std::cell::Cell;
use std::collections::HashMap;
use std::io::Write;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Arc, Mutex, OnceLock};

thread_local! {
    static LAST_FAILURE: Cell<Option<Failure>> = const { Cell::new(None) };
}

/// Clears this thread's diagnostic before a C/JNI operation, including argument validation.
pub fn clear_failure() {
    LAST_FAILURE.set(None);
}

/// The last failed bridge call on this thread. Read before making another bridge call.
pub fn last_failure() -> Option<Failure> {
    LAST_FAILURE.get()
}

/// Records safe diagnostics for native or adapter failures, without input paths or tokens.
pub fn record_failure(error: Failure) {
    LAST_FAILURE.set(Some(error));
    if error.kind() != Error::InvalidHandle {
        let _ = writeln!(std::io::stderr().lock(), "nativeInstance failed: {error}");
    }
}

struct Registry {
    next: u64,
    instances: HashMap<u64, Arc<Instance>>,
}

fn registry() -> &'static Mutex<Registry> {
    static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();
    REGISTRY.get_or_init(|| {
        Mutex::new(Registry {
            next: 1,
            instances: HashMap::new(),
        })
    })
}

fn contained(name: &'static str, operation: impl FnOnce() -> Result<i64>) -> i64 {
    clear_failure();
    let error = match catch_unwind(AssertUnwindSafe(operation)) {
        Ok(Ok(result)) => return result,
        Ok(Err(error)) => error,
        Err(_) => Failure::from(Error::Internal),
    }
    .context(name);
    record_failure(error);
    error.kind() as i64
}

fn lookup(handle: u64) -> Result<Arc<Instance>> {
    let registry = registry().lock().map_err(|_| Error::Internal)?;
    registry
        .instances
        .get(&handle)
        .cloned()
        .ok_or_else(|| Error::InvalidHandle.into())
}

/// Returns a positive primary handle, zero after acknowledged activation, or a
/// negative [`Error`] code. Handles are never reused within this library instance.
pub fn acquire_or_activate(
    coordination_dir: &str,
    runtime_dir: &str,
    identity: &str,
    timeout_ms: u64,
) -> i64 {
    contained("acquire", || {
        match crate::acquire_or_activate(coordination_dir, runtime_dir, identity, timeout_ms)? {
            Acquisition::Activated => Ok(0),
            Acquisition::Primary(instance) => {
                let mut registry = registry().lock().map_err(|_| Error::Internal)?;
                let handle = registry.next;
                if handle > i64::MAX as u64 {
                    return Err(Error::Internal.into());
                }
                registry.next += 1;
                registry.instances.insert(handle, Arc::new(instance));
                Ok(handle as i64)
            }
        }
    })
}

/// Blocks for activation (one), shutdown (zero), or a negative [`Error`] code.
/// The registry lock is released before waiting; concurrent stop/close wakes it.
pub fn wait_event(handle: u64) -> i64 {
    contained("wait_activation", || {
        Ok(i64::from(lookup(handle)?.await_activation()?))
    })
}

/// Stops accepting and wakes waiters, retaining ownership. Returns zero or a
/// negative [`Error`] code.
pub fn stop(handle: u64) -> i64 {
    contained("stop", || {
        lookup(handle)?.stop()?;
        Ok(0)
    })
}

/// Consumes a handle and releases ownership after transport shutdown. Returns
/// zero or a negative [`Error`] code. Repeated close reports `InvalidHandle`.
pub fn close(handle: u64) -> i64 {
    contained("close", || {
        let instance = {
            let mut registry = registry().lock().map_err(|_| Error::Internal)?;
            registry
                .instances
                .remove(&handle)
                .ok_or(Error::InvalidHandle)?
        };
        instance.close()?;
        Ok(0)
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn diagnostics_are_thread_local_and_success_clears_them() {
        let failure = Failure::from(std::io::Error::from_raw_os_error(2)).context("read_endpoint");
        assert_eq!(contained("test", || Err(failure)), Error::Io as i64);
        std::thread::spawn(|| {
            assert_eq!(last_failure(), None);
            assert_eq!(
                contained("other", || Err(Error::Permission.into())),
                Error::Permission as i64
            );
            assert_eq!(last_failure().unwrap().kind(), Error::Permission);
        })
        .join()
        .unwrap();
        assert_eq!(last_failure(), Some(failure));
        assert_eq!(contained("success", || Ok(1)), 1);
        assert_eq!(last_failure(), None);
    }
}
