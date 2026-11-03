use pgp::{
    composed::{KeyType, SecretKeyParamsBuilder, SignedSecretKey},
    crypto::hash::HashAlgorithm,
    packet::{SignatureConfig, SignatureType, Subpacket, SubpacketData, UserId},
    types::{Password, SigningKey, Timestamp},
};
use rand::{SeedableRng, rngs::StdRng};

use super::*;

/// RFC 9580 §5.2.4 key framing, written independently of production code.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum KeyFraming {
    /// `0x99` and a two-octet length, hashed by V3 and V4 signatures.
    V4,
    /// `0x9B` and a four-octet length, hashed by V6 signatures.
    V6,
}

impl KeyFraming {
    /// Returns the framing that a signature by a `version` key hashes.
    pub(crate) fn of_signer(version: KeyVersion) -> Self {
        if version == KeyVersion::V6 {
            Self::V6
        } else {
            Self::V4
        }
    }

    pub(crate) fn frame(self, key: &impl Serialize) -> Vec<u8> {
        let mut body = Vec::new();
        key.to_writer(&mut body).expect("serialize framed key");
        let mut framed = match self {
            Self::V4 => {
                let length = u16::try_from(body.len()).expect("two-octet key length");
                [&[0x99][..], &length.to_be_bytes()].concat()
            }
            Self::V6 => {
                let length = u32::try_from(body.len()).expect("four-octet key length");
                [&[0x9b][..], &length.to_be_bytes()].concat()
            }
        };
        framed.extend_from_slice(&body);
        framed
    }

    /// Returns the data a V4 or V6 User ID certification hashes after the salt.
    pub(crate) fn user_id_certification_data(
        self,
        primary: &impl Serialize,
        user_id: &UserId,
    ) -> Vec<u8> {
        let mut body = Vec::new();
        user_id
            .to_writer(&mut body)
            .expect("serialize certified User ID");
        let length = u32::try_from(body.len()).expect("User ID length");
        let mut data = self.frame(primary);
        data.push(0xb4);
        data.extend_from_slice(&length.to_be_bytes());
        data.extend_from_slice(&body);
        data
    }
}

/// Returns a SHA-256 configuration of the signature version `signer` issues.
pub(crate) fn third_party_signature_config(
    signer: &impl KeyDetails,
    typ: SignatureType,
    creation_time: u32,
) -> SignatureConfig {
    let mut config = if signer.version() == KeyVersion::V6 {
        SignatureConfig::v6_with_salt(
            typ,
            signer.algorithm(),
            HashAlgorithm::Sha256,
            vec![0x5a; 16],
        )
    } else {
        SignatureConfig::v4(typ, signer.algorithm(), HashAlgorithm::Sha256)
    };
    config.hashed_subpackets = vec![
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            creation_time,
        )))
        .expect("signature creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(signer.fingerprint()))
            .expect("signature issuer fingerprint"),
    ];
    config
}

/// Returns the digest a signature over exactly `signed_data` signs.
pub(crate) fn signed_data_digest(config: &SignatureConfig, signed_data: &[u8]) -> Vec<u8> {
    let mut hasher = config
        .hash_alg
        .new_hasher()
        .expect("create signature hasher");
    if let SignatureVersionSpecific::V6 { salt } = &config.version_specific {
        hasher.update(salt);
    }
    hasher.update(signed_data);
    let signature_data_len = config
        .hash_signature_data(&mut hasher)
        .expect("hash signature metadata");
    hasher.update(
        &config
            .trailer(signature_data_len)
            .expect("signature trailer"),
    );
    hasher.finalize().to_vec()
}

/// Signs `signed_data` as given, so a test chooses the framing of every key.
pub(crate) fn sign_signed_data(
    config: SignatureConfig,
    signer: &impl SigningKey,
    signed_data: &[u8],
) -> Signature {
    let digest = signed_data_digest(&config, signed_data);
    let signature = signer
        .sign(&Password::empty(), config.hash_alg, &digest)
        .expect("sign signature digest");
    Signature::from_config(config, [digest[0], digest[1]], signature).expect("construct signature")
}

fn generated_secret(version: KeyVersion, seed: u64) -> SignedSecretKey {
    let key_type = if version == KeyVersion::V6 {
        KeyType::Ed25519
    } else {
        KeyType::Ed25519Legacy
    };
    SecretKeyParamsBuilder::default()
        .version(version)
        .key_type(key_type)
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs(1_700_000_000))
        .primary_user_id(format!("Key signature {seed} <key-signature@example.test>"))
        .passphrase(None)
        .build()
        .expect("build test key")
        .generate(StdRng::seed_from_u64(seed))
        .expect("generate test key")
}

#[test]
fn signed_keys_are_framed_by_the_signature_version() {
    let v4 = generated_secret(KeyVersion::V4, 0x4b53_4634);
    let v6 = generated_secret(KeyVersion::V6, 0x4b53_4636);
    for key in [v4.primary_key.public_key(), v6.primary_key.public_key()] {
        for (signature_version, framing) in [
            (SignatureVersion::V2, KeyFraming::V4),
            (SignatureVersion::V3, KeyFraming::V4),
            (SignatureVersion::V4, KeyFraming::V4),
            (SignatureVersion::V6, KeyFraming::V6),
        ] {
            assert_eq!(
                signature_key_hash_data(signature_version, key),
                Some(framing.frame(key)),
                "{signature_version:?} signature over a {:?} key",
                key.version(),
            );
        }
        for signature_version in [SignatureVersion::V5, SignatureVersion::Other(7)] {
            assert_eq!(signature_key_hash_data(signature_version, key), None);
        }
    }
}

#[test]
fn cross_version_digests_verify_only_with_the_signature_version_framing() {
    let v4 = generated_secret(KeyVersion::V4, 0x4b53_5634);
    let v6 = generated_secret(KeyVersion::V6, 0x4b53_5636);
    for (signer, target) in [(&v6, &v4), (&v4, &v6)] {
        let target_key = target.primary_key.public_key();
        let user_id = &target.details.users[0].id;
        let signature_framing = KeyFraming::of_signer(signer.primary_key.version());
        let key_framing = KeyFraming::of_signer(target_key.version());
        assert_ne!(signature_framing, key_framing);
        for (framing, verifies) in [(signature_framing, true), (key_framing, false)] {
            let case = format!(
                "{:?} signer, {:?} target, {framing:?} framing",
                signer.primary_key.version(),
                target_key.version(),
            );
            let revocation = sign_signed_data(
                third_party_signature_config(
                    &signer.primary_key,
                    SignatureType::KeyRevocation,
                    1_700_000_001,
                ),
                &signer.primary_key,
                &framing.frame(target_key),
            );
            let digest =
                direct_key_signature_digest(&revocation, target_key).expect("hash key revocation");
            assert_eq!(
                verify_key_signature_digest(&revocation, signer.primary_key.public_key(), &digest),
                verifies,
                "key revocation: {case}",
            );

            let certification = sign_signed_data(
                third_party_signature_config(
                    &signer.primary_key,
                    SignatureType::CertGeneric,
                    1_700_000_001,
                ),
                &signer.primary_key,
                &framing.user_id_certification_data(target_key, user_id),
            );
            let digest =
                certification_signature_digest(&certification, target_key, Tag::UserId, user_id)
                    .expect("hash certification");
            assert_eq!(
                verify_key_signature_digest(
                    &certification,
                    signer.primary_key.public_key(),
                    &digest,
                ),
                verifies,
                "certification: {case}",
            );
            assert!(
                !verify_key_signature_digest(&certification, target_key, &digest),
                "only the signer verifies: {case}",
            );
        }
    }
}
