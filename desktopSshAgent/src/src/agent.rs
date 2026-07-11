//! SSH agent implementation backed by the Keyguard IPC client.
//!
//! This module implements the `ssh-agent-lib` `Session` trait, handling SSH agent
//! protocol requests by delegating to the Keyguard desktop app via IPC.

use anyhow::Result;
use keyguard_agent_identity::AuthorizationContextFingerprint;
use ssh_agent_lib::agent::Session;
use ssh_agent_lib::error::AgentError;
use ssh_agent_lib::proto::extension::SessionBind;
use ssh_agent_lib::proto::Extension;
use ssh_agent_lib::proto::Identity;
use ssh_agent_lib::proto::SignRequest;
use ssh_encoding::{Decode, Encode};
use ssh_key::public::KeyData;
use ssh_key::sha2::{Digest, Sha256};
use ssh_key::{Algorithm, Certificate, Signature};
use thiserror::Error;
use tracing::{debug, info, warn};

#[cfg(windows)]
use crate::ipc::messages::CallerAuthorization;
use crate::ipc::messages::{CallerIdentity, ListKeysResponse, SignDataResponse};

const SESSION_BIND_EXTENSION: &str = "session-bind@openssh.com";
const MAX_SESSION_BINDINGS: usize = 16;
const MAX_SESSION_ID_LEN: usize = 128;
const MAX_HOST_KEY_BLOB_LEN: usize = 64 * 1024;
const MAX_SIGNATURE_BLOB_LEN: usize = 64 * 1024;
const MAX_USERAUTH_FIELD_LEN: usize = 128;
const SSH2_MSG_USERAUTH_REQUEST: u8 = 50;
const SSH_CONNECTION_SERVICE: &[u8] = b"ssh-connection";
const PUBLICKEY_METHOD: &[u8] = b"publickey";
const HOSTBOUND_PUBLICKEY_METHOD: &[u8] = b"publickey-hostbound-v00@openssh.com";
const BINDING_DIGEST_DOMAIN: &[u8] = b"keyguard-ssh-session-bind-v1\0";
const BINDING_CACHE_CONTEXT_DOMAIN: &[u8] = b"keyguard-ssh-session-bind-cache-context-v1\0";

/// Stable, domain-separated summary of a verified OpenSSH session-binding path.
///
/// This context is intentionally independent from caller display metadata. It can be
/// attached to an authorization request without serializing the raw session IDs.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct VerifiedSessionBindingContext {
    /// Exact connection context, including ephemeral SSH session identifiers.
    digest: [u8; 32],
    /// Stable approval context for the verified ordered host-key path.
    cache_context_digest: [u8; 32],
    binding_count: usize,
    forwarding_hops: usize,
    final_is_forwarding: bool,
}

impl VerifiedSessionBindingContext {
    /// SHA-256 over the ordered, length-delimited host-key/session-ID binding path.
    #[cfg(test)]
    pub fn digest(&self) -> &[u8; 32] {
        &self.digest
    }

    /// SHA-256 over the ordered host-key/forwarding path, excluding ephemeral
    /// SSH session identifiers that are validated separately for every sign.
    pub fn cache_context_digest(&self) -> &[u8; 32] {
        &self.cache_context_digest
    }

    /// Number of verified bindings in this connection's path.
    pub fn binding_count(&self) -> usize {
        self.binding_count
    }

    /// Number of path entries marked as forwarding hops.
    pub fn forwarding_hops(&self) -> usize {
        self.forwarding_hops
    }

    /// Whether the most recently verified binding is itself a forwarding hop.
    pub fn final_is_forwarding(&self) -> bool {
        self.final_is_forwarding
    }
}

#[derive(Clone, Debug)]
struct VerifiedSessionBinding {
    host_key_blob: Box<[u8]>,
    session_id: Box<[u8]>,
    is_forwarding: bool,
}

#[derive(Clone, Debug)]
enum SessionBindingState {
    Accepting(Vec<VerifiedSessionBinding>),
    Poisoned,
}

impl Default for SessionBindingState {
    fn default() -> Self {
        Self::Accepting(Vec::new())
    }
}

impl SessionBindingState {
    fn is_poisoned(&self) -> bool {
        matches!(self, Self::Poisoned)
    }

    fn bindings(&self) -> Option<&[VerifiedSessionBinding]> {
        match self {
            Self::Accepting(bindings) => Some(bindings),
            Self::Poisoned => None,
        }
    }

    fn record(&mut self, binding: VerifiedSessionBinding) -> Result<(), SessionBindError> {
        let bindings = match self {
            Self::Accepting(bindings) => bindings,
            Self::Poisoned => return Err(SessionBindError::Poisoned),
        };

        if let Some(previous) = bindings
            .iter()
            .find(|previous| previous.session_id == binding.session_id)
        {
            if previous.host_key_blob == binding.host_key_blob
                && previous.is_forwarding == binding.is_forwarding
            {
                // Match OpenSSH's idempotent handling. Replaying the exact
                // already-verified tuple cannot extend or alter the path.
                return Ok(());
            }
            return Err(SessionBindError::ConflictingBinding);
        }

        if bindings.len() >= MAX_SESSION_BINDINGS {
            return Err(SessionBindError::TooManyBindings);
        }

        if bindings.iter().any(|binding| !binding.is_forwarding) {
            return Err(SessionBindError::AuthenticationBindingIsFinal);
        }

        bindings.push(binding);
        Ok(())
    }

    fn context(&self) -> Option<VerifiedSessionBindingContext> {
        let bindings = self.bindings()?;
        let final_binding = bindings.last()?;
        let mut hasher = Sha256::new();
        hasher.update(BINDING_DIGEST_DOMAIN);
        hash_len(&mut hasher, bindings.len());

        let mut cache_context_hasher = Sha256::new();
        cache_context_hasher.update(BINDING_CACHE_CONTEXT_DOMAIN);
        hash_len(&mut cache_context_hasher, bindings.len());

        for binding in bindings {
            hash_len(&mut hasher, binding.host_key_blob.len());
            hasher.update(&binding.host_key_blob);
            hash_len(&mut hasher, binding.session_id.len());
            hasher.update(&binding.session_id);
            hasher.update([u8::from(binding.is_forwarding)]);

            hash_len(&mut cache_context_hasher, binding.host_key_blob.len());
            cache_context_hasher.update(&binding.host_key_blob);
            cache_context_hasher.update([u8::from(binding.is_forwarding)]);
        }

        Some(VerifiedSessionBindingContext {
            digest: hasher.finalize().into(),
            cache_context_digest: cache_context_hasher.finalize().into(),
            binding_count: bindings.len(),
            forwarding_hops: bindings
                .iter()
                .filter(|binding| binding.is_forwarding)
                .count(),
            final_is_forwarding: final_binding.is_forwarding,
        })
    }

    fn validate_sign_request(&self, request: &SignRequest) -> Result<(), SessionBindError> {
        let bindings = self.bindings().ok_or(SessionBindError::Poisoned)?;
        let Some(final_binding) = bindings.last() else {
            return Ok(());
        };
        if final_binding.is_forwarding {
            return Err(SessionBindError::SigningOnForwardingHop);
        }

        validate_bound_userauth_request(bindings, request)
    }
}

fn hash_len(hasher: &mut Sha256, len: usize) {
    let len = u32::try_from(len).expect("session-binding lengths are protocol-capped");
    hasher.update(len.to_be_bytes());
}

