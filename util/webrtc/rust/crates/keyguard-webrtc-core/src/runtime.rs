//! Tokio runtimes owned by runtime handles.
//!
//! Every blocking export enters its peer's runtime through [`RuntimeEntry`].
//! A call holds a read lock for as long as it uses the runtime, so
//! [`shutdown`] can take the runtime out only after in-flight calls return;
//! closing the peers first makes every blocked call return promptly.

use std::{
    future::Future,
    sync::{
        Arc, PoisonError, RwLock,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};

use keyguard_ffi::{SessionTag, SharedTable};
use tokio::task::JoinSet;

use crate::{
    MAX_RUNTIMES, WebrtcError,
    peer::{PEERS, PeerEntry},
};

/// Worker threads of each runtime.
const WORKER_THREADS: usize = 2;
/// Name of every runtime thread.
const THREAD_NAME: &str = "keyguard-webrtc";
/// How long shutdown waits for the peers to close gracefully.
const CLOSE_TIMEOUT: Duration = Duration::from_secs(1);
/// How long shutdown waits for the runtime's tasks to stop.
const SHUTDOWN_TIMEOUT: Duration = Duration::from_secs(2);

/// Tag of a runtime handle.
const HANDLE_TAG: SessionTag = SessionTag::new(1);

/// The live runtimes.
pub(crate) static RUNTIMES: SharedTable<RuntimeEntry> = SharedTable::new(HANDLE_TAG, MAX_RUNTIMES);

/// One runtime and the peers built on it.
pub(crate) struct RuntimeEntry {
    runtime: RwLock<Option<tokio::runtime::Runtime>>,
    closed: AtomicBool,
}

impl RuntimeEntry {
    /// Whether shutdown started.
    pub(crate) fn is_closed(&self) -> bool {
        self.closed.load(Ordering::Acquire)
    }

    /// Answers [`WebrtcError::Closed`] once shutdown started.
    pub(crate) fn check_open(&self) -> Result<(), WebrtcError> {
        if self.is_closed() {
            Err(WebrtcError::Closed)
        } else {
            Ok(())
        }
    }

    /// Runs `future` to completion on the calling thread.
    pub(crate) fn block_on<F: Future>(&self, future: F) -> Result<F::Output, WebrtcError> {
        if self.is_closed() {
            return Err(WebrtcError::Closed);
        }
        let runtime = self.runtime.read().unwrap_or_else(PoisonError::into_inner);
        let runtime = runtime.as_ref().ok_or(WebrtcError::Closed)?;
        Ok(runtime.block_on(future))
    }

    /// Runs `future` in the background.
    pub(crate) fn spawn<F>(&self, future: F) -> Result<(), WebrtcError>
    where
        F: Future<Output = ()> + Send + 'static,
    {
        let runtime = self.runtime.read().unwrap_or_else(PoisonError::into_inner);
        let runtime = runtime.as_ref().ok_or(WebrtcError::Closed)?;
        runtime.spawn(future);
        Ok(())
    }
}

/// Creates a runtime and returns its handle.
///
/// # Errors
///
/// Returns [`WebrtcError::Internal`] when the threads cannot be started, and
/// [`WebrtcError::Busy`] when [`MAX_RUNTIMES`] are live.
pub fn runtime_new() -> Result<u64, WebrtcError> {
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(WORKER_THREADS)
        .thread_name(THREAD_NAME)
        .enable_all()
        .build()
        .map_err(|_| WebrtcError::Internal)?;
    let entry = Arc::new(RuntimeEntry {
        runtime: RwLock::new(Some(runtime)),
        closed: AtomicBool::new(false),
    });
    Ok(RUNTIMES.insert(entry)?)
}

/// Closes every peer of the runtime, then stops the runtime.
///
/// Idempotent: shutting down a runtime that is already shut down succeeds.
/// Waits at most about [`CLOSE_TIMEOUT`] plus [`SHUTDOWN_TIMEOUT`], plus the
/// remainder of any request another thread has in flight.
///
/// # Errors
///
/// Returns [`WebrtcError::InvalidHandle`] for a value that is not a runtime handle.
pub fn runtime_shutdown(handle: u64) -> Result<(), WebrtcError> {
    let Some(entry) = RUNTIMES.remove(handle)? else {
        return Ok(());
    };
    // Set before collecting the peers: a concurrent `peer_new` checks it under
    // the same lock, so no peer can be added after the collection.
    entry.closed.store(true, Ordering::Release);
    let peers: Vec<Arc<PeerEntry>> = PEERS.remove_where(|peer| Arc::ptr_eq(peer.runtime(), &entry));
    for peer in &peers {
        peer.mark_closed();
    }
    {
        let runtime = entry.runtime.read().unwrap_or_else(PoisonError::into_inner);
        if let Some(runtime) = runtime.as_ref() {
            let mut closes = JoinSet::new();
            for peer in &peers {
                let connection = Arc::clone(peer.connection());
                closes.spawn_on(
                    async move {
                        let _ = connection.close().await;
                    },
                    runtime.handle(),
                );
            }
            runtime.block_on(async {
                let _ = tokio::time::timeout(CLOSE_TIMEOUT, closes.join_all()).await;
            });
        }
    }
    drop(peers);
    let runtime = entry
        .runtime
        .write()
        .unwrap_or_else(PoisonError::into_inner)
        .take();
    if let Some(runtime) = runtime {
        runtime.shutdown_timeout(SHUTDOWN_TIMEOUT);
    }
    Ok(())
}
