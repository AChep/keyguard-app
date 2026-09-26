//! Process ownership and authenticated activation shared by JNI and C consumers.
//!
//! Ownership is held separately from transport availability. A delivery failure
//! never authorizes starting another primary. There is no filesystem watcher or
//! recurring idle timer. The caller must retain the primary until shared-state
//! services finish shutting down (or the process terminates).

pub mod bridge;
mod coordinator;
mod error;
mod protocol;

#[cfg(unix)]
#[path = "unix.rs"]
mod platform;
#[cfg(windows)]
#[path = "windows.rs"]
mod platform;

use std::sync::{Condvar, Mutex};

pub use coordinator::{Acquisition, Instance, acquire_or_activate};

/// Version of the native bridge ABI.
pub const ABI_VERSION: u32 = 2;

pub use error::{Error, Failure};

/// Result with a stable failure category and safe native diagnostics.
pub type Result<T> = std::result::Result<T, Failure>;

#[derive(Default)]
struct EventState {
    pending: bool,
    stopped: bool,
    failure: Option<Failure>,
}

#[derive(Default)]
pub(crate) struct Events {
    state: Mutex<EventState>,
    changed: Condvar,
}

impl Events {
    pub(crate) fn activate(&self) -> bool {
        let Ok(mut state) = self.state.lock() else {
            return false;
        };
        if state.stopped || state.failure.is_some() {
            return false;
        }
        state.pending = true;
        self.changed.notify_one();
        true
    }

    pub(crate) fn fail(&self, failure: impl Into<Failure>) {
        if let Ok(mut state) = self.state.lock() {
            state.failure = Some(failure.into());
            self.changed.notify_all();
        }
    }

    fn stop(&self) {
        if let Ok(mut state) = self.state.lock() {
            state.stopped = true;
            state.pending = false;
            self.changed.notify_all();
        }
    }

    pub(crate) fn is_stopped(&self) -> bool {
        self.state
            .lock()
            .map_or(true, |state| state.stopped || state.failure.is_some())
    }

    fn wait(&self) -> Result<bool> {
        let mut state = self.state.lock().map_err(|_| Error::Internal)?;
        loop {
            if state.stopped {
                return Ok(false);
            }
            if state.pending {
                state.pending = false;
                return Ok(true);
            }
            if let Some(failure) = state.failure {
                return Err(failure);
            }
            state = self.changed.wait(state).map_err(|_| Error::Internal)?;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn worker_failure_preserves_acknowledged_activation_and_stop_takes_precedence() {
        let events = Events::default();
        assert!(events.activate());
        events.fail(Error::Io);
        assert!(!events.activate());
        assert_eq!(events.wait(), Ok(true));
        assert_eq!(events.wait(), Err(Error::Io.into()));
        events.stop();
        assert_eq!(events.wait(), Ok(false));
    }
}
