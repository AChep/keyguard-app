use super::*;
use crate::openpgp::message::VerificationStatus;
use p384::ecdsa::signature::hazmat::PrehashSigner;
use pgp::{packet::Signature, types::SignatureBytes};
use rand::{SeedableRng, rngs::StdRng};

const CONTENT: &[u8] = b"inline signer compatibility";

fn p384_certificate() -> SignedSecretKey {
    let mut secret = SecretKeyParamsBuilder::default()
        .version(KeyVersion::V4)
        .key_type(KeyType::ECDSA(ECCCurve::P384))
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs(TEST_TIME as u32))
        .primary_user_id("Compatibility TEST ONLY <compatibility@example.test>".to_owned())
        .subkey(
            SubkeyParamsBuilder::default()
                .version(KeyVersion::V4)
                .key_type(KeyType::ECDSA(ECCCurve::P384))
                .can_sign(true)
                .created_at(Timestamp::from_secs(TEST_TIME as u32))
                .build()
                .unwrap(),
        )
        .build()
        .unwrap()
        .generate(StdRng::seed_from_u64(0x494e_4c49_4e45_4543))
        .unwrap();
    // The builder timestamps certifications with the wall clock. Reissue the
    // identity, binding and back-signature at the fixture's fixed time.
    let primary = &secret.primary_key;
    let subkey = &mut secret.secret_subkeys[0];
    let mut flags = KeyFlags::default();
    flags.set_sign(true);
    flags.set_certify(true);
    let mut identity = v4_config(SecretPacketRef::Primary(primary), HashAlgorithm::Sha384);
    identity.typ = SignatureType::CertPositive;
    identity
        .hashed_subpackets
        .push(Subpacket::regular(SubpacketData::KeyFlags(flags)).unwrap());
    let user = &mut secret.details.users[0];
    user.signatures = vec![
        identity
            .sign_certification(
                primary,
                primary.public_key(),
                &Password::empty(),
                Tag::UserId,
                &user.id,
            )
            .unwrap(),
    ];
    let mut back = v4_config(SecretPacketRef::Subkey(&subkey.key), HashAlgorithm::Sha384);
    back.typ = SignatureType::KeyBinding;
    let back = back
        .sign_primary_key_binding(
            &subkey.key,
            subkey.key.public_key(),
            &Password::empty(),
            primary.public_key(),
        )
        .unwrap();
    let mut binding = v4_config(SecretPacketRef::Primary(primary), HashAlgorithm::Sha384);
    binding.typ = SignatureType::SubkeyBinding;
    let mut flags = KeyFlags::default();
    flags.set_sign(true);
    binding.hashed_subpackets.extend([
        Subpacket::regular(SubpacketData::KeyFlags(flags)).unwrap(),
        Subpacket::regular(SubpacketData::EmbeddedSignature(Box::new(back))).unwrap(),
    ]);
    subkey.signatures = vec![
        binding
            .sign_subkey_binding(
                primary,
                primary.public_key(),
                &Password::empty(),
                subkey.key.public_key(),
            )
            .unwrap(),
    ];
    secret
}

fn p384_signature(signer: SecretPacketRef<'_>, config: SignatureConfig) -> Signature {
    // rPGP's signing API enforces the digest floor. Sign the actual OpenPGP
    // prehash directly so a rejection cannot be explained by invalid MPIs or
    // a mismatched hash prefix instead of the missing compatibility policy.
    let mut hasher = config.hash_alg.new_hasher().unwrap();
    hasher.update(CONTENT);
    let len = config.hash_signature_data(&mut hasher).unwrap();
    hasher.update(&config.trailer(len).unwrap());
    let digest = hasher.finalize();
    let signature = signer
        .unlock(&Password::empty(), |_, params| {
            let PlainSecretParams::ECDSA(params) = params else {
                panic!("expected ECDSA fixture");
            };
            let secret = Zeroizing::new(params.to_bytes());
            let key = p384::ecdsa::SigningKey::from_slice(&secret)?;
            let signature: p384::ecdsa::Signature = key.sign_prehash(&digest)?;
            let (r, s) = signature.split_bytes();
            Ok(SignatureBytes::Mpis(vec![
                Mpi::from_slice(&r),
                Mpi::from_slice(&s),
            ]))
        })
        .unwrap()
        .unwrap();
    Signature::from_config(config, [digest[0], digest[1]], signature).unwrap()
}

