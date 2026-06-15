//! SSH agent implementation backed by the Keyguard IPC client.
//!
//! This module implements the `ssh-agent-lib` `Session` trait, handling SSH agent
//! protocol requests by delegating to the Keyguard desktop app via IPC.

use anyhow::Result;
use ssh_agent_lib::agent::Session;
use ssh_agent_lib::error::AgentError;
use ssh_agent_lib::proto::extension::QueryResponse;
use ssh_agent_lib::proto::Extension;
use ssh_agent_lib::proto::Identity;
use ssh_agent_lib::proto::SignRequest;
use ssh_key::{Algorithm, Signature};
use tracing::{debug, info, warn};

use crate::ipc::messages::{CallerIdentity, ListKeysResponse, SignDataResponse};

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
}

impl<K: KeyProvider> KeyguardAgent<K> {
    #[cfg(test)]
    pub fn new(key_provider: K) -> Self {
        Self {
            key_provider,
            caller: None,
        }
    }

    pub fn with_caller(key_provider: K, caller: Option<CallerIdentity>) -> Self {
        Self { key_provider, caller }
    }
}

#[ssh_agent_lib::async_trait]
impl<K: KeyProvider> Session for KeyguardAgent<K> {
    async fn request_identities(&mut self) -> Result<Vec<Identity>, AgentError> {
        debug!("Handling RequestIdentities");

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
        let sign_response = self
            .key_provider
            .sign_data(
                &key.public_key,
                &request.data,
                request.flags,
                self.caller.clone(),
            )
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
            "query" => Ok(Some(Extension::new_message(QueryResponse {
                extensions: vec!["query".to_string()],
            })?)),
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
impl<K: KeyProvider> ssh_agent_lib::agent::Agent<tokio::net::UnixListener> for KeyguardAgentFactory<K> {
    fn new_session(&mut self, socket: &tokio::net::UnixStream) -> impl Session {
        let caller = crate::caller_identity::caller_from_unix_stream(socket);
        KeyguardAgent::with_caller(self.key_provider.clone(), caller)
    }
}

#[cfg(windows)]
impl<K: KeyProvider> ssh_agent_lib::agent::Agent<ssh_agent_lib::agent::NamedPipeListener>
    for KeyguardAgentFactory<K>
{
    fn new_session(&mut self, _socket: &tokio::net::windows::named_pipe::NamedPipeServer) -> impl Session {
        KeyguardAgent::with_caller(self.key_provider.clone(), None)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ipc::messages::SshKey;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::Mutex;

    // A well-known Ed25519 test public key (generated for testing).
    const TEST_ED25519_PUBKEY: &str =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHLbRWVjaj0MMLgFjoxGc8TFfDfb8rVIeONrdiZpigKW test@keyguard";

    // A second key that is intentionally invalid.
    const TEST_INVALID_PUBKEY: &str = "not-a-valid-key";

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
            self.last_list_caller
                .lock()
                .ok()
                .and_then(|v| v.clone())
        }

        fn last_sign_caller(&self) -> Option<CallerIdentity> {
            self.last_sign_caller
                .lock()
                .ok()
                .and_then(|v| v.clone())
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
        assert!(response.is_some(), "Query extension should return a response");
        let response = response.unwrap();

        let parsed = response
            .parse_message::<QueryResponse>()
            .expect("Query response should parse");
        assert!(parsed.is_some(), "Response should be a query extension payload");

        let payload = parsed.unwrap();
        assert_eq!(payload.extensions, vec!["query".to_string()]);
    }

    #[tokio::test]
    async fn extension_unknown_name_returns_extension_failure() {
        let provider = FakeKeyProvider::new(vec![]);
        let mut agent = KeyguardAgent::new(provider);

        let request = Extension {
            name: "session-bind@openssh.com".to_string(),
            details: Vec::new().into(),
        };

        let result = agent.extension(request).await;
        assert!(
            matches!(result, Err(AgentError::ExtensionFailure)),
            "Unknown extension should return extension failure"
        );
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
