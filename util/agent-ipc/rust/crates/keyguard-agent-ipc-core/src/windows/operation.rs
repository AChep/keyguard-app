//! One overlapped call on a pipe, and the drain that makes cancelling it safe.
//!
//! Each lane owns an operation [`Context`]: its own manual-reset event and a
//! `Box<OVERLAPPED>`, as in `keyguard-instance-core`.
//!
//! - The guard is armed only after a submission returns `ERROR_IO_PENDING`. While
//!   armed, its `Drop` calls `CancelIoEx(handle, &overlapped)`, then
//!   `GetOverlappedResult(handle, &overlapped, &n, TRUE)`.
//! - Every synchronous outcome (a TRUE return, `ERROR_PIPE_CONNECTED`, the
//!   broken-pipe family, any other immediate error) leaves the guard disarmed and
//!   never waits.
//! - Byte counts always come from `GetOverlappedResult(FALSE)`, with NULL count
//!   pointers at submission.
//! - The zeroized read scratch is a field of the guard: `Drop::drop` (the drain)
//!   runs before the fields drop, so the kernel never writes into freed memory.
//! - The 1-byte observer buffer lives in the inbound lane, owned by the
//!   connection's `Arc`.

use std::{
    mem::zeroed,
    os::windows::io::{AsRawHandle, OwnedHandle},
};

use windows_sys::{
    Win32::{
        Foundation::{ERROR_IO_INCOMPLETE, ERROR_IO_PENDING, GetLastError, WAIT_OBJECT_0},
        System::{
            IO::{CancelIoEx, GetOverlappedResult, OVERLAPPED},
            Threading::{INFINITE, ResetEvent, WaitForMultipleObjects},
        },
    },
    core::BOOL,
};
use zeroize::Zeroizing;

use crate::{Error, Failure, Result};

/// One lane's event and `OVERLAPPED`, used by one call at a time.
pub(super) struct Context {
    event: OwnedHandle,
    overlapped: Box<OVERLAPPED>,
}

// SAFETY: `OVERLAPPED` holds kernel bookkeeping and the raw handle of `event`,
// which this context owns. A lane's lock gives one thread at a time access, and
// every call drains before it releases the context.
unsafe impl Send for Context {}

impl Context {
    pub(super) fn new(operation: &'static str) -> Result<Self> {
        let event = super::event(operation)?;
        // SAFETY: Zero is the documented initial state of every OVERLAPPED field.
        let mut overlapped: Box<OVERLAPPED> = Box::new(unsafe { zeroed() });
        overlapped.hEvent = event.as_raw_handle();
        Ok(Self { event, overlapped })
    }

    #[cfg(test)]
    pub(super) fn event(&self) -> &OwnedHandle {
        &self.event
    }
}

/// How a call ended.
pub(super) enum Completion {
    /// It completed and transferred this many bytes.
    Done(u32),
    /// It failed with this OS error code.
    Failed(u32),
    /// The wake event fired while the call was pending. The call was cancelled and
    /// drained; this is how it ended: a byte count or an OS error code.
    Woken(std::result::Result<u32, u32>),
}

/// The guard of one call on `pipe` through `context`.
pub(super) struct Operation<'a> {
    pipe: &'a OwnedHandle,
    context: &'a mut Context,
    /// The kernel fills it during a read. It drops, zeroized, only after
    /// `Drop::drop` drained the call.
    scratch: Zeroizing<Vec<u8>>,
    armed: bool,
}

impl<'a> Operation<'a> {
    /// Prepares `context` for one submission on `pipe`.
    pub(super) fn new(
        pipe: &'a OwnedHandle,
        context: &'a mut Context,
        operation: &'static str,
    ) -> Result<Self> {
        Self::with_scratch(pipe, context, 0, operation)
    }

