use super::*;
use ssh_encoding::Encode;
use ssh_key::{certificate, private::KeypairData, HashAlg, PrivateKey, PublicKey};

// PKCS#1 v1.5 signatures made and independently verified with Python cryptography
// using the vendored id_rsa_3072 test key. The message is the counter encoded as
// a 32-byte big-endian session ID. Both signatures have a leading zero octet.
const SIGNATURES: [(HashAlg, u8, &str); 2] = [
    (
        HashAlg::Sha256,
        32,
        concat!(
            "004bc081829c240dc37dbb3f1630e12c3ca91dca8e0c85af840be1b6c463d1c5e",
            "b9f234450e5e7b118ea400408d9f601b960fe9db83bf4dd4909eb434705a34e3a",
            "9158d7b7f0fe96d612c4ce5a282cd9b64b24034460819659e1e7b0044bbb18742",
            "2b0476d2c1ae3dfefbe75b7d67e4e1db1575cf3090c61d091386e509e1c1dc004",
            "8d2c86306fc6db51d74e5252c9405a64702b2dbb705c67b14af45b1c6bfba85e3",
            "ff6ea105ae9322539bfe7afc4622c01ac87402b12e84368692dae6caaff4afb230",
            "c3c299ad96808f63f8726a64d90d142a5997e66d17d6d5904a9b6b8c6bcf6fd2",
            "898e7893908de7bd5ae6c2597fa53bc9729f085b7494e8f552485f2632b45f0b9",
            "2837921278140dc055112980cffc9fb6859e6c8f4522dfde0fcf640176d6ff9035",
            "b641788c8841f516621360b12f67954740a0a3dbda0f5663ca6c68676ce6613945",
            "514e3490dd50060306f0ce78fd09e1d8170956e39c3cf474064003cb79a29940b2",
            "9a124f1dbb7db4c7c3d280964b2ca01055a347540b92be089c",
        ),
    ),
    (
        HashAlg::Sha512,
        121,
        concat!(
            "00f8fcac4bf314b8af59c2936b9cb7a782a598a4f8a6527d4fe01f6a7cb641da3",
            "bac7039f55b8fd3b3f2caa31330a94f0c0ac9362b71f46c5c45f2b92b0511711",
            "85f5f2bd3d10cb0e8bf6509fc9d47943d27b64b582e9991312137146f46103829",
            "c258d9c514dee4b0c83c966c31c07253e9685c43480afb49657956887757dd086b",
            "5df3fafd1d73607aa7499c2aa783b71bcf39fcb92319045dacd2508442012a5d25",
            "8a56c90051a7d08bb1c7c23454a263c502d7ce1f55b1645cd5288091425a3d9cd",
            "6e84cac1628827e858ab65b6ce6fd86cb702efdbfeff1f85adc6a114a99536c6d0",
            "10b3ae0f67cd4177cc7fb07a0d3a11ab3e2c021180e7cf9f3d7eac0beabb8f83",
            "8f5a5c17759480e86b52c202de39d3c6cd828e57a72c14f55cd79b27b054e46a2",
            "fee812a65b5a29f35bc74ba11233b9f341ef06835dc2912ecb3c60505879a4a73c",
            "22efabcedf9c2a721ac2c2ef421774a2b0777c4e6542a940ea9407a7deecf20b28",
            "432a1ebd4055743a23c5d9a71e87651b5fd0a9879a738e0a46",
        ),
    ),
];

fn host_key_blobs() -> [Vec<u8>; 2] {
    let public_key = PublicKey::from_openssh(include_str!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../thirdParty/rust/ssh-key-0.6.7-keyguard/tests/examples/id_rsa_3072.pub"
    )))
    .unwrap();
    let ca = PrivateKey::new(
        KeypairData::Ed25519(Ed25519Keypair::from_seed(&[42; 32])),
        "test CA",
    )
    .unwrap();
    let mut builder = certificate::Builder::new(
        vec![42; 32],
        public_key.key_data().clone(),
        0,
        4_000_000_000,
    )
    .unwrap();
    builder.cert_type(certificate::CertType::Host).unwrap();
    builder.valid_principal("test-host").unwrap();
    let certificate = builder.sign(&ca).unwrap();
    [
        public_key.to_bytes().unwrap(),
        certificate.to_bytes().unwrap(),
    ]
}

