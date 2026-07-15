use super::session_binding::{
    HOSTBOUND_PUBLICKEY_METHOD, MAX_SESSION_BINDINGS, MAX_SESSION_ID_LEN, PUBLICKEY_METHOD,
    SESSION_BIND_EXTENSION, SSH2_MSG_USERAUTH_REQUEST, SSH_CONNECTION_SERVICE,
};
use super::*;
use crate::ipc::messages::{
    CallerAuthorization, CallerAuthorizationEvidenceSource, CallerAuthorizationSubject,
    CallerAuthorizationSubjectKind, SshKey,
};
use keyguard_agent_identity::PRINCIPAL_FINGERPRINT_LEN;
use signature::Signer;
use ssh_agent_lib::proto::extension::SessionBind;
use ssh_key::private::Ed25519Keypair;
use ssh_key::public::KeyData;
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
                evidence_source: CallerAuthorizationEvidenceSource::MacosApplicationAncestry as i32,
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
    let expected_context = AuthorizationContextFingerprint::derive(context.cache_context_digest())
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
