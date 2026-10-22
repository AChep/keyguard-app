//! One activation of the app bridge: a loopback listener, the broadcast that
//! wakes the app, and the authenticated connection back from it.

use anyhow::{Context, Result};
use prost::Message;
use tokio::net::{TcpListener, TcpStream};
use tokio::time::{timeout, timeout_at, Duration, Instant};
use zeroize::Zeroize;

use crate::broadcast::{
    AndroidBroadcastLauncher, BridgeContract, BroadcastCommandSpec, BroadcastOutcome,
    LegacyAcknowledgement,
};
use crate::log_debug;
use crate::secure_transport::{SecureTransport, SESSION_ID_LEN, SESSION_SECRET_LEN};

/// How long a whole activation may take by default, in milliseconds.
pub const DEFAULT_CONNECT_TIMEOUT_MS: u64 = 60_000;

// Must cover a full app cold start plus the receiver's 5s service-start
// acknowledgement window (AgentBridgeReceiver.SERVICE_START_ACK_TIMEOUT_MS);
// still bounded by the overall connect timeout.
const BROADCAST_ACK_TIMEOUT: Duration = Duration::from_secs(30);
// The app answers each request within its 60s approval deadline, however
// many prompts it shows, so this leaves time for the operation itself.
const APP_RESPONSE_TIMEOUT: Duration = Duration::from_secs(75);
const REQUEST_WRITE_TIMEOUT: Duration = Duration::from_secs(10);

/// A bound loopback listener and the session credentials the app must prove.
struct PendingBridge {
    listener: TcpListener,
    proxy_port: u16,
    session_id: [u8; SESSION_ID_LEN],
    session_secret: [u8; SESSION_SECRET_LEN],
    deadline: Instant,
}

impl PendingBridge {
    /// Binds an ephemeral loopback port; the whole activation must finish
    /// within `connect_timeout`.
    async fn bind(connect_timeout: Duration) -> Result<Self> {
        let listener = TcpListener::bind(("127.0.0.1", 0))
            .await
            .context("Failed to bind localhost proxy listener")?;
        let proxy_port = listener
            .local_addr()
            .context("Failed to read proxy listener address")?
            .port();
        Ok(Self {
            listener,
            proxy_port,
            session_id: random_array(),
            session_secret: random_array(),
            deadline: Instant::now() + connect_timeout,
        })
    }

    /// The loopback port the app connects back to.
    fn proxy_port(&self) -> u16 {
        self.proxy_port
    }

    /// Asks the app's receiver to connect back to this bridge.
    async fn broadcast(
        &self,
        launcher: &AndroidBroadcastLauncher,
        contract: &BridgeContract,
        component: &str,
    ) -> Result<BroadcastOutcome> {
        let broadcast_deadline = self.deadline.min(Instant::now() + BROADCAST_ACK_TIMEOUT);
        launcher
            .launch(
                contract,
                component,
                self.proxy_port,
                &self.session_id,
                &self.session_secret,
                broadcast_deadline,
            )
            .await
    }

    /// Waits for the app to connect and completes the secure handshake.
    async fn connect(self) -> Result<SecureTransport<TcpStream>> {
        let (app_stream, _) = timeout_at(self.deadline, self.listener.accept())
            .await
            .context("Timed out waiting for the Keyguard app to connect")?
            .context("Failed to accept the Keyguard app connection")?;

        let transport = timeout_at(
            self.deadline,
            SecureTransport::connect_as_tool(app_stream, &self.session_id, &self.session_secret),
        )
        .await
        .context("Timed out during the agent bridge handshake")?
        .context("Agent bridge handshake failed")?;
        Ok(transport)
    }
}

/// What a helper needs to wake the app and open a bridge to it.
#[derive(Clone, Debug)]
pub struct BridgeConfig {
    /// The app's receiver, as `am broadcast -n` takes it.
    pub android_component: String,
    /// The bound of a whole activation.
    pub connect_timeout: Duration,
    /// Whether to log to stderr.
    pub debug: bool,
    /// The command that sends the broadcast.
    pub broadcast_command: BroadcastCommandSpec,
}

impl BridgeConfig {
    /// The bridge of a helper's command-line options, which sends its
    /// broadcast with `am`.
    pub fn new(android_component: String, connect_timeout_ms: u64, debug: bool) -> Self {
        Self {
            android_component,
            connect_timeout: Duration::from_millis(connect_timeout_ms),
            debug,
            broadcast_command: BroadcastCommandSpec::default(),
        }
    }
}

/// Why a bridge did not open.
#[derive(Debug)]
pub enum OpenBridgeError {
    /// The broadcast could not be sent.
    Broadcast(anyhow::Error),
    /// The app answered the broadcast, but not with a bridge.
    Rejected(BroadcastOutcome),
    /// The listener, or the app's connection back to it, failed.
    Failed(anyhow::Error),
}

