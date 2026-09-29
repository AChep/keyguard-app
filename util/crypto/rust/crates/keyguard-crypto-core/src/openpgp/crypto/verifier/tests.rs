use pgp::{
    composed::{
        Deserializable, KeyType, SecretKeyParamsBuilder, SignedSecretKey, SubkeyParamsBuilder,
    },
    crypto::ecc_curve::ECCCurve,
    packet::{KeyFlags, Signature, SignatureConfig, SignatureType, Subpacket, SubpacketData},
    types::{Password, SigningKey, Tag},
};
use rand::{SeedableRng, rngs::StdRng};

use super::*;

pub(crate) fn secp256k1_fixture() -> SignedSecretKey {
    SignedSecretKey::from_reader_single(io::Cursor::new(include_bytes!(
        "../../../../tests/fixtures/openpgp/secp256k1-high-s-secret.asc"
    )))
    .expect("parse public test-only high-S secret key")
    .0
}

pub(crate) fn high_s_signature(signature: &Signature) -> Signature {
    let high = high_s_bytes(signature.signature().expect("signature material"));
    Signature::from_config(
        signature.config().expect("signature config").clone(),
        signature.signed_hash_value().expect("signed hash prefix"),
        high,
    )
    .expect("replace only the signature's S representative")
}

/// Binds a new signing subkey of `subkey_type` to `primary`, created at
/// `created_at`. Every signature made by a secp256k1 key uses its high-S form.
pub(crate) fn with_high_s_signing_subkey(
    mut primary: SignedSecretKey,
    subkey_type: KeyType,
    created_at: u32,
) -> SignedSecretKey {
    let binding_config = |typ, signer: &dyn KeyDetails| {
        let mut config = SignatureConfig::v4(typ, signer.algorithm(), HashAlgorithm::Sha256);
        config.hashed_subpackets = vec![
            Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
                created_at + 1,
            )))
            .unwrap(),
            Subpacket::regular(SubpacketData::IssuerFingerprint(signer.fingerprint())).unwrap(),
        ];
        config
    };
    let high_s_if_secp256k1 = |signature: Signature, signer: &dyn KeyDetails| {
        if matches!(
            signer.public_params(),
            PublicParams::ECDSA(EcdsaPublicParams::Secp256k1 { .. })
        ) {
            high_s_signature(&signature)
        } else {
            signature
        }
    };

    let mut generated = SecretKeyParamsBuilder::default()
        .version(KeyVersion::V4)
        .key_type(KeyType::Ed25519Legacy)
        .can_certify(true)
        .created_at(Timestamp::from_secs(created_at))
        .primary_user_id("Subkey source <subkey@example.test>".to_owned())
        .subkey(
            SubkeyParamsBuilder::default()
                .version(KeyVersion::V4)
                .key_type(subkey_type)
                .can_sign(true)
                .created_at(Timestamp::from_secs(created_at))
                .build()
                .unwrap(),
        )
        .build()
        .unwrap()
        .generate(StdRng::seed_from_u64(0x5345_4350_4249_4e44))
        .unwrap();
    let mut subkey = generated.secret_subkeys.remove(0);
    let back = binding_config(SignatureType::KeyBinding, &subkey.key)
        .sign_primary_key_binding(
            &subkey.key,
            subkey.key.public_key(),
            &Password::empty(),
            primary.primary_key.public_key(),
        )
        .unwrap();
    let mut config = binding_config(SignatureType::SubkeyBinding, &primary.primary_key);
    let mut flags = KeyFlags::default();
    flags.set_sign(true);
    config.hashed_subpackets.extend([
        Subpacket::regular(SubpacketData::KeyFlags(flags)).unwrap(),
        Subpacket::regular(SubpacketData::EmbeddedSignature(Box::new(
            high_s_if_secp256k1(back, &subkey.key),
        )))
        .unwrap(),
    ]);
    let binding = config
        .sign_subkey_binding(
            &primary.primary_key,
            primary.primary_key.public_key(),
            &Password::empty(),
            subkey.key.public_key(),
        )
        .unwrap();
    subkey.signatures = vec![high_s_if_secp256k1(binding, &primary.primary_key)];
    primary.secret_subkeys = vec![subkey];
    primary
}

fn high_s_bytes(signature: &SignatureBytes) -> SignatureBytes {
    let SignatureBytes::Mpis(mpis) = signature else {
        panic!("expected ECDSA MPIs");
    };
    assert_eq!(mpis.len(), 2);
    let mut bytes = [0_u8; 64];
    for (field, mpi) in bytes.as_chunks_mut::<32>().0.iter_mut().zip(mpis) {
        let value: &[u8] = mpi.as_ref();
        let offset = field.len() - value.len();
        field[offset..].copy_from_slice(value);
    }
    let signature = k256::ecdsa::Signature::from_slice(&bytes).expect("valid secp256k1 scalars");
    let low = signature.normalize_s().unwrap_or(signature);
    let (r, s) = low.split_scalars();
    let high = k256::ecdsa::Signature::from_scalars(r.to_bytes(), (-s).to_bytes())
        .expect("equivalent high-S signature");
    assert!(high.normalize_s().is_some());
    let (r, s) = high.split_bytes();
    SignatureBytes::Mpis(vec![Mpi::from_slice(&r), Mpi::from_slice(&s)])
}

