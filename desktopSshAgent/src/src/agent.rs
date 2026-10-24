//! SSH agent implementation backed by the Keyguard IPC client.
//!
//! This module implements the `ssh-agent-lib` `Session` trait, handling SSH agent
//! protocol requests by delegating to the Keyguard desktop app via IPC.

use anyhow::Result;
use keyguard_agent_identity::caller_guard::CallerGuard;
use keyguard_agent_identity::AuthorizationContextFingerprint;
use ssh_agent_lib::agent::Session;
use ssh_agent_lib::error::AgentError;
use ssh_agent_lib::proto::Extension;
use ssh_agent_lib::proto::Identity;
use ssh_agent_lib::proto::SignRequest;
use ssh_encoding::Encode;
use ssh_key::{Algorithm, Signature};
use tracing::{debug, warn};

#[cfg(windows)]
use crate::ipc::messages::CallerAuthorization;
mod session_binding;

use crate::ipc::messages::{CallerIdentity, ListKeysResponse, SignDataResponse};
pub use session_binding::VerifiedSessionBindingContext;
use session_binding::{
    parse_session_binding, SessionBindError, SessionBindingState, SESSION_BIND_EXTENSION,
};

/// Trait abstracting the key-listing and signing operations that
/// `KeyguardAgent` needs. This allows replacing the real `IpcClient`
/// with a test fake.
#[ssh_agent_lib::async_trait]
pub trait KeyProvider: Clone + Send + Sync + Unpin + 'static {
    /// Returns the list of SSH keys available in the Keyguard vault.
    async fn list_keys(&self, caller: Option<CallerIdentity>) -> Result<ListKeysResponse>;

    /// Requests Keyguard to sign data with the specified key.
    async fn sign_data(
        &self,
        public_key: &str,
        data: &[u8],
        flags: u32,
        caller: Option<CallerIdentity>,
    ) -> Result<SignDataResponse>;
}

/// SSH agent session backed by a [`KeyProvider`].
///
/// This type implements both `Session` and `Clone`, which means
/// `ssh-agent-lib` will automatically provide an `Agent` impl
/// that clones this for each new connection.
#[derive(Clone)]
pub struct KeyguardAgent<K: KeyProvider> {
    key_provider: K,
    caller: Option<CallerIdentity>,
    session_bindings: SessionBindingState,
    caller_guard: std::sync::Arc<CallerGuard>,
    #[cfg(windows)]
    windows_caller_guard: Option<std::sync::Arc<crate::windows_identity::WindowsCallerIdentity>>,
}

impl<K: KeyProvider> KeyguardAgent<K> {
    #[cfg(test)]
    pub fn new(key_provider: K) -> Self {
        Self::with_caller(key_provider, None)
    }

    #[cfg(test)]
    pub fn with_caller(key_provider: K, caller: Option<CallerIdentity>) -> Self {
        Self::with_caller_guard(key_provider, caller, CallerGuard::default())
    }

    fn with_caller_guard(
        key_provider: K,
        caller: Option<CallerIdentity>,
        caller_guard: CallerGuard,
    ) -> Self {
        Self {
            key_provider,
            caller,
            session_bindings: SessionBindingState::default(),
            caller_guard: std::sync::Arc::new(caller_guard),
            #[cfg(windows)]
            windows_caller_guard: None,
        }
    }

    #[cfg(windows)]
    fn with_windows_caller(
        key_provider: K,
        caller: Option<CallerIdentity>,
        guard: Option<crate::windows_identity::WindowsCallerIdentity>,
    ) -> Self {
        Self {
            windows_caller_guard: guard.map(std::sync::Arc::new),
            ..Self::with_caller_guard(key_provider, caller, CallerGuard::default())
        }
    }

    /// Refuses the operation when the accepted caller changed. On Windows,
    /// also refreshes the best-effort pipe-opener evidence.
    fn ensure_caller_valid(&mut self) -> Result<(), AgentError> {
        if let Err(error) = self.caller_guard.revalidate() {
            warn!(%error, "Caller identity changed; refusing agent operation");
            return Err(AgentError::Failure);
        }
        #[cfg(windows)]
        if let (Some(guard), Some(caller)) = (&self.windows_caller_guard, &mut self.caller) {
            guard.update_caller(caller).map_err(|error| {
                warn!(%error, "Windows pipe opener changed; refusing agent operation");
                AgentError::Failure
            })?;
        }
        Ok(())
    }