/// Binds a bridge, wakes the app with `contract`'s broadcast, and waits for
/// the app's authenticated connection back.
pub async fn open_bridge(
    config: &BridgeConfig,
    contract: &BridgeContract,
) -> Result<SecureTransport<TcpStream>, OpenBridgeError> {
    let bridge = PendingBridge::bind(config.connect_timeout)
        .await
        .map_err(OpenBridgeError::Failed)?;
    log_debug(
        config.debug,
        &format!("Opening Android bridge: proxy_port={}", bridge.proxy_port()),
    );

    let outcome = bridge
        .broadcast(
            &AndroidBroadcastLauncher::new(config.broadcast_command.clone()),
            contract,
            &config.android_component,
        )
        .await
        .map_err(OpenBridgeError::Broadcast)?;
    match outcome {
        BroadcastOutcome::Accepted | BroadcastOutcome::Deferred => {}
        BroadcastOutcome::LegacyNoAcknowledgement => match contract.legacy_acknowledgement {
            LegacyAcknowledgement::Tolerate => log_debug(
                config.debug,
                "Keyguard returned no broadcast acknowledgement; using legacy behavior",
            ),
            LegacyAcknowledgement::Reject => return Err(OpenBridgeError::Rejected(outcome)),
        },
        BroadcastOutcome::Busy
        | BroadcastOutcome::Disabled
        | BroadcastOutcome::Invalid
        | BroadcastOutcome::StartFailed
        | BroadcastOutcome::InternalError => return Err(OpenBridgeError::Rejected(outcome)),
    }

    bridge.connect().await.map_err(OpenBridgeError::Failed)
}

/// Sends `request` to the app and decodes its response.
pub async fn exchange_message<Q: Message, R: Message + Default>(
    transport: &mut SecureTransport<TcpStream>,
    request: &Q,
) -> Result<R> {
    let response = exchange(transport, &request.encode_to_vec()).await?;
    R::decode(response.as_slice())
        .context("Failed to decode protobuf response from the Keyguard app")
}

/// Sends one encoded protobuf request to the app and returns its encoded response.
async fn exchange(transport: &mut SecureTransport<TcpStream>, request: &[u8]) -> Result<Vec<u8>> {
    timeout(REQUEST_WRITE_TIMEOUT, transport.write_packet(request))
        .await
        .context("Timed out sending protobuf request to the Keyguard app")?
        .context("Failed to send protobuf request to the Keyguard app")?;
    timeout(APP_RESPONSE_TIMEOUT, transport.read_packet())
        .await
        .context("Timed out waiting for a protobuf response from the Keyguard app")?
        .context("Failed to read protobuf response from the Keyguard app")?
        .context("Keyguard app disconnected before replying")
}

impl Drop for PendingBridge {
    fn drop(&mut self) {
        self.session_secret.zeroize();
    }
}

fn random_array<const N: usize>() -> [u8; N] {
    let mut out = [0u8; N];
    rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut out);
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::OsString;

    const CONTRACT: BridgeContract = BridgeContract {
        action: "test.action",
        extra_protocol_version: "test.version",
        extra_proxy_port: "test.port",
        extra_session_id: "test.session_id",
        extra_session_secret: "test.session_secret",
        result_prefix: "test.bridge.v1:",
        legacy_acknowledgement: LegacyAcknowledgement::Tolerate,
    };

    /// A bridge whose broadcast runs `answer` in place of `am`.
    fn config(answer: &str, connect_timeout: Duration) -> BridgeConfig {
        BridgeConfig {
            android_component: "component".to_string(),
            connect_timeout,
            debug: false,
            broadcast_command: BroadcastCommandSpec::new(
                OsString::from("sh"),
                vec![
                    OsString::from("-c"),
                    OsString::from(answer),
                    OsString::from("sh"),
                ],
            ),
        }
    }

    #[tokio::test]
    async fn a_broadcast_that_fails_to_run_is_a_broadcast_error() {
        let config = config("exit 1", Duration::from_secs(5));
        let result = open_bridge(&config, &CONTRACT).await;
        assert!(matches!(result, Err(OpenBridgeError::Broadcast(_))));
    }

    #[tokio::test]
    async fn a_refusal_of_the_app_is_rejected() {
        let answer = "echo 'Broadcast completed: result=-1, data=\"test.bridge.v1:busy\"'";
        let config = config(answer, Duration::from_secs(5));
        let result = open_bridge(&config, &CONTRACT).await;
        assert!(matches!(
            result,
            Err(OpenBridgeError::Rejected(BroadcastOutcome::Busy))
        ));
    }

    #[tokio::test]
    async fn an_unacknowledged_broadcast_fails_at_once_where_no_release_ignores_it() {
        // What `am` prints when no receiver handles the broadcast.
        let config = config(
            "echo 'Broadcast completed: result=0'",
            Duration::from_secs(60),
        );
        let result = tokio::time::timeout(
            Duration::from_secs(10),
            open_bridge(
                &config,
                &BridgeContract {
                    legacy_acknowledgement: LegacyAcknowledgement::Reject,
                    ..CONTRACT
                },
            ),
        )
        .await
        .expect("waited for an app that never answered");
        assert!(matches!(
            result,
            Err(OpenBridgeError::Rejected(
                BroadcastOutcome::LegacyNoAcknowledgement
            ))
        ));
    }

    #[tokio::test]
    async fn an_unacknowledged_broadcast_waits_for_the_app_where_tolerated() {
        let config = config(
            "echo 'Broadcast completed: result=0'",
            Duration::from_millis(200),
        );
        let result = open_bridge(&config, &CONTRACT).await;
        let Err(OpenBridgeError::Failed(error)) = result else {
            panic!("expected the wait for the app to time out");
        };
        assert!(
            format!("{error:#}").contains("waiting for the Keyguard app"),
            "{error:#}"
        );
    }
}
