//! One activation of the app bridge: a loopback listener, the broadcast that
//! wakes the app, and the authenticated connection back from it.

use anyhow::{Context, Result};
use tokio::net::{TcpListener, TcpStream};
use tokio::time::{timeout, timeout_at, Duration, Instant};
use zeroize::Zeroize;

use crate::broadcast::{AndroidBroadcastLauncher, BridgeContract, BroadcastOutcome};
use crate::secure_transport::{SecureTransport, SESSION_ID_LEN, SESSION_SECRET_LEN};

// Must cover a full app cold start plus the receiver's 5s service-start
// acknowledgement window (AgentBridgeReceiver.SERVICE_START_ACK_TIMEOUT_MS);
// still bounded by the overall connect timeout.
const BROADCAST_ACK_TIMEOUT: Duration = Duration::from_secs(30);
// The app answers each request within its 60s approval deadline, however
// many prompts it shows, so this leaves time for the operation itself.
const APP_RESPONSE_TIMEOUT: Duration = Duration::from_secs(75);
const REQUEST_WRITE_TIMEOUT: Duration = Duration::from_secs(10);

/// A bound loopback listener and the session credentials the app must prove.
pub struct PendingBridge {
    listener: TcpListener,
    proxy_port: u16,
    session_id: [u8; SESSION_ID_LEN],
    session_secret: [u8; SESSION_SECRET_LEN],
    deadline: Instant,
}

impl PendingBridge {
    /// Binds an ephemeral loopback port; the whole activation must finish
    /// within `connect_timeout`.
    pub async fn bind(connect_timeout: Duration) -> Result<Self> {
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
    pub fn proxy_port(&self) -> u16 {
        self.proxy_port
    }

    /// Asks the app's receiver to connect back to this bridge.
    pub async fn broadcast(
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
    pub async fn connect(self) -> Result<SecureTransport<TcpStream>> {
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

/// Sends one encoded protobuf request to the app and returns its encoded response.
pub async fn exchange(
    transport: &mut SecureTransport<TcpStream>,
    request: &[u8],
) -> Result<Vec<u8>> {
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