#[derive(Debug, Error)]
enum SessionBindError {
    #[error("session binding was already poisoned")]
    Poisoned,
    #[error("missing {field} length")]
    MissingLength { field: &'static str },
    #[error("{field} exceeds the {maximum}-byte limit")]
    FieldTooLong { field: &'static str, maximum: usize },
    #[error("truncated {field}")]
    TruncatedField { field: &'static str },
    #[error("invalid host key")]
    InvalidHostKey,
    #[error("invalid host-key signature encoding")]
    InvalidSignatureEncoding,
    #[error("session ID must not be empty")]
    EmptySessionId,
    #[error("invalid forwarding flag {0}; expected 0 or 1")]
    InvalidForwardingFlag(u8),
    #[error("session binding contains {0} trailing bytes")]
    TrailingData(usize),
    #[error("host-key signature verification failed")]
    SignatureVerification,
    #[error("session ID conflicts with a previously recorded binding")]
    ConflictingBinding,
    #[error("cannot add a binding after a non-forwarding authentication binding")]
    AuthenticationBindingIsFinal,
    #[error("too many session bindings")]
    TooManyBindings,
    #[error("cannot sign while the newest session binding is a forwarding hop")]
    SigningOnForwardingHop,
    #[error("invalid bound SSH userauth request: {0}")]
    InvalidBoundUserauth(&'static str),
    #[error("signing request session ID does not match the newest binding")]
    SigningSessionMismatch,
    #[error("signing request key does not match the requested agent key")]
    SigningKeyMismatch,
    #[error("a forwarded signing request must use the host-bound public-key method")]
    HostboundKeyRequired,
    #[error("host-bound signing request does not match the newest bound host key")]
    HostboundKeyMismatch,
}

struct ParsedSessionBinding {
    host_key_blob: Box<[u8]>,
    verification_key: KeyData,
    session_id: Box<[u8]>,
    signature: Signature,
    is_forwarding: bool,
}

impl ParsedSessionBinding {
    fn verify_signature(&self) -> Result<(), SessionBindError> {
        SessionBind {
            host_key: self.verification_key.clone(),
            session_id: self.session_id.to_vec(),
            signature: self.signature.clone(),
            is_forwarding: self.is_forwarding,
        }
        .verify_signature()
        .map_err(|_| SessionBindError::SignatureVerification)
    }

    fn into_verified(self) -> VerifiedSessionBinding {
        VerifiedSessionBinding {
            host_key_blob: self.host_key_blob,
            session_id: self.session_id,
            is_forwarding: self.is_forwarding,
        }
    }
}

fn parse_session_binding(details: &[u8]) -> Result<ParsedSessionBinding, SessionBindError> {
    let mut input = details;
    let host_key_blob = read_ssh_string(&mut input, "host key", MAX_HOST_KEY_BLOB_LEN)?;
    let session_id = read_ssh_string(&mut input, "session ID", MAX_SESSION_ID_LEN)?;
    if session_id.is_empty() {
        return Err(SessionBindError::EmptySessionId);
    }
    let signature_blob = read_ssh_string(&mut input, "signature", MAX_SIGNATURE_BLOB_LEN)?;
    let forwarding_flag = input
        .first()
        .copied()
        .ok_or(SessionBindError::TruncatedField {
            field: "forwarding flag",
        })?;
    input = &input[1..];

    let is_forwarding = match forwarding_flag {
        0 => false,
        1 => true,
        other => return Err(SessionBindError::InvalidForwardingFlag(other)),
    };

    if !input.is_empty() {
        return Err(SessionBindError::TrailingData(input.len()));
    }

    let verification_key = parse_host_verification_key(host_key_blob)?;
    let mut signature_reader = signature_blob;
    let signature = Signature::decode(&mut signature_reader)
        .map_err(|_| SessionBindError::InvalidSignatureEncoding)?;
    if !signature_reader.is_empty() {
        return Err(SessionBindError::InvalidSignatureEncoding);
    }

    Ok(ParsedSessionBinding {
        host_key_blob: host_key_blob.into(),
        verification_key,
        session_id: session_id.into(),
        signature,
        is_forwarding,
    })
}

fn read_ssh_string<'a>(
    input: &mut &'a [u8],
    field: &'static str,
    maximum: usize,
) -> Result<&'a [u8], SessionBindError> {
    let raw_len = input
        .get(..4)
        .ok_or(SessionBindError::MissingLength { field })?;
    let len = u32::from_be_bytes(
        raw_len
            .try_into()
            .map_err(|_| SessionBindError::MissingLength { field })?,
    );
    let len =
        usize::try_from(len).map_err(|_| SessionBindError::FieldTooLong { field, maximum })?;
    if len > maximum {
        return Err(SessionBindError::FieldTooLong { field, maximum });
    }

    let body_and_rest = &input[4..];
    let body = body_and_rest
        .get(..len)
        .ok_or(SessionBindError::TruncatedField { field })?;
    *input = &body_and_rest[len..];
    Ok(body)
}

fn parse_host_verification_key(host_key_blob: &[u8]) -> Result<KeyData, SessionBindError> {
    if let Ok(certificate) = Certificate::from_bytes(host_key_blob) {
        return Ok(certificate.public_key().clone());
    }

    let mut host_key_reader = host_key_blob;
    let host_key =
        KeyData::decode(&mut host_key_reader).map_err(|_| SessionBindError::InvalidHostKey)?;
    if !host_key_reader.is_empty() {
        return Err(SessionBindError::InvalidHostKey);
    }
    Ok(host_key)
}

fn validate_bound_userauth_request(
    bindings: &[VerifiedSessionBinding],
    request: &SignRequest,
) -> Result<(), SessionBindError> {
    let final_binding = bindings
        .last()
        .ok_or(SessionBindError::InvalidBoundUserauth("missing binding"))?;
    let mut input = request.data.as_slice();

    let session_id = read_ssh_string(&mut input, "userauth session ID", MAX_SESSION_ID_LEN)?;
    if session_id != final_binding.session_id.as_ref() {
        return Err(SessionBindError::SigningSessionMismatch);
    }

    let message_type = input
        .first()
        .copied()
        .ok_or(SessionBindError::InvalidBoundUserauth(
            "missing message type",
        ))?;
    input = &input[1..];
    if message_type != SSH2_MSG_USERAUTH_REQUEST {
        return Err(SessionBindError::InvalidBoundUserauth(
            "unexpected message type",
        ));
    }

    read_ssh_string(&mut input, "userauth username", MAX_HOST_KEY_BLOB_LEN)?;
    let service = read_ssh_string(&mut input, "userauth service", MAX_USERAUTH_FIELD_LEN)?;
    if service != SSH_CONNECTION_SERVICE {
        return Err(SessionBindError::InvalidBoundUserauth("unexpected service"));
    }
    let method = read_ssh_string(&mut input, "userauth method", MAX_USERAUTH_FIELD_LEN)?;

    let signature_follows =
        input
            .first()
            .copied()
            .ok_or(SessionBindError::InvalidBoundUserauth(
                "missing signature flag",
            ))?;
    input = &input[1..];
    if signature_follows != 1 {
        return Err(SessionBindError::InvalidBoundUserauth(
            "signature flag is not true",
        ));
    }

    let algorithm = read_ssh_string(&mut input, "userauth algorithm", MAX_USERAUTH_FIELD_LEN)?;
    let algorithm = std::str::from_utf8(algorithm)
        .ok()
        .and_then(|algorithm| Algorithm::new(algorithm).ok())
        .ok_or(SessionBindError::InvalidBoundUserauth(
            "invalid public-key algorithm",
        ))?;
    let key_algorithm = request.pubkey.algorithm();
    let algorithm_matches = match (&key_algorithm, &algorithm) {
        // RFC 8332 RSA SHA-2 identifiers intentionally differ from the
        // `ssh-rsa` identifier encoded inside the public-key blob.
        (Algorithm::Rsa { .. }, Algorithm::Rsa { .. }) => true,
        _ => key_algorithm == algorithm,
    };
    if !algorithm_matches {
        return Err(SessionBindError::InvalidBoundUserauth(
            "public-key algorithm does not match the requested key",
        ));
    }
    let public_key_blob =
        read_ssh_string(&mut input, "userauth public key", MAX_HOST_KEY_BLOB_LEN)?;
    let mut public_key_reader = public_key_blob;
    let public_key = KeyData::decode(&mut public_key_reader)
        .map_err(|_| SessionBindError::InvalidBoundUserauth("invalid public key"))?;
    if !public_key_reader.is_empty() {
        return Err(SessionBindError::InvalidBoundUserauth(
            "public key contains trailing bytes",
        ));
    }
    if public_key != request.pubkey {
        return Err(SessionBindError::SigningKeyMismatch);
    }

    match method {
        PUBLICKEY_METHOD => {
            if bindings.len() > 1 {
                return Err(SessionBindError::HostboundKeyRequired);
            }
        }
        HOSTBOUND_PUBLICKEY_METHOD => {
            let host_key_blob =
                read_ssh_string(&mut input, "userauth host key", MAX_HOST_KEY_BLOB_LEN)?;
            // Host-key encodings are canonical SSH key/certificate blobs. An
            // exact comparison avoids treating a different certificate over
            // the same public key as the bound destination.
            if host_key_blob != final_binding.host_key_blob.as_ref() {
                return Err(SessionBindError::HostboundKeyMismatch);
            }
        }
        _ => {
            return Err(SessionBindError::InvalidBoundUserauth(
                "unexpected authentication method",
            ));
        }
    }

    if !input.is_empty() {
        return Err(SessionBindError::InvalidBoundUserauth("trailing bytes"));
    }
    Ok(())
}

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
    #[cfg(target_os = "macos")]
    macos_caller_guard: Option<keyguard_agent_identity::macos::MacosPeerIdentity>,
    #[cfg(target_os = "linux")]
    linux_caller_guard:
        Option<std::sync::Arc<keyguard_agent_identity::linux_identity::LinuxProcessIdentity>>,
}

impl<K: KeyProvider> KeyguardAgent<K> {
    #[cfg(test)]
    pub fn new(key_provider: K) -> Self {
        Self {
            key_provider,
            caller: None,
            session_bindings: SessionBindingState::default(),
            #[cfg(target_os = "macos")]
            macos_caller_guard: None,
            #[cfg(target_os = "linux")]
            linux_caller_guard: None,
        }
    }