    /// Returns the verified session-binding context for this connection.
    ///
    /// `None` means no binding was accepted or an invalid binding poisoned the
    /// connection. Signing sends the stable host-path digest as independent
    /// authorization context.
    pub fn verified_session_binding_context(&self) -> Option<VerifiedSessionBindingContext> {
        self.session_bindings.context()
    }

    fn caller_for_sign(&self) -> Option<CallerIdentity> {
        let mut caller = self.caller.clone()?;
        let Some(binding_context) = self.verified_session_binding_context() else {
            return Some(caller);
        };
        let Some(authorization) = caller.authorization.as_mut() else {
            return Some(caller);
        };

        match AuthorizationContextFingerprint::derive(binding_context.cache_context_digest()) {
            Ok(context) => {
                authorization.authorization_context_fingerprint = context.into_bytes().to_vec();
            }
            Err(_) => {
                caller.authorization = None;
            }
        }
        Some(caller)
    }

    fn bind_session(&mut self, details: &[u8]) -> Result<(), SessionBindError> {
        if self.session_bindings.is_poisoned() {
            return Err(SessionBindError::Poisoned);
        }

        let had_verified_binding = self
            .session_bindings
            .bindings()
            .is_some_and(|bindings| !bindings.is_empty());

        let result = (|| {
            let binding = parse_session_binding(details)?;
            binding.verify_signature()?;
            self.session_bindings.record(binding.into_verified())
        })();

        // A failed first bind makes the connection's claimed provenance
        // unusable. Once a path is verified, however, preserve it when a
        // later malformed/duplicate/conflicting extension is rejected.
        if result.is_err() && !had_verified_binding {
            self.session_bindings = SessionBindingState::Poisoned;
        } else if let Some(context) = self.verified_session_binding_context() {
            debug!(
                binding_count = context.binding_count(),
                forwarding_hops = context.forwarding_hops(),
                final_is_forwarding = context.final_is_forwarding(),
                "Recorded verified SSH session binding"
            );
        }
        result
    }
}

#[ssh_agent_lib::async_trait]
impl<K: KeyProvider> Session for KeyguardAgent<K> {
    async fn request_identities(&mut self) -> Result<Vec<Identity>, AgentError> {
        debug!("Handling RequestIdentities");

        self.ensure_caller_valid()?;

        let keys_response = self
            .key_provider
            .list_keys(self.caller.clone())
            .await
            .map_err(|_| {
                // Backend error messages may contain vault data; keep only the operation.
                warn!("Failed to list keys from Keyguard");
                AgentError::Failure
            })?;

        let mut identities = Vec::new();
        for key in &keys_response.keys {
            match parse_openssh_pubkey(&key.public_key) {
                Ok(pubkey) => {
                    identities.push(Identity {
                        pubkey: pubkey.key_data().clone(),
                        comment: key.name.clone(),
                    });
                }
                Err(_) => {
                    // Neither the key's display name nor parser input belongs in logs.
                    warn!("Failed to parse SSH public key, skipping");
                }
            }
        }

        debug!(count = identities.len(), "Returning SSH key identities");
        Ok(identities)
    }

    async fn sign(&mut self, request: SignRequest) -> Result<Signature, AgentError> {
        debug!("Handling SignRequest");

        if let Err(error) = self.session_bindings.validate_sign_request(&request) {
            warn!(%error, "Refusing sign request that does not match its SSH session binding");
            return Err(AgentError::Failure);
        }

        self.ensure_caller_valid()?;

        // Find the matching key by comparing the public key data.
        let keys_response = self
            .key_provider
            .list_keys(self.caller.clone())
            .await
            .map_err(|_| {
                warn!("Failed to list keys for sign request");
                AgentError::Failure
            })?;

        let matching_key =
            keys_response
                .keys
                .iter()
                .find(|key| match parse_openssh_pubkey(&key.public_key) {
                    Ok(pubkey) => *pubkey.key_data() == request.pubkey,
                    Err(_) => false,
                });

        let Some(key) = matching_key else {
            warn!("No matching key found for sign request");
            return Err(AgentError::Failure);
        };

        debug!("Requesting signature from Keyguard");

        // Request signing from Keyguard (may prompt user for approval).
        self.ensure_caller_valid()?;
        let sign_caller = self.caller_for_sign();
        let sign_response = self
            .key_provider
            .sign_data(&key.public_key, &request.data, request.flags, sign_caller)
            .await
            .map_err(|_| {
                warn!("Signing request failed");
                AgentError::Failure
            })?;

        // Parse the raw signature bytes into an ssh_key::Signature.
        // The IPC response contains the algorithm name and signature blob.
        let algorithm = Algorithm::new(&sign_response.algorithm).map_err(AgentError::other)?;
        let signature =
            Signature::new(algorithm, sign_response.signature).map_err(AgentError::other)?;

        Ok(signature)
    }

