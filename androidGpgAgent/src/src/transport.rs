//! Protobuf IPC to the Keyguard app over the Android bridge.

use anyhow::{bail, Context, Result};
use common_gpg_agent_rust::messages::{ipc_request, ipc_response, IpcRequest, IpcResponse};
use keyguard_android_agent_bridge::bridge::{
    exchange_message, open_bridge, BridgeConfig, OpenBridgeError,
};
use keyguard_android_agent_bridge::broadcast::BroadcastOutcome;
use keyguard_android_agent_bridge::log_debug;
use keyguard_android_agent_bridge::secure_transport::SecureTransport;
use keyguard_gpg_agent_assuan::ipc::{BoxFuture, IpcTransport};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use tokio::net::TcpStream;
use tokio::sync::Mutex;
use tokio::time::{Duration, Instant};

use crate::GPG_AGENT_CONTRACT;

// Must stay below DEFAULT_GPG_PROXY_PACKET_READ_TIMEOUT_MS (6 min) in the app,
// which closes a bridge after that long without a request. Equal to the Assuan
// idle timeout, so a bridge only reaches it while commands that never reach
// the app keep the connection alive.
const BRIDGE_IDLE_LIMIT: Duration = Duration::from_secs(5 * 60);

enum State<T> {
    Idle,
    Open { transport: T, last_used: Instant },
    Closed,
}

impl<T> State<T> {
    /// Drops a bridge that has been idle long enough for the app to close
    /// it, so the next request opens a new one instead of failing.
    fn retire_if_idle(&mut self, now: Instant) {
        if let State::Open { last_used, .. } = self {
            if now.saturating_duration_since(*last_used) >= BRIDGE_IDLE_LIMIT {
                *self = State::Idle;
            }
        }
    }
}

/// Wakes the app on the first request of a connection, so commands that
/// GnuPG sends on every connect never reach it. A bridge idle for
/// [`BRIDGE_IDLE_LIMIT`] is reopened on the next request. A failed bridge
/// stays closed for the rest of the connection instead of broadcasting again.
pub(crate) struct BridgeTransport {
    config: Arc<BridgeConfig>,
    state: Mutex<State<SecureTransport<TcpStream>>>,
    next_id: AtomicU64,
}

impl BridgeTransport {
    pub(crate) fn new(config: Arc<BridgeConfig>) -> Self {
        Self {
            config,
            state: Mutex::new(State::Idle),
            next_id: AtomicU64::new(1),
        }
    }

    async fn open(&self) -> Result<SecureTransport<TcpStream>> {
        let transport = open_bridge(&self.config, &GPG_AGENT_CONTRACT)
            .await
            .or_else(|error| match error {
                OpenBridgeError::Broadcast(error) => {
                    Err(error).context("Failed to send Android GPG agent broadcast")
                }
                OpenBridgeError::Rejected(BroadcastOutcome::LegacyNoAcknowledgement) => bail!(
                    "Keyguard didn't answer the GPG agent broadcast. \
                     Update Keyguard, or check --android-component"
                ),
                OpenBridgeError::Rejected(outcome) => {
                    bail!("Keyguard rejected Android bridge activation: {outcome:?}")
                }
                OpenBridgeError::Failed(error) => Err(error),
            })?;
        log_debug(self.config.debug, "GPG agent handshake established");
        Ok(transport)
    }
}

impl IpcTransport for BridgeTransport {
    fn round_trip(
        &self,
        request: ipc_request::Request,
    ) -> BoxFuture<'_, Result<ipc_response::Response>> {
        Box::pin(async move {
            let mut state = self.state.lock().await;
            state.retire_if_idle(Instant::now());
            if let State::Idle = *state {
                *state = State::Closed;
                *state = State::Open {
                    transport: self.open().await?,
                    last_used: Instant::now(),
                };
            }
            let State::Open {
                transport,
                last_used,
            } = &mut *state
            else {
                bail!("Keyguard app bridge is closed");
            };
            let id = self.next_id.fetch_add(1, Ordering::Relaxed);
            let request = IpcRequest {
                id,
                request: Some(request),
            };
            let response = exchange_message::<_, IpcResponse>(transport, &request).await;
            match response {
                Ok(response) => {
                    *last_used = Instant::now();
                    reply_to(id, response)
                }
                Err(error) => {
                    // A timed-out exchange may still be answered later, so the
                    // stream can no longer pair requests with responses.
                    *state = State::Closed;
                    Err(error)
                }
            }
        })
    }
}

/// The payload of `response`, which must answer the request `id`.
fn reply_to(id: u64, response: IpcResponse) -> Result<ipc_response::Response> {
    if response.id != id {
        bail!(
            "protobuf response id mismatch: expected {} got {}",
            id,
            response.id
        );
    }
    response.response.context("IPC response has no payload")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_response_to_another_request_is_rejected() {
        let response = IpcResponse {
            id: 2,
            response: None,
        };
        let error = reply_to(1, response).unwrap_err().to_string();
        assert!(error.contains("id mismatch"), "{error}");
    }

    #[test]
    fn idle_bridge_is_retired_at_the_limit() {
        let opened = Instant::now();
        let mut state = State::Open {
            transport: (),
            last_used: opened,
        };

        state.retire_if_idle(opened + BRIDGE_IDLE_LIMIT - Duration::from_secs(1));
        assert!(matches!(state, State::Open { .. }));

        state.retire_if_idle(opened + BRIDGE_IDLE_LIMIT);
        assert!(matches!(state, State::Idle));
    }

    #[test]
    fn closed_bridge_is_not_reopened() {
        let mut state = State::<()>::Closed;
        state.retire_if_idle(Instant::now() + BRIDGE_IDLE_LIMIT);
        assert!(matches!(state, State::Closed));
    }
}
