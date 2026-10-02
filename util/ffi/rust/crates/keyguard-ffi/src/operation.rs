//! The cancellable operation protocol of the hardware bridges.
//!
//! A caller creates a handle, may cancel it from any thread, executes one
//! bounded request with it, and closes it. A response is a zero status byte
//! followed by the body, or a single non-zero status byte.

use std::{
    collections::BTreeMap,
    ptr, slice,
    sync::{
        Arc, Mutex, MutexGuard,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
};

use zeroize::Zeroizing;

use crate::{PanicHook, contained};

/// Status byte of a successful response.
const OK: u8 = 0;

/// Largest number of live handles.
const MAX_OPERATIONS: usize = 64;

/// Status codes the shared protocol reports. Each bridge numbers its own.
pub trait OperationStatus: Copy {
    /// The handle or the request violates the ABI contract.
    const INVALID_ARGUMENT: Self;
    /// The handle was canceled or closed.
    const CANCELED: Self;
    /// The backend produced a response outside the bounds.
    const PROTOCOL: Self;
    /// Another operation holds the device.
    const BUSY: Self;
    /// The bridge failed internally.
    const INTERNAL: Self;

    /// Returns the status byte.
    fn code(self) -> u8;
}

/// Request and response bounds of a bridge.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct OperationLimits {
    /// Smallest accepted request.
    pub min_request: usize,
    /// Largest accepted request.
    pub max_request: usize,
    /// Size of the largest response, status byte included.
    pub max_response: usize,
}

impl OperationLimits {
    pub(crate) const fn accepts_request(self, length: usize) -> bool {
        self.min_request <= length && length <= self.max_request
    }
}

/// Cancellation tokens of the live operations, and the lock that serializes
/// device access.
pub struct OperationRegistry {
    next_id: AtomicU64,
    operations: Mutex<BTreeMap<u64, Arc<AtomicBool>>>,
    device: Mutex<()>,
}

impl OperationRegistry {
    /// Returns an empty registry.
    #[must_use]
    pub const fn new() -> Self {
        Self {
            next_id: AtomicU64::new(1),
            operations: Mutex::new(BTreeMap::new()),
            device: Mutex::new(()),
        }
    }

    /// Allocates a cancellation token, not a device. Returns zero on failure.
    pub fn create(&self) -> u64 {
        let Ok(mut operations) = self.operations.lock() else {
            return 0;
        };
        if operations.len() >= MAX_OPERATIONS {
            return 0;
        }
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        if id == 0 || id > i64::MAX as u64 {
            return 0;
        }
        operations.insert(id, Arc::new(AtomicBool::new(false)));
        id
    }

    /// Cancels an operation. Can run concurrently with its execution.
    pub fn cancel(&self, id: u64) {
        if let Ok(operations) = self.operations.lock()
            && let Some(token) = operations.get(&id)
        {
            token.store(true, Ordering::Release);
        }
    }

    /// Removes the token; an in-flight operation keeps its own copy and is
    /// canceled.
    pub fn close(&self, id: u64) {
        if let Ok(mut operations) = self.operations.lock()
            && let Some(token) = operations.remove(&id)
        {
            token.store(true, Ordering::Release);
        }
    }

    /// Claims the device for the operation `id`.
    ///
    /// # Errors
    ///
    /// Returns [`OperationStatus::INTERNAL`] for a poisoned registry,
    /// [`OperationStatus::INVALID_ARGUMENT`] for an unknown handle,
    /// [`OperationStatus::CANCELED`] for a canceled one, and
    /// [`OperationStatus::BUSY`] while another operation holds the device.
    pub fn begin<E: OperationStatus>(&self, id: u64) -> Result<OperationGuard<'_>, E> {
        let canceled = self
            .operations
            .lock()
            .map_err(|_| E::INTERNAL)?
            .get(&id)
            .cloned()
            .ok_or(E::INVALID_ARGUMENT)?;
        if canceled.load(Ordering::Acquire) {
            return Err(E::CANCELED);
        }
        let device = self.device.try_lock().map_err(|_| E::BUSY)?;
        Ok(OperationGuard {
            canceled,
            _device: device,
        })
    }
}