    #[cfg(any(test, not(any(target_os = "linux", target_os = "macos", windows))))]
    pub fn with_caller(key_provider: K, caller: Option<CallerIdentity>) -> Self {
        Self {
            key_provider,
            caller,
            session_bindings: SessionBindingState::default(),
            #[cfg(target_os = "macos")]
            macos_caller_guard: None,
            #[cfg(target_os = "linux")]
            linux_caller_guard: None,
        }
    }

    #[cfg(target_os = "macos")]
    fn with_macos_caller(
        key_provider: K,
        context: Option<crate::caller_identity::UnixCallerContext>,
    ) -> Self {
        let (caller, macos_caller_guard) = match context {
            Some(context) => (Some(context.caller), context.macos_guard),
            None => (None, None),
        };
        Self {
            key_provider,
            caller,
            session_bindings: SessionBindingState::default(),
            macos_caller_guard,
        }
    }

    #[cfg(target_os = "linux")]
    fn with_linux_caller(
        key_provider: K,
        context: Option<crate::caller_identity::UnixCallerContext>,
    ) -> Self {
        let (caller, linux_caller_guard) = match context {
            Some(context) => (
                Some(context.caller),
                context.linux_guard.map(std::sync::Arc::new),
            ),
            None => (None, None),
        };
        Self {
            key_provider,
            caller,
            session_bindings: SessionBindingState::default(),
            linux_caller_guard,
        }
    }