    async fn extension(&mut self, extension: Extension) -> Result<Option<Extension>, AgentError> {
        debug!(name = %extension.name, "Handling Extension request");

        match extension.name.as_str() {
            "query" => {
                // RFC 9987 section 5.8.1 encodes this list as consecutive SSH
                // strings through the end of the message, without a list prefix.
                let mut details = Vec::with_capacity(4 + SESSION_BIND_EXTENSION.len());
                SESSION_BIND_EXTENSION
                    .encode(&mut details)
                    .map_err(AgentError::other)?;

                Ok(Some(Extension {
                    name: "query".to_string(),
                    details: details.into(),
                }))
            }
            SESSION_BIND_EXTENSION => match self.bind_session(extension.details.as_ref()) {
                Ok(()) => Ok(None),
                Err(error) => {
                    warn!(%error, "Rejected SSH session-binding request");
                    Err(AgentError::ExtensionFailure)
                }
            },
            // RFC 9987 section 5.8 reserves extension failure for supported
            // extensions; an unsupported name receives generic failure.
            _ => Err(AgentError::Failure),
        }
    }
}

/// Parses an OpenSSH public key string (authorized_keys format).
pub(crate) fn parse_openssh_pubkey(pubkey_str: &str) -> Result<ssh_key::PublicKey, ssh_key::Error> {
    ssh_key::PublicKey::from_openssh(pubkey_str)
}

/// SSH agent session factory that captures per-connection socket metadata.
#[derive(Clone)]
pub struct KeyguardAgentFactory<K: KeyProvider> {
    key_provider: K,
}

impl<K: KeyProvider> KeyguardAgentFactory<K> {
    pub fn new(key_provider: K) -> Self {
        Self { key_provider }
    }
}

#[cfg(unix)]
impl<K: KeyProvider> ssh_agent_lib::agent::Agent<tokio::net::UnixListener>
    for KeyguardAgentFactory<K>
{
    fn new_session(&mut self, socket: &tokio::net::UnixStream) -> impl Session {
        let (caller, guard) = keyguard_agent_proto_adapter::caller_from_unix_stream(socket);
        KeyguardAgent::with_caller_guard(self.key_provider.clone(), caller, guard)
    }
}

#[cfg(windows)]
impl<K, L> ssh_agent_lib::agent::Agent<L> for KeyguardAgentFactory<K>
where
    K: KeyProvider,
    L: ssh_agent_lib::agent::ListeningSocket<
            Stream = tokio::net::windows::named_pipe::NamedPipeServer,
        > + std::fmt::Debug
        + Send,
{
    fn new_session(
        &mut self,
        socket: &tokio::net::windows::named_pipe::NamedPipeServer,
    ) -> impl Session {
        let caller = windows_connection_caller();
        let guard = crate::windows_identity::WindowsCallerIdentity::from_pipe(socket)
            .map_err(|error| debug!(%error, "Windows caller unavailable; using connection scope"))
            .ok();
        KeyguardAgent::with_windows_caller(self.key_provider.clone(), caller, guard)
    }
}

#[cfg(windows)]
fn windows_connection_caller() -> Option<CallerIdentity> {
    use keyguard_agent_identity::ConnectionFingerprint;

    // Always retain the independent connection scope, including when process
    // discovery is unavailable or the user explicitly chooses Per connection.
    let mut caller = unverified_windows_caller();
    match ConnectionFingerprint::generate() {
        Ok(connection) => {
            caller.authorization = Some(connection_only_authorization(connection));
        }
        Err(error) => {
            warn!(%error, "Failed to generate a Windows SSH connection principal");
        }
    }
    Some(caller)
}

#[cfg(windows)]
fn connection_only_authorization(
    connection: keyguard_agent_identity::ConnectionFingerprint,
) -> CallerAuthorization {
    CallerAuthorization {
        connection_fingerprint: connection.into_bytes().to_vec(),
        subjects: Vec::new(),
        authorization_context_fingerprint: Vec::new(),
    }
}

#[cfg(windows)]
fn unverified_windows_caller() -> CallerIdentity {
    CallerIdentity {
        pid: 0,
        uid: 0,
        gid: 0,
        process_name: String::new(),
        executable_path: String::new(),
        app_pid: 0,
        app_name: "Unverified caller".to_string(),
        app_bundle_path: String::new(),
        authorization: None,
    }
}

#[cfg(test)]
mod tests;
