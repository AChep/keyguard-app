//! OpenSSH session-binding parsing, verification, and connection state.

use anyhow::Result;
use ssh_agent_lib::proto::extension::SessionBind;
use ssh_agent_lib::proto::SignRequest;
use ssh_encoding::Decode;
use ssh_key::public::KeyData;
use ssh_key::sha2::{Digest, Sha256};
use ssh_key::{Algorithm, Certificate, Signature};
use thiserror::Error;

pub(super) const SESSION_BIND_EXTENSION: &str = "session-bind@openssh.com";
pub(super) const MAX_SESSION_BINDINGS: usize = 16;
pub(super) const MAX_SESSION_ID_LEN: usize = 128;
const MAX_HOST_KEY_BLOB_LEN: usize = 64 * 1024;
const MAX_SIGNATURE_BLOB_LEN: usize = 64 * 1024;
const MAX_USERAUTH_FIELD_LEN: usize = 128;
pub(super) const SSH2_MSG_USERAUTH_REQUEST: u8 = 50;
pub(super) const SSH_CONNECTION_SERVICE: &[u8] = b"ssh-connection";
pub(super) const PUBLICKEY_METHOD: &[u8] = b"publickey";
pub(super) const HOSTBOUND_PUBLICKEY_METHOD: &[u8] = b"publickey-hostbound-v00@openssh.com";
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
pub(super) struct VerifiedSessionBinding {
    host_key_blob: Box<[u8]>,
    session_id: Box<[u8]>,
    is_forwarding: bool,
}

#[derive(Clone, Debug)]
pub(super) enum SessionBindingState {
    Accepting(Vec<VerifiedSessionBinding>),
    Poisoned,
}

impl Default for SessionBindingState {
    fn default() -> Self {
        Self::Accepting(Vec::new())
    }
}

impl SessionBindingState {
    pub(super) fn is_poisoned(&self) -> bool {
        matches!(self, Self::Poisoned)
    }

    pub(super) fn bindings(&self) -> Option<&[VerifiedSessionBinding]> {
        match self {
            Self::Accepting(bindings) => Some(bindings),
            Self::Poisoned => None,
        }
    }

    pub(super) fn record(
        &mut self,
        binding: VerifiedSessionBinding,
    ) -> Result<(), SessionBindError> {
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

    pub(super) fn context(&self) -> Option<VerifiedSessionBindingContext> {
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

    pub(super) fn validate_sign_request(
        &self,
        request: &SignRequest,
    ) -> Result<(), SessionBindError> {
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
pub(super) enum SessionBindError {
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

pub(super) struct ParsedSessionBinding {
    host_key_blob: Box<[u8]>,
    verification_key: KeyData,
    session_id: Box<[u8]>,
    signature: Signature,
    is_forwarding: bool,
}

impl ParsedSessionBinding {
    pub(super) fn verify_signature(&self) -> Result<(), SessionBindError> {
        SessionBind {
            host_key: self.verification_key.clone(),
            session_id: self.session_id.to_vec(),
            signature: self.signature.clone(),
            is_forwarding: self.is_forwarding,
        }
        .verify_signature()
        .map_err(|_| SessionBindError::SignatureVerification)
    }

    pub(super) fn into_verified(self) -> VerifiedSessionBinding {
        VerifiedSessionBinding {
            host_key_blob: self.host_key_blob,
            session_id: self.session_id,
            is_forwarding: self.is_forwarding,
        }
    }
}

pub(super) fn parse_session_binding(
    details: &[u8],
) -> Result<ParsedSessionBinding, SessionBindError> {
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