fn v4_config(signer: SecretPacketRef<'_>, hash: HashAlgorithm) -> SignatureConfig {
    let mut config = SignatureConfig::v4(SignatureType::Binary, signer.algorithm(), hash);
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            (TEST_TIME + 1) as u32,
        )))
        .unwrap(),
        Subpacket::regular(SubpacketData::IssuerFingerprint(signer.fingerprint())).unwrap(),
    ];
    config
}

fn inline_message(signature: &Signature, signer: SecretPacketRef<'_>, one_pass: bool) -> Vec<u8> {
    let mut message = Vec::new();
    if one_pass {
        OnePassSignature::v3(
            SignatureType::Binary,
            signature.config().unwrap().hash_alg,
            signer.algorithm(),
            signer.legacy_key_id(),
        )
        .to_writer_with_header(&mut message)
        .unwrap();
    } else {
        signature.to_writer_with_header(&mut message).unwrap();
    }
    write_literal_packet(
        &mut message,
        CONTENT,
        b"compatibility.txt",
        Timestamp::from_secs((TEST_TIME + 1) as u32),
    )
    .unwrap();
    if one_pass {
        signature.to_writer_with_header(&mut message).unwrap();
    }
    message
}

fn assert_mathematically_valid(message: &[u8], signer: SecretPacketRef<'_>) {
    let mut parsed = Message::from_bytes(message).unwrap();
    let mut content = Vec::new();
    parsed.read_to_end(&mut content).unwrap();
    assert_eq!(content, CONTENT);
    let public = match signer {
        SecretPacketRef::Primary(key) => PublicComponent::Primary(key.public_key().clone()),
        SecretPacketRef::Subkey(key) => PublicComponent::Subkey(key.public_key().clone()),
    };
    parsed.verify_nested_explicit(0, &public).unwrap();
}

fn assert_decryption_policy(
    message: &[u8],
    private_keys: Vec<Vec<u8>>,
    public_key: &[u8],
    valid: bool,
) {
    let encrypted = !private_keys.is_empty();
    let one_shot = decrypt_request(OpenPgpDecryptRequest {
        content: message.to_vec(),
        private_keys: private_keys.clone(),
        verification_public_keys: vec![public_key.to_vec()],
        reference_time_epoch_seconds: Some(TEST_TIME + 2),
        allow_signed_only: Some(true),
    });
    let mut session = open_decryption_session(OpenPgpDecryptStreamOpenRequest {
        private_keys,
        verification_public_keys: vec![public_key.to_vec()],
        reference_time_epoch_seconds: Some(TEST_TIME + 2),
        allow_signed_only: Some(true),
        staging_directory: None,
    })
    .unwrap();
    let mut content = Vec::new();
    for chunk in message.chunks(7) {
        content.extend(session.update(chunk).unwrap());
    }
    let streamed = session.finish();
    if !encrypted && !valid {
        assert_eq!(one_shot, Err(OpenPgpWriteError::InvalidArgument));
        assert!(matches!(streamed, Err(OpenPgpWriteError::InvalidArgument)));
        return;
    }
    let one_shot = OpenPgpDecryptResult::decode(one_shot.unwrap().as_slice()).unwrap();
    let streamed = streamed.unwrap();
    content.extend_from_slice(&streamed.data);
    assert_eq!(one_shot.data, CONTENT);
    assert_eq!(content, CONTENT);
    assert_eq!(one_shot.encrypted, encrypted);
    assert_eq!(streamed.encrypted, encrypted);
    assert_eq!(
        one_shot.verification.as_ref().unwrap().status,
        if valid {
            OpenPgpVerificationStatus::Valid
        } else {
            OpenPgpVerificationStatus::Invalid
        } as i32,
    );
    assert_eq!(
        streamed.verification.unwrap().status,
        if valid {
            VerificationStatus::Valid
        } else {
            VerificationStatus::Invalid
        },
    );
}