impl Default for OperationRegistry {
    fn default() -> Self {
        Self::new()
    }
}

/// A claimed device. Dropping the guard releases it.
pub struct OperationGuard<'a> {
    canceled: Arc<AtomicBool>,
    _device: MutexGuard<'a, ()>,
}

impl OperationGuard<'_> {
    /// Returns the cancellation token the backend polls.
    #[must_use]
    pub fn canceled(&self) -> &AtomicBool {
        &self.canceled
    }
}

/// Frames a backend result as a response and wipes the body.
///
/// A body of `1..max_response` bytes follows the zero status byte; any other
/// body length is [`OperationStatus::PROTOCOL`].
#[must_use]
pub fn respond<E: OperationStatus>(result: Result<Vec<u8>, E>, max_response: usize) -> Vec<u8> {
    match result {
        Ok(body) => {
            let body = Zeroizing::new(body);
            if body.is_empty() || body.len() >= max_response {
                return vec![E::PROTOCOL.code()];
            }
            let mut response = Vec::with_capacity(body.len() + 1);
            response.push(OK);
            response.extend_from_slice(&body);
            response
        }
        Err(error) => vec![error.code()],
    }
}

/// Executes a request borrowed from a C caller and copies the response into
/// the caller's buffer.
///
/// Returns zero for invalid buffers; otherwise returns the number of bytes
/// written.
///
/// # Safety
///
/// `input` must identify `length` readable bytes and `output` `capacity`
/// writable bytes. The buffers must not overlap. Neither pointer is retained.
#[allow(unsafe_code)]
pub unsafe fn execute_into<E: OperationStatus>(
    hook: PanicHook,
    limits: OperationLimits,
    input: *const u8,
    length: usize,
    output: *mut u8,
    capacity: usize,
    execute: impl FnOnce(&[u8]) -> Vec<u8>,
) -> usize {
    if input.is_null()
        || output.is_null()
        || !limits.accepts_request(length)
        || capacity < limits.max_response
    {
        return 0;
    }
    let result = contained(hook, || {
        // SAFETY: The caller guarantees `length` readable bytes; null and
        // oversized inputs were rejected.
        execute(unsafe { slice::from_raw_parts(input, length) })
    })
    .unwrap_or_else(|_| vec![E::INTERNAL.code()]);
    let result = Zeroizing::new(result);
    // A bounded response always fits; this keeps the copy in bounds whatever
    // `execute` returns.
    let internal = [E::INTERNAL.code()];
    let response = if result.len() <= capacity {
        &result[..]
    } else {
        &internal[..]
    };
    // SAFETY: The output has `capacity` writable bytes, the response is no
    // longer, and the caller guarantees the buffers are disjoint.
    unsafe {
        ptr::copy_nonoverlapping(response.as_ptr(), output, response.len());
    }
    response.len()
}

#[cfg(test)]
#[allow(unsafe_code)]
mod tests {
    use super::*;

    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    #[repr(u8)]
    enum Status {
        InvalidArgument = 1,
        Canceled = 2,
        Protocol = 3,
        Busy = 4,
        Internal = 5,
    }

    impl OperationStatus for Status {
        const INVALID_ARGUMENT: Self = Self::InvalidArgument;
        const CANCELED: Self = Self::Canceled;
        const PROTOCOL: Self = Self::Protocol;
        const BUSY: Self = Self::Busy;
        const INTERNAL: Self = Self::Internal;

        fn code(self) -> u8 {
            self as u8
        }
    }

    const LIMITS: OperationLimits = OperationLimits {
        min_request: 2,
        max_request: 4,
        max_response: 4,
    };