#[test]
fn secp256k1_high_s_fixture_verifies_without_changing_packet_bytes() {
    let secret = secp256k1_fixture();
    let key = secret.primary_key.public_key();
    let user = &secret.details.users[0];
    let signature = &user.signatures[0];
    let before = signature.to_bytes().expect("serialize signature");
    assert!(matches!(
        normalize_secp256k1(signature.signature().expect("signature material")),
        Ok(Cow::Owned(_))
    ));
    signature
        .verify_certification(&OpenPgpVerifier(key), Tag::UserId, &user.id)
        .expect("high-S self-certification verifies");
    assert_eq!(
        signature.to_bytes().expect("serialize original signature"),
        before
    );
    assert_eq!(
        OpenPgpVerifier(key).to_bytes().unwrap(),
        key.to_bytes().unwrap()
    );
}

#[test]
fn secp256k1_both_s_forms_verify_and_wrong_digest_or_key_fails() {
    let secret = secp256k1_fixture();
    let digest = [0x42_u8; 32];
    let low = secret
        .primary_key
        .sign(&Password::empty(), HashAlgorithm::Sha256, &digest)
        .expect("sign fixed digest");
    let high = high_s_bytes(&low);
    let other = generated_secret(KeyType::ECDSA(ECCCurve::Secp256k1));
    for signature in [&low, &high] {
        OpenPgpVerifier(secret.primary_key.public_key())
            .verify(HashAlgorithm::Sha256, &digest, signature)
            .expect("equivalent S representative verifies");
        assert!(
            OpenPgpVerifier(secret.primary_key.public_key())
                .verify(HashAlgorithm::Sha256, &[0x43; 32], signature)
                .is_err()
        );
        assert!(
            OpenPgpVerifier(other.primary_key.public_key())
                .verify(HashAlgorithm::Sha256, &digest, signature)
                .is_err()
        );
    }
}

#[test]
fn secp256k1_normalization_accepts_short_mpis_and_rejects_invalid_scalars() {
    let short = SignatureBytes::Mpis(vec![Mpi::from_slice(&[1]), Mpi::from_slice(&[2])]);
    assert!(matches!(
        normalize_secp256k1(&short).unwrap(),
        Cow::Borrowed(_)
    ));
    assert_eq!(
        normalize_secp256k1(&high_s_bytes(&short)).unwrap().as_ref(),
        &short
    );

    let order = [
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xfe, 0xba, 0xae, 0xdc, 0xe6, 0xaf, 0x48, 0xa0, 0x3b, 0xbf, 0xd2, 0x5e, 0x8c, 0xd0, 0x36,
        0x41, 0x41,
    ];
    for invalid in [vec![], vec![0], order.to_vec(), vec![0xff; 32], vec![1; 33]] {
        for index in 0..2 {
            let mut mpis = vec![Mpi::from_slice(&[1]), Mpi::from_slice(&[2])];
            mpis[index] = Mpi::from_slice(&invalid);
            assert!(normalize_secp256k1(&SignatureBytes::Mpis(mpis)).is_err());
        }
    }
    for count in [0, 1, 3] {
        let signature = SignatureBytes::Mpis(vec![Mpi::from_slice(&[1]); count]);
        assert!(normalize_secp256k1(&signature).is_err());
    }
    assert!(normalize_secp256k1(&SignatureBytes::Native(vec![1; 64].into())).is_err());
}

fn generated_secret(key_type: KeyType) -> SignedSecretKey {
    SecretKeyParamsBuilder::default()
        .version(KeyVersion::V4)
        .key_type(key_type)
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs(1_700_000_000))
        .primary_user_id("Verifier TEST ONLY <verifier@example.test>".to_owned())
        .build()
        .unwrap()
        .generate(StdRng::seed_from_u64(0x5645_5249_4649_4552))
        .unwrap()
}

#[test]
fn other_verification_algorithms_keep_their_existing_behavior() {
    for key_type in [
        KeyType::ECDSA(ECCCurve::P256),
        KeyType::ECDSA(ECCCurve::P384),
        KeyType::ECDSA(ECCCurve::P521),
        KeyType::Ed25519Legacy,
    ] {
        let key = generated_secret(key_type);
        let signature = key
            .primary_key
            .sign(&Password::empty(), HashAlgorithm::Sha512, &[0x42; 64])
            .unwrap();
        OpenPgpVerifier(key.primary_key.public_key())
            .verify(HashAlgorithm::Sha512, &[0x42; 64], &signature)
            .unwrap();
        assert!(
            OpenPgpVerifier(key.primary_key.public_key())
                .verify(HashAlgorithm::Sha512, &[0x43; 64], &signature)
                .is_err()
        );
    }
}
