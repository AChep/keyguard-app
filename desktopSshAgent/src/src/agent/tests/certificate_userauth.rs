use super::*;
use ssh_encoding::{Decode, Encode};
use ssh_key::{certificate, private::KeypairData, Certificate, PrivateKey, PublicKey};

const SESSION_ID: &[u8] = b"certificate-authentication";
const HOST_SEED: u8 = 45;
const ED25519_CERT: &str = "ssh-ed25519-cert-v01@openssh.com";
const CASES: [(&str, &str, u32); 4] = [
    (ED25519_CERT, "ssh-ed25519", 0),
    ("ssh-rsa-cert-v01@openssh.com", "ssh-rsa", 0),
    ("rsa-sha2-256-cert-v01@openssh.com", "rsa-sha2-256", 2),
    ("rsa-sha2-512-cert-v01@openssh.com", "rsa-sha2-512", 4),
];

const FOREVER_ED25519_CERT: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../../thirdParty/rust/ssh-key-0.6.7-keyguard/tests/examples/ed25519-user-forever-cert.pub"
));
const FOREVER_RSA_CERT: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../../thirdParty/rust/ssh-key-0.6.7-keyguard/tests/examples/rsa-user-forever-cert.pub"
));

fn certificate_identity(name: &str, public_key: String) -> SshKey {
    SshKey {
        name: name.to_owned(),
        public_key,
        key_type: ED25519_CERT.to_owned(),
        fingerprint: String::new(),
    }
}

#[tokio::test]
async fn certificate_identities_are_listed_as_certificates() {
    let cert = Certificate::from_openssh(FOREVER_ED25519_CERT).unwrap();
    let public_key = PublicKey::new(cert.public_key().clone(), "");
    let provider = FakeKeyProvider::new(vec![
        certificate_identity("certificate", FOREVER_ED25519_CERT.trim().to_owned()),
        SshKey {
            name: "key".to_owned(),
            public_key: public_key.to_openssh().unwrap(),
            key_type: "ssh-ed25519".to_owned(),
            fingerprint: String::new(),
        },
    ]);
    let mut agent = KeyguardAgent::new(provider);

    let identities = agent.request_identities().await.unwrap();
    assert_eq!(identities.len(), 2);
    let PublicCredential::Cert(listed) = &identities[0].credential else {
        panic!("expected a certificate identity: {:?}", identities[0]);
    };
    assert_eq!(listed.to_bytes().unwrap(), cert.to_bytes().unwrap());
    assert_eq!(identities[0].comment, "certificate");
    assert_eq!(
        identities[1].credential,
        PublicCredential::Key(public_key.key_data().clone())
    );
}

#[tokio::test]
async fn certificate_credential_signs_for_the_listed_certificate() {
    let cert = Certificate::from_openssh(FOREVER_ED25519_CERT).unwrap();
    // The stored line keeps its comment; a sign request never carries one.
    let stored = FOREVER_ED25519_CERT.trim().to_owned();
    assert!(!cert.comment().is_empty());
    let provider = FakeKeyProvider::new(vec![certificate_identity("certificate", stored.clone())])
        .with_sign_result(SignDataResponse {
            signature: vec![42; 64],
            algorithm: "ssh-ed25519".to_owned(),
        });
    let mut agent = KeyguardAgent::new(provider.clone());
    let request_cert = Certificate::from_bytes(&cert.to_bytes().unwrap()).unwrap();
    let result = agent
        .sign(SignRequest {
            credential: PublicCredential::Cert(Box::new(request_cert)),
            data: vec![1, 2, 3],
            flags: 0,
        })
        .await;

    // Like OpenSSH's agent, the signature names the key's algorithm.
    assert_eq!(result.unwrap().algorithm().as_str(), "ssh-ed25519");
    let signed = provider.last_sign_request().unwrap();
    assert_eq!(signed.public_key, stored);
    assert_eq!(signed.data, vec![1, 2, 3]);
}

