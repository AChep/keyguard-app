//! Authenticated protobuf IPC connection to the Keyguard desktop app, over the
//! shared reconnecting [`Client`].

use anyhow::Result;
use common_gpg_agent_rust::messages::{ipc_request, ipc_response};
use keyguard_agent_ipc::client::{AppEndpoint, Client, Connect};
use keyguard_agent_ipc::launch::AuthToken;
use keyguard_gpg_agent_assuan::ipc::{BoxFuture, IpcTransport};
use std::path::Path;

pub use keyguard_gpg_agent_assuan::ipc::IpcClient;

keyguard_agent_ipc::envelope_protocol! {
    /// The GPG agent's messages, as the shared client sends them.
    struct GpgIpc;
    messages = common_gpg_agent_rust::messages;
    // Signing and decryption prompt the user: a replay could prompt twice.
    // PKSIGN and PKDECRYPT list the keys first, which repairs a connection
    // that the app closed while idle.
    retry_safe = ListKeys;
}

/// The Assuan layer's transport over the shared client, which numbers the
/// requests on the wire. An error that the app answers with is an
/// [`keyguard_agent_ipc::error::IpcError`].
struct AppTransport(Client<GpgIpc>);

impl IpcTransport for AppTransport {
    fn round_trip(
        &self,
        request: ipc_request::Request,
    ) -> BoxFuture<'_, Result<ipc_response::Response>> {
        Box::pin(self.0.request(request))
    }
}

/// Connects to the Keyguard app and authenticates with `auth_token`, which
/// the client keeps to authenticate again after a reconnect.
pub async fn connect(
    socket_path: &Path,
    auth_token: AuthToken,
    expected_parent_pid: u32,
) -> Result<IpcClient> {
    let endpoint = AppEndpoint::new(socket_path, expected_parent_pid)?;
    connect_with(endpoint, auth_token).await
}

async fn connect_with(connector: impl Connect, auth_token: AuthToken) -> Result<IpcClient> {
    let client = Client::connect(connector, auth_token).await?;
    Ok(IpcClient::new(AppTransport(client)))
}

#[cfg(test)]
mod tests;