    #[cfg(windows)]
    fn with_windows_caller(key_provider: K, caller: Option<CallerIdentity>) -> Self {
        Self {
            key_provider,
            caller,
            session_bindings: SessionBindingState::default(),
        }
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

    #[cfg(target_os = "macos")]
    fn ensure_macos_caller_valid(&self) -> Result<(), AgentError> {
        let Some(guard) = self.macos_caller_guard.as_ref() else {
            return Ok(());
        };
        if let Err(error) = guard.revalidate() {
            warn!(%error, "macOS caller identity changed; refusing agent operation");
            return Err(AgentError::Failure);
        }
        Ok(())
    }

    #[cfg(target_os = "linux")]
    fn ensure_linux_caller_valid(&self) -> Result<(), AgentError> {
        let Some(guard) = self.linux_caller_guard.as_ref() else {
            return Ok(());
        };
        if let Err(error) = guard.revalidate() {
            warn!(%error, "Linux caller process changed; refusing agent operation");
            return Err(AgentError::Failure);
        }
        Ok(())
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

        #[cfg(target_os = "macos")]
        self.ensure_macos_caller_valid()?;
        #[cfg(target_os = "linux")]
        self.ensure_linux_caller_valid()?;

        let keys_response = self
            .key_provider
            .list_keys(self.caller.clone())
            .await
            .map_err(|e| {
                warn!("Failed to list keys from Keyguard: {}", e);
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
                Err(e) => {
                    warn!(
                        name = %key.name,
                        "Failed to parse SSH public key, skipping: {}",
                        e
                    );
                }
            }
        }

        info!(count = identities.len(), "Returning SSH key identities");
        Ok(identities)
    }

    async fn sign(&mut self, request: SignRequest) -> Result<Signature, AgentError> {
        debug!("Handling SignRequest");

        if let Err(error) = self.session_bindings.validate_sign_request(&request) {
            warn!(%error, "Refusing sign request that does not match its SSH session binding");
            return Err(AgentError::Failure);
        }

        #[cfg(target_os = "macos")]
        self.ensure_macos_caller_valid()?;
        #[cfg(target_os = "linux")]
        self.ensure_linux_caller_valid()?;

        // Find the matching key by comparing the public key data.
        let keys_response = self
            .key_provider
            .list_keys(self.caller.clone())
            .await
            .map_err(|e| {
                warn!("Failed to list keys for sign request: {}", e);
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

        info!(
            name = %key.name,
            "Requesting signature from Keyguard"
        );

        // Request signing from Keyguard (may prompt user for approval).
        let sign_caller = self.caller_for_sign();
        #[cfg(target_os = "macos")]
        self.ensure_macos_caller_valid()?;
        #[cfg(target_os = "linux")]
        self.ensure_linux_caller_valid()?;
        let sign_response = self
            .key_provider
            .sign_data(&key.public_key, &request.data, request.flags, sign_caller)
            .await
            .map_err(|e| {
                warn!("Signing request failed: {}", e);
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
            _ => Err(AgentError::ExtensionFailure),
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
        #[cfg(target_os = "macos")]
        {
            let context = crate::caller_identity::caller_context_from_unix_stream(socket);
            KeyguardAgent::with_macos_caller(self.key_provider.clone(), context)
        }

        #[cfg(target_os = "linux")]
        {
            let context = crate::caller_identity::caller_context_from_unix_stream(socket);
            KeyguardAgent::with_linux_caller(self.key_provider.clone(), context)
        }

        #[cfg(not(any(target_os = "linux", target_os = "macos")))]
        let caller = crate::caller_identity::caller_from_unix_stream(socket);
        #[cfg(not(any(target_os = "linux", target_os = "macos")))]
        {
            KeyguardAgent::with_caller(self.key_provider.clone(), caller)
        }
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
        _socket: &tokio::net::windows::named_pipe::NamedPipeServer,
    ) -> impl Session {
        let caller = windows_connection_caller();
        KeyguardAgent::with_windows_caller(self.key_provider.clone(), caller)
    }
}

#[cfg(windows)]
fn windows_connection_caller() -> Option<CallerIdentity> {
    use keyguard_agent_identity::ConnectionFingerprint;

    // GetNamedPipeClientProcessId is intentionally not queried here: a client
    // can transfer the handle and spoof/recycle that PID. Windows identity is
    // unknown until a future design binds actual client I/O to an impersonation
    // token, so both authorization and presentation stay connection-only.
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
mod tests {
    use super::*;
    use crate::ipc::messages::{
        CallerAuthorization, CallerAuthorizationEvidenceSource, CallerAuthorizationSubject,
        CallerAuthorizationSubjectKind, SshKey,
    };
    use keyguard_agent_identity::PRINCIPAL_FINGERPRINT_LEN;
    use signature::Signer;
    use ssh_key::private::Ed25519Keypair;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::Mutex;

    // A well-known Ed25519 test public key (generated for testing).
    const TEST_ED25519_PUBKEY: &str =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHLbRWVjaj0MMLgFjoxGc8TFfDfb8rVIeONrdiZpigKW test@keyguard";

    #[cfg(windows)]
    #[test]
    fn windows_authorization_is_strictly_connection_scoped() {
        assert_eq!(unverified_windows_caller().app_name, "Unverified caller");
        let authorization = connection_only_authorization(
            keyguard_agent_identity::ConnectionFingerprint::from_bytes([0xA5; 32]),
        );

        assert_eq!(authorization.connection_fingerprint, vec![0xA5; 32]);
        assert!(authorization.subjects.is_empty());
        assert!(authorization.authorization_context_fingerprint.is_empty());
    }

    // A second key that is intentionally invalid.
    const TEST_INVALID_PUBKEY: &str = "not-a-valid-key";

    fn session_bind_extension(seed_byte: u8, session_id: &[u8], is_forwarding: bool) -> Extension {
        session_bind_extension_signed_for(seed_byte, session_id, session_id, is_forwarding)
    }

    fn session_bind_extension_signed_for(
        seed_byte: u8,
        session_id: &[u8],
        signed_session_id: &[u8],
        is_forwarding: bool,
    ) -> Extension {
        let keypair = Ed25519Keypair::from_seed(&[seed_byte; 32]);
        let signature = keypair
            .try_sign(signed_session_id)
            .expect("test Ed25519 signature should be valid");

        Extension::new_message(SessionBind {
            host_key: KeyData::Ed25519(keypair.public),
            session_id: session_id.to_vec(),
            signature,
            is_forwarding,
        })
        .expect("test session-bind message should encode")
    }

    fn session_bind_host_key_blob(seed_byte: u8) -> Vec<u8> {
        let keypair = Ed25519Keypair::from_seed(&[seed_byte; 32]);
        ssh_key::PublicKey::new(KeyData::Ed25519(keypair.public), "")
            .to_bytes()
            .expect("test host key should encode")
    }

    fn push_ssh_string(output: &mut Vec<u8>, value: &[u8]) {
        let len = u32::try_from(value.len()).expect("test SSH string fits in u32");
        output.extend_from_slice(&len.to_be_bytes());
        output.extend_from_slice(value);
    }

    fn bound_userauth_data(
        session_id: &[u8],
        public_key: &KeyData,
        host_key_blob: Option<&[u8]>,
    ) -> Vec<u8> {
        let public_key_blob = ssh_key::PublicKey::new(public_key.clone(), "")
            .to_bytes()
            .expect("test public key should encode");
        let method = if host_key_blob.is_some() {
            HOSTBOUND_PUBLICKEY_METHOD
        } else {
            PUBLICKEY_METHOD
        };

        let mut output = Vec::new();
        push_ssh_string(&mut output, session_id);
        output.push(SSH2_MSG_USERAUTH_REQUEST);
        push_ssh_string(&mut output, b"test-user");
        push_ssh_string(&mut output, SSH_CONNECTION_SERVICE);
        push_ssh_string(&mut output, method);
        output.push(1);
        push_ssh_string(&mut output, public_key.algorithm().as_str().as_bytes());
        push_ssh_string(&mut output, &public_key_blob);
        if let Some(host_key_blob) = host_key_blob {
            push_ssh_string(&mut output, host_key_blob);
        }
        output
    }

    /// A fake `KeyProvider` for testing.
    #[derive(Clone)]
    struct FakeKeyProvider {
        keys: Vec<SshKey>,
        sign_result: Option<SignDataResponse>,
        should_fail_list: Arc<AtomicBool>,
        should_fail_sign: Arc<AtomicBool>,
        last_list_caller: Arc<Mutex<Option<CallerIdentity>>>,
        last_sign_caller: Arc<Mutex<Option<CallerIdentity>>>,
    }

    use std::sync::Arc;

    impl FakeKeyProvider {
        fn new(keys: Vec<SshKey>) -> Self {
            Self {
                keys,
                sign_result: None,
                should_fail_list: Arc::new(AtomicBool::new(false)),
                should_fail_sign: Arc::new(AtomicBool::new(false)),
                last_list_caller: Arc::new(Mutex::new(None)),
                last_sign_caller: Arc::new(Mutex::new(None)),
            }
        }

        fn with_sign_result(mut self, result: SignDataResponse) -> Self {
            self.sign_result = Some(result);
            self
        }

        fn set_list_failure(&self, fail: bool) {
            self.should_fail_list.store(fail, Ordering::Relaxed);
        }

        fn set_sign_failure(&self, fail: bool) {
            self.should_fail_sign.store(fail, Ordering::Relaxed);
        }

        fn last_list_caller(&self) -> Option<CallerIdentity> {
            self.last_list_caller.lock().ok().and_then(|v| v.clone())
        }

        fn last_sign_caller(&self) -> Option<CallerIdentity> {
            self.last_sign_caller.lock().ok().and_then(|v| v.clone())
        }
    }

    #[ssh_agent_lib::async_trait]
    impl KeyProvider for FakeKeyProvider {
        async fn list_keys(&self, caller: Option<CallerIdentity>) -> Result<ListKeysResponse> {
            if let Ok(mut slot) = self.last_list_caller.lock() {
                *slot = caller;
            }
            if self.should_fail_list.load(Ordering::Relaxed) {
                anyhow::bail!("Simulated list_keys failure");
            }
            Ok(ListKeysResponse {
                keys: self.keys.clone(),
            })
        }

        async fn sign_data(
            &self,
            _public_key: &str,
            _data: &[u8],
            _flags: u32,
            caller: Option<CallerIdentity>,
        ) -> Result<SignDataResponse> {
            if let Ok(mut slot) = self.last_sign_caller.lock() {
                *slot = caller;
            }
            if self.should_fail_sign.load(Ordering::Relaxed) {
                anyhow::bail!("Simulated sign_data failure");
            }
            self.sign_result
                .clone()
                .ok_or_else(|| anyhow::anyhow!("No sign result configured"))
        }
    }

    // ================================================================
    // parse_openssh_pubkey tests
    // ================================================================

    #[test]
    fn parse_openssh_pubkey_valid_ed25519() {
        let result = parse_openssh_pubkey(TEST_ED25519_PUBKEY);
        assert!(result.is_ok(), "Should parse a valid Ed25519 public key");
    }

    #[test]
    fn parse_openssh_pubkey_invalid_returns_error() {
        let result = parse_openssh_pubkey(TEST_INVALID_PUBKEY);
        assert!(result.is_err(), "Should fail on an invalid public key");
    }

    #[test]
    fn parse_openssh_pubkey_empty_returns_error() {
        let result = parse_openssh_pubkey("");
        assert!(result.is_err(), "Should fail on empty string");
    }

    // ================================================================
    // KeyguardAgent::request_identities tests
    // ================================================================

    #[tokio::test]
    async fn request_identities_returns_valid_keys() {
        let provider = FakeKeyProvider::new(vec![SshKey {
            name: "test-key".to_string(),
            public_key: TEST_ED25519_PUBKEY.to_string(),
            key_type: "ssh-ed25519".to_string(),
            fingerprint: "SHA256:test".to_string(),
        }]);
        let mut agent = KeyguardAgent::new(provider);

        let identities = agent.request_identities().await.unwrap();
        assert_eq!(identities.len(), 1);
        assert_eq!(identities[0].comment, "test-key");
    }

    #[tokio::test]
    async fn request_identities_skips_unparseable_keys() {
        let provider = FakeKeyProvider::new(vec![
            SshKey {
                name: "valid-key".to_string(),
                public_key: TEST_ED25519_PUBKEY.to_string(),
                key_type: "ssh-ed25519".to_string(),
                fingerprint: "".to_string(),
            },
            SshKey {
                name: "bad-key".to_string(),
                public_key: TEST_INVALID_PUBKEY.to_string(),
                key_type: "unknown".to_string(),
                fingerprint: "".to_string(),
            },
        ]);
        let mut agent = KeyguardAgent::new(provider);

        let identities = agent.request_identities().await.unwrap();
        assert_eq!(identities.len(), 1, "Should skip the unparseable key");
        assert_eq!(identities[0].comment, "valid-key");
    }

    #[tokio::test]
    async fn request_identities_empty_list() {
        let provider = FakeKeyProvider::new(vec![]);
        let mut agent = KeyguardAgent::new(provider);

        let identities = agent.request_identities().await.unwrap();
        assert!(identities.is_empty());
    }

    #[tokio::test]
    async fn request_identities_ipc_error_returns_failure() {
        let provider = FakeKeyProvider::new(vec![]);
        provider.set_list_failure(true);
        let mut agent = KeyguardAgent::new(provider);

        let result = agent.request_identities().await;
        assert!(result.is_err(), "Should return AgentError on IPC failure");
    }

    // ================================================================
    // KeyguardAgent::sign tests
    // ================================================================

    #[tokio::test]
    async fn sign_no_matching_key_returns_failure() {
        // Provide one key but request signing with a different pubkey.
        let provider = FakeKeyProvider::new(vec![SshKey {
            name: "other-key".to_string(),
            public_key: TEST_ED25519_PUBKEY.to_string(),
            key_type: "ssh-ed25519".to_string(),
            fingerprint: "".to_string(),
        }]);
        let mut agent = KeyguardAgent::new(provider);

        // Build a SignRequest with a pubkey that doesn't match any key.
        let request = SignRequest {
            pubkey: ssh_key::public::KeyData::Ed25519(ssh_key::public::Ed25519PublicKey(
                Default::default(),
            )),
            data: vec![1, 2, 3],
            flags: 0,
        };

        let result = agent.sign(request).await;
        assert!(result.is_err(), "Should fail when no key matches");
    }

    #[tokio::test]
    async fn sign_ipc_error_returns_failure() {
        let provider = FakeKeyProvider::new(vec![SshKey {
            name: "test-key".to_string(),
            public_key: TEST_ED25519_PUBKEY.to_string(),
            key_type: "ssh-ed25519".to_string(),
            fingerprint: "".to_string(),
        }])
        .with_sign_result(SignDataResponse {
            signature: vec![0; 64],
            algorithm: "ssh-ed25519".to_string(),
        });
        provider.set_sign_failure(true);

        let mut agent = KeyguardAgent::new(provider);

        // Parse the real pubkey to get the matching KeyData.
        let pubkey = ssh_key::PublicKey::from_openssh(TEST_ED25519_PUBKEY).unwrap();
        let request = SignRequest {
            pubkey: pubkey.key_data().clone(),
            data: vec![1, 2, 3],
            flags: 0,
        };

        let result = agent.sign(request).await;
        assert!(result.is_err(), "Should fail when IPC sign fails");
    }

    #[tokio::test]
    async fn sign_returns_valid_signature() {
        let fake_sig = vec![42u8; 64];
        let provider = FakeKeyProvider::new(vec![SshKey {
            name: "test-key".to_string(),
            public_key: TEST_ED25519_PUBKEY.to_string(),
            key_type: "ssh-ed25519".to_string(),
            fingerprint: "".to_string(),
        }])
        .with_sign_result(SignDataResponse {
            signature: fake_sig.clone(),
            algorithm: "ssh-ed25519".to_string(),
        });

        let mut agent = KeyguardAgent::new(provider);

        let pubkey = ssh_key::PublicKey::from_openssh(TEST_ED25519_PUBKEY).unwrap();
        let request = SignRequest {
            pubkey: pubkey.key_data().clone(),
            data: vec![1, 2, 3],
            flags: 0,
        };

        let result = agent.sign(request).await;
        assert!(result.is_ok(), "Should succeed with valid sign response");
        let sig = result.unwrap();
        assert_eq!(sig.algorithm(), Algorithm::Ed25519);
    }

    #[tokio::test]
    async fn caller_identity_is_forwarded_to_provider() {
        let provider = FakeKeyProvider::new(vec![SshKey {
            name: "test-key".to_string(),
            public_key: TEST_ED25519_PUBKEY.to_string(),
            key_type: "ssh-ed25519".to_string(),
            fingerprint: "".to_string(),
        }])
        .with_sign_result(SignDataResponse {
            signature: vec![42u8; 64],
            algorithm: "ssh-ed25519".to_string(),
        });

        let caller = CallerIdentity {
            pid: 123,
            uid: 456,
            gid: 789,
            process_name: "ssh".to_string(),
            executable_path: "/usr/bin/ssh".to_string(),
            app_pid: 321,
            app_name: "Terminal".to_string(),
            app_bundle_path: "/System/Applications/Utilities/Terminal.app".to_string(),
            authorization: None,
        };

        let mut agent = KeyguardAgent::with_caller(provider.clone(), Some(caller.clone()));

        // List keys should forward identity.
        agent.request_identities().await.unwrap();
        assert_eq!(provider.last_list_caller().unwrap().pid, caller.pid);

        // Sign should forward identity.
        let pubkey = ssh_key::PublicKey::from_openssh(TEST_ED25519_PUBKEY).unwrap();
        let request = SignRequest {
            pubkey: pubkey.key_data().clone(),
            data: vec![1, 2, 3],
            flags: 0,
        };
        agent.sign(request).await.unwrap();
        assert_eq!(provider.last_sign_caller().unwrap().pid, caller.pid);
    }

    #[tokio::test]
    async fn verified_session_binding_partitions_sign_authorization_only() {
        let provider = FakeKeyProvider::new(vec![SshKey {
            name: "test-key".to_string(),
            public_key: TEST_ED25519_PUBKEY.to_string(),
            key_type: "ssh-ed25519".to_string(),
            fingerprint: "".to_string(),
        }])
        .with_sign_result(SignDataResponse {
            signature: vec![42u8; 64],
            algorithm: "ssh-ed25519".to_string(),
        });
        let connection = [0xa5; PRINCIPAL_FINGERPRINT_LEN];
        let caller = CallerIdentity {
            pid: 123,
            uid: 456,
            gid: 789,
            process_name: "ssh".to_string(),
            executable_path: "/usr/bin/ssh".to_string(),
            app_pid: 321,
            app_name: "Terminal".to_string(),
            app_bundle_path: "/System/Applications/Utilities/Terminal.app".to_string(),
            authorization: Some(CallerAuthorization {
                connection_fingerprint: connection.to_vec(),
                subjects: Vec::new(),
                authorization_context_fingerprint: Vec::new(),
            }),
        };
        let mut agent = KeyguardAgent::with_caller(provider.clone(), Some(caller));
        agent
            .extension(session_bind_extension(6, b"bound-sign-session", false))
            .await
            .unwrap();
        let context = agent.verified_session_binding_context().unwrap();
        let expected = AuthorizationContextFingerprint::derive(context.cache_context_digest())
            .expect("verified cache context")
            .into_bytes();
        let pubkey = ssh_key::PublicKey::from_openssh(TEST_ED25519_PUBKEY).unwrap();

        agent
            .sign(SignRequest {
                pubkey: pubkey.key_data().clone(),
                data: bound_userauth_data(b"bound-sign-session", pubkey.key_data(), None),
                flags: 0,
            })
            .await
            .unwrap();

        let list_authorization = provider
            .last_list_caller()
            .and_then(|caller| caller.authorization)
            .expect("list caller authorization");
        let sign_authorization = provider
            .last_sign_caller()
            .and_then(|caller| caller.authorization)
            .expect("sign caller authorization");
        assert_eq!(list_authorization.connection_fingerprint, connection);
        assert_eq!(sign_authorization.connection_fingerprint, connection);
        assert!(list_authorization
            .authorization_context_fingerprint
            .is_empty());
        assert_eq!(
            sign_authorization.authorization_context_fingerprint,
            expected
        );
    }

    #[tokio::test]
    async fn session_binding_preserves_subject_and_sets_separate_cache_context() {
        let provider = FakeKeyProvider::new(vec![SshKey {
            name: "test-key".to_string(),
            public_key: TEST_ED25519_PUBKEY.to_string(),
            key_type: "ssh-ed25519".to_string(),
            fingerprint: String::new(),
        }])
        .with_sign_result(SignDataResponse {
            signature: vec![42u8; 64],
            algorithm: "ssh-ed25519".to_string(),
        });
        let subject = [0x51; PRINCIPAL_FINGERPRINT_LEN];
        let connection = [0x62; PRINCIPAL_FINGERPRINT_LEN];
        let caller = CallerIdentity {
            pid: 123,
            uid: 456,
            gid: 789,
            process_name: "ssh".to_string(),
            executable_path: "/usr/bin/ssh".to_string(),
            app_pid: 0,
            app_name: String::new(),
            app_bundle_path: String::new(),
            authorization: Some(CallerAuthorization {
                connection_fingerprint: connection.to_vec(),
                subjects: vec![CallerAuthorizationSubject {
                    kind: CallerAuthorizationSubjectKind::StableApplication as i32,
                    evidence_source: CallerAuthorizationEvidenceSource::MacosApplicationAncestry
                        as i32,
                    fingerprint: subject.to_vec(),
                }],
                authorization_context_fingerprint: Vec::new(),
            }),
        };
        let mut agent = KeyguardAgent::with_caller(provider.clone(), Some(caller));
        agent
            .extension(session_bind_extension(32, b"v2-bound-session", false))
            .await
            .unwrap();
        let context = agent.verified_session_binding_context().unwrap();
        let expected_context =
            AuthorizationContextFingerprint::derive(context.cache_context_digest())
                .expect("verified cache context")
                .into_bytes();
        let pubkey = ssh_key::PublicKey::from_openssh(TEST_ED25519_PUBKEY).unwrap();

        agent
            .sign(SignRequest {
                pubkey: pubkey.key_data().clone(),
                data: bound_userauth_data(b"v2-bound-session", pubkey.key_data(), None),
                flags: 0,
            })
            .await
            .unwrap();

        let list = provider
            .last_list_caller()
            .and_then(|caller| caller.authorization)
            .expect("list authorization");
        let sign = provider
            .last_sign_caller()
            .and_then(|caller| caller.authorization)
            .expect("sign authorization");
        assert_eq!(list.subjects[0].fingerprint, subject);
        assert_eq!(sign.subjects[0].fingerprint, subject);
        assert_eq!(list.connection_fingerprint, connection);
        assert_eq!(sign.connection_fingerprint, connection);
        assert!(list.authorization_context_fingerprint.is_empty());
        assert_eq!(sign.authorization_context_fingerprint, expected_context);
    }

    // ================================================================
    // Additional edge case tests
    // ================================================================

    #[tokio::test]
    async fn request_identities_all_invalid_keys_returns_empty() {
        let provider = FakeKeyProvider::new(vec![
            SshKey {
                name: "bad1".to_string(),
                public_key: "invalid-key-1".to_string(),
                key_type: "unknown".to_string(),
                fingerprint: "".to_string(),
            },
            SshKey {
                name: "bad2".to_string(),
                public_key: "also not a key".to_string(),
                key_type: "unknown".to_string(),
                fingerprint: "".to_string(),
            },
        ]);
        let mut agent = KeyguardAgent::new(provider);

        let identities = agent.request_identities().await.unwrap();
        assert!(
            identities.is_empty(),
            "All unparseable keys should be skipped"
        );
    }

    #[tokio::test]
    async fn request_identities_preserves_comment_from_key_name() {
        let provider = FakeKeyProvider::new(vec![SshKey {
            name: "My Important Server Key".to_string(),
            public_key: TEST_ED25519_PUBKEY.to_string(),
            key_type: "ssh-ed25519".to_string(),
            fingerprint: "SHA256:test".to_string(),
        }]);
        let mut agent = KeyguardAgent::new(provider);

        let identities = agent.request_identities().await.unwrap();
        assert_eq!(identities.len(), 1);
        assert_eq!(
            identities[0].comment, "My Important Server Key",
            "Identity comment should match the key name"
        );
    }

    #[tokio::test]
    async fn sign_list_keys_failure_during_sign_returns_error() {
        // The sign() method calls list_keys() to find the matching key.
        // If list_keys fails during this step, sign should fail.
        let provider = FakeKeyProvider::new(vec![SshKey {
            name: "test-key".to_string(),
            public_key: TEST_ED25519_PUBKEY.to_string(),
            key_type: "ssh-ed25519".to_string(),
            fingerprint: "".to_string(),
        }])
        .with_sign_result(SignDataResponse {
            signature: vec![0; 64],
            algorithm: "ssh-ed25519".to_string(),
        });
        // Fail the list_keys call that sign() makes internally.
        provider.set_list_failure(true);

        let mut agent = KeyguardAgent::new(provider);

        let pubkey = ssh_key::PublicKey::from_openssh(TEST_ED25519_PUBKEY).unwrap();
        let request = SignRequest {
            pubkey: pubkey.key_data().clone(),
            data: vec![1, 2, 3],
            flags: 0,
        };

        let result = agent.sign(request).await;
        assert!(
            result.is_err(),
            "Should fail when list_keys fails during sign"
        );
    }

    #[tokio::test]
    async fn sign_with_only_invalid_keys_returns_failure() {
        // All keys are unparseable, so no match can be found.
        let provider = FakeKeyProvider::new(vec![SshKey {
            name: "bad-key".to_string(),
            public_key: "not-a-real-key".to_string(),
            key_type: "unknown".to_string(),
            fingerprint: "".to_string(),
        }])
        .with_sign_result(SignDataResponse {
            signature: vec![0; 64],
            algorithm: "ssh-ed25519".to_string(),
        });

        let mut agent = KeyguardAgent::new(provider);

        let request = SignRequest {
            pubkey: ssh_key::public::KeyData::Ed25519(ssh_key::public::Ed25519PublicKey(
                Default::default(),
            )),
            data: vec![1, 2, 3],
            flags: 0,
        };

        let result = agent.sign(request).await;
        assert!(
            result.is_err(),
            "Should fail when no keys can be parsed to match"
        );
    }

    #[tokio::test]
    async fn extension_query_returns_supported_extensions() {
        let provider = FakeKeyProvider::new(vec![]);
        let mut agent = KeyguardAgent::new(provider);

        let request = Extension {
            name: "query".to_string(),
            details: Vec::new().into(),
        };

        let result = agent.extension(request).await;
        assert!(result.is_ok(), "Query extension should be supported");

        let response = result.unwrap();
        assert!(
            response.is_some(),
            "Query extension should return a response"
        );
        let response = response.unwrap();

        assert_eq!(response.name, "query");
        assert_eq!(
            response.details.as_ref(),
            b"\x00\x00\x00\x18session-bind@openssh.com",
            "Query response must encode extension names as consecutive SSH strings",
        );
    }

    #[tokio::test]
    async fn extension_unknown_name_returns_extension_failure() {
        let provider = FakeKeyProvider::new(vec![]);
        let mut agent = KeyguardAgent::new(provider);

        let request = Extension {
            name: "unknown@example.com".to_string(),
            details: Vec::new().into(),
        };

        let result = agent.extension(request).await;
        assert!(
            matches!(result, Err(AgentError::ExtensionFailure)),
            "Unknown extension should return extension failure"
        );
    }

    #[tokio::test]
    async fn session_bind_valid_signature_records_verified_context() {
        let provider = FakeKeyProvider::new(vec![]);
        let mut agent = KeyguardAgent::new(provider);
        let request = session_bind_extension(7, b"valid-session-id", false);

        let result = agent.extension(request).await;

        assert!(matches!(result, Ok(None)));
        let context = agent
            .verified_session_binding_context()
            .expect("valid session binding should expose a context");
        assert_eq!(context.binding_count(), 1);
        assert_eq!(context.forwarding_hops(), 0);
        assert!(!context.final_is_forwarding());
        assert_eq!(context.digest().len(), 32);
    }

    #[tokio::test]
    async fn session_bind_digest_is_deterministic_for_the_ordered_path() {
        let provider = FakeKeyProvider::new(vec![]);
        let mut first_agent = KeyguardAgent::new(provider.clone());
        let mut second_agent = KeyguardAgent::new(provider);
        let path = [
            session_bind_extension(8, b"forwarded-hop", true),
            session_bind_extension(9, b"authentication-hop", false),
        ];

        for request in path.iter().cloned() {
            first_agent.extension(request).await.unwrap();
        }
        for request in path {
            second_agent.extension(request).await.unwrap();
        }

        let first = first_agent.verified_session_binding_context().unwrap();
        let second = second_agent.verified_session_binding_context().unwrap();
        assert_eq!(first, second);
        assert_eq!(first.binding_count(), 2);
        assert_eq!(first.forwarding_hops(), 1);
        assert!(!first.final_is_forwarding());
    }

    #[tokio::test]
    async fn session_bind_cache_context_is_stable_across_ephemeral_session_ids() {
        let provider = FakeKeyProvider::new(vec![]);
        let mut first_agent = KeyguardAgent::new(provider.clone());
        let mut second_agent = KeyguardAgent::new(provider);

        first_agent
            .extension(session_bind_extension(31, b"session-one", false))
            .await
            .unwrap();
        second_agent
            .extension(session_bind_extension(31, b"session-two", false))
            .await
            .unwrap();

        let first = first_agent.verified_session_binding_context().unwrap();
        let second = second_agent.verified_session_binding_context().unwrap();
        assert_ne!(first.digest(), second.digest());
        assert_eq!(
            first.cache_context_digest(),
            second.cache_context_digest(),
            "the verified host path, not the ephemeral SSH session ID, scopes reusable approval",
        );
    }

    #[tokio::test]
    async fn session_bind_invalid_signature_poisoned_connection_cannot_sign() {
        let provider = FakeKeyProvider::new(vec![SshKey {
            name: "test-key".to_string(),
            public_key: TEST_ED25519_PUBKEY.to_string(),
            key_type: "ssh-ed25519".to_string(),
            fingerprint: "".to_string(),
        }])
        .with_sign_result(SignDataResponse {
            signature: vec![42; 64],
            algorithm: "ssh-ed25519".to_string(),
        });
        let mut agent = KeyguardAgent::new(provider.clone());
        let invalid = session_bind_extension_signed_for(
            10,
            b"claimed-session-id",
            b"different-session-id",
            false,
        );

        let bind_result = agent.extension(invalid).await;
        let pubkey = ssh_key::PublicKey::from_openssh(TEST_ED25519_PUBKEY).unwrap();
        let sign_result = agent
            .sign(SignRequest {
                pubkey: pubkey.key_data().clone(),
                data: vec![1, 2, 3],
                flags: 0,
            })
            .await;

        assert!(matches!(bind_result, Err(AgentError::ExtensionFailure)));
        assert!(sign_result.is_err());
        assert!(agent.verified_session_binding_context().is_none());
        assert!(
            provider.last_list_caller().is_none(),
            "poisoned signing must fail before IPC"
        );
    }

    #[tokio::test]
    async fn session_bind_duplicate_is_idempotent_and_preserves_connection() {
        let provider = FakeKeyProvider::new(vec![]);
        let mut agent = KeyguardAgent::new(provider);
        let binding = session_bind_extension(11, b"duplicate-session-id", true);

        assert!(agent.extension(binding.clone()).await.is_ok());
        let duplicate_result = agent.extension(binding).await;

        assert!(matches!(duplicate_result, Ok(None)));
        let context = agent.verified_session_binding_context().unwrap();
        assert_eq!(context.binding_count(), 1);
        assert!(context.final_is_forwarding());
    }

    #[tokio::test]
    async fn session_bind_same_id_with_different_host_key_is_rejected() {
        let provider = FakeKeyProvider::new(vec![]);
        let mut agent = KeyguardAgent::new(provider);

        assert!(agent
            .extension(session_bind_extension(12, b"conflicting-session-id", true))
            .await
            .is_ok());
        let conflict_result = agent
            .extension(session_bind_extension(13, b"conflicting-session-id", true))
            .await;

        assert!(matches!(conflict_result, Err(AgentError::ExtensionFailure)));
        let context = agent.verified_session_binding_context().unwrap();
        assert_eq!(context.binding_count(), 1);
        assert!(context.final_is_forwarding());
    }

    #[tokio::test]
    async fn session_bind_authentication_binding_is_final() {
        let provider = FakeKeyProvider::new(vec![]);
        let mut agent = KeyguardAgent::new(provider);

        assert!(agent
            .extension(session_bind_extension(14, b"authentication", false))
            .await
            .is_ok());
        let late_binding_result = agent
            .extension(session_bind_extension(14, b"late-forward", true))
            .await;

        assert!(matches!(
            late_binding_result,
            Err(AgentError::ExtensionFailure)
        ));
        let context = agent.verified_session_binding_context().unwrap();
        assert_eq!(context.binding_count(), 1);
        assert!(!context.final_is_forwarding());
    }

    #[tokio::test]
    async fn session_bind_multiple_forwarded_hops_then_authentication_is_accepted() {
        let provider = FakeKeyProvider::new(vec![]);
        let mut agent = KeyguardAgent::new(provider);

        for request in [
            session_bind_extension(15, b"forward-one", true),
            session_bind_extension(16, b"forward-two", true),
            session_bind_extension(17, b"final-authentication", false),
        ] {
            assert!(matches!(agent.extension(request).await, Ok(None)));
        }

        let context = agent.verified_session_binding_context().unwrap();
        assert_eq!(context.binding_count(), 3);
        assert_eq!(context.forwarding_hops(), 2);
        assert!(!context.final_is_forwarding());
    }

    #[tokio::test]
    async fn session_bind_forwarding_hop_cannot_sign() {
        let provider = FakeKeyProvider::new(vec![SshKey {
            name: "test-key".to_string(),
            public_key: TEST_ED25519_PUBKEY.to_string(),
            key_type: "ssh-ed25519".to_string(),
            fingerprint: String::new(),
        }])
        .with_sign_result(SignDataResponse {
            signature: vec![42; 64],
            algorithm: "ssh-ed25519".to_string(),
        });
        let mut agent = KeyguardAgent::new(provider.clone());
        agent
            .extension(session_bind_extension(22, b"forwarding-hop", true))
            .await
            .unwrap();
        let public_key = ssh_key::PublicKey::from_openssh(TEST_ED25519_PUBKEY).unwrap();

        let result = agent
            .sign(SignRequest {
                pubkey: public_key.key_data().clone(),
                data: bound_userauth_data(b"forwarding-hop", public_key.key_data(), None),
                flags: 0,
            })
            .await;

        assert!(result.is_err());
        assert!(provider.last_list_caller().is_none());
        assert!(provider.last_sign_caller().is_none());
    }

    #[tokio::test]
    async fn session_bind_wrong_session_sign_request_is_rejected_before_ipc() {
        let provider = FakeKeyProvider::new(vec![SshKey {
            name: "test-key".to_string(),
            public_key: TEST_ED25519_PUBKEY.to_string(),
            key_type: "ssh-ed25519".to_string(),
            fingerprint: String::new(),
        }]);
        let mut agent = KeyguardAgent::new(provider.clone());
        agent
            .extension(session_bind_extension(23, b"expected-session", false))
            .await
            .unwrap();
        let public_key = ssh_key::PublicKey::from_openssh(TEST_ED25519_PUBKEY).unwrap();

        let result = agent
            .sign(SignRequest {
                pubkey: public_key.key_data().clone(),
                data: bound_userauth_data(b"wrong-session", public_key.key_data(), None),
                flags: 0,
            })
            .await;

        assert!(result.is_err());
        assert!(provider.last_list_caller().is_none());
        assert!(provider.last_sign_caller().is_none());
    }

    #[tokio::test]
    async fn forwarded_path_requires_the_final_hostbound_key() {
        let provider = FakeKeyProvider::new(vec![SshKey {
            name: "test-key".to_string(),
            public_key: TEST_ED25519_PUBKEY.to_string(),
            key_type: "ssh-ed25519".to_string(),
            fingerprint: String::new(),
        }])
        .with_sign_result(SignDataResponse {
            signature: vec![42; 64],
            algorithm: "ssh-ed25519".to_string(),
        });
        let mut agent = KeyguardAgent::new(provider.clone());
        agent
            .extension(session_bind_extension(24, b"forwarded-session", true))
            .await
            .unwrap();
        agent
            .extension(session_bind_extension(25, b"final-session", false))
            .await
            .unwrap();
        let public_key = ssh_key::PublicKey::from_openssh(TEST_ED25519_PUBKEY).unwrap();

        let plain_result = agent
            .sign(SignRequest {
                pubkey: public_key.key_data().clone(),
                data: bound_userauth_data(b"final-session", public_key.key_data(), None),
                flags: 0,
            })
            .await;
        let wrong_host_result = agent
            .sign(SignRequest {
                pubkey: public_key.key_data().clone(),
                data: bound_userauth_data(
                    b"final-session",
                    public_key.key_data(),
                    Some(&session_bind_host_key_blob(26)),
                ),
                flags: 0,
            })
            .await;
        let correct_host_result = agent
            .sign(SignRequest {
                pubkey: public_key.key_data().clone(),
                data: bound_userauth_data(
                    b"final-session",
                    public_key.key_data(),
                    Some(&session_bind_host_key_blob(25)),
                ),
                flags: 0,
            })
            .await;

        assert!(plain_result.is_err());
        assert!(wrong_host_result.is_err());
        assert!(correct_host_result.is_ok());
    }

    #[tokio::test]
    async fn session_bind_trailing_bytes_are_rejected() {
        let provider = FakeKeyProvider::new(vec![]);
        let mut agent = KeyguardAgent::new(provider);
        let request = session_bind_extension(18, b"session-id", false);
        let mut details = request.details.into_bytes();
        details.push(0xaa);

        let result = agent
            .extension(Extension {
                name: SESSION_BIND_EXTENSION.to_string(),
                details: details.into(),
            })
            .await;

        assert!(matches!(result, Err(AgentError::ExtensionFailure)));
        assert!(agent.verified_session_binding_context().is_none());
    }

    #[tokio::test]
    async fn session_bind_noncanonical_boolean_is_rejected() {
        let provider = FakeKeyProvider::new(vec![]);
        let mut agent = KeyguardAgent::new(provider);
        let request = session_bind_extension(19, b"session-id", false);
        let mut details = request.details.into_bytes();
        *details
            .last_mut()
            .expect("encoded session-bind includes a forwarding flag") = 2;

        let result = agent
            .extension(Extension {
                name: SESSION_BIND_EXTENSION.to_string(),
                details: details.into(),
            })
            .await;

        assert!(matches!(result, Err(AgentError::ExtensionFailure)));
    }

    #[tokio::test]
    async fn session_bind_caps_session_identifier_length() {
        let provider = FakeKeyProvider::new(vec![]);
        let mut agent = KeyguardAgent::new(provider);
        let request = session_bind_extension(20, &[0x55; MAX_SESSION_ID_LEN + 1], false);

        let result = agent.extension(request).await;

        assert!(matches!(result, Err(AgentError::ExtensionFailure)));
    }

    #[tokio::test]
    async fn session_bind_caps_number_of_bindings() {
        let provider = FakeKeyProvider::new(vec![]);
        let mut agent = KeyguardAgent::new(provider);

        for index in 0..MAX_SESSION_BINDINGS {
            let session_id = index.to_be_bytes();
            assert!(agent
                .extension(session_bind_extension(21, &session_id, true))
                .await
                .is_ok());
        }
        let overflow_result = agent
            .extension(session_bind_extension(21, b"overflow", true))
            .await;

        assert!(matches!(overflow_result, Err(AgentError::ExtensionFailure)));
        let context = agent.verified_session_binding_context().unwrap();
        assert_eq!(context.binding_count(), MAX_SESSION_BINDINGS);
        assert!(context.final_is_forwarding());
    }

    #[tokio::test]
    async fn extension_query_does_not_require_key_provider_calls() {
        let provider = FakeKeyProvider::new(vec![]);
        let mut agent = KeyguardAgent::new(provider.clone());

        let request = Extension {
            name: "query".to_string(),
            details: Vec::new().into(),
        };

        let result = agent.extension(request).await;
        assert!(result.is_ok(), "Query extension should succeed");
        assert!(
            provider.last_list_caller().is_none(),
            "Query extension should not call list_keys"
        );
        assert!(
            provider.last_sign_caller().is_none(),
            "Query extension should not call sign_data"
        );
    }

    #[test]
    fn parse_openssh_pubkey_with_whitespace_only() {
        let result = parse_openssh_pubkey("   ");
        assert!(result.is_err(), "Should fail on whitespace-only input");
    }

    #[test]
    fn parse_openssh_pubkey_partial_key_type() {
        let result = parse_openssh_pubkey("ssh-ed25519");
        assert!(result.is_err(), "Should fail on key type without key data");
    }
}