    /// As [`Operation::new`], with a zeroized read buffer of `length` bytes.
    pub(super) fn with_scratch(
        pipe: &'a OwnedHandle,
        context: &'a mut Context,
        length: usize,
        operation: &'static str,
    ) -> Result<Self> {
        // SAFETY: The event is open, and no call uses this context: the previous
        // guard drained before it released the exclusive borrow.
        if unsafe { ResetEvent(context.event.as_raw_handle()) } == 0 {
            return Err(Failure::last_os_error(Error::Io, operation, "reset_event"));
        }
        // SAFETY: As above; every field is reset before reuse.
        *context.overlapped = unsafe { zeroed() };
        context.overlapped.hEvent = context.event.as_raw_handle();
        Ok(Self {
            pipe,
            context,
            scratch: Zeroizing::new(vec![0; length]),
            armed: false,
        })
    }

    /// The `OVERLAPPED` to submit with. It stays in place until the call drains.
    pub(super) fn overlapped(&mut self) -> *mut OVERLAPPED {
        &mut *self.context.overlapped
    }

    /// The read buffer to submit with. It stays in place until the call drains.
    pub(super) fn scratch_ptr(&mut self) -> *mut u8 {
        self.scratch.as_mut_ptr()
    }

    /// The read buffer after the call completed.
    pub(super) fn scratch(&self) -> &[u8] {
        &self.scratch
    }

    /// Finishes the call that `submitted` returned for. A pending call arms the
    /// guard and waits for it or for `wake`; every synchronous outcome returns at
    /// once.
    pub(super) fn finish(&mut self, submitted: BOOL, wake: &OwnedHandle) -> Completion {
        if submitted == 0 {
            // SAFETY: No preconditions; read right after the failed submission.
            let code = unsafe { GetLastError() };
            if code != ERROR_IO_PENDING {
                return Completion::Failed(code);
            }
            self.armed = true;
            let handles = [wake.as_raw_handle(), self.context.event.as_raw_handle()];
            // SAFETY: Both events stay open for the whole wait.
            let state = unsafe { WaitForMultipleObjects(2, handles.as_ptr(), 0, INFINITE) };
            if state == WAIT_OBJECT_0 {
                return Completion::Woken(self.drain());
            }
            if state != WAIT_OBJECT_0 + 1 {
                // SAFETY: No preconditions; read right after the failed wait.
                let code = unsafe { GetLastError() };
                // Still armed: the drop drains the call.
                return Completion::Failed(code);
            }
        }
        match self.result(false) {
            // Not complete after all: still armed, so the drop drains it.
            Err(ERROR_IO_INCOMPLETE) => Completion::Failed(ERROR_IO_INCOMPLETE),
            result => {
                self.armed = false;
                match result {
                    Ok(count) => Completion::Done(count),
                    Err(code) => Completion::Failed(code),
                }
            }
        }
    }

    /// Cancels the pending call and waits until the kernel is done with it.
    fn drain(&mut self) -> std::result::Result<u32, u32> {
        // SAFETY: The call is pending on this handle with this OVERLAPPED, and both
        // stay alive. Cancelling a call that already completed is harmless.
        unsafe { CancelIoEx(self.pipe.as_raw_handle(), &*self.context.overlapped) };
        let result = self.result(true);
        self.armed = false;
        result
    }

    /// `GetOverlappedResult`; `wait` blocks until the call completes.
    fn result(&mut self, wait: bool) -> std::result::Result<u32, u32> {
        let mut count = 0;
        // SAFETY: The OVERLAPPED is the one the call was submitted with, it stays in
        // place, and `count` is writable.
        let completed = unsafe {
            GetOverlappedResult(
                self.pipe.as_raw_handle(),
                &*self.context.overlapped,
                &mut count,
                BOOL::from(wait),
            )
        };
        if completed == 0 {
            // SAFETY: No preconditions; read right after the failed call.
            return Err(unsafe { GetLastError() });
        }
        Ok(count)
    }
}

impl Drop for Operation<'_> {
    fn drop(&mut self) {
        if self.armed {
            let _ = self.drain();
        }
    }
}
