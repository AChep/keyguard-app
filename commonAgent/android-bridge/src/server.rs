//! Bounded accept loop of a helper's Unix socket, and its shutdown signals.

use anyhow::{Context, Result};
use std::future::Future;
use std::io::{self, ErrorKind};
use std::sync::Arc;
use tokio::net::{UnixListener, UnixStream};
use tokio::signal::unix::{signal, SignalKind};
use tokio::sync::Semaphore;
use tokio::task::JoinSet;
use tokio::time::{sleep, Duration};

use crate::log_debug;

const ACCEPT_RETRY_DELAY: Duration = Duration::from_millis(100);

/// Keep aligned with `AgentBridgeContract.MAX_CONCURRENT_SESSIONS` in the app.
pub const MAX_CONCURRENT_SESSIONS: usize = 8;

/// Serves at most [`MAX_CONCURRENT_SESSIONS`] clients at a time, each with `handle`.
pub async fn serve_clients<F, Fut>(listener: UnixListener, debug: bool, handle: F) -> Result<()>
where
    F: Fn(UnixStream) -> Fut,
    Fut: Future<Output = Result<()>> + Send + 'static,
{
    let session_permits = Arc::new(Semaphore::new(MAX_CONCURRENT_SESSIONS));
    let mut sessions = JoinSet::new();

    loop {
        while let Some(result) = sessions.try_join_next() {
            if let Err(err) = result {
                log_debug(debug, &format!("Client session task failed: {err}"));
            }
        }

        // Reserve capacity before accepting. Once all slots are occupied, new
        // clients remain in the kernel's bounded listen backlog instead of
        // creating unbounded tasks, TCP listeners, or Android broadcasts.
        let permit = session_permits
            .clone()
            .acquire_owned()
            .await
            .context("Client session limiter closed")?;

        let accepted = loop {
            tokio::select! {
                result = listener.accept() => break result,
                result = sessions.join_next(), if !sessions.is_empty() => {
                    if let Some(Err(err)) = result {
                        log_debug(debug, &format!("Client session task failed: {err}"));
                    }
                }
            }
        };

        match accepted {
            Ok((client, _)) => {
                let session = handle(client);
                sessions.spawn(async move {
                    let _permit = permit;
                    if let Err(err) = session.await {
                        log_debug(debug, &format!("Client session failed: {err:#}"));
                    }
                });
            }
            Err(err) if is_retryable_accept_error(&err) => {
                drop(permit);
                log_debug(debug, &format!("Transient client accept failure: {err}"));
                sleep(ACCEPT_RETRY_DELAY).await;
            }
            Err(err) => {
                drop(permit);
                sessions.shutdown().await;
                return Err(err).context("Failed to accept a client connection");
            }
        }
    }
}

fn is_retryable_accept_error(err: &io::Error) -> bool {
    matches!(
        err.kind(),
        ErrorKind::Interrupted
            | ErrorKind::WouldBlock
            | ErrorKind::TimedOut
            | ErrorKind::ConnectionAborted
            | ErrorKind::ConnectionReset
    )
}

/// Runs `future` to completion on a new multi-threaded runtime.
///
/// # Errors
///
/// Fails when the runtime cannot start, or with the error of `future`.
pub fn run_blocking<T>(future: impl Future<Output = Result<T>>) -> Result<T> {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .context("Failed to create Tokio runtime")?
        .block_on(future)
}

/// Completes on SIGHUP, SIGINT, or SIGTERM.
pub async fn wait_for_shutdown_signal() -> Result<()> {
    let mut sighup = signal(SignalKind::hangup()).context("Failed to subscribe to SIGHUP")?;
    let mut sigint = signal(SignalKind::interrupt()).context("Failed to subscribe to SIGINT")?;
    let mut sigterm = signal(SignalKind::terminate()).context("Failed to subscribe to SIGTERM")?;
    tokio::select! {
        _ = sighup.recv() => {}
        _ = sigint.recv() => {}
        _ = sigterm.recv() => {}
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accept_error_classification_keeps_retryable_errors_local() {
        assert!(is_retryable_accept_error(&io::Error::from(
            ErrorKind::Interrupted
        )));
        assert!(is_retryable_accept_error(&io::Error::from(
            ErrorKind::ConnectionAborted
        )));
        assert!(!is_retryable_accept_error(&io::Error::from(
            ErrorKind::PermissionDenied
        )));
    }
}