#[tokio::test]
async fn certificate_credential_requires_that_certificate() {
    let key = user_key("ssh-ed25519");
    let listed = certificate(key.key_data(), certificate::CertType::User);
    let other = user_certificate(key.key_data(), "other-user");
    let cases = [
        // Only the plain key is listed.
        (key.to_openssh().unwrap(), listed.clone()),
        // Another certificate over the same key.
        (listed.to_openssh().unwrap(), other),
    ];
    for (listed_line, requested) in cases {
        let provider = FakeKeyProvider::new(vec![certificate_identity("listed", listed_line)]);
        let mut agent = KeyguardAgent::new(provider.clone());
        let result = agent
            .sign(SignRequest {
                credential: PublicCredential::Cert(Box::new(requested)),
                data: vec![1, 2, 3],
                flags: 0,
            })
            .await;
        assert!(result.is_err());
        assert!(provider.last_sign_request().is_none());
    }
}

#[tokio::test]
async fn bound_certificate_credential_requires_the_same_certificate_in_userauth() {
    use super::super::session_binding::SessionBindError;

    let key = user_key("ssh-ed25519");
    let cert = certificate(key.key_data(), certificate::CertType::User);
    let other = user_certificate(key.key_data(), "other-user");
    let line = cert.to_openssh().unwrap();
    let provider = FakeKeyProvider::new(vec![certificate_identity("certificate", line.clone())])
        .with_sign_result(SignDataResponse {
            signature: vec![42; 64],
            algorithm: "ssh-ed25519".to_owned(),
        });
    let mut agent = bound_agent(provider.clone(), false).await;
    let request = |data: Vec<u8>| SignRequest {
        credential: PublicCredential::Cert(Box::new(cert.clone())),
        data,
        flags: 0,
    };

    for (label, data) in [
        (
            "another certificate",
            bound_userauth_data_with_key_blob(
                SESSION_ID,
                ED25519_CERT,
                &other.to_bytes().unwrap(),
                None,
            ),
        ),
        (
            "plain key",
            bound_userauth_data(SESSION_ID, key.key_data(), None),
        ),
    ] {
        let error = agent
            .session_bindings
            .validate_sign_request(&request(data.clone()))
            .unwrap_err();
        assert!(
            matches!(error, SessionBindError::SigningKeyMismatch),
            "{label}: {error:?}"
        );
        assert!(agent.sign(request(data)).await.is_err(), "{label}");
        assert!(provider.last_sign_request().is_none(), "{label}");
    }

    let data = bound_userauth_data_with_key_blob(
        SESSION_ID,
        ED25519_CERT,
        &cert.to_bytes().unwrap(),
        None,
    );
    assert!(agent.sign(request(data.clone())).await.is_ok());
    let signed = provider.last_sign_request().unwrap();
    assert_eq!(signed.public_key, line);
    assert_eq!(signed.data, data);
}

#[tokio::test]
async fn certificate_userauth_accepts_openssh_forever_certificates() {
    for (algorithm, signature_algorithm, flags) in CASES {
        let text = if signature_algorithm == "ssh-ed25519" {
            FOREVER_ED25519_CERT
        } else {
            FOREVER_RSA_CERT
        };
        let cert = Certificate::from_openssh(text).unwrap();
        assert_eq!(cert.valid_before(), u64::MAX);
        let public_key = PublicKey::new(cert.public_key().clone(), "")
            .to_openssh()
            .unwrap();
        for forwarded in [false, true] {
            let provider = FakeKeyProvider::new(vec![SshKey {
                name: "certificate backing key".to_owned(),
                public_key: public_key.clone(),
                key_type: cert.algorithm().as_str().to_owned(),
                fingerprint: String::new(),
            }])
            .with_sign_result(SignDataResponse {
                signature: vec![
                    42;
                    if signature_algorithm == "ssh-ed25519" {
                        64
                    } else {
                        384
                    }
                ],
                algorithm: signature_algorithm.to_owned(),
            });
            let mut agent = bound_agent(provider.clone(), forwarded).await;
            let data = bound_userauth_data_with_key_blob(
                SESSION_ID,
                algorithm,
                &cert.to_bytes().unwrap(),
                Some(&session_bind_host_key_blob(HOST_SEED)),
            );
            let result = agent
                .sign(SignRequest {
                    credential: PublicCredential::Key(cert.public_key().clone()),
                    data: data.clone(),
                    flags,
                })
                .await;
            assert!(
                result.is_ok(),
                "{algorithm}, forwarded={forwarded}: {result:?}"
            );
            let signed = provider.last_sign_request().unwrap();
            assert_eq!(signed.data, data);
            assert_eq!(signed.flags, flags);
        }
    }
}