fn rsa_binding(
    host_key_blob: &[u8],
    session_id: &[u8],
    hash: HashAlg,
    raw_signature: &[u8],
) -> Extension {
    let signature = Signature::new(Algorithm::Rsa { hash: Some(hash) }, raw_signature).unwrap();
    let mut signature_blob = Vec::new();
    signature.encode(&mut signature_blob).unwrap();
    let mut details = Vec::new();
    push_ssh_string(&mut details, host_key_blob);
    push_ssh_string(&mut details, session_id);
    push_ssh_string(&mut details, &signature_blob);
    details.push(0);
    Extension {
        name: SESSION_BIND_EXTENSION.to_owned(),
        details: details.into(),
    }
}

#[tokio::test]
async fn session_bind_short_rsa_signatures_allow_signing() {
    let public_key = PublicKey::from_openssh(TEST_ED25519_PUBKEY).unwrap();
    let provider = FakeKeyProvider::new(vec![SshKey {
        name: "test-key".to_owned(),
        public_key: TEST_ED25519_PUBKEY.to_owned(),
        key_type: "ssh-ed25519".to_owned(),
        fingerprint: String::new(),
    }])
    .with_sign_result(SignDataResponse {
        signature: vec![42; 64],
        algorithm: "ssh-ed25519".to_owned(),
    });
    for (hash, counter, signature_hex) in SIGNATURES {
        let mut session_id = [0; 32];
        session_id[31] = counter;
        let signature = hex::decode(signature_hex).unwrap();
        assert_eq!(signature.len(), 384);
        assert_eq!(signature[0], 0);
        assert_ne!(signature[1], 0);
        for host_key_blob in host_key_blobs() {
            let mut full_context = None;
            for raw_signature in [signature.as_slice(), &signature[1..]] {
                let mut agent = KeyguardAgent::new(provider.clone());
                let result = agent
                    .extension(rsa_binding(
                        &host_key_blob,
                        &session_id,
                        hash,
                        raw_signature,
                    ))
                    .await;
                assert!(
                    matches!(result, Ok(None)),
                    "{hash:?}, {} bytes: {result:?}",
                    raw_signature.len()
                );
                let context = agent.verified_session_binding_context().unwrap();
                if let Some(expected) = &full_context {
                    assert_eq!(&context, expected);
                } else {
                    full_context = Some(context);
                }
                // A successful bind must keep authentication usable, including
                // exact host-certificate matching for the host-bound method.
                let result = agent
                    .sign(SignRequest {
                        pubkey: public_key.key_data().clone(),
                        data: bound_userauth_data(
                            &session_id,
                            public_key.key_data(),
                            Some(&host_key_blob),
                        ),
                        flags: 0,
                    })
                    .await;
                assert!(result.is_ok(), "{result:?}");
            }
        }
    }
}

#[tokio::test]
async fn session_bind_invalid_rsa_signatures_still_block_signing() {
    let public_key = PublicKey::from_openssh(TEST_ED25519_PUBKEY).unwrap();
    for (hash, counter, signature_hex) in SIGNATURES {
        let mut session_id = [0; 32];
        session_id[31] = counter;
        let signature = hex::decode(signature_hex).unwrap();
        let mut tampered = signature[1..].to_vec();
        *tampered.last_mut().unwrap() ^= 1;
        let overlong = [vec![0], signature.clone()].concat();
        for invalid in [tampered, signature[2..].to_vec(), overlong, Vec::new()] {
            for host_key_blob in host_key_blobs() {
                let provider = FakeKeyProvider::new(vec![]);
                let mut agent = KeyguardAgent::new(provider.clone());
                let result = agent
                    .extension(rsa_binding(&host_key_blob, &session_id, hash, &invalid))
                    .await;
                assert!(matches!(result, Err(AgentError::ExtensionFailure)));
                assert!(agent.verified_session_binding_context().is_none());
                assert!(agent
                    .sign(SignRequest {
                        pubkey: public_key.key_data().clone(),
                        data: bound_userauth_data(
                            &session_id,
                            public_key.key_data(),
                            Some(&host_key_blob)
                        ),
                        flags: 0,
                    })
                    .await
                    .is_err());
                assert!(
                    provider.last_list_caller().is_none(),
                    "rejected binding must block signing before IPC"
                );
            }
        }
    }
}