    fn begin(registry: &OperationRegistry, id: u64) -> Result<(), Status> {
        registry.begin::<Status>(id).map(|_| ())
    }

    #[test]
    fn handles_are_capped_and_freed_by_close() {
        let registry = OperationRegistry::new();
        let ids: Vec<_> = (0..MAX_OPERATIONS).map(|_| registry.create()).collect();
        assert!(ids.iter().all(|&id| id != 0));
        assert_eq!(registry.create(), 0);
        registry.close(ids[0]);
        assert_ne!(registry.create(), 0);
    }

    #[test]
    fn begin_reports_unknown_canceled_and_closed_handles() {
        let registry = OperationRegistry::new();
        assert_eq!(begin(&registry, 1), Err(Status::InvalidArgument));
        let id = registry.create();
        assert_eq!(begin(&registry, id), Ok(()));
        registry.cancel(id);
        registry.cancel(id);
        assert_eq!(begin(&registry, id), Err(Status::Canceled));
        registry.close(id);
        registry.close(id);
        assert_eq!(begin(&registry, id), Err(Status::InvalidArgument));
    }

    #[test]
    fn a_claimed_device_is_busy_and_close_cancels_it() {
        let registry = OperationRegistry::new();
        let first = registry.create();
        let second = registry.create();
        let guard = registry.begin::<Status>(first).unwrap();
        assert_eq!(begin(&registry, second), Err(Status::Busy));
        registry.close(first);
        assert!(guard.canceled().load(Ordering::Acquire));
        drop(guard);
        assert_eq!(begin(&registry, second), Ok(()));
    }

    #[test]
    fn responses_are_bounded() {
        assert_eq!(
            respond::<Status>(Ok(vec![]), 4),
            vec![Status::Protocol as u8]
        );
        assert_eq!(respond::<Status>(Ok(vec![7, 8, 9]), 4), vec![OK, 7, 8, 9]);
        assert_eq!(
            respond::<Status>(Ok(vec![7, 8, 9, 10]), 4),
            vec![Status::Protocol as u8]
        );
        assert_eq!(respond(Err(Status::Busy), 4), vec![Status::Busy as u8]);
    }

    #[test]
    fn invalid_buffers_do_not_execute() {
        let request = [1_u8, 2];
        let mut output = [0_u8; 4];
        let run = |input: *const u8, length: usize, output: &mut [u8], capacity: usize| {
            // SAFETY: Every pointer is valid or null, and lengths never exceed
            // the buffers.
            unsafe {
                execute_into::<Status>(
                    PanicHook::Keep,
                    LIMITS,
                    input,
                    length,
                    output.as_mut_ptr(),
                    capacity,
                    |_| panic!("an invalid buffer must not execute"),
                )
            }
        };
        assert_eq!(run(ptr::null(), 2, &mut output, 4), 0);
        assert_eq!(run(request.as_ptr(), 1, &mut output, 4), 0);
        assert_eq!(run(request.as_ptr(), 2, &mut output, 3), 0);
    }

    #[test]
    fn responses_are_copied_and_failures_are_internal() {
        let request = [1_u8, 2];
        let run = |execute: fn(&[u8]) -> Vec<u8>| {
            let mut output = [0_u8; 4];
            // SAFETY: Both buffers are valid for their exact lengths.
            let written = unsafe {
                execute_into::<Status>(
                    PanicHook::Keep,
                    LIMITS,
                    request.as_ptr(),
                    request.len(),
                    output.as_mut_ptr(),
                    output.len(),
                    execute,
                )
            };
            output[..written].to_vec()
        };
        assert_eq!(run(|request| [&[OK], request].concat()), vec![OK, 1, 2]);
        assert_eq!(run(|_| panic!("boom")), vec![Status::Internal as u8]);
        assert_eq!(run(|_| vec![OK; 5]), vec![Status::Internal as u8]);
    }
}