#[tokio::test]
async fn session_bind_accepts_forever_host_certificate_and_requires_exact_destination() {
    let host = Certificate::from_openssh(include_str!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../thirdParty/rust/ssh-key-0.6.7-keyguard/tests/examples/ed25519-host-forever-cert.pub"
    )))
    .unwrap();
    let host_private_key = PrivateKey::from_openssh(include_str!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../thirdParty/rust/ssh-key-0.6.7-keyguard/tests/examples/id_ed25519"
    )))
    .unwrap();
    let host_blob = host.to_bytes().unwrap();
    let mut signature = Vec::new();
    host_private_key
        .try_sign(SESSION_ID)
        .unwrap()
        .encode(&mut signature)
        .unwrap();
    let mut details = Vec::new();
    push_ssh_string(&mut details, &host_blob);
    push_ssh_string(&mut details, SESSION_ID);
    push_ssh_string(&mut details, &signature);
    details.push(0);

    let user = user_key("ssh-ed25519");
    let provider = FakeKeyProvider::new(vec![SshKey {
        name: "test-key".to_owned(),
        public_key: user.to_openssh().unwrap(),
        key_type: "ssh-ed25519".to_owned(),
        fingerprint: String::new(),
    }])
    .with_sign_result(SignDataResponse {
        signature: vec![42; 64],
        algorithm: "ssh-ed25519".to_owned(),
    });
    let mut agent = KeyguardAgent::with_caller(provider.clone(), Some(CallerIdentity::default()));
    agent
        .extension(session_bind_extension(46, b"forwarding-hop", true))
        .await
        .unwrap();
    agent
        .extension(Extension {
            name: SESSION_BIND_EXTENSION.to_owned(),
            details: details.into(),
        })
        .await
        .unwrap();

    // A raw key or another certificate for the same host key must not match.
    for destination in [
        PublicKey::new(host.public_key().clone(), "")
            .to_bytes()
            .unwrap(),
        certificate(host.public_key(), certificate::CertType::Host)
            .to_bytes()
            .unwrap(),
    ] {
        assert!(agent
            .sign(SignRequest {
                credential: PublicCredential::Key(user.key_data().clone()),
                data: bound_userauth_data(SESSION_ID, user.key_data(), Some(&destination)),
                flags: 0,
            })
            .await
            .is_err());
        assert!(provider.last_list_caller().is_none());
        assert!(provider.last_sign_request().is_none());
    }
    let data = bound_userauth_data(SESSION_ID, user.key_data(), Some(&host_blob));
    assert!(agent
        .sign(SignRequest {
            credential: PublicCredential::Key(user.key_data().clone()),
            data: data.clone(),
            flags: 0
        })
        .await
        .is_ok());
    assert_eq!(provider.last_sign_request().unwrap().data, data);
}

fn user_key(signature_algorithm: &str) -> PublicKey {
    let text = if signature_algorithm == "ssh-ed25519" {
        TEST_ED25519_PUBKEY
    } else {
        include_str!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../thirdParty/rust/ssh-key-0.6.7-keyguard/tests/examples/id_rsa_3072.pub"
        ))
    };
    PublicKey::from_openssh(text).unwrap()
}

fn certificate(key: &KeyData, cert_type: certificate::CertType) -> Certificate {
    let ca = PrivateKey::new(
        KeypairData::Ed25519(Ed25519Keypair::from_seed(&[44; 32])),
        "test CA",
    )
    .unwrap();
    let mut builder =
        certificate::Builder::new(vec![42; 32], key.clone(), 0, 4_000_000_000).unwrap();
    builder.cert_type(cert_type).unwrap();
    builder.valid_principal("test-user").unwrap();
    builder.sign(&ca).unwrap()
}

