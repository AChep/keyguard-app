//! IPC client that connects to the Keyguard desktop application.
//!
//! The client communicates over a Unix domain socket (macOS/Linux) or named pipe
//! (Windows) using length-prefixed Protobuf messages, through the shared
//! reconnecting [`Client`].

use std::path::Path;

use anyhow::{bail, Result};
use keyguard_agent_ipc::client::{AppEndpoint, Client};
use keyguard_agent_ipc::launch::AuthToken;
use tracing::debug;

use super::messages::{
    ipc_request, ipc_response, CallerIdentity, ListKeysRequest, ListKeysResponse, SignDataRequest,
    SignDataResponse,
};
use crate::agent::KeyProvider;

keyguard_agent_ipc::envelope_protocol! {
    /// The SSH agent's messages, as the shared client sends them.
    struct SshIpc;
    messages = super::messages;
    // A failed SignData response is ambiguous: the server may have
    // completed the signature before the channel broke.
    retry_safe = ListKeys;
}

/// IPC client for communicating with the Keyguard desktop application.
///
/// This is safe to clone and share across tasks -- all access to the
/// underlying socket is serialized via a mutex.
#[derive(Clone)]
pub struct IpcClient(Client<SshIpc>);

impl IpcClient {
    /// Connects to the Keyguard IPC server and authenticates.
    ///
    /// # Arguments
    /// * `socket_path` - Path to the IPC Unix socket (or named pipe on Windows).
    /// * `auth_token` - The launch token, kept to re-authenticate after a reconnect.
    pub async fn connect(
        socket_path: &Path,
        auth_token: AuthToken,
        expected_parent_pid: u32,
    ) -> Result<Self> {
        let endpoint = AppEndpoint::new(socket_path, expected_parent_pid)?;
        debug!(path = %socket_path.display(), "Connecting to IPC server");
        Ok(Self(Client::connect(endpoint, auth_token).await?))
    }

    /// Requests the list of SSH keys available in the Keyguard vault.
    pub async fn list_keys(&self, caller: Option<CallerIdentity>) -> Result<ListKeysResponse> {
        let resp = self
            .0
            .request(ipc_request::Request::ListKeys(ListKeysRequest { caller }))
            .await?;

        match resp {
            ipc_response::Response::ListKeys(keys) => Ok(keys),
            _ => bail!("Unexpected response to list_keys request"),
        }
    }

    /// Requests Keyguard to sign data with the specified key.
    ///
    /// This may trigger a user approval prompt in the Keyguard UI.
    pub async fn sign_data(
        &self,
        public_key: &str,
        data: &[u8],
        flags: u32,
        caller: Option<CallerIdentity>,
    ) -> Result<SignDataResponse> {
        let resp = self
            .0
            .request(ipc_request::Request::SignData(SignDataRequest {
                public_key: public_key.to_string(),
                data: data.to_vec(),
                flags,
                caller,
            }))
            .await?;

        match resp {
            ipc_response::Response::SignData(sig) => Ok(sig),
            _ => bail!("Unexpected response to sign_data request"),
        }
    }
}

#[ssh_agent_lib::async_trait]
impl KeyProvider for IpcClient {
    async fn list_keys(&self, caller: Option<CallerIdentity>) -> Result<ListKeysResponse> {
        self.list_keys(caller).await
    }

    async fn sign_data(
        &self,
        public_key: &str,
        data: &[u8],
        flags: u32,
        caller: Option<CallerIdentity>,
    ) -> Result<SignDataResponse> {
        self.sign_data(public_key, data, flags, caller).await
    }
}

#[cfg(test)]
mod tests;
