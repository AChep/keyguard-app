//! Protobuf IPC to the Keyguard app over the Android bridge.

use anyhow::{bail, Context, Result};
use common_gpg_agent_rust::messages::{IpcRequest, IpcResponse};
use keyguard_android_agent_bridge::bridge::{exchange, PendingBridge};
use keyguard_android_agent_bridge::broadcast::{AndroidBroadcastLauncher, BroadcastOutcome};
use keyguard_android_agent_bridge::log_debug;
use keyguard_android_agent_bridge::secure_transport::SecureTransport;
use keyguard_gpg_agent_assuan::ipc::{BoxFuture, IpcTransport};
use prost::Message;
use std::sync::Arc;
use tokio::net::TcpStream;
use tokio::sync::Mutex;
use tokio::time::{Duration, Instant};

use crate::daemon::Config;
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
    config: Arc<Config>,
    state: Mutex<State<SecureTransport<TcpStream>>>,
}

impl BridgeTransport {
    pub(crate) fn new(config: Arc<Config>) -> Self {
        Self {
            config,
            state: Mutex::new(State::Idle),
        }
    }

    async fn open(&self) -> Result<SecureTransport<TcpStream>> {
        let config = &self.config;
        let bridge = PendingBridge::bind(config.connect_timeout).await?;
        log_debug(
            config.debug,
            &format!("Opening Android bridge: proxy_port={}", bridge.proxy_port()),
        );

        let outcome = bridge
            .broadcast(
                &AndroidBroadcastLauncher::new(config.broadcast_command.clone()),
                &GPG_AGENT_CONTRACT,
                &config.android_component,
            )
            .await
            .context("Failed to send Android GPG agent broadcast")?;
        match outcome {
            BroadcastOutcome::Accepted | BroadcastOutcome::Deferred => {}
            // Unlike SSH, no Keyguard release handles the GPG agent without
            // acknowledging it, so a missing answer means no receiver.
            BroadcastOutcome::LegacyNoAcknowledgement => {
                bail!(
                    "Keyguard didn't answer the GPG agent broadcast. \
                     Update Keyguard, or check --android-component"
                );
            }
            BroadcastOutcome::Busy
            | BroadcastOutcome::Disabled
            | BroadcastOutcome::Invalid
            | BroadcastOutcome::StartFailed
            | BroadcastOutcome::InternalError => {
                bail!("Keyguard rejected Android bridge activation: {outcome:?}");
            }
        }

        let transport = bridge.connect().await?;
        log_debug(config.debug, "GPG agent handshake established");
        Ok(transport)
    }
}

impl IpcTransport for BridgeTransport {
    fn round_trip(&self, request: IpcRequest) -> BoxFuture<'_, Result<IpcResponse>> {
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
            let response = exchange(transport, &request.encode_to_vec())
                .await
                .and_then(|response| {
                    IpcResponse::decode(response.as_slice())
                        .context("Failed to decode protobuf response from the Keyguard app")
                });
            match response {
                Ok(response) => {
                    *last_used = Instant::now();
                    Ok(response)
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

#[cfg(test)]
mod tests {
    use super::*;

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