fn user_certificate(key: &KeyData, principal: &str) -> Certificate {
    let ca = PrivateKey::new(
        KeypairData::Ed25519(Ed25519Keypair::from_seed(&[44; 32])),
        "test CA",
    )
    .unwrap();
    let mut builder =
        certificate::Builder::new(vec![43; 32], key.clone(), 0, 4_000_000_000).unwrap();
    builder.cert_type(certificate::CertType::User).unwrap();
    builder.valid_principal(principal).unwrap();
    builder.sign(&ca).unwrap()
}

async fn bound_agent(provider: FakeKeyProvider, forwarded: bool) -> KeyguardAgent<FakeKeyProvider> {
    // A present caller lets the tests distinguish no IPC from IPC with no caller.
    let mut agent = KeyguardAgent::with_caller(provider, Some(CallerIdentity::default()));
    if forwarded {
        agent
            .extension(session_bind_extension(46, b"forwarding-hop", true))
            .await
            .unwrap();
    }
    agent
        .extension(session_bind_extension(HOST_SEED, SESSION_ID, false))
        .await
        .unwrap();
    agent
}

#[tokio::test]
async fn certificate_userauth_preserves_payload_and_signing_flags() {
    let host_key_blob = session_bind_host_key_blob(HOST_SEED);
    for (algorithm, signature_algorithm, flags) in CASES {
        let key = user_key(signature_algorithm);
        let public_key = key.to_openssh().unwrap();
        let cert = certificate(key.key_data(), certificate::CertType::User);
        let cert_blob = cert.to_bytes().unwrap();
        let signature = vec![
            42;
            if signature_algorithm == "ssh-ed25519" {
                64
            } else {
                384
            }
        ];
        for (forwarded, hostbound) in [(false, false), (false, true), (true, true)] {
            let provider = FakeKeyProvider::new(vec![SshKey {
                name: "certificate backing key".to_owned(),
                public_key: public_key.clone(),
                key_type: key.algorithm().as_str().to_owned(),
                fingerprint: String::new(),
            }])
            .with_sign_result(SignDataResponse {
                signature: signature.clone(),
                algorithm: signature_algorithm.to_owned(),
            });
            let mut agent = bound_agent(provider.clone(), forwarded).await;
            let data = bound_userauth_data_with_key_blob(
                SESSION_ID,
                algorithm,
                &cert_blob,
                hostbound.then_some(host_key_blob.as_slice()),
            );
            // OpenSSH selects the plain agent key, while the bytes to sign
            // contain the certificate supplied by CertificateFile.
            let request = SignRequest {
                credential: PublicCredential::Key(key.key_data().clone()),
                data: data.clone(),
                flags,
            };
            let mut wire = Vec::new();
            request.encode(&mut wire).unwrap();
            let request = SignRequest::decode(&mut wire.as_slice()).unwrap();
            let result = agent.sign(request).await;
            assert!(
                result.is_ok(),
                "{algorithm}, forwarded={forwarded}, hostbound={hostbound}: {result:?}"
            );
            let result = result.unwrap();
            assert_eq!(result.algorithm().as_str(), signature_algorithm);
            assert_eq!(result.as_bytes(), signature);
            let signed = provider.last_sign_request().unwrap();
            assert_eq!(signed.public_key, public_key);
            assert_eq!(signed.data, data);
            assert_eq!(signed.flags, flags);
        }
    }
}