#[test]
fn inline_verification_enforces_ecdsa_digest_floor_across_decryption_paths() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let secret = p384_certificate();
    let public_key = secret.to_public_key().to_bytes().unwrap();
    let recipient = generated_modern_material();
    let (recipient_public, _) =
        SignedPublicKey::from_reader_single(recipient.public_key_armored.as_slice()).unwrap();
    let recipient_component = recipient_public
        .public_subkeys
        .into_iter()
        .find(|subkey| subkey.key.algorithm().can_encrypt())
        .map(|subkey| PublicComponent::Subkey(subkey.key))
        .unwrap();

    for signer in [
        SecretPacketRef::Primary(&secret.primary_key),
        SecretPacketRef::Subkey(&secret.secret_subkeys[0].key),
    ] {
        for (hash, valid) in [
            (HashAlgorithm::Sha256, false),
            (HashAlgorithm::Sha384, true),
        ] {
            let signature = p384_signature(signer, v4_config(signer, hash));
            let detached = verify_detached_with_keys(
                CONTENT,
                &DetachedSignature::new(signature.clone())
                    .to_bytes()
                    .unwrap(),
                vec![public_key.clone()],
            );
            assert_eq!(
                detached.status,
                if valid {
                    OpenPgpVerificationStatus::Valid
                } else {
                    OpenPgpVerificationStatus::Invalid
                } as i32,
            );
            for one_pass in [false, true] {
                let signed = inline_message(&signature, signer, one_pass);
                assert_mathematically_valid(&signed, signer);
                assert_decryption_policy(&signed, Vec::new(), &public_key, valid);
                let encrypted = encrypt_composed_message(
                    &signed,
                    std::slice::from_ref(&recipient_component),
                    ProtectionMode::SeipdV1Mdc,
                    SymmetricKeyAlgorithm::AES256,
                )
                .unwrap();
                assert_decryption_policy(
                    &encrypted,
                    vec![recipient.private_key_armored.clone()],
                    &public_key,
                    valid,
                );
            }
        }
    }
}

#[test]
fn inline_verification_rejects_v3_ecdsa_with_a_sufficient_digest() {
    let _stream_guard = STREAM_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let secret = p384_certificate();
    let signer = SecretPacketRef::Primary(&secret.primary_key);
    let config = SignatureConfig::v3(
        SignatureType::Binary,
        signer.algorithm(),
        HashAlgorithm::Sha384,
        Timestamp::from_secs((TEST_TIME + 1) as u32),
        signer.legacy_key_id(),
    );
    let signature = p384_signature(signer, config);
    let signed = inline_message(&signature, signer, false);
    assert_mathematically_valid(&signed, signer);
    assert_decryption_policy(
        &signed,
        Vec::new(),
        &secret.to_public_key().to_bytes().unwrap(),
        false,
    );
}

#[test]
fn preverified_signatures_enforce_compatibility_before_the_callback() {
    let secret = p384_certificate();
    let signer = SecretPacketRef::Primary(&secret.primary_key);
    let weak = p384_signature(signer, v4_config(signer, HashAlgorithm::Sha256));
    let mut issuerless_config = v4_config(signer, HashAlgorithm::Sha384);
    issuerless_config
        .hashed_subpackets
        .retain(|packet| !matches!(packet.data, SubpacketData::IssuerFingerprint(_)));
    let issuerless = p384_signature(signer, issuerless_config);
    let mut visited = Vec::new();
    let result = evaluate_preverified_signatures_with_recipients(
        &[weak, issuerless],
        &[secret.to_public_key()],
        DataSignatureVerificationTime::from_reference_time(Some(TEST_TIME + 2)),
        &[None, None],
        |index, _| {
            visited.push(index);
            true
        },
    )
    .unwrap();
    assert_eq!(visited, [1]);
    assert_eq!(result.status, VerificationStatus::Valid);
    assert_eq!(result.signatures[0].status, VerificationStatus::Invalid);
    assert_eq!(result.signatures[1].status, VerificationStatus::Valid);
}