#[tokio::test]
async fn certificate_userauth_rejects_invalid_identity_before_ipc() {
    let ed = user_key("ssh-ed25519");
    let rsa = user_key("ssh-rsa");
    let other = KeyData::Ed25519(Ed25519Keypair::from_seed(&[47; 32]).public);
    let ed_cert = certificate(ed.key_data(), certificate::CertType::User)
        .to_bytes()
        .unwrap();
    let mut trailing = ed_cert.clone();
    trailing.push(0);
    let cases = [
        (
            "different key",
            ED25519_CERT,
            certificate(&other, certificate::CertType::User)
                .to_bytes()
                .unwrap(),
        ),
        (
            "host certificate",
            ED25519_CERT,
            certificate(ed.key_data(), certificate::CertType::Host)
                .to_bytes()
                .unwrap(),
        ),
        (
            "RSA certificate labeled Ed25519",
            ED25519_CERT,
            certificate(rsa.key_data(), certificate::CertType::User)
                .to_bytes()
                .unwrap(),
        ),
        (
            "Ed25519 certificate labeled RSA",
            "rsa-sha2-256-cert-v01@openssh.com",
            ed_cert.clone(),
        ),
        (
            "plain key with certificate algorithm",
            ED25519_CERT,
            ed.to_bytes().unwrap(),
        ),
        (
            "certificate with plain algorithm",
            "ssh-ed25519",
            ed_cert.clone(),
        ),
        (
            "unknown certificate algorithm",
            "ssh-unknown-cert-v01@openssh.com",
            ed_cert.clone(),
        ),
        (
            "truncated certificate",
            ED25519_CERT,
            ed_cert[..ed_cert.len() - 1].to_vec(),
        ),
        ("trailing certificate data", ED25519_CERT, trailing),
    ];
    for (label, algorithm, key_blob) in cases {
        let provider = FakeKeyProvider::new(vec![]);
        let mut agent = bound_agent(provider.clone(), false).await;
        let result = agent
            .sign(SignRequest {
                credential: PublicCredential::Key(ed.key_data().clone()),
                data: bound_userauth_data_with_key_blob(SESSION_ID, algorithm, &key_blob, None),
                flags: 0,
            })
            .await;
        assert!(
            matches!(result, Err(AgentError::Failure)),
            "{label}: {result:?}"
        );
        assert!(
            provider.last_list_caller().is_none(),
            "{label}: listed keys over IPC"
        );
        assert!(
            provider.last_sign_request().is_none(),
            "{label}: requested signing over IPC"
        );
    }
}

#[tokio::test]
async fn certificate_userauth_preserves_session_and_destination_checks() {
    let key = user_key("ssh-ed25519");
    let cert = certificate(key.key_data(), certificate::CertType::User)
        .to_bytes()
        .unwrap();
    let host = session_bind_host_key_blob(HOST_SEED);
    let wrong_host = session_bind_host_key_blob(48);
    let mut trailing =
        bound_userauth_data_with_key_blob(SESSION_ID, ED25519_CERT, &cert, Some(&host));
    trailing.push(0);
    let cases = [
        (
            "wrong session",
            false,
            bound_userauth_data_with_key_blob(b"other-session", ED25519_CERT, &cert, Some(&host)),
        ),
        (
            "wrong host",
            false,
            bound_userauth_data_with_key_blob(SESSION_ID, ED25519_CERT, &cert, Some(&wrong_host)),
        ),
        (
            "forwarded request missing host",
            true,
            bound_userauth_data_with_key_blob(SESSION_ID, ED25519_CERT, &cert, None),
        ),
        (
            "forwarded request wrong host",
            true,
            bound_userauth_data_with_key_blob(SESSION_ID, ED25519_CERT, &cert, Some(&wrong_host)),
        ),
        ("trailing request data", false, trailing),
    ];
    for (label, forwarded, data) in cases {
        let provider = FakeKeyProvider::new(vec![]);
        let mut agent = bound_agent(provider.clone(), forwarded).await;
        // Check the specific guard so a certificate-parser failure cannot
        // accidentally make these negative tests pass.
        let error = agent
            .session_bindings
            .validate_sign_request(&SignRequest {
                credential: PublicCredential::Key(key.key_data().clone()),
                data: data.clone(),
                flags: 0,
            })
            .unwrap_err();
        use super::super::session_binding::SessionBindError;
        match label {
            "wrong session" => assert!(matches!(error, SessionBindError::SigningSessionMismatch)),
            "wrong host" | "forwarded request wrong host" => {
                assert!(matches!(error, SessionBindError::HostboundKeyMismatch))
            }
            "forwarded request missing host" => {
                assert!(matches!(error, SessionBindError::HostboundKeyRequired))
            }
            _ => assert!(matches!(
                error,
                SessionBindError::InvalidBoundUserauth("trailing bytes")
            )),
        }
        assert!(agent
            .sign(SignRequest {
                credential: PublicCredential::Key(key.key_data().clone()),
                data,
                flags: 0
            })
            .await
            .is_err());
        assert!(provider.last_list_caller().is_none(), "{label}");
        assert!(provider.last_sign_request().is_none(), "{label}");
    }
}
