use super::*;
use pgp::{
    armor::{self, BlockType},
    composed::{
        EncryptionCaps, KeyType, SecretKeyParamsBuilder, SignedSecretKey, SubkeyParamsBuilder,
    },
    crypto::hash::HashAlgorithm,
    packet::UserAttribute,
    packet::{KeyFlags, SignatureConfig},
    types::{
        Duration, Fingerprint, Mpi, Password, RevocationKey, RevocationKeyClass, SignatureBytes,
        SigningKey, Timestamp,
    },
};
use prost::bytes::Bytes;
use rand::{SeedableRng, rngs::StdRng};

use crate::openpgp::policy::{OpenPgpPolicyBudget, certificate_components, validate_certificate};

const PUBLIC_KEY: &[u8] = include_bytes!("../../../../tests/fixtures/openpgp/cv25519-public.asc");
const SECRET_KEY: &[u8] = include_bytes!("../../../../tests/fixtures/openpgp/cv25519-secret.asc");
const OTHER_SECRET_KEY: &[u8] = include_bytes!("../../../../tests/fixtures/openpgp/mdc-secret.asc");
const REVOKED_PUBLIC_KEY: &[u8] =
    include_bytes!("../../../../tests/fixtures/openpgp/designated-revoked-public.asc");

struct TestRawPackets<'a>(&'a [u8]);

impl Serialize for TestRawPackets<'_> {
    fn to_writer<W: std::io::Write>(&self, writer: &mut W) -> pgp::errors::Result<()> {
        writer.write_all(self.0)?;
        Ok(())
    }

    fn write_len(&self) -> usize {
        self.0.len()
    }
}

fn armor_public_packets(packets: &[u8]) -> Vec<u8> {
    let mut output = Vec::new();
    armor::write(
        &TestRawPackets(packets),
        BlockType::PublicKey,
        &mut output,
        None,
        true,
    )
    .expect("armor public packets");
    output
}

fn marker_packet(body: &[u8]) -> Vec<u8> {
    let mut marker = Vec::new();
    CanonicalPacket {
        tag: MARKER_TAG,
        body: body.to_vec(),
    }
    .write_to(&mut marker)
    .expect("write Marker packet");
    marker
}

fn old_format_marker_packet(body: &[u8]) -> Vec<u8> {
    let length = u8::try_from(body.len()).expect("test Marker body fits old-format length");
    let mut marker = Vec::with_capacity(2 + body.len());
    marker.extend_from_slice(&[0xa8, length]);
    marker.extend_from_slice(body);
    marker
}

fn public_document_with_marker(marker: &[u8], leading: bool) -> Vec<u8> {
    let fixture = RawPacketStream::parse(PUBLIC_KEY, MAX_MERGE_PACKETS)
        .expect("parse public fixture packet stream");
    let mut document = Vec::with_capacity(fixture.bytes().len() + marker.len());
    if leading {
        document.extend_from_slice(marker);
    }
    for (index, packet) in fixture.packets().iter().enumerate() {
        document.extend_from_slice(fixture.raw(packet));
        if !leading && index == 0 {
            document.extend_from_slice(marker);
        }
    }
    document
}

fn parse_fixture() -> PublicCertificatePacketSet {
    parse_document(PUBLIC_KEY)
        .into_iter()
        .next()
        .expect("fixture contains one certificate")
}

fn parse_document(data: &[u8]) -> Vec<PublicCertificatePacketSet> {
    let stream = RawPacketStream::parse(data, 8 * 1024).expect("parse packet stream");
    parse_public_certificate_packet_sets(&stream)
        .expect("parse certificate packet set")
        .certificates
}

fn damaged_revoked_certificate_keyring(separator: &CanonicalPacket) -> Vec<u8> {
    let damaged = RawPacketStream::parse(REVOKED_PUBLIC_KEY, MAX_MERGE_PACKETS)
        .expect("parse revoked certificate fixture");
    let revocation_index = damaged
        .packets()
        .iter()
        .position(|packet| {
            if packet.tag() != SIGNATURE_TAG {
                return false;
            }
            let packet = CanonicalPacket::from_span(&damaged, packet);
            parse_signature_packet(&packet).is_ok_and(|signature| {
                matches!(
                    signature.typ(),
                    Some(
                        SignatureType::KeyRevocation
                            | SignatureType::SubkeyRevocation
                            | SignatureType::CertRevocation
                    )
                )
            })
        })
        .expect("fixture contains a key revocation");
    let mut keyring = Vec::new();
    for (index, packet) in damaged.packets().iter().enumerate() {
        if index != revocation_index {
            keyring.extend_from_slice(damaged.raw(packet));
        }
    }
    separator
        .write_to(&mut keyring)
        .expect("write inappropriate separator");
    keyring.extend_from_slice(damaged.raw(&damaged.packets()[revocation_index]));
    let independent = RawPacketStream::parse(PUBLIC_KEY, MAX_MERGE_PACKETS)
        .expect("parse independent certificate fixture");
    keyring.extend_from_slice(independent.bytes());
    keyring
}

#[test]
fn certificate_merge_rejects_v5_primary_keys_and_subkeys() {
    let stream =
        RawPacketStream::parse(PUBLIC_KEY, MAX_MERGE_PACKETS).expect("parse fixture packet stream");

    for target_tag in [PUBLIC_KEY_TAG, PUBLIC_SUBKEY_TAG] {
        let mut replaced = false;
        let mut unsupported = Vec::new();
        for span in stream.packets() {
            let mut packet = CanonicalPacket::from_span(&stream, span);
            if !replaced && packet.tag == target_tag {
                packet.body[0] = u8::from(KeyVersion::V5);
                replaced = true;
            }
            packet
                .write_to(&mut unsupported)
                .expect("serialize unsupported certificate fixture");
        }
        assert!(replaced, "fixture contains packet tag {target_tag}");
        assert!(matches!(
            parse_single_certificate_packet_set(&unsupported),
            Err(CertificateMergeError::UnsupportedKeyVersion)
        ));
    }
}

fn merge(certificates: Vec<PublicCertificatePacketSet>) -> Vec<u8> {
    merge_public_certificate_packet_sets(certificates, 64)
        .expect("merge certificates")
        .first()
        .expect("one merged certificate")
        .finalize()
        .expect("finalize merged certificate")
        .bytes
}

fn parse_secret(data: &[u8]) -> SignedSecretKey {
    SignedSecretKey::from_reader_single(Cursor::new(data))
        .expect("parse test secret key")
        .0
}

fn contains_packet_body(data: &[u8], expected: &CanonicalPacket) -> bool {
    let stream =
        RawPacketStream::parse(data, MAX_MERGE_PACKETS).expect("parse serialized test certificate");
    stream.packets().iter().any(|packet| {
        packet.tag() == expected.tag && stream.body(packet).as_slice() == expected.body
    })
}

fn raw_transferable_bytes(certificate: &PublicCertificatePacketSet) -> Vec<u8> {
    raw_transferable_bytes_with_candidates(certificate, &[])
}

fn raw_transferable_bytes_with_candidates(
    certificate: &PublicCertificatePacketSet,
    candidates: &[PublicComponent],
) -> Vec<u8> {
    let canonical = certificate
        .finalize_with_revocation_candidates(candidates)
        .expect("finalize certificate");
    let stream = RawPacketStream::parse(&canonical.retained_bytes, MAX_MERGE_PACKETS)
        .expect("parse retained bytes");
    let mut output = Vec::new();
    for packet in stream
        .packets()
        .iter()
        .filter(|packet| raw_packet_is_exportable(&canonical, &stream, packet))
    {
        output.extend_from_slice(stream.raw(packet));
    }
    output
}

fn assert_packet_export_status(
    certificate: &PublicCertificatePacketSet,
    packet: &CanonicalPacket,
    expected_exported: bool,
    case: &str,
) {
    let canonical = certificate.finalize().expect("finalize certificate");
    assert!(
        contains_packet_body(&canonical.retained_bytes, packet),
        "retained packet: {case}",
    );
    assert_eq!(
        contains_packet_body(&canonical.bytes, packet),
        expected_exported,
        "canonical export: {case}",
    );
    assert_eq!(
        contains_packet_body(&raw_transferable_bytes(certificate), packet),
        expected_exported,
        "raw export: {case}",
    );
}

fn certificate_packet_set(secret: &SignedSecretKey) -> PublicCertificatePacketSet {
    let public = secret
        .to_public_key()
        .to_bytes()
        .expect("serialize test public certificate");
    parse_document(&public)
        .into_iter()
        .next()
        .expect("generated certificate packet set")
}

fn generated_v6_secret(seed: u64) -> SignedSecretKey {
    SecretKeyParamsBuilder::default()
        .version(KeyVersion::V6)
        .key_type(KeyType::Ed25519)
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs(1_782_541_300))
        .primary_user_id(format!(
            "V6 export boundary {seed} <v6-{seed}@example.test>"
        ))
        .passphrase(None)
        .build()
        .expect("build v6 test key")
        .generate(StdRng::seed_from_u64(seed))
        .expect("generate v6 test key")
}

fn generated_uniform_version_secret(version: KeyVersion, seed: u64) -> SignedSecretKey {
    let subkey = SubkeyParamsBuilder::default()
        .version(version)
        .key_type(KeyType::X25519)
        .can_encrypt(EncryptionCaps::All)
        .created_at(Timestamp::from_secs(1_782_541_301))
        .build()
        .expect("build versioned encryption subkey");
    SecretKeyParamsBuilder::default()
        .version(version)
        .key_type(KeyType::Ed25519)
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs(1_782_541_300))
        .primary_user_id(format!(
            "Versioned certificate {seed} <versioned-{seed}@example.test>"
        ))
        .passphrase(None)
        .subkey(subkey)
        .build()
        .expect("build versioned certificate")
        .generate(StdRng::seed_from_u64(seed))
        .expect("generate versioned certificate")
}

fn generated_v4_signing_subkey_secret(seed: u64) -> SignedSecretKey {
    let subkey = SubkeyParamsBuilder::default()
        .version(KeyVersion::V4)
        .key_type(KeyType::Ed25519)
        .can_sign(true)
        .created_at(Timestamp::from_secs(1_782_541_301))
        .build()
        .expect("build v4 signing subkey");
    SecretKeyParamsBuilder::default()
        .version(KeyVersion::V4)
        .key_type(KeyType::Ed25519)
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs(1_782_541_300))
        .primary_user_id(format!(
            "Signing subkey certificate {seed} <signing-subkey-{seed}@example.test>"
        ))
        .passphrase(None)
        .subkey(subkey)
        .build()
        .expect("build signing-subkey certificate")
        .generate(StdRng::seed_from_u64(seed))
        .expect("generate signing-subkey certificate")
}

fn test_primary_key_binding(
    certificate: &SignedSecretKey,
    signing_key: &SignedSecretKey,
) -> Signature {
    test_embedded_binding(certificate, signing_key, SignatureType::KeyBinding)
}

fn test_embedded_binding(
    certificate: &SignedSecretKey,
    signing_key: &SignedSecretKey,
    signature_type: SignatureType,
) -> Signature {
    let bound_subkey = certificate
        .secret_subkeys
        .first()
        .expect("certificate has a signing subkey")
        .key
        .public_key();
    let signer = &signing_key
        .secret_subkeys
        .first()
        .expect("signer has a signing subkey")
        .key;
    let mut config = SignatureConfig::v4(signature_type, signer.algorithm(), HashAlgorithm::Sha256);
    config.hashed_subpackets.extend([
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            1_782_541_400,
        )))
        .expect("backsig creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(bound_subkey.fingerprint()))
            .expect("backsig issuer fingerprint"),
    ]);
    config
        .sign_primary_key_binding(
            signer,
            &bound_subkey,
            &Password::empty(),
            certificate.primary_key.public_key(),
        )
        .expect("sign primary-key binding")
}

fn test_subkey_binding(
    certificate: &SignedSecretKey,
    hashed_flags: Option<KeyFlags>,
    unhashed_flags: Option<KeyFlags>,
    embedded: Option<(Signature, bool)>,
) -> CanonicalPacket {
    let subkey = certificate
        .secret_subkeys
        .first()
        .expect("certificate has a signing subkey")
        .key
        .public_key();
    let mut config = SignatureConfig::v4(
        SignatureType::SubkeyBinding,
        certificate.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets.extend([
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            1_782_541_400,
        )))
        .expect("binding creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            certificate.primary_key.fingerprint(),
        ))
        .expect("binding issuer fingerprint"),
    ]);
    if let Some(flags) = hashed_flags {
        config
            .hashed_subpackets
            .push(Subpacket::regular(SubpacketData::KeyFlags(flags)).expect("hashed key flags"));
    }
    if let Some(flags) = unhashed_flags {
        config
            .unhashed_subpackets
            .push(Subpacket::regular(SubpacketData::KeyFlags(flags)).expect("unhashed key flags"));
    }
    if let Some((embedded, hashed)) = embedded {
        let subpacket = Subpacket::regular(SubpacketData::EmbeddedSignature(Box::new(embedded)))
            .expect("embedded primary-key binding");
        if hashed {
            config.hashed_subpackets.push(subpacket);
        } else {
            config.unhashed_subpackets.push(subpacket);
        }
    }
    CanonicalPacket {
        tag: SIGNATURE_TAG,
        body: config
            .sign_subkey_binding(
                &certificate.primary_key,
                certificate.primary_key.public_key(),
                &Password::empty(),
                &subkey,
            )
            .expect("sign subkey binding")
            .to_bytes()
            .expect("serialize subkey binding"),
    }
}

fn signing_flags() -> KeyFlags {
    let mut flags = KeyFlags::default();
    flags.set_sign(true);
    flags
}

fn public_certificate_with_subkeys_from(
    primary_source: &SignedSecretKey,
    subkey_source: &SignedSecretKey,
) -> Vec<u8> {
    let primary_bytes = primary_source
        .to_public_key()
        .to_bytes()
        .expect("serialize primary-source certificate");
    let subkey_bytes = subkey_source
        .to_public_key()
        .to_bytes()
        .expect("serialize subkey-source certificate");
    let primary_stream = RawPacketStream::parse(&primary_bytes, MAX_MERGE_PACKETS)
        .expect("parse primary-source certificate");
    let subkey_stream = RawPacketStream::parse(&subkey_bytes, MAX_MERGE_PACKETS)
        .expect("parse subkey-source certificate");
    let primary_subkey = primary_stream
        .packets()
        .iter()
        .position(|packet| packet.tag() == PUBLIC_SUBKEY_TAG)
        .expect("primary source has a public subkey");
    let donor_subkey = subkey_stream
        .packets()
        .iter()
        .position(|packet| packet.tag() == PUBLIC_SUBKEY_TAG)
        .expect("subkey source has a public subkey");
    let mut certificate = Vec::new();
    for packet in primary_stream.packets().iter().take(primary_subkey) {
        certificate.extend_from_slice(primary_stream.raw(packet));
    }
    for packet in subkey_stream.packets().iter().skip(donor_subkey) {
        certificate.extend_from_slice(subkey_stream.raw(packet));
    }
    certificate
}

fn generated_v4_two_subkey_secret(seed: u64) -> SignedSecretKey {
    let subkey = |created_at| {
        SubkeyParamsBuilder::default()
            .version(KeyVersion::V4)
            .key_type(KeyType::X25519)
            .can_encrypt(EncryptionCaps::All)
            .created_at(Timestamp::from_secs(created_at))
            .build()
            .expect("build v4 encryption subkey")
    };
    SecretKeyParamsBuilder::default()
        .version(KeyVersion::V4)
        .key_type(KeyType::Ed25519)
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs(1_782_541_300))
        .primary_user_id(format!(
            "Two subkey fixture {seed} <two-subkey-{seed}@example.test>"
        ))
        .passphrase(None)
        .subkey(subkey(1_782_541_301))
        .subkey(subkey(1_782_541_302))
        .build()
        .expect("build two-subkey test key")
        .generate(StdRng::seed_from_u64(seed))
        .expect("generate two-subkey test key")
}

#[test]
fn certificate_parser_requires_subkeys_to_match_the_primary_version() {
    let v4 = generated_uniform_version_secret(KeyVersion::V4, 0x5634_554e_4946_4f52);
    let v6 = generated_uniform_version_secret(KeyVersion::V6, 0x5636_554e_4946_4f52);

    for (primary, subkey, case) in [
        (&v4, &v6, "V4 primary with V6 subkey"),
        (&v6, &v4, "V6 primary with V4 subkey"),
    ] {
        let mixed = public_certificate_with_subkeys_from(primary, subkey);
        assert!(
            matches!(
                parse_single_certificate_packet_set(&mixed),
                Err(CertificateMergeError::Malformed)
            ),
            "reject {case}",
        );
    }

    for (certificate, case) in [(&v4, "uniform V4"), (&v6, "uniform V6")] {
        let public = certificate
            .to_public_key()
            .to_bytes()
            .expect("serialize uniform public certificate");
        parse_single_certificate_packet_set(&public).unwrap_or_else(|error| {
            panic!("accept {case} certificate: {error}");
        });
    }
}

fn designated_revoker_declaration(
    target: &SignedSecretKey,
    revoker: &SignedSecretKey,
    class: RevocationKeyClass,
) -> CanonicalPacket {
    signed_packet(target, SignatureType::Key, |mut config| {
        config.hashed_subpackets.extend([
            Subpacket::regular(SubpacketData::IssuerFingerprint(
                target.primary_key.fingerprint(),
            ))
            .expect("declaration issuer fingerprint"),
            revocation_key_subpacket(revoker, class),
        ]);
        config
            .sign_key(
                &target.primary_key,
                &Password::empty(),
                target.primary_key.public_key(),
            )
            .expect("sign designated-revoker declaration")
    })
}

fn v6_designated_revoker_declaration(
    target: &SignedSecretKey,
    revoker: &SignedSecretKey,
) -> CanonicalPacket {
    let mut config = SignatureConfig::v6(
        StdRng::seed_from_u64(0x5636_5245_564f_4b45),
        SignatureType::Key,
        target.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    )
    .expect("v6 signature config");
    config.hashed_subpackets.extend([
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            1_782_541_400,
        )))
        .expect("signature creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            target.primary_key.fingerprint(),
        ))
        .expect("declaration issuer fingerprint"),
        revocation_key_subpacket(revoker, RevocationKeyClass::Sensitive),
    ]);
    CanonicalPacket {
        tag: SIGNATURE_TAG,
        body: config
            .sign_key(
                &target.primary_key,
                &Password::empty(),
                target.primary_key.public_key(),
            )
            .expect("sign v6 designated-revoker declaration")
            .to_bytes()
            .expect("serialize v6 designated-revoker declaration"),
    }
}

fn revocation_key_subpacket(revoker: &SignedSecretKey, class: RevocationKeyClass) -> Subpacket {
    Subpacket::regular(SubpacketData::RevocationKey(RevocationKey::new(
        class,
        revoker.primary_key.algorithm(),
        revoker.primary_key.fingerprint().as_bytes(),
    )))
    .expect("revocation key subpacket")
}

fn key_revocation(target: &SignedSecretKey, signer: &SignedSecretKey) -> CanonicalPacket {
    signed_packet(signer, SignatureType::KeyRevocation, |mut config| {
        // Legacy designated revocations may identify the issuer only through
        // this unhashed v4 Key ID.
        config.unhashed_subpackets.push(
            Subpacket::regular(SubpacketData::IssuerKeyId(
                signer.primary_key.legacy_key_id(),
            ))
            .expect("revocation issuer key ID"),
        );
        config
            .sign_key(
                &signer.primary_key,
                &Password::empty(),
                target.primary_key.public_key(),
            )
            .expect("sign key revocation")
    })
}

fn v6_key_revocation(target: &SignedSecretKey, seed: u64) -> CanonicalPacket {
    let mut config = SignatureConfig::v6(
        StdRng::seed_from_u64(seed),
        SignatureType::KeyRevocation,
        target.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    )
    .expect("v6 revocation signature config");
    config.hashed_subpackets.extend([
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            1_782_541_400,
        )))
        .expect("revocation signature creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            target.primary_key.fingerprint(),
        ))
        .expect("revocation issuer fingerprint"),
    ]);
    CanonicalPacket {
        tag: SIGNATURE_TAG,
        body: config
            .sign_key(
                &target.primary_key,
                &Password::empty(),
                target.primary_key.public_key(),
            )
            .expect("sign v6 key revocation")
            .to_bytes()
            .expect("serialize v6 key revocation"),
    }
}

fn subkey_self_signature(
    target: &SignedSecretKey,
    subkey: &PublicSubkey,
    signature_type: SignatureType,
    seed: u64,
    local: bool,
) -> CanonicalPacket {
    assert!(matches!(
        signature_type,
        SignatureType::SubkeyBinding | SignatureType::SubkeyRevocation,
    ));
    let mut config = match target.primary_key.version() {
        KeyVersion::V4 => SignatureConfig::v4(
            signature_type,
            target.primary_key.algorithm(),
            HashAlgorithm::Sha256,
        ),
        KeyVersion::V6 => SignatureConfig::v6(
            StdRng::seed_from_u64(seed),
            signature_type,
            target.primary_key.algorithm(),
            HashAlgorithm::Sha256,
        )
        .expect("v6 subkey signature config"),
        version => panic!("unsupported subkey signature version: {version:?}"),
    };
    config.hashed_subpackets.extend([
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            1_782_541_400,
        )))
        .expect("subkey signature creation time"),
        Subpacket::regular(SubpacketData::IssuerFingerprint(
            target.primary_key.fingerprint(),
        ))
        .expect("subkey signature issuer fingerprint"),
    ]);
    if local {
        config = mark_local(config);
    }
    CanonicalPacket {
        tag: SIGNATURE_TAG,
        body: config
            .sign_subkey_binding(
                &target.primary_key,
                target.primary_key.public_key(),
                &Password::empty(),
                subkey,
            )
            .expect("sign subkey self-signature")
            .to_bytes()
            .expect("serialize subkey self-signature"),
    }
}

fn first_signature_packet(certificate: &PublicCertificatePacketSet) -> CanonicalPacket {
    certificate
        .direct
        .values()
        .chain(
            certificate
                .identities
                .values()
                .flat_map(|attached| attached.values()),
        )
        .chain(
            certificate
                .subkeys
                .values()
                .flat_map(|component| component.attached.values()),
        )
        .chain(
            certificate
                .unknowns
                .values()
                .flat_map(|attached| attached.values()),
        )
        .find(|packet| packet.tag == SIGNATURE_TAG)
        .cloned()
        .expect("fixture contains a signature")
}

fn signature_with_unhashed_experimental(
    packet: &CanonicalPacket,
    marker: u16,
    payload_bytes: usize,
) -> CanonicalPacket {
    let signature = parse_signature_packet(packet).expect("parse fixture signature");
    let mut config = signature
        .config()
        .cloned()
        .expect("known fixture signature");
    let mut payload = Vec::with_capacity(payload_bytes.max(2));
    payload.extend_from_slice(&marker.to_be_bytes());
    payload.resize(payload_bytes.max(2), 0xa5);
    config.unhashed_subpackets.push(
        Subpacket::regular(SubpacketData::Experimental(100, payload.into()))
            .expect("build experimental subpacket"),
    );
    CanonicalPacket {
        tag: SIGNATURE_TAG,
        body: rebuild_signature_body(&signature, config).expect("rebuild signature variant"),
    }
}

fn signature_with_unhashed_subpackets(
    packet: &CanonicalPacket,
    subpackets: impl IntoIterator<Item = Subpacket>,
) -> CanonicalPacket {
    let signature = parse_signature_packet(packet).expect("parse fixture signature");
    let mut config = signature
        .config()
        .cloned()
        .expect("known fixture signature");
    config.unhashed_subpackets.extend(subpackets);
    CanonicalPacket {
        tag: SIGNATURE_TAG,
        body: rebuild_signature_body(&signature, config)
            .expect("rebuild signature with unhashed subpackets"),
    }
}

fn signature_with_hashed_experimental(packet: &CanonicalPacket, marker: u16) -> CanonicalPacket {
    let signature = parse_signature_packet(packet).expect("parse fixture signature");
    let mut config = signature
        .config()
        .cloned()
        .expect("known fixture signature");
    config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::Experimental(
            100,
            marker.to_be_bytes().to_vec().into(),
        ))
        .expect("build experimental subpacket"),
    );
    CanonicalPacket {
        tag: SIGNATURE_TAG,
        body: rebuild_signature_body(&signature, config).expect("rebuild signature variant"),
    }
}

fn signature_with_digest_prefix(
    packet: &CanonicalPacket,
    signed_hash_value: [u8; 2],
) -> CanonicalPacket {
    let signature = parse_signature_packet(packet).expect("parse fixture signature");
    CanonicalPacket {
        tag: SIGNATURE_TAG,
        body: rebuild_signature_body_with_prefix(
            &signature,
            signature
                .config()
                .cloned()
                .expect("known fixture signature"),
            signed_hash_value,
        )
        .expect("rebuild signature digest prefix"),
    }
}

fn signature_with_v3_unhashed_fields(
    packet: &CanonicalPacket,
    issuer_key_id: KeyId,
    signed_hash_value: [u8; 2],
) -> CanonicalPacket {
    let signature = parse_signature_packet(packet).expect("parse fixture v3 signature");
    let mut config = signature
        .config()
        .cloned()
        .expect("known fixture v3 signature");
    let SignatureVersionSpecific::V3 {
        issuer_key_id: issuer,
        ..
    } = &mut config.version_specific
    else {
        panic!("fixture signature must be v3");
    };
    *issuer = issuer_key_id;
    CanonicalPacket {
        tag: SIGNATURE_TAG,
        body: rebuild_signature_body_with_prefix(&signature, config, signed_hash_value)
            .expect("rebuild v3 signature unhashed fields"),
    }
}

fn v3_user_id_certification(
    secret: &SignedSecretKey,
    identity: &CanonicalPacket,
) -> CanonicalPacket {
    assert_eq!(identity.tag, USER_ID_TAG);
    let config = SignatureConfig::v3(
        SignatureType::CertPositive,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
        Timestamp::from_secs(1_782_541_400),
        secret.primary_key.legacy_key_id(),
    );
    let mut hasher = HashAlgorithm::Sha256
        .new_hasher()
        .expect("create v3 certification hasher");
    hasher.update(
        &signature_key_hash_data(secret.primary_key.public_key())
            .expect("serialize v4 primary key for v3 signature"),
    );
    hasher.update(&identity.body);
    let signature_data_len = config
        .hash_signature_data(&mut hasher)
        .expect("hash v3 certification metadata");
    hasher.update(
        &config
            .trailer(signature_data_len)
            .expect("v3 certification trailer"),
    );
    let digest = hasher.finalize();
    let signature = secret
        .primary_key
        .sign(&Password::empty(), HashAlgorithm::Sha256, &digest)
        .expect("sign v3 user ID certification");
    CanonicalPacket {
        tag: SIGNATURE_TAG,
        body: Signature::from_config(config, [digest[0], digest[1]], signature)
            .and_then(|signature| signature.to_bytes())
            .expect("serialize v3 user ID certification"),
    }
}

fn signature_with_changed_material(packet: &CanonicalPacket) -> CanonicalPacket {
    let signature = parse_signature_packet(packet).expect("parse fixture signature");
    let config = signature
        .config()
        .cloned()
        .expect("known fixture signature");
    let mut material = signature.signature().cloned().expect("signature material");
    match &mut material {
        SignatureBytes::Mpis(mpis) => {
            let first = mpis.first_mut().expect("signature contains an MPI");
            let mut bytes = first.as_ref().to_vec();
            let last = bytes.last_mut().expect("signature MPI is nonempty");
            *last ^= 1;
            *first = Mpi::from_slice(&bytes);
        }
        SignatureBytes::Native(bytes) => {
            let mut changed = bytes.to_vec();
            let last = changed.last_mut().expect("signature material is nonempty");
            *last ^= 1;
            *bytes = changed.into();
        }
    }
    let body = Signature::from_config(
        config,
        signature.signed_hash_value().expect("signed hash prefix"),
        material,
    )
    .and_then(|signature| signature.to_bytes())
    .expect("rebuild changed signature material");
    CanonicalPacket {
        tag: SIGNATURE_TAG,
        body,
    }
}

fn insert_unknown_component(certificate: &mut PublicCertificatePacketSet, packet: CanonicalPacket) {
    certificate.unknown_order.push(packet.clone());
    certificate
        .unknowns
        .insert(packet, AttachedPackets::default());
}

fn attached_from(packets: impl IntoIterator<Item = CanonicalPacket>) -> AttachedPackets {
    let mut attached = AttachedPackets::default();
    for packet in packets {
        attached.insert(packet).expect("insert attached packet");
    }
    attached
}

fn signed_packet(
    secret: &SignedSecretKey,
    signature_type: SignatureType,
    sign: impl FnOnce(SignatureConfig) -> Signature,
) -> CanonicalPacket {
    signed_packet_at(secret, signature_type, 1_782_541_400, sign)
}

fn signed_packet_at(
    secret: &SignedSecretKey,
    signature_type: SignatureType,
    creation_time: u32,
    sign: impl FnOnce(SignatureConfig) -> Signature,
) -> CanonicalPacket {
    let mut config = SignatureConfig::v4(
        signature_type,
        secret.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    );
    config.hashed_subpackets.push(
        Subpacket::regular(SubpacketData::SignatureCreationTime(Timestamp::from_secs(
            creation_time,
        )))
        .expect("signature creation time"),
    );
    CanonicalPacket {
        tag: SIGNATURE_TAG,
        body: sign(config).to_bytes().expect("serialize test signature"),
    }
}

#[test]
fn canonical_equal_time_signatures_follow_cryptographic_material_order() {
    const CREATION_TIME: u32 = 1_782_541_400;
    let secret = generated_uniform_version_secret(KeyVersion::V4, 0x5349_4754_4945_0001);
    let direct = |expiration_seconds| {
        signed_packet_at(&secret, SignatureType::Key, CREATION_TIME, |mut config| {
            config.hashed_subpackets.push(
                Subpacket::regular(SubpacketData::KeyExpirationTime(Duration::from_secs(
                    expiration_seconds,
                )))
                .expect("key expiration"),
            );
            config
                .sign_key(
                    &secret.primary_key,
                    &Password::empty(),
                    secret.primary_key.public_key(),
                )
                .expect("sign Direct Key signature")
        })
    };
    let first = direct(3_600);
    let second = direct(7_200);
    let first_signature = parse_signature_packet(&first).expect("parse first signature");
    let second_signature = parse_signature_packet(&second).expect("parse second signature");
    let expected_first =
        if cryptographic_signature_material_cmp(&first_signature, &second_signature).is_lt() {
            &first
        } else {
            &second
        };

    let mut certificate = certificate_packet_set(&secret);
    certificate.direct = attached_from([second.clone(), first.clone()]);
    let canonical = certificate.finalize().expect("finalize certificate");
    let stream = RawPacketStream::parse(&canonical.retained_bytes, MAX_MERGE_PACKETS)
        .expect("parse canonical certificate");
    let tied = stream
        .packets()
        .iter()
        .filter_map(|span| {
            let packet = CanonicalPacket::from_span(&stream, span);
            let signature = parse_signature_packet(&packet).ok()?;
            (signature.typ() == Some(SignatureType::Key)
                && signature_creation_time(&signature) == Some(CREATION_TIME))
            .then_some(packet)
        })
        .collect::<Vec<_>>();

    assert_eq!(tied.len(), 2);
    assert_eq!(&tied[0], expected_first);
}

fn synthetic_direct_certification_revocation(secret: &SignedSecretKey) -> CanonicalPacket {
    let direct = signed_packet(secret, SignatureType::Key, |config| {
        config
            .sign_key(
                &secret.primary_key,
                &Password::empty(),
                secret.primary_key.public_key(),
            )
            .expect("sign source Direct Key signature")
    });
    let signature = parse_signature_packet(&direct).expect("parse source Direct Key signature");
    let mut config = signature.config().cloned().expect("known signature config");
    config.typ = SignatureType::CertRevocation;
    CanonicalPacket {
        tag: SIGNATURE_TAG,
        body: rebuild_signature_body(&signature, config)
            .expect("construct synthetic direct certification revocation"),
    }
}

fn signature_types_after(data: &[u8], owner: &CanonicalPacket) -> Vec<SignatureType> {
    let stream = RawPacketStream::parse(data, MAX_MERGE_PACKETS)
        .expect("parse serialized certificate packet stream");
    let owner_index = stream
        .packets()
        .iter()
        .position(|packet| {
            packet.tag() == owner.tag && stream.body(packet).as_slice() == owner.body
        })
        .expect("find signature owner in serialized certificate");
    stream
        .packets()
        .iter()
        .skip(owner_index + 1)
        .take_while(|packet| packet.tag() == SIGNATURE_TAG)
        .map(|packet| {
            let packet = CanonicalPacket::from_span(&stream, packet);
            parse_signature_packet(&packet)
                .expect("parse attached signature")
                .typ()
                .expect("known signature type")
        })
        .collect()
}

fn assert_canonical_certificate_round_trip(data: &[u8]) {
    SignedPublicKey::from_reader_single(Cursor::new(data))
        .expect("reparse canonical certificate with composed parser");
    let (round_tripped, _) =
        canonicalize_public_certificate(data).expect("canonicalize serialized certificate again");
    assert_eq!(round_tripped, data);
}

fn assert_retained_certificate_round_trip(data: &[u8]) {
    SignedPublicKey::from_reader_single(Cursor::new(data))
        .expect("reparse retained certificate with composed parser");
    let round_tripped = parse_document(data)
        .remove(0)
        .finalize()
        .expect("finalize retained certificate again")
        .retained_bytes;
    assert_eq!(round_tripped, data);
}

fn first_verified_user_id_signature(
    certificate: &PublicCertificatePacketSet,
) -> (CanonicalPacket, CanonicalPacket) {
    let primary = parse_primary_key(&certificate.primary).expect("parse fixture primary key");
    for identity in certificate
        .identity_order
        .iter()
        .filter(|identity| identity.tag == USER_ID_TAG)
    {
        for packet in certificate.identities[identity].values() {
            if parse_signature_packet(packet).is_ok_and(|signature| {
                signature
                    .verify_certification(&primary, Tag::UserId, &RawIdentityBody(&identity.body))
                    .is_ok()
            }) {
                return (identity.clone(), packet.clone());
            }
        }
    }
    panic!("fixture must contain a verified User ID certification");
}

fn replace_signature_with_flood(
    certificate: &[u8],
    original: &CanonicalPacket,
    variants: impl IntoIterator<Item = CanonicalPacket>,
) -> Vec<u8> {
    let stream = RawPacketStream::parse(certificate, MAX_MERGE_PACKETS)
        .expect("parse source certificate for signature flood");
    let mut output = Vec::new();
    let mut replaced = false;
    let mut variants = Some(variants.into_iter());
    for span in stream.packets() {
        let packet = CanonicalPacket::from_span(&stream, span);
        if !replaced && packet == *original {
            for variant in variants.take().expect("signature variants used once") {
                variant
                    .write_to(&mut output)
                    .expect("write signature flood variant");
            }
            original
                .write_to(&mut output)
                .expect("write genuine signature last");
            replaced = true;
        } else {
            output.extend_from_slice(stream.raw(span));
        }
    }
    assert!(replaced, "find signature replaced by flood");
    output
}

fn replace_signature_variant(
    certificate: &[u8],
    original: &CanonicalPacket,
    replacement: &CanonicalPacket,
) -> Vec<u8> {
    let stream = RawPacketStream::parse(certificate, MAX_MERGE_PACKETS)
        .expect("parse source certificate for signature replacement");
    let mut output = Vec::new();
    let mut replaced = false;
    for span in stream.packets() {
        let packet = CanonicalPacket::from_span(&stream, span);
        if !replaced && packet == *original {
            replacement
                .write_to(&mut output)
                .expect("write replacement signature variant");
            replaced = true;
        } else {
            output.extend_from_slice(stream.raw(span));
        }
    }
    assert!(replaced, "find signature replaced by variant");
    output
}

fn wrong_digest_prefixes(original: &CanonicalPacket) -> Vec<[u8; 2]> {
    let genuine = parse_signature_packet(original)
        .expect("parse genuine signature")
        .signed_hash_value()
        .expect("genuine digest prefix");
    (0..=u16::MAX)
        .map(u16::to_be_bytes)
        .filter(|candidate| *candidate != genuine)
        .take(MAX_SIGNATURES_PER_OBJECT)
        .collect()
}

fn assert_flooded_certificate_is_usable(
    flooded: &[u8],
    identity: &CanonicalPacket,
    genuine: &CanonicalPacket,
    expected_signature_count: usize,
) {
    let canonical = canonicalize_public_certificate_material(flooded)
        .expect("canonicalize signature-flooded certificate");
    assert_eq!(canonical.signature_count, expected_signature_count);
    assert!(contains_packet_body(&canonical.retained_bytes, genuine));
    assert_canonical_certificate_round_trip(&canonical.bytes);

    let reparsed = parse_document(&canonical.retained_bytes).remove(0);
    let retained = reparsed
        .identities
        .get(identity)
        .expect("retain flooded User ID");
    assert_eq!(
        retained
            .values()
            .filter(|packet| attached_packet_key(packet) == attached_packet_key(genuine))
            .count(),
        1,
    );

    let candidates = certificate_components(&canonical.semantic).collect::<Vec<_>>();
    let policy = validate_certificate(
        &canonical.semantic,
        &candidates,
        2_000_000_000,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate signature-flooded certificate policy");
    assert_eq!(policy.verified_user_ids_for_test().len(), 1);
}

fn split_three_ways(
    certificate: &PublicCertificatePacketSet,
) -> (
    PublicCertificatePacketSet,
    PublicCertificatePacketSet,
    PublicCertificatePacketSet,
) {
    let mut direct = certificate.clone();
    direct.identities.clear();
    direct.identity_order.clear();
    direct.subkeys.clear();
    direct.subkey_order.clear();

    let mut identities = certificate.clone();
    identities.direct = AttachedPackets::default();
    identities.subkeys.clear();
    identities.subkey_order.clear();

    let mut subkeys = certificate.clone();
    subkeys.direct = AttachedPackets::default();
    subkeys.identities.clear();
    subkeys.identity_order.clear();
    (direct, identities, subkeys)
}

#[test]
fn merge_keeps_direct_certification_revocation_with_the_primary_key() {
    let secret = parse_secret(SECRET_KEY);
    let revocation = synthetic_direct_certification_revocation(&secret);
    let canonical = parse_fixture()
        .canonical_bytes()
        .expect("serialize fixture certificate");
    let stream =
        RawPacketStream::parse(&canonical, MAX_MERGE_PACKETS).expect("parse canonical fixture");
    let mut with_revocation = Vec::new();
    with_revocation.extend_from_slice(stream.raw(&stream.packets()[0]));
    revocation
        .write_to(&mut with_revocation)
        .expect("insert direct certification revocation");
    for packet in stream.packets().iter().skip(1) {
        with_revocation.extend_from_slice(stream.raw(packet));
    }

    let imported = parse_document(&with_revocation)
        .into_iter()
        .next()
        .expect("parse certificate with direct certification revocation");
    assert!(imported.direct.values().any(|packet| packet == &revocation));
    assert!(
        imported
            .identities
            .values()
            .all(|attached| attached.values().all(|packet| packet != &revocation)),
    );
    assert!(imported.subkeys.values().all(|component| {
        component
            .attached
            .values()
            .all(|packet| packet != &revocation)
    }),);

    let merged = merge(vec![parse_fixture(), imported]);
    let reparsed = parse_document(&merged)
        .into_iter()
        .next()
        .expect("reparse merged certificate");
    assert!(reparsed.direct.values().any(|packet| packet == &revocation));
    assert_canonical_certificate_round_trip(&merged);
}

#[test]
fn raw_certificate_merge_orders_primary_revocation_before_newer_direct_signature() {
    let (secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY)).expect("parse secret key");
    let revocation = signed_packet_at(
        &secret,
        SignatureType::KeyRevocation,
        1_700_000_000,
        |config| {
            config
                .sign_key(
                    &secret.primary_key,
                    &Password::empty(),
                    secret.primary_key.public_key(),
                )
                .expect("sign primary-key revocation")
        },
    );
    let direct = signed_packet_at(&secret, SignatureType::Key, 1_800_000_000, |config| {
        config
            .sign_key(
                &secret.primary_key,
                &Password::empty(),
                secret.primary_key.public_key(),
            )
            .expect("sign direct-key signature")
    });
    let mut revoked = parse_fixture();
    let mut current = revoked.clone();
    revoked.direct = attached_from([revocation]);
    current.direct = attached_from([direct]);
    let owner = revoked.primary.clone();

    let merged = merge(vec![current, revoked]);

    assert_eq!(
        signature_types_after(&merged, &owner),
        vec![SignatureType::KeyRevocation, SignatureType::Key],
    );
    assert_canonical_certificate_round_trip(&merged);
}

#[test]
fn raw_certificate_merge_orders_identity_revocation_before_newer_certification() {
    let (secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY)).expect("parse secret key");
    let mut revoked = parse_fixture();
    let owner = revoked
        .identities
        .keys()
        .find(|packet| packet.tag == USER_ID_TAG)
        .cloned()
        .expect("fixture user ID");
    let user_id = parse_user_id(&owner).expect("parse fixture user ID");
    let revocation = signed_packet_at(
        &secret,
        SignatureType::CertRevocation,
        1_700_000_000,
        |config| {
            config
                .sign_certification(
                    &secret.primary_key,
                    secret.primary_key.public_key(),
                    &Password::empty(),
                    Tag::UserId,
                    &user_id,
                )
                .expect("sign certification revocation")
        },
    );
    let certification = signed_packet_at(
        &secret,
        SignatureType::CertPositive,
        1_800_000_000,
        |config| {
            config
                .sign_certification(
                    &secret.primary_key,
                    secret.primary_key.public_key(),
                    &Password::empty(),
                    Tag::UserId,
                    &user_id,
                )
                .expect("sign user ID certification")
        },
    );
    let mut current = revoked.clone();
    *revoked
        .identities
        .get_mut(&owner)
        .expect("fixture user ID bundle") = attached_from([revocation]);
    *current
        .identities
        .get_mut(&owner)
        .expect("fixture user ID bundle") = attached_from([certification]);

    let merged = merge(vec![current, revoked]);

    assert_eq!(
        signature_types_after(&merged, &owner),
        vec![SignatureType::CertRevocation, SignatureType::CertPositive],
    );
    assert_canonical_certificate_round_trip(&merged);
}

#[test]
fn raw_certificate_merge_orders_subkey_revocation_before_newer_binding() {
    let (secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY)).expect("parse secret key");
    let mut revoked = parse_fixture();
    let (fingerprint, component) = revoked
        .subkeys
        .iter()
        .next()
        .map(|(fingerprint, component)| (fingerprint.clone(), component.clone()))
        .expect("fixture subkey");
    let owner = component.packet;
    let subkey = parse_public_subkey(&owner).expect("parse fixture subkey");
    let revocation = signed_packet_at(
        &secret,
        SignatureType::SubkeyRevocation,
        1_700_000_000,
        |config| {
            config
                .sign_subkey_binding(
                    &secret.primary_key,
                    secret.primary_key.public_key(),
                    &Password::empty(),
                    &subkey,
                )
                .expect("sign subkey revocation")
        },
    );
    let binding = signed_packet_at(
        &secret,
        SignatureType::SubkeyBinding,
        1_800_000_000,
        |config| {
            config
                .sign_subkey_binding(
                    &secret.primary_key,
                    secret.primary_key.public_key(),
                    &Password::empty(),
                    &subkey,
                )
                .expect("sign subkey binding")
        },
    );
    let mut current = revoked.clone();
    revoked
        .subkeys
        .get_mut(&fingerprint)
        .expect("fixture subkey bundle")
        .attached = attached_from([revocation]);
    current
        .subkeys
        .get_mut(&fingerprint)
        .expect("fixture subkey bundle")
        .attached = attached_from([binding]);

    let merged = merge(vec![current, revoked]);

    assert_eq!(
        signature_types_after(&merged, &owner),
        vec![
            SignatureType::SubkeyRevocation,
            SignatureType::SubkeyBinding,
        ],
    );
    assert_canonical_certificate_round_trip(&merged);
}

#[test]
fn canonicalization_cryptographically_rehomes_a_self_certification_before_its_user_id() {
    let stream =
        RawPacketStream::parse(PUBLIC_KEY, MAX_MERGE_PACKETS).expect("parse fixture packet stream");
    let packet = |tag| {
        let span = stream
            .packets()
            .iter()
            .find(|packet| packet.tag() == tag)
            .expect("fixture component");
        CanonicalPacket::from_span(&stream, span)
    };
    let signature_packets = stream
        .packets()
        .iter()
        .filter(|packet| packet.tag() == SIGNATURE_TAG)
        .map(|packet| CanonicalPacket::from_span(&stream, packet))
        .collect::<Vec<_>>();
    let user_id_signature = signature_packets[0].clone();
    let subkey_signature = signature_packets[1].clone();
    let primary = packet(PUBLIC_KEY_TAG);
    let user_id_a = packet(USER_ID_TAG);
    let subkey = packet(PUBLIC_SUBKEY_TAG);
    let user_id_b = CanonicalPacket {
        tag: USER_ID_TAG,
        body: b"Unbound User <unbound@example.test>".to_vec(),
    };

    let (secret, _) =
        SignedSecretKey::from_reader_single(Cursor::new(SECRET_KEY)).expect("parse secret key");
    let direct_signature = signed_packet(&secret, SignatureType::Key, |config| {
        config
            .sign_key(
                &secret.primary_key,
                &Password::empty(),
                secret.primary_key.public_key(),
            )
            .expect("sign primary key")
    });
    let attribute = UserAttribute::new_image(Bytes::from_static(b"ownership test image"))
        .expect("build user attribute");
    let attribute_packet = CanonicalPacket {
        tag: USER_ATTRIBUTE_TAG,
        body: attribute.to_bytes().expect("serialize user attribute"),
    };
    let attribute_signature = signed_packet(&secret, SignatureType::CertPositive, |config| {
        config
            .sign_certification(
                &secret.primary_key,
                secret.primary_key.public_key(),
                &Password::empty(),
                Tag::UserAttribute,
                &attribute,
            )
            .expect("sign user attribute")
    });

    // The certification is deliberately misfiled in the Direct position
    // ahead of the first User ID, and the Direct Key signature sits behind
    // the last User ID.  The subkey binding remains in its only legal
    // structural position, immediately after its subkey.
    let mut shuffled = Vec::new();
    for packet in [
        &primary,
        &user_id_signature,
        &user_id_a,
        &attribute_packet,
        &attribute_signature,
        &user_id_b,
        &direct_signature,
        &subkey,
        &subkey_signature,
    ] {
        packet.write_to(&mut shuffled).expect("write test packet");
    }

    let retained = canonicalize_public_certificate_material(&shuffled)
        .expect("canonicalize retained misfiled signatures")
        .retained_bytes;
    let certificate = parse_document(&retained).remove(0);

    // A primary-key signature has one possible target, so it is hoisted out
    // of the User ID bundle it was found in.
    assert_eq!(certificate.direct.values().next(), Some(&direct_signature));
    // Signatures that were adjacent to a component they can plausibly bind
    // stay with that component.
    assert_eq!(
        certificate.identities[&attribute_packet].values().next(),
        Some(&attribute_signature),
    );
    assert_eq!(
        certificate.identities[&user_id_a].values().next(),
        Some(&user_id_signature),
    );
    assert!(
        !certificate.unknowns.contains_key(&user_id_signature),
        "an exactly verified certification must not remain opaque",
    );
    assert!(certificate.identities[&user_id_b].is_empty());
    // The certification found ahead of the first User ID is verified over
    // the exact raw User ID packet and moved to its unique signed target.
    // The subkey binding stays on its source component.
    let last_subkey = certificate
        .subkey_order
        .last()
        .expect("fixture subkey")
        .clone();
    assert_eq!(
        certificate.subkeys[&last_subkey]
            .attached
            .values()
            .cloned()
            .collect::<BTreeSet<_>>(),
        BTreeSet::from([subkey_signature]),
    );

    // RFC 9580 §10.1: nothing but the primary key's own signatures may be
    // serialized between the primary key and the first identity.
    let serialized =
        RawPacketStream::parse(&retained, MAX_MERGE_PACKETS).expect("parse retained packet stream");
    let first_identity = serialized
        .packets()
        .iter()
        .position(|packet| matches!(packet.tag(), USER_ID_TAG | USER_ATTRIBUTE_TAG))
        .expect("canonical certificate has an identity");
    for span in &serialized.packets()[1..first_identity] {
        let leading = CanonicalPacket::from_span(&serialized, span);
        assert_eq!(leading.tag, SIGNATURE_TAG);
        assert!(matches!(
            parse_signature_packet(&leading)
                .expect("parse leading signature")
                .typ(),
            Some(SignatureType::Key | SignatureType::KeyRevocation),
        ));
    }
    assert_retained_certificate_round_trip(&retained);

    // Policy still remains authoritative for authentication. Its one
    // verification pass now sees the self-certification under the exact
    // component whose bytes it signed.
    let semantic_stream =
        RawPacketStream::parse(&retained, MAX_MERGE_PACKETS).expect("parse retained stream");
    let semantic = semantic_stream.semantic_bytes();
    let (public, _) = SignedPublicKey::from_reader_single(Cursor::new(semantic.as_slice()))
        .expect("parse canonical certificate");
    let candidates = certificate_components(&public).collect::<Vec<_>>();
    let policy = validate_certificate(
        &public,
        &candidates,
        2_000_000_000,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate canonical certificate policy");
    assert!(policy.primary.authenticated);
    assert_eq!(policy.subkeys.len(), 1);
    assert!(policy.subkeys[0].authenticated);
    assert_eq!(policy.verified_user_ids_for_test().len(), 1);
}

#[test]
fn parser_rejects_identity_components_after_entering_the_subkey_phase() {
    let stream =
        RawPacketStream::parse(PUBLIC_KEY, MAX_MERGE_PACKETS).expect("parse fixture packet stream");
    assert_eq!(
        stream
            .packets()
            .iter()
            .map(RawPacketSpan::tag)
            .collect::<Vec<_>>(),
        vec![
            PUBLIC_KEY_TAG,
            USER_ID_TAG,
            SIGNATURE_TAG,
            PUBLIC_SUBKEY_TAG,
            SIGNATURE_TAG,
        ],
    );

    let mut user_id_after_subkey = Vec::new();
    for index in [0, 3, 4, 1, 2] {
        user_id_after_subkey.extend_from_slice(stream.raw(&stream.packets()[index]));
    }

    let attribute = UserAttribute::new_image(Bytes::from_static(b"late identity image"))
        .expect("build user attribute");
    let attribute = CanonicalPacket {
        tag: USER_ATTRIBUTE_TAG,
        body: attribute.to_bytes().expect("serialize user attribute"),
    };
    let mut user_attribute_after_subkey = Vec::new();
    for index in [0, 3, 4] {
        user_attribute_after_subkey.extend_from_slice(stream.raw(&stream.packets()[index]));
    }
    attribute
        .write_to(&mut user_attribute_after_subkey)
        .expect("write late user attribute");

    for (case, malformed) in [
        ("User ID", user_id_after_subkey),
        ("User Attribute", user_attribute_after_subkey),
    ] {
        // This is a certificate grammar violation, not merely an unusable
        // signature, so tolerant signature retention must not accept it.
        assert_eq!(
            parse_single_certificate_packet_set(&malformed),
            Err(CertificateMergeError::Malformed),
            "reject late {case}",
        );
    }
}

#[test]
fn keyring_parser_skips_identity_after_subkey_and_recovers_the_next_certificate() {
    let malformed =
        RawPacketStream::parse(PUBLIC_KEY, MAX_MERGE_PACKETS).expect("parse fixture packet stream");
    let mut keyring = Vec::new();
    for index in [0, 3, 4, 1, 2] {
        keyring.extend_from_slice(malformed.raw(&malformed.packets()[index]));
    }
    keyring.extend_from_slice(malformed.bytes());

    let stream = RawPacketStream::parse(&keyring, MAX_MERGE_PACKETS)
        .expect("parse malformed and valid keyring framing");
    let parsed =
        parse_public_certificate_packet_sets(&stream).expect("recover the later certificate");

    assert_eq!(parsed.skipped_malformed, 1);
    assert_eq!(parsed.certificates.len(), 1);
    assert_eq!(
        parsed.certificates[0].fingerprint,
        parse_fixture().fingerprint
    );
}

#[test]
fn appended_unusable_signatures_do_not_poison_certificates_or_later_keyring_entries() {
    let fixture =
        RawPacketStream::parse(PUBLIC_KEY, MAX_MERGE_PACKETS).expect("parse fixture packet stream");
    let expected_first = parse_fixture();
    let expected_signature_count = expected_first.signature_count();
    let independent_bytes = parse_secret(OTHER_SECRET_KEY)
        .to_public_key()
        .to_bytes()
        .expect("serialize independent public certificate");
    let independent_stream = RawPacketStream::parse(&independent_bytes, MAX_MERGE_PACKETS)
        .expect("parse independent packet stream");
    let expected_second = parse_document(independent_stream.bytes())
        .remove(0)
        .fingerprint;
    let cases = [
        (
            "truncated V4 signature",
            CanonicalPacket {
                tag: SIGNATURE_TAG,
                body: vec![u8::from(SignatureVersion::V4)],
            },
        ),
        (
            "future signature version",
            CanonicalPacket {
                tag: SIGNATURE_TAG,
                body: vec![0x7f, 0xde, 0xad, 0xbe, 0xef],
            },
        ),
    ];

    assert!(matches!(
        parse_signature_packet(&cases[0].1),
        Err(CertificateMergeError::Malformed)
    ));
    assert_eq!(
        parse_signature_packet(&cases[1].1)
            .expect("rPGP preserves a future signature version")
            .typ(),
        None,
    );

    for (case, opaque_signature) in cases {
        let mut poisoned = fixture.bytes().to_vec();
        opaque_signature
            .write_to(&mut poisoned)
            .expect("append framed signature packet");

        let canonical = canonicalize_public_certificate_material(&poisoned)
            .unwrap_or_else(|error| panic!("accept {case}: {error}"));
        assert_eq!(
            canonical.fingerprint,
            expected_first.fingerprint.upper_hex()
        );
        assert_eq!(
            canonical.signature_count,
            expected_signature_count + 1,
            "count opaque signature against the certificate budget: {case}",
        );
        assert!(
            contains_packet_body(&canonical.retained_bytes, &opaque_signature),
            "retain opaque evidence: {case}",
        );
        assert!(
            contains_packet_body(&canonical.bytes, &opaque_signature),
            "retain opaque evidence in canonical export: {case}",
        );
        let (canonical_twice, _) = canonicalize_public_certificate(&canonical.bytes)
            .unwrap_or_else(|error| panic!("canonicalize retained {case} twice: {error}"));
        assert_eq!(
            canonical_twice, canonical.bytes,
            "opaque signature placement is idempotent: {case}",
        );
        assert_eq!(
            export_public_certificate_preserving_framing(&poisoned)
                .expect("preserve original certificate framing"),
            poisoned,
            "preserve the opaque packet's original framing: {case}",
        );

        let mut keyring = poisoned;
        keyring.extend_from_slice(independent_stream.bytes());
        let keyring_stream =
            RawPacketStream::parse(&keyring, MAX_MERGE_PACKETS).expect("parse framed keyring");
        let parsed = parse_public_certificate_packet_sets(&keyring_stream)
            .unwrap_or_else(|error| panic!("parse keyring after {case}: {error}"));
        assert_eq!(
            parsed.skipped_malformed, 0,
            "do not drop a certificate: {case}"
        );
        assert_eq!(
            parsed.certificates.len(),
            2,
            "retain both certificates: {case}"
        );
        assert_eq!(
            parsed.certificates[0].fingerprint,
            expected_first.fingerprint
        );
        assert!(
            parsed.certificates[0]
                .unknowns
                .contains_key(&opaque_signature)
        );
        assert_eq!(parsed.certificates[1].fingerprint, expected_second);
    }
}

#[test]
fn canonicalization_preserves_external_certification_with_its_user_id() {
    let mut certificate = parse_fixture();
    let identity_packet = certificate
        .identities
        .keys()
        .find(|packet| packet.tag == USER_ID_TAG)
        .cloned()
        .expect("fixture user ID");
    let user_id = parse_user_id(&identity_packet).expect("parse fixture user ID");
    let primary = parse_primary_key(&certificate.primary).expect("parse target primary key");
    let (certifier, _) = SignedSecretKey::from_reader_single(Cursor::new(OTHER_SECRET_KEY))
        .expect("parse external certifier");
    let certification = signed_packet(&certifier, SignatureType::CertPositive, |config| {
        config
            .sign_certification(
                &certifier.primary_key,
                &primary,
                &Password::empty(),
                Tag::UserId,
                &user_id,
            )
            .expect("sign external certification")
    });
    parse_signature_packet(&certification)
        .expect("parse external certification")
        .verify_third_party_certification(
            &primary,
            certifier.primary_key.public_key(),
            Tag::UserId,
            &user_id,
        )
        .expect("verify external certification");
    certificate
        .identities
        .get_mut(&identity_packet)
        .expect("fixture user ID bundle")
        .insert(certification.clone())
        .expect("attach external certification");

    let encoded = certificate
        .canonical_bytes()
        .expect("serialize externally certified certificate");
    let reparsed = parse_document(&encoded).remove(0);

    assert_eq!(
        reparsed.identities[&identity_packet]
            .values()
            .find(|packet| *packet == &certification),
        Some(&certification),
    );
}

#[test]
fn canonicalization_retains_a_cryptographically_invalid_signature_with_its_component() {
    let stream =
        RawPacketStream::parse(PUBLIC_KEY, MAX_MERGE_PACKETS).expect("parse fixture packet stream");
    let packets = stream
        .packets()
        .iter()
        .map(|span| CanonicalPacket::from_span(&stream, span))
        .collect::<Vec<_>>();
    let invalid_signature = signature_with_changed_material(&packets[2]);
    let mut malformed = Vec::new();
    for packet in [
        &packets[0],
        &packets[1],
        &invalid_signature,
        &packets[3],
        &packets[4],
    ] {
        packet.write_to(&mut malformed).expect("write test packet");
    }

    let retained = canonicalize_public_certificate_material(&malformed)
        .expect("retain an unverifiable signature without dropping it")
        .retained_bytes;
    let certificate = parse_document(&retained).remove(0);
    // A certification that already has a legal syntactic owner is retained
    // there when cryptography cannot prove a different exact target. Policy
    // is the stage that refuses to authenticate it.
    assert!(certificate.direct.is_empty());
    let identity = certificate.identity_order.first().expect("fixture user ID");
    assert_eq!(
        certificate.identities[identity].values().next(),
        Some(&invalid_signature),
    );

    let canonical_stream =
        RawPacketStream::parse(&retained, MAX_MERGE_PACKETS).expect("parse retained packet stream");
    assert_eq!(
        canonical_stream
            .packets()
            .iter()
            .map(RawPacketSpan::tag)
            .collect::<Vec<_>>(),
        vec![
            PUBLIC_KEY_TAG,
            USER_ID_TAG,
            SIGNATURE_TAG,
            PUBLIC_SUBKEY_TAG,
            SIGNATURE_TAG,
        ],
    );
    assert_retained_certificate_round_trip(&retained);
}

#[test]
fn unknown_noncritical_packets_do_not_change_binding_owners() {
    let stream =
        RawPacketStream::parse(PUBLIC_KEY, MAX_MERGE_PACKETS).expect("parse fixture packet stream");
    let user_id_index = stream
        .packets()
        .iter()
        .position(|packet| packet.tag() == USER_ID_TAG)
        .expect("fixture user ID");
    let subkey_index = stream
        .packets()
        .iter()
        .position(|packet| packet.tag() == PUBLIC_SUBKEY_TAG)
        .expect("fixture subkey");
    let user_id_signature = CanonicalPacket::from_span(
        &stream,
        stream
            .packets()
            .get(user_id_index + 1)
            .expect("fixture user ID certification"),
    );
    let subkey_signature = CanonicalPacket::from_span(
        &stream,
        stream
            .packets()
            .get(subkey_index + 1)
            .expect("fixture subkey binding"),
    );
    let user_id_unknown = CanonicalPacket {
        tag: 40,
        body: b"future packet inside the user ID bundle".to_vec(),
    };
    let subkey_unknown = CanonicalPacket {
        tag: 63,
        body: b"private packet inside the subkey bundle".to_vec(),
    };
    let mut extended = Vec::new();
    for (index, packet) in stream.packets().iter().enumerate() {
        extended.extend_from_slice(stream.raw(packet));
        if index == user_id_index {
            user_id_unknown
                .write_to(&mut extended)
                .expect("write unknown user ID packet");
        } else if index == subkey_index {
            subkey_unknown
                .write_to(&mut extended)
                .expect("write unknown subkey packet");
        }
    }

    let certificate = parse_document(&extended).remove(0);
    let identity = certificate.identity_order.first().expect("fixture user ID");
    let subkey = certificate.subkey_order.first().expect("fixture subkey");
    assert_eq!(
        certificate.identities[identity].values().next(),
        Some(&user_id_signature),
    );
    assert_eq!(
        certificate.subkeys[subkey].attached.values().next(),
        Some(&subkey_signature),
    );
    assert!(certificate.unknowns[&user_id_unknown].is_empty());
    assert!(certificate.unknowns[&subkey_unknown].is_empty());

    let finalized = certificate
        .finalize()
        .expect("finalize extended certificate");
    assert!(contains_packet_body(&finalized.bytes, &user_id_unknown));
    assert!(contains_packet_body(&finalized.bytes, &subkey_unknown));
    assert_eq!(finalized.semantic.details.users.len(), 1);
    assert_eq!(finalized.semantic.public_subkeys.len(), 1);

    let candidates = certificate_components(&finalized.semantic).collect::<Vec<_>>();
    let policy = validate_certificate(
        &finalized.semantic,
        &candidates,
        2_000_000_000,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate extended certificate policy");
    assert_eq!(policy.verified_user_ids_for_test().len(), 1);
    assert_eq!(policy.subkeys.len(), 1);
    assert!(policy.subkeys[0].authenticated);
    assert_canonical_certificate_round_trip(&finalized.bytes);
}

#[test]
fn unknown_noncritical_packets_do_not_quarantine_component_revocations() {
    let secret = parse_secret(SECRET_KEY);
    let stream =
        RawPacketStream::parse(PUBLIC_KEY, MAX_MERGE_PACKETS).expect("parse fixture packet stream");
    let user_id_index = stream
        .packets()
        .iter()
        .position(|packet| packet.tag() == USER_ID_TAG)
        .expect("fixture user ID");
    let subkey_index = stream
        .packets()
        .iter()
        .position(|packet| packet.tag() == PUBLIC_SUBKEY_TAG)
        .expect("fixture subkey");
    let user_id_packet = CanonicalPacket::from_span(&stream, &stream.packets()[user_id_index]);
    let user_id = parse_user_id(&user_id_packet).expect("parse fixture user ID");
    let subkey_packet = CanonicalPacket::from_span(&stream, &stream.packets()[subkey_index]);
    let subkey = parse_public_subkey(&subkey_packet).expect("parse fixture subkey");
    let user_id_revocation = signed_packet(&secret, SignatureType::CertRevocation, |config| {
        config
            .sign_certification(
                &secret.primary_key,
                secret.primary_key.public_key(),
                &Password::empty(),
                Tag::UserId,
                &user_id,
            )
            .expect("sign certification revocation")
    });
    let subkey_revocation = signed_packet(&secret, SignatureType::SubkeyRevocation, |config| {
        config
            .sign_subkey_binding(
                &secret.primary_key,
                secret.primary_key.public_key(),
                &Password::empty(),
                &subkey,
            )
            .expect("sign subkey revocation")
    });
    let user_id_unknown = CanonicalPacket {
        tag: 40,
        body: b"future packet before a user ID revocation".to_vec(),
    };
    let subkey_unknown = CanonicalPacket {
        tag: 63,
        body: b"private packet before a subkey revocation".to_vec(),
    };
    let mut extended = Vec::new();
    for (index, packet) in stream.packets().iter().enumerate() {
        extended.extend_from_slice(stream.raw(packet));
        if index == user_id_index + 1 {
            user_id_unknown
                .write_to(&mut extended)
                .expect("write unknown user ID packet");
            user_id_revocation
                .write_to(&mut extended)
                .expect("write user ID revocation");
        } else if index == subkey_index + 1 {
            subkey_unknown
                .write_to(&mut extended)
                .expect("write unknown subkey packet");
            subkey_revocation
                .write_to(&mut extended)
                .expect("write subkey revocation");
        }
    }

    let certificate = parse_document(&extended).remove(0);
    let identity = certificate.identity_order.first().expect("fixture user ID");
    let subkey = certificate.subkey_order.first().expect("fixture subkey");
    assert!(
        certificate.identities[identity]
            .values()
            .any(|packet| packet == &user_id_revocation),
    );
    assert!(
        certificate.subkeys[subkey]
            .attached
            .values()
            .any(|packet| packet == &subkey_revocation),
    );
    assert!(certificate.unknowns[&user_id_unknown].is_empty());
    assert!(certificate.unknowns[&subkey_unknown].is_empty());

    let finalized = certificate
        .finalize()
        .expect("finalize revoked certificate");
    assert!(contains_packet_body(&finalized.bytes, &user_id_unknown));
    assert!(contains_packet_body(&finalized.bytes, &subkey_unknown));
    let candidates = certificate_components(&finalized.semantic).collect::<Vec<_>>();
    let policy = validate_certificate(
        &finalized.semantic,
        &candidates,
        2_000_000_000,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate revoked certificate policy");
    assert!(policy.verified_user_ids_for_test().is_empty());
    assert_eq!(policy.subkeys.len(), 1);
    assert!(policy.subkeys[0].revoked);
    assert_canonical_certificate_round_trip(&finalized.bytes);
}

#[test]
fn displaced_subkey_bindings_and_revocations_are_rehomed_to_the_exact_subkey() {
    let secret = generated_v4_two_subkey_secret(0x4741_505f_5355_424b);
    let certificate = certificate_packet_set(&secret);
    assert_eq!(certificate.subkey_order.len(), 2);
    let primary = parse_primary_key(&certificate.primary).expect("parse primary key");
    let first_fingerprint = certificate.subkey_order[0].clone();
    let second_fingerprint = certificate.subkey_order[1].clone();
    let first_subkey = parse_public_subkey(&certificate.subkeys[&first_fingerprint].packet)
        .expect("parse first subkey");
    let second_subkey = parse_public_subkey(&certificate.subkeys[&second_fingerprint].packet)
        .expect("parse second subkey");
    let binding = certificate.subkeys[&first_fingerprint]
        .attached
        .values()
        .find(|packet| {
            parse_signature_packet(packet)
                .is_ok_and(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
        })
        .cloned()
        .expect("first subkey binding");
    let revocation = signed_packet(&secret, SignatureType::SubkeyRevocation, |config| {
        config
            .sign_subkey_binding(
                &secret.primary_key,
                secret.primary_key.public_key(),
                &Password::empty(),
                &first_subkey,
            )
            .expect("sign first-subkey revocation")
    });
    let valid = certificate
        .canonical_bytes()
        .expect("serialize valid two-subkey certificate");

    for (case, signature) in [("binding", binding.clone()), ("revocation", revocation)] {
        let parsed_signature =
            parse_signature_packet(&signature).expect("parse adversarial signature");
        assert!(
            parsed_signature
                .verify_subkey_binding(&primary, &first_subkey)
                .is_ok(),
            "{case} must verify for the first subkey",
        );
        assert!(
            parsed_signature
                .verify_subkey_binding(&primary, &second_subkey)
                .is_err(),
            "{case} must not verify for the later subkey",
        );

        for placement in ["leading gap", "after wrong subkey"] {
            let stream = RawPacketStream::parse(&valid, MAX_MERGE_PACKETS)
                .expect("parse valid two-subkey certificate");
            let mut displaced = Vec::new();
            for (index, packet) in stream.packets().iter().enumerate() {
                if case == "binding"
                    && packet.tag() == SIGNATURE_TAG
                    && stream.body(packet).as_slice() == binding.body
                {
                    continue;
                }
                displaced.extend_from_slice(stream.raw(packet));
                if placement == "leading gap" && index == 0 {
                    signature
                        .write_to(&mut displaced)
                        .expect("write leading-gap signature");
                }
            }
            if placement == "after wrong subkey" {
                signature
                    .write_to(&mut displaced)
                    .expect("write signature after wrong subkey");
            }

            let (once, _) = canonicalize_public_certificate(&displaced)
                .unwrap_or_else(|error| panic!("rehome {placement} {case}: {error}"));
            let (twice, _) = canonicalize_public_certificate(&once)
                .expect("canonicalize rehomed subkey signature again");
            assert_eq!(twice, once, "idempotent {placement} {case}");

            let reparsed = parse_single_certificate_packet_set(&once)
                .expect("parse rehomed subkey certificate");
            assert!(
                reparsed.subkeys[&first_fingerprint]
                    .attached
                    .values()
                    .any(|packet| packet == &signature),
                "first subkey owns {placement} {case}",
            );
            assert!(
                reparsed.subkeys[&second_fingerprint]
                    .attached
                    .values()
                    .all(|packet| packet != &signature),
                "second subkey must not own {placement} {case}",
            );
        }
    }
}

#[test]
fn displaced_unverifiable_and_third_party_certifications_remain_local_and_inert() {
    let stream =
        RawPacketStream::parse(PUBLIC_KEY, MAX_MERGE_PACKETS).expect("parse fixture packet stream");
    let packets = stream
        .packets()
        .iter()
        .map(|packet| CanonicalPacket::from_span(&stream, packet))
        .collect::<Vec<_>>();
    let primary = parse_primary_key(&packets[0]).expect("parse fixture primary key");
    let user_id = parse_user_id(&packets[1]).expect("parse fixture user ID");
    let invalid_self_certification = signature_with_changed_material(&packets[2]);
    let (certifier, _) = SignedSecretKey::from_reader_single(Cursor::new(OTHER_SECRET_KEY))
        .expect("parse external certifier");
    let external_certifications = [
        (
            "unavailable third-party generic certification",
            SignatureType::CertGeneric,
        ),
        (
            "unavailable third-party persona certification",
            SignatureType::CertPersona,
        ),
        (
            "unavailable third-party casual certification",
            SignatureType::CertCasual,
        ),
        (
            "unavailable third-party positive certification",
            SignatureType::CertPositive,
        ),
    ]
    .map(|(case, signature_type)| {
        let signature = signed_packet(&certifier, signature_type, |config| {
            config
                .sign_certification(
                    &certifier.primary_key,
                    &primary,
                    &Password::empty(),
                    Tag::UserId,
                    &user_id,
                )
                .expect("sign external certification")
        });
        parse_signature_packet(&signature)
            .expect("parse external certification")
            .verify_third_party_certification(
                &primary,
                certifier.primary_key.public_key(),
                Tag::UserId,
                &user_id,
            )
            .expect("external certification is cryptographically valid");
        (case, signature)
    });

    let second_identity = CanonicalPacket {
        tag: USER_ID_TAG,
        body: b"Another possible target <other@example.test>".to_vec(),
    };
    let valid = parse_fixture()
        .canonical_bytes()
        .expect("serialize valid recovery certificate");
    for (case, displaced_signature) in
        std::iter::once(("invalid self-certification", invalid_self_certification))
            .chain(external_certifications)
    {
        let mut displaced = Vec::new();
        for packet in [
            &packets[0],
            &displaced_signature,
            &packets[1],
            &packets[2],
            &second_identity,
            &packets[3],
            &packets[4],
        ] {
            packet
                .write_to(&mut displaced)
                .expect("write displaced certification fixture");
        }
        let certificate = parse_single_certificate_packet_set(&displaced)
            .unwrap_or_else(|error| panic!("retain {case} without guessing: {error}"));
        assert!(
            certificate.unknowns.contains_key(&displaced_signature),
            "retain {case} as an opaque component",
        );
        assert!(
            certificate
                .attached_packets()
                .all(|packet| packet != &displaced_signature),
            "do not attach {case} to any certificate component",
        );

        let canonical = certificate
            .finalize()
            .unwrap_or_else(|error| panic!("finalize {case}: {error}"));
        assert!(
            contains_packet_body(&canonical.retained_bytes, &displaced_signature),
            "retain {case} locally",
        );
        assert!(
            contains_packet_body(&canonical.local_public_bytes, &displaced_signature),
            "retain {case} in the local public projection",
        );
        assert!(
            !contains_packet_body(&canonical.bytes, &displaced_signature),
            "omit ownerless {case} from transferable canonical bytes",
        );
        let local_stream = RawPacketStream::parse(&canonical.local_public_bytes, MAX_MERGE_PACKETS)
            .unwrap_or_else(|error| panic!("parse local-public {case}: {error:?}"));
        let unowned_index = local_stream
            .packets()
            .iter()
            .position(|packet| {
                packet.tag() == SIGNATURE_TAG
                    && local_stream.body(packet).as_slice() == displaced_signature.body
            })
            .expect("local-public bytes retain the unowned signature");
        let first_component_index = local_stream
            .packets()
            .iter()
            .position(|packet| {
                matches!(
                    packet.tag(),
                    USER_ID_TAG | USER_ATTRIBUTE_TAG | PUBLIC_SUBKEY_TAG
                )
            })
            .expect("canonical certificate contains a component");
        assert!(
            unowned_index < first_component_index,
            "serialize local {case} before all possible component owners",
        );
        let reparsed = parse_single_certificate_packet_set(&canonical.local_public_bytes)
            .unwrap_or_else(|error| panic!("reparse local-public {case}: {error}"));
        assert!(
            reparsed.unknowns.contains_key(&displaced_signature),
            "local-public reparse keeps {case} unowned",
        );
        assert!(
            reparsed
                .attached_packets()
                .all(|packet| packet != &displaced_signature),
            "local-public reparse does not assign {case}",
        );
        let (twice, _) = canonicalize_public_certificate(&canonical.bytes)
            .unwrap_or_else(|error| panic!("canonicalize {case} twice: {error}"));
        assert_eq!(twice, canonical.bytes, "idempotent canonical {case}");
        let displaced_stream = RawPacketStream::parse(&displaced, MAX_MERGE_PACKETS)
            .expect("parse displaced certificate for framing comparison");
        let mut expected_export = Vec::new();
        for packet in displaced_stream.packets().iter().filter(|packet| {
            (packet.tag() != second_identity.tag
                || displaced_stream.body(packet).as_slice() != second_identity.body)
                && (packet.tag() != displaced_signature.tag
                    || displaced_stream.body(packet).as_slice() != displaced_signature.body)
        }) {
            expected_export.extend_from_slice(displaced_stream.raw(packet));
        }
        assert_eq!(
            export_public_certificate_preserving_framing(&displaced)
                .unwrap_or_else(|error| panic!("preserve framing for {case}: {error}")),
            expected_export,
            "preserve retained packet framing while omitting the unbound identity for {case}",
        );
        let local_preserving = local_public_certificate_preserving_framing(&displaced)
            .unwrap_or_else(|error| panic!("preserve local framing for {case}: {error}"));
        assert!(
            contains_packet_body(&local_preserving, &displaced_signature),
            "local framing-preserving serialization keeps {case}",
        );

        let exported_stream = RawPacketStream::parse(&canonical.bytes, MAX_MERGE_PACKETS)
            .unwrap_or_else(|error| panic!("parse transferable {case}: {error:?}"));
        let mut component_tag = PUBLIC_KEY_TAG;
        for span in exported_stream.packets() {
            if matches!(
                span.tag(),
                PUBLIC_KEY_TAG | USER_ID_TAG | USER_ATTRIBUTE_TAG | PUBLIC_SUBKEY_TAG
            ) {
                component_tag = span.tag();
                continue;
            }
            if span.tag() != SIGNATURE_TAG {
                continue;
            }
            let signature =
                parse_signature_packet(&CanonicalPacket::from_span(&exported_stream, span))
                    .expect("parse exported signature");
            if matches!(
                signature.typ(),
                Some(
                    SignatureType::CertGeneric
                        | SignatureType::CertPersona
                        | SignatureType::CertCasual
                        | SignatureType::CertPositive
                )
            ) {
                assert!(
                    matches!(component_tag, USER_ID_TAG | USER_ATTRIBUTE_TAG),
                    "transferable {case} certification follows its identity",
                );
            }
        }

        let mut keyring_bytes = displaced;
        keyring_bytes.extend_from_slice(&valid);
        let keyring = RawPacketStream::parse(&keyring_bytes, MAX_MERGE_PACKETS)
            .expect("parse recovery keyring");
        let parsed = parse_public_certificate_packet_sets(&keyring)
            .expect("recover later keyring certificate");
        assert_eq!(parsed.skipped_malformed, 0, "do not skip {case}");
        assert_eq!(parsed.certificates.len(), 2, "retain both after {case}");
        assert!(
            parsed.certificates[0]
                .unknowns
                .contains_key(&displaced_signature),
            "keyring import keeps {case} inert",
        );
    }
}

#[cfg(unix)]
#[test]
fn gnupg_imports_transferable_output_without_ownerless_certification() {
    use std::{
        fs,
        os::unix::fs::PermissionsExt,
        process::Command,
        time::{SystemTime, UNIX_EPOCH},
    };

    if !Command::new("gpg")
        .arg("--version")
        .output()
        .is_ok_and(|output| output.status.success())
    {
        return;
    }

    struct GnuPgHome(std::path::PathBuf);

    impl Drop for GnuPgHome {
        fn drop(&mut self) {
            let _ = Command::new("gpgconf")
                .arg("--homedir")
                .arg(&self.0)
                .args(["--kill", "all"])
                .status();
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    let stream =
        RawPacketStream::parse(PUBLIC_KEY, MAX_MERGE_PACKETS).expect("parse fixture packet stream");
    let packets = stream
        .packets()
        .iter()
        .map(|packet| CanonicalPacket::from_span(&stream, packet))
        .collect::<Vec<_>>();
    let ownerless = signature_with_changed_material(&packets[2]);
    let mut displaced = Vec::new();
    for packet in [
        &packets[0],
        &ownerless,
        &packets[1],
        &packets[2],
        &packets[3],
        &packets[4],
    ] {
        packet
            .write_to(&mut displaced)
            .expect("write displaced GnuPG fixture");
    }
    let canonical = parse_single_certificate_packet_set(&displaced)
        .expect("quarantine ownerless GnuPG fixture")
        .finalize()
        .expect("finalize GnuPG fixture");
    assert!(contains_packet_body(
        &canonical.local_public_bytes,
        &ownerless,
    ));
    assert!(!contains_packet_body(&canonical.bytes, &ownerless));

    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("system clock after Unix epoch")
        .as_nanos();
    // GnuPG agent sockets have a short platform path limit. `/tmp` keeps the
    // isolated home below it even when the host's TMPDIR is long.
    let home = GnuPgHome(std::path::Path::new("/tmp").join(format!(
        "kg-gpg-ownerless-cert-{}-{nonce:x}",
        std::process::id(),
    )));
    fs::create_dir(&home.0).expect("create isolated GnuPG home");
    fs::set_permissions(&home.0, fs::Permissions::from_mode(0o700))
        .expect("restrict isolated GnuPG home");
    let agent = Command::new("gpg-connect-agent")
        .arg("--homedir")
        .arg(&home.0)
        .arg("/bye")
        .output();
    if !agent.is_ok_and(|output| output.status.success()) {
        // Sandboxed test runners may provide gpg but prohibit its Unix-domain
        // socket. Deterministic packet tests still cover the wire contract.
        return;
    }

    let certificate_path = home.0.join("transferable.pgp");
    fs::write(&certificate_path, &canonical.bytes).expect("write transferable GnuPG certificate");
    let imported = Command::new("gpg")
        .args(["--batch", "--yes", "--no-tty", "--homedir"])
        .arg(&home.0)
        .arg("--import")
        .arg(&certificate_path)
        .output()
        .expect("run GnuPG certificate import");
    assert!(
        imported.status.success(),
        "GnuPG rejected certificate after ownerless certification omission: {}",
        String::from_utf8_lossy(&imported.stderr),
    );
}

#[test]
fn unowned_certifications_count_against_the_component_limit_after_rehoming() {
    let stream =
        RawPacketStream::parse(PUBLIC_KEY, MAX_MERGE_PACKETS).expect("parse fixture packet stream");
    let packets = stream
        .packets()
        .iter()
        .map(|packet| CanonicalPacket::from_span(&stream, packet))
        .collect::<Vec<_>>();
    let certificate_with_unowned = |count: usize| {
        let mut certificate = Vec::new();
        packets[0]
            .write_to(&mut certificate)
            .expect("write fixture primary key");
        for marker in 0..count {
            signature_with_hashed_experimental(
                &packets[2],
                u16::try_from(marker).expect("component limit fits in u16"),
            )
            .write_to(&mut certificate)
            .expect("write distinct unowned certification");
        }
        for packet in [&packets[1], &packets[3], &packets[4]] {
            packet
                .write_to(&mut certificate)
                .expect("write fixture component");
        }
        certificate
    };

    // The fixture has one subkey, and the primary itself also consumes a
    // component slot. The remaining slots may hold opaque signatures.
    let at_limit = certificate_with_unowned(MAX_COMPONENTS - 2);
    let parsed = parse_single_certificate_packet_set(&at_limit)
        .expect("accept exactly the component limit after rehoming");
    assert_eq!(parsed.unknowns.len(), MAX_COMPONENTS - 2);

    let over_limit = certificate_with_unowned(MAX_COMPONENTS - 1);
    assert_eq!(
        parse_single_certificate_packet_set(&over_limit),
        Err(CertificateMergeError::ResourceLimit),
    );
}

#[test]
fn signature_rehoming_has_a_fixed_verification_budget() {
    let mut budget = SignatureRehomingBudget::default();
    for _ in 0..MAX_SIGNATURE_REHOMING_VERIFICATIONS {
        assert!(budget.verify(|| true).expect("verification within cap"));
    }
    assert!(matches!(
        budget.verify(|| true),
        Err(CertificateMergeError::ResourceLimit)
    ));
}

#[test]
fn signature_rehoming_request_limit_cannot_multiply_across_certificates() {
    let mut budget = SignatureRehomingBudget::default();
    for marker in [1_u8, 2] {
        budget.begin_certificate(&FingerprintKey {
            version: 4,
            bytes: vec![marker; 20],
        });
        for _ in 0..MAX_SIGNATURE_REHOMING_VERIFICATIONS {
            assert!(!budget.verify(|| false).expect("verification within cap"));
        }
    }
    assert_eq!(
        budget.request_verifications,
        MAX_SIGNATURE_REHOMING_VERIFICATIONS_PER_REQUEST,
    );

    budget.begin_certificate(&FingerprintKey {
        version: 4,
        bytes: vec![3; 20],
    });
    assert!(matches!(
        budget.verify(|| false),
        Err(CertificateMergeError::ResourceLimit)
    ));
    assert_eq!(budget.verifications, 0);
}

#[test]
fn export_classification_has_a_fixed_verification_budget() {
    let mut budget = ExportClassificationBudget::default();
    budget.begin_certificate(&FingerprintKey {
        version: 4,
        bytes: vec![1; 20],
    });
    for _ in 0..MAX_EXPORT_CLASSIFICATION_VERIFICATIONS {
        assert!(budget.verify(|| true).expect("verification within cap"));
    }
    assert!(matches!(
        budget.verify(|| true),
        Err(CertificateMergeError::ResourceLimit)
    ));
}

#[test]
fn export_classification_request_limit_cannot_multiply_across_certificates() {
    let mut budget = ExportClassificationBudget::default();
    for marker in [1_u8, 2] {
        budget.begin_certificate(&FingerprintKey {
            version: 4,
            bytes: vec![marker; 20],
        });
        for _ in 0..MAX_EXPORT_CLASSIFICATION_VERIFICATIONS {
            assert!(!budget.verify(|| false).expect("verification within cap"));
        }
    }
    assert_eq!(
        budget.request_verifications,
        MAX_EXPORT_CLASSIFICATION_VERIFICATIONS_PER_REQUEST,
    );

    budget.begin_certificate(&FingerprintKey {
        version: 4,
        bytes: vec![3; 20],
    });
    assert!(matches!(
        budget.verify(|| false),
        Err(CertificateMergeError::ResourceLimit)
    ));
    assert_eq!(budget.verifications, 0);
}

#[test]
fn exhausted_request_rehoming_budget_rejects_the_next_certificate_pipeline() {
    let mut budget = SignatureRehomingBudget::default();
    for marker in [1_u8, 2] {
        budget.begin_certificate(&FingerprintKey {
            version: 4,
            bytes: vec![marker; 20],
        });
        for _ in 0..MAX_SIGNATURE_REHOMING_VERIFICATIONS {
            budget
                .verify(|| false)
                .expect("fill request rehoming allowance");
        }
    }
    let stream =
        RawPacketStream::parse(PUBLIC_KEY, MAX_MERGE_PACKETS).expect("parse certificate framing");

    assert!(matches!(
        parse_public_certificate_packet_sets_with_budget(&stream, &mut budget),
        Err(CertificateMergeError::ResourceLimit),
    ));
}

#[test]
fn two_subkey_revocation_round_trip_is_idempotent_and_keeps_its_owner() {
    let secret = generated_v4_two_subkey_secret(0x524f_554e_4454_5249);
    let mut certificate = certificate_packet_set(&secret);
    assert_eq!(certificate.subkey_order.len(), 2);
    let first_fingerprint = certificate.subkey_order[0].clone();
    let second_fingerprint = certificate.subkey_order[1].clone();
    let first_subkey = parse_public_subkey(&certificate.subkeys[&first_fingerprint].packet)
        .expect("parse first subkey");
    let revocation = signed_packet(&secret, SignatureType::SubkeyRevocation, |config| {
        config
            .sign_subkey_binding(
                &secret.primary_key,
                secret.primary_key.public_key(),
                &Password::empty(),
                &first_subkey,
            )
            .expect("sign first-subkey revocation")
    });
    certificate
        .subkeys
        .get_mut(&first_fingerprint)
        .expect("first subkey bundle")
        .attached
        .insert(revocation.clone())
        .expect("attach first-subkey revocation");

    let encoded = certificate
        .canonical_bytes()
        .expect("serialize revoked two-subkey certificate");
    let (once, _) = canonicalize_public_certificate(&encoded)
        .expect("canonicalize revoked two-subkey certificate");
    let (twice, _) = canonicalize_public_certificate(&once)
        .expect("canonicalize revoked two-subkey certificate again");
    assert_eq!(once, encoded);
    assert_eq!(twice, once);
    assert_canonical_certificate_round_trip(&twice);

    let reparsed = parse_single_certificate_packet_set(&twice)
        .expect("parse canonical revoked two-subkey certificate");
    assert!(
        reparsed.subkeys[&first_fingerprint]
            .attached
            .values()
            .any(|packet| packet == &revocation),
    );
    assert!(
        reparsed.subkeys[&second_fingerprint]
            .attached
            .values()
            .all(|packet| packet != &revocation),
    );

    let finalized = reparsed.finalize().expect("finalize reparsed certificate");
    let candidates = certificate_components(&finalized.semantic).collect::<Vec<_>>();
    let policy = validate_certificate(
        &finalized.semantic,
        &candidates,
        2_000_000_000,
        &mut OpenPgpPolicyBudget::default(),
    )
    .expect("evaluate revoked two-subkey certificate");
    assert_eq!(policy.subkeys.len(), 2);
    assert!(policy.subkeys[0].revoked);
    assert!(!policy.subkeys[1].revoked);
}

#[test]
fn merge_deduplicates_ordinary_documents_but_keeps_request_work_bounded() {
    let stream =
        RawPacketStream::parse(PUBLIC_KEY, MAX_MERGE_PACKETS).expect("parse fixture packet stream");
    let packets = stream.packets().len();
    let signatures = parse_fixture().signature_count();
    let copies = (MAX_MERGE_PACKETS / packets).max(MAX_SIGNATURES / signatures) + 1;

    let (canonical, _) = canonicalize_public_certificate(PUBLIC_KEY).expect("canonicalize fixture");
    let (merged, _) = merge_public_certificate_documents(&vec![PUBLIC_KEY; 64])
        .expect("ordinary duplicate keyserver documents remain usable");
    assert_eq!(merged, canonical);

    // Deduplication still happens before retained-shape accounting, but it
    // must not erase the cryptographic work already spent parsing thousands
    // of attacker-supplied copies of the same certificate.
    assert!(matches!(
        merge_public_certificate_documents(&vec![PUBLIC_KEY; copies]),
        Err(CertificateMergeError::ResourceLimit),
    ));
}

#[test]
fn unknown_components_participate_in_component_limits() {
    let mut certificate = parse_fixture();
    let known_components = certificate.subkeys.len() + 1;
    for index in known_components..MAX_COMPONENTS {
        insert_unknown_component(
            &mut certificate,
            CanonicalPacket {
                tag: 40,
                body: index.to_be_bytes().to_vec(),
            },
        );
    }
    certificate
        .validate_shape()
        .expect("maximum component count is accepted");

    insert_unknown_component(
        &mut certificate,
        CanonicalPacket {
            tag: 40,
            body: MAX_COMPONENTS.to_be_bytes().to_vec(),
        },
    );
    assert_eq!(
        certificate.validate_shape(),
        Err(CertificateMergeError::ResourceLimit),
    );
}

#[test]
fn packet_set_merge_routes_legacy_key_id_collision_by_full_fingerprint() {
    // Producing two real v4 keys with the same 64-bit Key ID would require
    // impractical collision work. Exercise the merge invariant directly:
    // distinct full fingerprints with identical legacy-Key-ID suffixes
    // must remain independently addressable.
    let mut certificate = parse_fixture();
    let first_fingerprint = certificate
        .subkey_order
        .first()
        .cloned()
        .expect("fixture subkey fingerprint");
    assert_eq!(first_fingerprint.version, 4);
    assert!(first_fingerprint.bytes.len() > 8);
    let first_component = certificate
        .subkeys
        .get(&first_fingerprint)
        .cloned()
        .expect("fixture subkey component");

    let mut colliding_fingerprint = first_fingerprint.clone();
    colliding_fingerprint.bytes[0] ^= 0x80;
    assert_ne!(colliding_fingerprint, first_fingerprint);
    assert_eq!(
        &colliding_fingerprint.bytes[colliding_fingerprint.bytes.len() - 8..],
        &first_fingerprint.bytes[first_fingerprint.bytes.len() - 8..],
    );

    let mut colliding_component = first_component.clone();
    colliding_component.packet.body.push(0);
    let mut fragment = certificate.empty_shell();
    fragment.subkey_order.push(colliding_fingerprint.clone());
    fragment
        .subkeys
        .insert(colliding_fingerprint.clone(), colliding_component.clone());

    assert!(certificate.merge(fragment).expect("merge colliding Key ID"));
    assert_eq!(
        certificate
            .subkeys
            .get(&first_fingerprint)
            .map(|component| &component.packet),
        Some(&first_component.packet),
    );
    assert_eq!(
        certificate
            .subkeys
            .get(&colliding_fingerprint)
            .map(|component| &component.packet),
        Some(&colliding_component.packet),
    );
}

#[test]
fn raw_certificate_merge_is_commutative_idempotent_and_associative() {
    let certificate = parse_fixture();
    let expected = merge(vec![certificate.clone()]);
    let (direct, identities, subkeys) = split_three_ways(&certificate);

    assert_eq!(
        merge(vec![direct.clone(), identities.clone(), subkeys.clone()]),
        expected,
    );
    assert_eq!(
        merge(vec![subkeys.clone(), identities.clone(), direct.clone()]),
        expected,
    );
    assert_eq!(
        merge(vec![certificate.clone(), certificate.clone()]),
        expected,
    );

    let direct_identities =
        parse_document(&merge(vec![direct.clone(), identities.clone()])).remove(0);
    let identities_subkeys = parse_document(&merge(vec![identities, subkeys.clone()])).remove(0);
    assert_eq!(
        merge(vec![direct_identities, subkeys]),
        merge(vec![direct, identities_subkeys]),
    );
}

#[test]
fn deterministic_material_merge_is_independent_of_subkey_input_order() {
    let secret = generated_v4_two_subkey_secret(0x4445_5445_524d_4f52);
    let ordered = certificate_packet_set(&secret);
    assert_eq!(ordered.subkey_order.len(), 2);

    let ordered_document = ordered
        .clone()
        .finalize()
        .expect("finalize ordered certificate")
        .retained_bytes;
    let mut reversed = ordered;
    reversed.subkey_order.reverse();
    let reversed_document = reversed
        .finalize()
        .expect("finalize reversed certificate")
        .retained_bytes;
    assert_ne!(ordered_document, reversed_document);

    let ordered_first = merge_public_certificate_material_documents_deterministic(&[
        &ordered_document,
        &reversed_document,
    ])
    .expect("merge ordered then reversed");
    let reversed_first = merge_public_certificate_material_documents_deterministic(&[
        &reversed_document,
        &ordered_document,
    ])
    .expect("merge reversed then ordered");

    assert_eq!(ordered_first.bytes, reversed_first.bytes);
    assert_eq!(
        ordered_first.local_public_bytes,
        reversed_first.local_public_bytes,
    );
    assert_eq!(ordered_first.retained_bytes, reversed_first.retained_bytes);
}

#[test]
fn normalized_signature_variants_retain_one_complete_wire_packet() {
    let original = first_signature_packet(&parse_fixture());
    let first = signature_with_unhashed_experimental(&original, 1, 2);
    let second = signature_with_unhashed_experimental(&original, 2, 2);
    assert_ne!(first, second);
    assert_eq!(attached_packet_key(&first), attached_packet_key(&second));

    let mut attached = AttachedPackets::default();
    attached
        .insert(first.clone())
        .expect("insert first variant");
    assert_eq!(attached.values().next(), Some(&first));
    attached
        .insert(second.clone())
        .expect("insert second variant");

    assert_eq!(signature_count(&attached), 1);
    assert_eq!(
        attached.values().next(),
        Some(std::cmp::min(&first, &second)),
    );
}

#[test]
fn prepared_signature_entry_reuses_the_parsed_value_through_merge() {
    let packet = first_signature_packet(&parse_fixture());
    let signature = Arc::new(parse_signature_packet(&packet).expect("parse fixture signature"));
    let expected_key = attached_packet_key(&packet).expect("normalize fixture signature");

    let mut prepared = AttachedPackets::default();
    prepared
        .insert_signature(
            packet.clone(),
            signature.clone(),
            SignatureVariantQuality::Unknown,
        )
        .expect("insert prepared signature");
    let (key, entry) = prepared.entries().next().expect("prepared entry");
    assert_eq!(key, &expected_key);
    assert_eq!(entry.creation_time, signature_creation_time(&signature));
    assert!(Arc::ptr_eq(
        entry.signature.as_ref().expect("cached signature"),
        &signature,
    ));

    let mut merged = AttachedPackets::default();
    assert!(merged.merge(prepared).expect("merge prepared entry"));
    let (key, entry) = merged.entries().next().expect("merged entry");
    assert_eq!(key, &expected_key);
    assert_eq!(&entry.packet, &packet);
    assert!(Arc::ptr_eq(
        entry.signature.as_ref().expect("moved cached signature"),
        &signature,
    ));

    let mut parsed_on_insert = AttachedPackets::default();
    parsed_on_insert
        .insert(packet)
        .expect("insert signature through compatibility path");
    assert_eq!(merged, parsed_on_insert);
}

#[test]
fn normalized_signature_variants_never_synthesize_issuer_metadata() {
    let secret = generated_uniform_version_secret(KeyVersion::V4, 0x4953_5355_4552_5634);
    let certificate = certificate_packet_set(&secret);
    let (_, original) = first_verified_user_id_signature(&certificate);
    let first = signature_with_unhashed_experimental(&original, 1, 2);
    let second = signature_with_unhashed_experimental(&original, 2, 2);
    let agreed = attached_from([first.clone(), second.clone()]);
    assert_eq!(agreed.values().next(), Some(std::cmp::min(&first, &second)),);

    let conflicting = signature_with_unhashed_subpackets(
        &second,
        [
            Subpacket::regular(SubpacketData::IssuerKeyId(KeyId::from([0xa5; 8])))
                .expect("build conflicting issuer Key ID"),
            Subpacket::regular(SubpacketData::IssuerFingerprint(Fingerprint::V6(
                [0x5a; 32],
            )))
            .expect("build conflicting issuer fingerprint"),
        ],
    );
    let merged = attached_from([first.clone(), conflicting.clone()]);
    assert_eq!(
        attached_from([conflicting.clone(), first.clone()]),
        merged,
        "complete-variant selection stays commutative",
    );
    assert_eq!(
        merged.values().next(),
        Some(std::cmp::min(&first, &conflicting)),
        "the retained packet must be one of the supplied packets",
    );
}

#[test]
fn v6_duplicate_with_forbidden_unhashed_issuer_key_id_cannot_poison_merge() {
    let secret = generated_uniform_version_secret(KeyVersion::V6, 0x4953_5355_4552_5636);
    let public = secret
        .to_public_key()
        .to_bytes()
        .expect("serialize v6 issuer-merge fixture");
    let certificate = parse_document(&public).remove(0);
    let primary = parse_primary_key(&certificate.primary).expect("parse v6 primary key");
    let (identity, genuine) = first_verified_user_id_signature(&certificate);
    let poisoned = signature_with_unhashed_subpackets(
        &genuine,
        [Subpacket::regular(SubpacketData::IssuerKeyId(
            secret.primary_key.legacy_key_id(),
        ))
        .expect("build forbidden v6 issuer Key ID")],
    );
    let poisoned_signature = parse_signature_packet(&poisoned).expect("parse poisoned v6 copy");
    assert_eq!(poisoned_signature.version(), SignatureVersion::V6);
    assert!(
        poisoned_signature
            .verify_certification(&primary, Tag::UserId, &RawIdentityBody(&identity.body))
            .is_ok(),
        "unhashed mutation leaves the cryptographic signature valid",
    );
    assert_eq!(
        attached_packet_key(&poisoned),
        attached_packet_key(&genuine)
    );

    let poisoned_document = replace_signature_variant(&public, &genuine, &poisoned);
    for documents in [
        [public.as_slice(), poisoned_document.as_slice()],
        [poisoned_document.as_slice(), public.as_slice()],
    ] {
        let canonical = merge_public_certificate_material_documents(&documents)
            .expect("merge genuine and poisoned v6 variants");
        let reparsed = parse_document(&canonical.retained_bytes).remove(0);
        let retained = reparsed
            .identities
            .get(&identity)
            .expect("retain v6 User ID")
            .values()
            .find(|packet| attached_packet_key(packet) == attached_packet_key(&genuine))
            .expect("retain one normalized certification");
        assert_eq!(retained, std::cmp::min(&genuine, &poisoned));

        let candidates = certificate_components(&canonical.semantic).collect::<Vec<_>>();
        let policy = validate_certificate(
            &canonical.semantic,
            &candidates,
            2_000_000_000,
            &mut OpenPgpPolicyBudget::default(),
        )
        .expect("evaluate merged v6 certificate policy");
        assert_eq!(policy.verified_user_ids_for_test().len(), 1);
    }
}

#[test]
fn normalized_signature_identity_retains_hashed_and_cryptographic_differences() {
    let original = first_signature_packet(&parse_fixture());
    let unhashed = signature_with_unhashed_experimental(&original, 1, 2);
    let digest_prefix = signature_with_digest_prefix(&original, [0xa5, 0x5a]);
    let hashed = signature_with_hashed_experimental(&original, 1);
    let changed_material = signature_with_changed_material(&original);

    let attached = attached_from([original, unhashed, digest_prefix, hashed, changed_material]);
    assert_eq!(signature_count(&attached), 3);
}

#[test]
fn v4_digest_prefix_flood_keeps_valid_signature_after_bad_variants() {
    let secret = generated_uniform_version_secret(KeyVersion::V4, 0x5634_5052_4546_4958);
    let public = secret
        .to_public_key()
        .to_bytes()
        .expect("serialize v4 flood fixture");
    let certificate = parse_document(&public).remove(0);
    let baseline = certificate
        .clone()
        .finalize()
        .expect("finalize v4 flood baseline");
    let (identity, genuine) = first_verified_user_id_signature(&certificate);
    assert_eq!(
        parse_signature_packet(&genuine)
            .expect("parse v4 certification")
            .version(),
        SignatureVersion::V4,
    );
    let variants = wrong_digest_prefixes(&genuine)
        .into_iter()
        .map(|prefix| signature_with_digest_prefix(&genuine, prefix));
    let flooded = replace_signature_with_flood(&public, &genuine, variants);

    assert_flooded_certificate_is_usable(&flooded, &identity, &genuine, baseline.signature_count);
}

#[test]
fn v6_digest_prefix_flood_keeps_valid_signature_after_bad_variants() {
    let secret = generated_uniform_version_secret(KeyVersion::V6, 0x5636_5052_4546_4958);
    let public = secret
        .to_public_key()
        .to_bytes()
        .expect("serialize v6 flood fixture");
    let certificate = parse_document(&public).remove(0);
    let baseline = certificate
        .clone()
        .finalize()
        .expect("finalize v6 flood baseline");
    let (identity, genuine) = first_verified_user_id_signature(&certificate);
    assert_eq!(
        parse_signature_packet(&genuine)
            .expect("parse v6 certification")
            .version(),
        SignatureVersion::V6,
    );
    let variants = wrong_digest_prefixes(&genuine)
        .into_iter()
        .map(|prefix| signature_with_digest_prefix(&genuine, prefix));
    let flooded = replace_signature_with_flood(&public, &genuine, variants);

    assert_flooded_certificate_is_usable(&flooded, &identity, &genuine, baseline.signature_count);
}

#[test]
fn v3_unhashed_field_flood_keeps_valid_signature_after_bad_variants() {
    // V3 elliptic-curve signatures are forbidden by RFC 9580 sections
    // 5.2.3.2 through 5.2.3.5. Use RSA for the valid V3 control so this flood
    // regression continues to exercise mutable V3 fields without relying on
    // a signature shape that production policy must reject.
    let secret = SecretKeyParamsBuilder::default()
        .version(KeyVersion::V4)
        .key_type(KeyType::Rsa(2_048))
        .can_certify(true)
        .can_sign(true)
        .created_at(Timestamp::from_secs(1_782_541_300))
        .primary_user_id("V3 RSA flood <v3-rsa-flood@example.test>".to_owned())
        .passphrase(None)
        .build()
        .expect("build V3 RSA flood certificate")
        .generate(StdRng::seed_from_u64(0x5633_5253_4146_4c44))
        .expect("generate V3 RSA flood certificate");
    let source = certificate_packet_set(&secret);
    let identity = source
        .identity_order
        .iter()
        .find(|identity| identity.tag == USER_ID_TAG)
        .cloned()
        .expect("fixture User ID");
    let genuine = v3_user_id_certification(&secret, &identity);
    assert_eq!(
        parse_signature_packet(&genuine)
            .expect("parse v3 certification")
            .version(),
        SignatureVersion::V3,
    );
    let mut minimal = Vec::new();
    source
        .primary
        .write_to(&mut minimal)
        .expect("write v3 fixture primary");
    identity
        .write_to(&mut minimal)
        .expect("write v3 fixture User ID");
    genuine
        .write_to(&mut minimal)
        .expect("write v3 fixture certification");

    let genuine_prefix = parse_signature_packet(&genuine)
        .expect("parse genuine v3 certification")
        .signed_hash_value()
        .expect("genuine v3 digest prefix");
    let variants =
        wrong_digest_prefixes(&genuine)
            .into_iter()
            .enumerate()
            .map(|(index, prefix)| {
                // Put a correct-issuer/bad-prefix copy first, then include
                // wrong-issuer/correct-prefix copies.  The remainder
                // varies both fields, so neither mutable V3 field can
                // split the identity or poison the genuine final copy.
                let issuer = if index == 0 {
                    secret.primary_key.legacy_key_id()
                } else {
                    let issuer = KeyId::from((index as u64 + 1).to_be_bytes());
                    assert_ne!(issuer, secret.primary_key.legacy_key_id());
                    issuer
                };
                let prefix = if index != 0 && index % 2 == 0 {
                    genuine_prefix
                } else {
                    prefix
                };
                signature_with_v3_unhashed_fields(&genuine, issuer, prefix)
            });
    let flooded = replace_signature_with_flood(&minimal, &genuine, variants);

    assert_eq!(
        attached_packet_key(&signature_with_v3_unhashed_fields(
            &genuine,
            KeyId::from([0xa5; 8]),
            [0x5a; 2],
        )),
        attached_packet_key(&genuine),
    );
    assert_flooded_certificate_is_usable(&flooded, &identity, &genuine, 1);
}

#[test]
fn normalized_signature_variant_selection_is_algebraic() {
    let original = first_signature_packet(&parse_fixture());
    let first = signature_with_unhashed_experimental(&original, 1, 30_000);
    let second = signature_with_unhashed_experimental(&original, 2, 30_000);
    let third = signature_with_unhashed_experimental(&original, 3, 30_000);

    let expected = attached_from([first.clone(), second.clone(), third.clone()]);
    assert_eq!(
        attached_from([third.clone(), first.clone(), second.clone()]),
        expected,
    );
    let duplicate = attached_from([first.clone(), first.clone()]);
    assert_eq!(duplicate, attached_from([first.clone()]));

    let mut left = attached_from([first.clone(), second.clone()]);
    left.merge(attached_from([third.clone()]))
        .expect("left-associated merge");
    let mut right = attached_from([first.clone()]);
    right
        .merge(attached_from([second.clone(), third.clone()]))
        .expect("right-associated merge");
    assert_eq!(left, right);
    assert_eq!(left, expected);

    assert_eq!(
        left.values().next(),
        Some(std::cmp::min(std::cmp::min(&first, &second), &third,)),
    );
}

#[test]
fn normalized_signature_flood_collapses_before_per_object_quota() {
    let stream =
        RawPacketStream::parse(PUBLIC_KEY, MAX_MERGE_PACKETS).expect("parse fixture packet stream");
    let target_index = stream
        .packets()
        .iter()
        .position(|packet| packet.tag() == SIGNATURE_TAG)
        .expect("fixture contains a signature");
    let target = CanonicalPacket::from_span(&stream, &stream.packets()[target_index]);
    let variants = (0..MAX_SIGNATURES_PER_OBJECT)
        .map(|marker| {
            signature_with_unhashed_experimental(
                &target,
                u16::try_from(marker).expect("bounded marker"),
                2,
            )
        })
        .collect::<Vec<_>>();
    let mut flooded = Vec::new();
    for (index, packet) in stream.packets().iter().enumerate() {
        if index == target_index {
            for variant in &variants {
                variant
                    .write_to(&mut flooded)
                    .expect("write signature variant");
            }
        } else {
            flooded.extend_from_slice(stream.raw(packet));
        }
    }

    let (merged, _) = merge_public_certificate_documents(&[PUBLIC_KEY, &flooded])
        .expect("normalized variants do not exhaust the signature quota");
    let merged_certificate = parse_document(&merged).remove(0);
    assert_eq!(
        merged_certificate.signature_count(),
        parse_fixture().signature_count(),
    );
}

#[test]
fn raw_certificate_merge_retains_unknown_noncritical_packets() {
    let certificate = parse_fixture();
    let mut extended = certificate.clone();
    insert_unknown_component(
        &mut extended,
        CanonicalPacket {
            tag: 40,
            body: b"future transferable evidence".to_vec(),
        },
    );

    let merged = merge(vec![certificate, extended]);
    let stream = RawPacketStream::parse(&merged, 8 * 1024).expect("parse merged packets");
    let retained = stream
        .packets()
        .iter()
        .find(|packet| packet.tag() == 40)
        .expect("retain unknown noncritical packet");
    assert_eq!(
        stream.body(retained).as_slice(),
        b"future transferable evidence",
    );
}

#[test]
fn per_object_signature_quota_is_inclusive() {
    let original = first_signature_packet(&parse_fixture());
    let variants = (0..=MAX_SIGNATURES_PER_OBJECT)
        .map(|marker| {
            signature_with_hashed_experimental(
                &original,
                u16::try_from(marker).expect("bounded marker"),
            )
        })
        .collect::<Vec<_>>();
    // Hashed variants are cryptographically distinct, so none of them
    // collapse into one another the way unhashed evidence does.
    let mut certificate = parse_fixture();
    let identity = certificate
        .identity_order
        .first()
        .cloned()
        .expect("fixture user ID");
    *certificate
        .identities
        .get_mut(&identity)
        .expect("fixture user ID bundle") =
        attached_from(variants.iter().take(MAX_SIGNATURES_PER_OBJECT).cloned());
    assert_eq!(certificate.validate_shape(), Ok(()));

    certificate
        .identities
        .get_mut(&identity)
        .expect("fixture user ID bundle")
        .insert(variants[MAX_SIGNATURES_PER_OBJECT].clone())
        .expect("insert one packet past the quota");
    assert_eq!(
        certificate.validate_shape(),
        Err(CertificateMergeError::ResourceLimit),
    );
}

#[test]
fn raw_certificate_merge_drops_marker_packets_and_retains_padding() {
    let mut certificate = parse_fixture();
    certificate
        .direct
        .insert(CanonicalPacket {
            tag: PADDING_TAG,
            body: vec![0xa5; 8],
        })
        .expect("insert padding");

    let mut with_marker = certificate.canonical_bytes().expect("serialize fixture");
    CanonicalPacket {
        tag: MARKER_TAG,
        body: b"PGP".to_vec(),
    }
    .write_to(&mut with_marker)
    .expect("append marker packet");

    let merged = merge(parse_document(&with_marker));
    let stream = RawPacketStream::parse(&merged, 8 * 1024).expect("parse merged packets");
    // RFC 9580 §5.8: Marker packets carry no information and must be
    // ignored, so nothing retains them.
    assert!(
        stream
            .packets()
            .iter()
            .all(|packet| packet.tag() != MARKER_TAG)
    );
    assert!(
        stream
            .packets()
            .iter()
            .any(|packet| packet.tag() == PADDING_TAG)
    );
    assert!(parse_document(&merged).remove(0).unknowns.is_empty());
}

#[test]
fn leading_marker_and_trust_packets_are_ignored_instead_of_failing_the_document() {
    let mut document = Vec::new();
    CanonicalPacket {
        tag: MARKER_TAG,
        body: b"PGP".to_vec(),
    }
    .write_to(&mut document)
    .expect("write leading marker");
    let stream =
        RawPacketStream::parse(PUBLIC_KEY, MAX_MERGE_PACKETS).expect("parse fixture packet stream");
    for (index, packet) in stream.packets().iter().enumerate() {
        document.extend_from_slice(stream.raw(packet));
        if index == 0 {
            CanonicalPacket {
                tag: TRUST_TAG,
                body: vec![0x03, 0x00],
            }
            .write_to(&mut document)
            .expect("write interleaved trust packet");
        }
    }

    let (canonical, fingerprint) = canonicalize_public_certificate(&document)
        .expect("marker and trust packets must not fail the document");
    assert_eq!(fingerprint, parse_fixture().fingerprint.upper_hex());
    let reparsed = RawPacketStream::parse(&canonical, MAX_MERGE_PACKETS)
        .expect("parse canonicalized document");
    assert!(
        reparsed
            .packets()
            .iter()
            .all(|packet| !matches!(packet.tag(), MARKER_TAG | TRUST_TAG))
    );
    assert_eq!(
        canonical,
        canonicalize_public_certificate(PUBLIC_KEY)
            .expect("canonicalize fixture")
            .0,
    );
}

#[test]
fn exact_marker_bodies_are_ignored_with_old_and_new_packet_headers() {
    let old_leading = public_document_with_marker(&old_format_marker_packet(b"PGP"), true);
    let new_interior = public_document_with_marker(&marker_packet(b"PGP"), false);

    for (case, document) in [
        ("old-format leading Marker", old_leading),
        ("new-format interior Marker", new_interior),
    ] {
        let certificate = parse_single_certificate_packet_set(&document)
            .unwrap_or_else(|error| panic!("accept {case}: {error}"));
        assert_eq!(
            certificate.fingerprint,
            parse_fixture().fingerprint,
            "{case}"
        );
    }
}

#[test]
fn malformed_marker_bodies_fail_closed_at_leading_and_interior_boundaries() {
    for (case, body) in [
        ("empty", b"".as_slice()),
        ("short", b"PG".as_slice()),
        ("long", b"PGPX".as_slice()),
        ("wrong byte", b"PGX".as_slice()),
    ] {
        for leading in [true, false] {
            let document = public_document_with_marker(&marker_packet(body), leading);
            RawPacketStream::parse(&document, MAX_MERGE_PACKETS)
                .unwrap_or_else(|error| panic!("raw framing must accept {case} Marker: {error:?}"));
            assert_eq!(
                parse_single_certificate_packet_set(&document),
                Err(CertificateMergeError::Malformed),
                "reject {case} Marker at {} placement",
                if leading { "leading" } else { "interior" },
            );
        }
    }
}

#[test]
fn armored_certificate_marker_validation_uses_the_decoded_packet_body() {
    let valid = public_document_with_marker(&old_format_marker_packet(b"PGP"), true);
    parse_single_certificate_packet_set(&armor_public_packets(&valid))
        .expect("accept exact Marker in armored certificate");

    let invalid = public_document_with_marker(&marker_packet(b"PGX"), false);
    assert_eq!(
        parse_single_certificate_packet_set(&armor_public_packets(&invalid)),
        Err(CertificateMergeError::Malformed),
    );
}

#[test]
fn malformed_marker_keyring_entries_do_not_hide_later_valid_certificates() {
    let marker = marker_packet(b"PGX");
    let valid = RawPacketStream::parse(PUBLIC_KEY, MAX_MERGE_PACKETS)
        .expect("parse public fixture packet stream")
        .bytes()
        .to_vec();
    let cases = [
        (
            "leading malformed sequence",
            public_document_with_marker(&marker, true),
        ),
        ("malformed first certificate", {
            let mut keyring = public_document_with_marker(&marker, false);
            keyring.extend_from_slice(&valid);
            keyring
        }),
    ];

    for (case, keyring) in cases {
        let stream = RawPacketStream::parse(&keyring, MAX_MERGE_PACKETS)
            .unwrap_or_else(|error| panic!("scan {case}: {error:?}"));
        let parsed = parse_public_certificate_packet_sets(&stream)
            .unwrap_or_else(|error| panic!("recover {case}: {error}"));
        assert_eq!(parsed.skipped_malformed, 1, "{case}");
        assert_eq!(parsed.certificates.len(), 1, "{case}");
        assert_eq!(
            parsed.certificates[0].fingerprint,
            parse_fixture().fingerprint
        );
    }
}

#[test]
fn single_certificate_parser_rejects_unrelated_leading_and_trailing_packets() {
    let certificate = parse_fixture()
        .canonical_bytes()
        .expect("serialize fixture certificate");
    let literal = CanonicalPacket {
        tag: 11,
        body: vec![b'b', 0, 0, 0, 0, 0, b'x'],
    };

    let mut leading = Vec::new();
    literal
        .write_to(&mut leading)
        .expect("write leading literal packet");
    leading.extend_from_slice(&certificate);
    assert_eq!(
        canonicalize_public_certificate(&leading),
        Err(CertificateMergeError::Malformed),
    );

    let mut trailing = certificate;
    literal
        .write_to(&mut trailing)
        .expect("write trailing literal packet");
    assert_eq!(
        canonicalize_public_certificate(&trailing),
        Err(CertificateMergeError::Malformed),
    );
}

#[test]
fn inappropriate_packet_taints_the_whole_certificate_before_the_next_primary() {
    let literal = CanonicalPacket {
        tag: 11,
        body: vec![b'b', 0, 0, 0, 0, 0, b'x'],
    };
    let keyring = damaged_revoked_certificate_keyring(&literal);
    let stream = RawPacketStream::parse(&keyring, MAX_MERGE_PACKETS)
        .expect("scan framed inappropriate packet");

    let parsed = parse_public_certificate_packet_sets(&stream)
        .expect("later independent certificate remains recoverable");

    assert_eq!(parsed.skipped_malformed, 1);
    assert_eq!(parsed.certificates.len(), 1);
    assert_eq!(
        parsed.certificates[0].fingerprint,
        parse_fixture().fingerprint
    );
}

#[test]
fn unsupported_certificates_are_skipped_without_failing_their_document() {
    // Old-format Public-Key packet with a structurally valid v3 RSA body.
    let v3_public_key = [
        0x98, 0x0f, 0x03, 0, 0, 0, 0, 0, 0, 0x01, 0, 12, 0x0c, 0xa1, 0, 5, 0x11,
    ];
    let supported = RawPacketStream::parse(PUBLIC_KEY, MAX_MERGE_PACKETS)
        .expect("parse fixture packet stream")
        .bytes()
        .to_vec();
    let mut document = v3_public_key.to_vec();
    document.extend_from_slice(&supported);

    let stream =
        RawPacketStream::parse(&document, MAX_MERGE_PACKETS).expect("parse mixed document");
    let parsed = parse_public_certificate_packet_sets(&stream).expect("parse mixed document");
    assert_eq!(parsed.skipped_unsupported, 1);
    assert_eq!(parsed.certificates.len(), 1);
    assert_eq!(
        parsed.certificates[0].fingerprint,
        parse_fixture().fingerprint,
    );
}

#[test]
fn sensitive_revoker_declarations_are_local_while_ordinary_declarations_transfer() {
    let target = parse_secret(SECRET_KEY);
    let revoker = parse_secret(OTHER_SECRET_KEY);
    let sensitive =
        designated_revoker_declaration(&target, &revoker, RevocationKeyClass::Sensitive);
    let ordinary = designated_revoker_declaration(&target, &revoker, RevocationKeyClass::Default);

    let mut sensitive_certificate = parse_fixture();
    sensitive_certificate
        .direct
        .insert(sensitive.clone())
        .expect("attach sensitive declaration");
    let sensitive_canonical = sensitive_certificate
        .finalize()
        .expect("finalize sensitive certificate");
    assert!(contains_packet_body(
        &sensitive_canonical.retained_bytes,
        &sensitive,
    ));
    assert!(
        sensitive_canonical
            .semantic
            .details
            .direct_signatures
            .iter()
            .filter_map(Signature::config)
            .flat_map(|config| config.hashed_subpackets())
            .any(|subpacket| matches!(
                &subpacket.data,
                SubpacketData::RevocationKey(revoker)
                    if revoker.class == RevocationKeyClass::Sensitive
            ))
    );
    assert!(!contains_packet_body(
        &sensitive_canonical.bytes,
        &sensitive,
    ));
    assert!(!contains_packet_body(
        &raw_transferable_bytes(&sensitive_certificate),
        &sensitive,
    ));
    assert_canonical_certificate_round_trip(&sensitive_canonical.bytes);

    let mut ordinary_certificate = parse_fixture();
    ordinary_certificate
        .direct
        .insert(ordinary.clone())
        .expect("attach ordinary declaration");
    let ordinary_canonical = ordinary_certificate
        .finalize()
        .expect("finalize ordinary certificate");
    assert!(contains_packet_body(&ordinary_canonical.bytes, &ordinary));
    assert!(contains_packet_body(
        &raw_transferable_bytes(&ordinary_certificate),
        &ordinary,
    ));
    assert_canonical_certificate_round_trip(&ordinary_canonical.bytes);
}

#[test]
fn unhashed_issuer_hints_never_reveal_a_sensitive_revoker_declaration() {
    let target = parse_secret(SECRET_KEY);
    let revoker = parse_secret(OTHER_SECRET_KEY);
    let declaration = signed_packet(&target, SignatureType::Key, |mut config| {
        config.hashed_subpackets.push(revocation_key_subpacket(
            &revoker,
            RevocationKeyClass::Sensitive,
        ));
        config
            .sign_key(
                &target.primary_key,
                &Password::empty(),
                target.primary_key.public_key(),
            )
            .expect("sign sensitive declaration without issuer metadata")
    });
    let declaration_signature =
        parse_signature_packet(&declaration).expect("parse sensitive declaration");
    declaration_signature
        .verify_key(target.primary_key.public_key())
        .expect("declaration without issuer metadata is genuine");
    let correct_key_id = target.primary_key.legacy_key_id();
    let mutated_key_id = revoker.primary_key.legacy_key_id();
    assert_ne!(correct_key_id, mutated_key_id);

    let cases = [
        ("missing", Vec::new()),
        (
            "correct",
            vec![
                Subpacket::regular(SubpacketData::IssuerKeyId(correct_key_id))
                    .expect("correct unhashed issuer key ID"),
            ],
        ),
        (
            "mutated",
            vec![
                Subpacket::regular(SubpacketData::IssuerKeyId(mutated_key_id))
                    .expect("mutated unhashed issuer key ID"),
            ],
        ),
        (
            "conflicting",
            vec![
                Subpacket::regular(SubpacketData::IssuerKeyId(correct_key_id))
                    .expect("correct unhashed issuer key ID"),
                Subpacket::regular(SubpacketData::IssuerKeyId(mutated_key_id))
                    .expect("conflicting unhashed issuer key ID"),
            ],
        ),
    ];

    for (case, issuer_subpackets) in cases {
        let candidate = signature_with_unhashed_subpackets(&declaration, issuer_subpackets);
        let candidate_signature = parse_signature_packet(&candidate)
            .expect("parse declaration with unhashed issuer metadata");
        assert_eq!(
            candidate_signature.signed_hash_value(),
            declaration_signature.signed_hash_value(),
            "signed hash prefix: {case}",
        );
        assert_eq!(
            candidate_signature.signature(),
            declaration_signature.signature(),
            "signature material: {case}",
        );
        let mut certificate = parse_fixture();
        certificate
            .direct
            .insert(candidate.clone())
            .expect("attach sensitive declaration");

        assert_packet_export_status(&certificate, &candidate, false, case);
    }
}

#[test]
fn unhashed_v6_issuer_key_id_does_not_reveal_a_sensitive_revoker_declaration() {
    let target = generated_v6_secret(0x5636_4558_504f_5254);
    let revoker = parse_secret(OTHER_SECRET_KEY);
    let declaration = v6_designated_revoker_declaration(&target, &revoker);
    let malformed = signature_with_unhashed_subpackets(
        &declaration,
        [Subpacket::regular(SubpacketData::IssuerKeyId(
            target.primary_key.legacy_key_id(),
        ))
        .expect("RFC-forbidden unhashed v6 issuer key ID")],
    );
    let signature = parse_signature_packet(&malformed).expect("parse malformed declaration");
    assert_eq!(signature.version(), SignatureVersion::V6);
    signature
        .verify_key(target.primary_key.public_key())
        .expect("unhashed v6 issuer key ID does not change signed material");
    let mut certificate = certificate_packet_set(&target);
    certificate
        .direct
        .insert(malformed.clone())
        .expect("attach malformed sensitive declaration");

    assert_packet_export_status(
        &certificate,
        &malformed,
        false,
        "RFC-forbidden unhashed v6 issuer key ID",
    );
}

#[test]
fn hashed_sensitive_revocation_key_is_local_despite_invalid_issuer_constraints() {
    let target = parse_secret(SECRET_KEY);
    let revoker = parse_secret(OTHER_SECRET_KEY);
    let correct_fingerprint = target.primary_key.fingerprint();
    let mismatched_fingerprint = revoker.primary_key.fingerprint();
    let mismatched_key_id = revoker.primary_key.legacy_key_id();
    let cases = [
        (
            "mismatched fingerprint",
            vec![
                Subpacket::regular(SubpacketData::IssuerFingerprint(
                    mismatched_fingerprint.clone(),
                ))
                .expect("mismatched hashed issuer fingerprint"),
            ],
        ),
        (
            "conflicting constraints",
            vec![
                Subpacket::regular(SubpacketData::IssuerFingerprint(
                    correct_fingerprint.clone(),
                ))
                .expect("correct hashed issuer fingerprint"),
                Subpacket::regular(SubpacketData::IssuerKeyId(mismatched_key_id))
                    .expect("conflicting hashed issuer key ID"),
            ],
        ),
    ];

    for (case, issuer_subpackets) in cases {
        let declaration = signed_packet(&target, SignatureType::Key, |mut config| {
            config.hashed_subpackets.extend(issuer_subpackets);
            config.hashed_subpackets.push(revocation_key_subpacket(
                &revoker,
                RevocationKeyClass::Sensitive,
            ));
            config
                .sign_key(
                    &target.primary_key,
                    &Password::empty(),
                    target.primary_key.public_key(),
                )
                .expect("sign declaration with invalid issuer constraints")
        });
        let mut certificate = parse_fixture();
        certificate
            .direct
            .insert(declaration.clone())
            .expect("attach declaration with invalid issuer constraints");

        assert_packet_export_status(&certificate, &declaration, false, case);
    }
}

#[test]
fn hashed_sensitive_revocation_key_is_local_despite_key_version_mismatch() {
    let target = parse_secret(SECRET_KEY);
    let revoker = parse_secret(OTHER_SECRET_KEY);
    let source = signed_packet(&target, SignatureType::Key, |mut config| {
        config.hashed_subpackets.push(revocation_key_subpacket(
            &revoker,
            RevocationKeyClass::Sensitive,
        ));
        config
            .sign_key(
                &target.primary_key,
                &Password::empty(),
                target.primary_key.public_key(),
            )
            .expect("sign source v4 declaration")
    });
    let signature = parse_signature_packet(&source).expect("parse source declaration");
    let mut config = signature
        .config()
        .cloned()
        .expect("source signature config");
    config.version_specific = SignatureConfig::v6(
        StdRng::seed_from_u64(0x5636_5645_5253_494f),
        SignatureType::Key,
        target.primary_key.algorithm(),
        HashAlgorithm::Sha256,
    )
    .expect("v6 signature config")
    .version_specific;
    let declaration = CanonicalPacket {
        tag: SIGNATURE_TAG,
        body: rebuild_signature_body(&signature, config)
            .expect("rebuild declaration with mismatched signature version"),
    };
    let signature = parse_signature_packet(&declaration).expect("parse version mismatch");
    assert_eq!(signature.version(), SignatureVersion::V6);
    assert_eq!(target.primary_key.version(), KeyVersion::V4);
    let mut certificate = parse_fixture();
    certificate
        .direct
        .insert(declaration.clone())
        .expect("attach version-mismatched declaration");

    assert_packet_export_status(
        &certificate,
        &declaration,
        false,
        "v6 signature with v4 primary",
    );
}

#[test]
fn forged_signature_with_hashed_sensitive_revocation_key_is_local() {
    let target = parse_secret(SECRET_KEY);
    let revoker = parse_secret(OTHER_SECRET_KEY);
    let genuine = designated_revoker_declaration(&target, &revoker, RevocationKeyClass::Sensitive);
    let forged = signature_with_changed_material(&genuine);
    assert!(
        parse_signature_packet(&forged)
            .expect("parse forged declaration")
            .verify_key(target.primary_key.public_key())
            .is_err()
    );
    let mut certificate = parse_fixture();
    certificate
        .direct
        .insert(forged.clone())
        .expect("attach forged declaration");

    assert_packet_export_status(&certificate, &forged, false, "cryptographic forgery");
}

#[test]
fn hashed_sensitive_revocation_key_on_user_id_certification_is_local() {
    let target = parse_secret(SECRET_KEY);
    let revoker = parse_secret(OTHER_SECRET_KEY);
    let mut certificate = parse_fixture();
    let identity = certificate
        .identity_order
        .first()
        .cloned()
        .expect("fixture user ID");
    let user_id = parse_user_id(&identity).expect("parse fixture user ID");
    let certification = signed_packet(&target, SignatureType::CertPositive, |mut config| {
        config.hashed_subpackets.push(revocation_key_subpacket(
            &revoker,
            RevocationKeyClass::Sensitive,
        ));
        config
            .sign_certification(
                &target.primary_key,
                target.primary_key.public_key(),
                &Password::empty(),
                Tag::UserId,
                &user_id,
            )
            .expect("sign user ID certification")
    });
    let ordinary_direct = signed_packet(&target, SignatureType::Key, |config| {
        config
            .sign_key(
                &target.primary_key,
                &Password::empty(),
                target.primary_key.public_key(),
            )
            .expect("sign ordinary Direct Key signature")
    });
    certificate.direct = attached_from([ordinary_direct.clone()]);
    *certificate
        .identities
        .get_mut(&identity)
        .expect("fixture user ID bundle") = attached_from([certification.clone()]);

    assert_packet_export_status(&certificate, &certification, false, "user ID certification");
    let canonical = certificate.finalize().expect("finalize certificate");
    assert!(contains_packet_body(&canonical.bytes, &ordinary_direct));
    assert!(!contains_packet_body(&canonical.bytes, &identity));
    assert_canonical_certificate_round_trip(&canonical.bytes);
}

#[test]
fn hashed_sensitive_revocation_key_on_subkey_binding_is_local() {
    let target = parse_secret(SECRET_KEY);
    let revoker = parse_secret(OTHER_SECRET_KEY);
    let subkey = target
        .secret_subkeys
        .first()
        .expect("fixture secret subkey")
        .key
        .public_key()
        .clone();
    let binding = signed_packet(&target, SignatureType::SubkeyBinding, |mut config| {
        config.hashed_subpackets.push(revocation_key_subpacket(
            &revoker,
            RevocationKeyClass::Sensitive,
        ));
        config
            .sign_subkey_binding(
                &target.primary_key,
                target.primary_key.public_key(),
                &Password::empty(),
                &subkey,
            )
            .expect("sign subkey binding")
    });
    let fingerprint = FingerprintKey::from_key(&subkey).expect("subkey fingerprint");
    let mut certificate = parse_fixture();
    certificate
        .subkeys
        .get_mut(&fingerprint)
        .expect("fixture public subkey")
        .attached = attached_from([binding.clone()]);

    assert_packet_export_status(&certificate, &binding, false, "subkey binding");
    let canonical = certificate.finalize().expect("finalize certificate");
    assert!(!contains_packet_body(
        &canonical.bytes,
        &certificate.subkeys[&fingerprint].packet,
    ));
    assert_canonical_certificate_round_trip(&canonical.bytes);
}

#[test]
fn hashed_sensitive_revocation_key_on_third_party_direct_signature_is_local() {
    let target = parse_secret(SECRET_KEY);
    let third_party = parse_secret(OTHER_SECRET_KEY);
    let direct = signed_packet(&third_party, SignatureType::Key, |mut config| {
        config.hashed_subpackets.extend([
            Subpacket::regular(SubpacketData::IssuerFingerprint(
                third_party.primary_key.fingerprint(),
            ))
            .expect("third-party issuer fingerprint"),
            revocation_key_subpacket(&third_party, RevocationKeyClass::Sensitive),
        ]);
        config
            .sign_key(
                &third_party.primary_key,
                &Password::empty(),
                target.primary_key.public_key(),
            )
            .expect("sign third-party Direct Key signature")
    });
    parse_signature_packet(&direct)
        .expect("parse third-party Direct Key signature")
        .verify_key_third_party(
            target.primary_key.public_key(),
            third_party.primary_key.public_key(),
        )
        .expect("third-party Direct Key signature is mathematically valid");
    let mut certificate = parse_fixture();
    certificate
        .direct
        .insert(direct.clone())
        .expect("attach third-party Direct Key signature");

    assert_packet_export_status(
        &certificate,
        &direct,
        false,
        "third-party Direct Key signature",
    );
    let canonical = certificate.finalize().expect("finalize certificate");
    assert_canonical_certificate_round_trip(&canonical.bytes);
}

#[test]
fn unhashed_sensitive_revocation_key_does_not_restrict_transferable_export() {
    let target = parse_secret(SECRET_KEY);
    let revoker = parse_secret(OTHER_SECRET_KEY);
    let direct = signed_packet(&target, SignatureType::Key, |config| {
        config
            .sign_key(
                &target.primary_key,
                &Password::empty(),
                target.primary_key.public_key(),
            )
            .expect("sign ordinary Direct Key signature")
    });
    let direct = signature_with_unhashed_subpackets(
        &direct,
        [revocation_key_subpacket(
            &revoker,
            RevocationKeyClass::Sensitive,
        )],
    );
    let signature = parse_signature_packet(&direct).expect("parse Direct Key signature");
    let config = signature.config().expect("signature config");
    assert!(!signature_has_sensitive_revocation_key(&signature));
    assert!(config.unhashed_subpackets.iter().any(|subpacket| {
        matches!(&subpacket.data, SubpacketData::RevocationKey(revoker) if {
            revoker.class == RevocationKeyClass::Sensitive
        })
    }));

    let mut certificate = parse_fixture();
    certificate
        .direct
        .insert(direct.clone())
        .expect("attach Direct Key signature");

    assert_packet_export_status(
        &certificate,
        &direct,
        true,
        "unhashed sensitive Revocation Key",
    );
}

#[test]
fn authenticated_matching_designated_revocation_exports_its_sensitive_declaration() {
    let target = parse_secret(SECRET_KEY);
    let revoker = parse_secret(OTHER_SECRET_KEY);
    let unrelated_revoker = generated_v4_two_subkey_secret(0x554e_5245_4c41_5445);
    let declaration =
        designated_revoker_declaration(&target, &revoker, RevocationKeyClass::Sensitive);
    let unrelated_declaration =
        designated_revoker_declaration(&target, &unrelated_revoker, RevocationKeyClass::Sensitive);
    let revocation = key_revocation(&target, &revoker);
    parse_signature_packet(&revocation)
        .expect("parse designated revocation")
        .verify_key_third_party(
            target.primary_key.public_key(),
            revoker.primary_key.public_key(),
        )
        .expect("designated revocation is mathematically valid");

    let mut certificate = parse_fixture();
    certificate
        .direct
        .insert(declaration.clone())
        .expect("attach sensitive declaration");
    certificate
        .direct
        .insert(unrelated_declaration.clone())
        .expect("attach unrelated sensitive declaration");
    certificate
        .direct
        .insert(revocation.clone())
        .expect("attach designated revocation");

    let candidates = vec![
        PublicComponent::Primary(revoker.primary_key.public_key().clone()),
        PublicComponent::Primary(unrelated_revoker.primary_key.public_key().clone()),
    ];
    let canonical = certificate
        .finalize_with_revocation_candidates(&candidates)
        .expect("finalize revoked certificate");
    assert!(contains_packet_body(
        &canonical.retained_bytes,
        &declaration
    ));
    assert!(contains_packet_body(&canonical.bytes, &declaration));
    assert!(!contains_packet_body(
        &canonical.bytes,
        &unrelated_declaration,
    ));
    assert!(contains_packet_body(&canonical.bytes, &revocation));
    let raw = raw_transferable_bytes_with_candidates(&certificate, &candidates);
    assert!(contains_packet_body(&raw, &declaration));
    assert!(!contains_packet_body(&raw, &unrelated_declaration));
    assert!(contains_packet_body(&raw, &revocation));
}

#[test]
fn forged_matching_issuer_hint_does_not_reveal_a_sensitive_declaration() {
    let target = parse_secret(SECRET_KEY);
    let revoker = parse_secret(OTHER_SECRET_KEY);
    let declaration =
        designated_revoker_declaration(&target, &revoker, RevocationKeyClass::Sensitive);
    let forged_revocation = signature_with_changed_material(&key_revocation(&target, &revoker));
    assert!(
        parse_signature_packet(&forged_revocation)
            .expect("parse forged designated revocation")
            .verify_key_third_party(
                target.primary_key.public_key(),
                revoker.primary_key.public_key(),
            )
            .is_err()
    );

    let mut certificate = parse_fixture();
    certificate
        .direct
        .insert(declaration.clone())
        .expect("attach sensitive declaration");
    certificate
        .direct
        .insert(forged_revocation.clone())
        .expect("attach forged designated revocation");

    let candidates = vec![PublicComponent::Primary(
        revoker.primary_key.public_key().clone(),
    )];
    let canonical = certificate
        .finalize_with_revocation_candidates(&candidates)
        .expect("finalize certificate with forged revocation");
    assert!(!contains_packet_body(&canonical.bytes, &declaration));
    assert!(contains_packet_body(&canonical.bytes, &forged_revocation,));
    let raw = raw_transferable_bytes_with_candidates(&certificate, &candidates);
    assert!(!contains_packet_body(&raw, &declaration));
    assert!(contains_packet_body(&raw, &forged_revocation));
}

#[test]
fn mismatched_designated_revocation_does_not_reveal_a_sensitive_declaration() {
    let target = parse_secret(SECRET_KEY);
    let revoker = parse_secret(OTHER_SECRET_KEY);
    let other_revoker = generated_v4_two_subkey_secret(0x4d49_534d_4154_4348);
    let declaration =
        designated_revoker_declaration(&target, &revoker, RevocationKeyClass::Sensitive);
    let mismatched_revocation = signature_with_unhashed_subpackets(
        &key_revocation(&target, &other_revoker),
        [Subpacket::regular(SubpacketData::IssuerKeyId(
            revoker.primary_key.legacy_key_id(),
        ))
        .expect("forged matching issuer key ID")],
    );
    parse_signature_packet(&mismatched_revocation)
        .expect("parse mismatched revocation")
        .verify_key_third_party(
            target.primary_key.public_key(),
            other_revoker.primary_key.public_key(),
        )
        .expect("unhashed issuer forgery preserves the real signature");

    let mut certificate = parse_fixture();
    certificate
        .direct
        .insert(declaration.clone())
        .expect("attach sensitive declaration");
    certificate
        .direct
        .insert(mismatched_revocation.clone())
        .expect("attach mismatched revocation");
    let candidates = vec![
        PublicComponent::Primary(revoker.primary_key.public_key().clone()),
        PublicComponent::Primary(other_revoker.primary_key.public_key().clone()),
    ];

    let canonical = certificate
        .finalize_with_revocation_candidates(&candidates)
        .expect("finalize certificate with mismatched revocation");
    assert!(!contains_packet_body(&canonical.bytes, &declaration));
    assert!(contains_packet_body(
        &canonical.bytes,
        &mismatched_revocation,
    ));
    let raw = raw_transferable_bytes_with_candidates(&certificate, &candidates);
    assert!(!contains_packet_body(&raw, &declaration));
    assert!(contains_packet_body(&raw, &mismatched_revocation));
}

#[test]
fn unrelated_revocation_does_not_reveal_a_sensitive_declaration() {
    let target = parse_secret(SECRET_KEY);
    let revoker = parse_secret(OTHER_SECRET_KEY);
    let declaration =
        designated_revoker_declaration(&target, &revoker, RevocationKeyClass::Sensitive);
    let self_revocation = key_revocation(&target, &target);

    let mut certificate = parse_fixture();
    certificate
        .direct
        .insert(declaration.clone())
        .expect("attach sensitive declaration");
    certificate
        .direct
        .insert(self_revocation.clone())
        .expect("attach unrelated revocation");

    let candidates = vec![
        PublicComponent::Primary(revoker.primary_key.public_key().clone()),
        PublicComponent::Primary(target.primary_key.public_key().clone()),
    ];
    let canonical = certificate
        .finalize_with_revocation_candidates(&candidates)
        .expect("finalize self-revoked certificate");
    assert!(!contains_packet_body(&canonical.bytes, &declaration));
    assert!(contains_packet_body(&canonical.bytes, &self_revocation));
    let raw = raw_transferable_bytes_with_candidates(&certificate, &candidates);
    assert!(!contains_packet_body(&raw, &declaration));
    assert!(contains_packet_body(&raw, &self_revocation));
}

#[test]
fn v4_bare_primary_key_is_transferable() {
    let secret = generated_uniform_version_secret(KeyVersion::V4, 0x5634_4241_5245_4b45);
    let mut certificate = certificate_packet_set(&secret);
    certificate.direct = AttachedPackets::default();
    certificate.identities.clear();
    certificate.identity_order.clear();
    certificate.subkeys.clear();
    certificate.subkey_order.clear();
    certificate.unknowns.clear();
    certificate.unknown_order.clear();

    let canonical = certificate
        .finalize()
        .expect("finalize bare v4 certificate");
    let raw = raw_transferable_bytes(&certificate);
    let preserving = export_public_certificate_preserving_framing(&canonical.retained_bytes)
        .expect("export bare v4 certificate");

    for (case, exported) in [
        ("canonical", canonical.bytes.as_slice()),
        ("raw", raw.as_slice()),
        ("framing preserving", preserving.as_slice()),
    ] {
        let stream = RawPacketStream::parse(exported, MAX_MERGE_PACKETS)
            .unwrap_or_else(|error| panic!("parse {case} bare v4 certificate: {error:?}"));
        assert_eq!(
            stream
                .packets()
                .iter()
                .map(RawPacketSpan::tag)
                .collect::<Vec<_>>(),
            vec![PUBLIC_KEY_TAG],
            "{case} bare v4 certificate",
        );
        assert!(contains_packet_body(exported, &certificate.primary));
    }
}

#[test]
fn v6_identity_self_certification_without_direct_key_signature_is_not_transferable() {
    let secret = generated_v6_secret(0x5636_4944_454e_5449);
    let mut certificate = certificate_packet_set(&secret);
    let identity = certificate
        .identity_order
        .first()
        .cloned()
        .expect("generated v6 User ID");
    let certification = certificate.identities[&identity]
        .values()
        .find(|packet| {
            parse_signature_packet(packet).is_ok_and(|signature| {
                matches!(
                    signature.typ(),
                    Some(
                        SignatureType::CertGeneric
                            | SignatureType::CertPersona
                            | SignatureType::CertCasual
                            | SignatureType::CertPositive
                    )
                )
            })
        })
        .cloned()
        .expect("generated v6 identity self-certification");
    let primary = parse_primary_key(&certificate.primary).expect("parse v6 primary key");
    assert!(
        is_exportable_identity_self_signature(
            &certification,
            &identity,
            &primary,
            &BTreeSet::new(),
            &mut ExportClassificationBudget::default(),
        )
        .expect("classify v6 identity self-certification"),
    );

    certificate.direct = AttachedPackets::default();
    let canonical = certificate
        .finalize()
        .expect("finalize identity-only v6 certificate");

    assert!(contains_packet_body(&canonical.retained_bytes, &identity));
    assert!(contains_packet_body(
        &canonical.retained_bytes,
        &certification,
    ));
    assert!(canonical.bytes.is_empty());
    assert!(raw_transferable_bytes(&certificate).is_empty());
    assert_eq!(
        export_public_certificate_preserving_framing(&canonical.retained_bytes),
        Ok(Vec::new()),
    );
}

#[test]
fn v6_valid_direct_key_signature_makes_ordinary_certificate_transferable() {
    let secret = generated_v6_secret(0x5636_4449_5245_4354);
    let mut certificate = certificate_packet_set(&secret);
    let direct = certificate
        .direct
        .values()
        .find(|packet| {
            parse_signature_packet(packet)
                .is_ok_and(|signature| signature.typ() == Some(SignatureType::Key))
        })
        .cloned()
        .expect("generated v6 Direct Key self-signature");
    let direct_signature = parse_signature_packet(&direct).expect("parse v6 Direct Key signature");
    let direct = CanonicalPacket {
        tag: SIGNATURE_TAG,
        body: mark_local(
            direct_signature
                .config()
                .cloned()
                .expect("v6 Direct Key signature config"),
        )
        .sign_key(
            &secret.primary_key,
            &Password::empty(),
            secret.primary_key.public_key(),
        )
        .expect("re-sign v6 Direct Key signature with inapplicable local flag")
        .to_bytes()
        .expect("serialize flagged v6 Direct Key signature"),
    };
    assert!(!is_non_exportable_signature(&direct));
    certificate.direct = attached_from([direct.clone()]);

    let canonical = certificate
        .finalize()
        .expect("finalize ordinary v6 certificate");

    assert!(contains_packet_body(&canonical.bytes, &certificate.primary));
    assert!(contains_packet_body(&canonical.bytes, &direct));
    for identity in &certificate.identity_order {
        assert!(contains_packet_body(&canonical.bytes, identity));
    }
    assert_eq!(raw_transferable_bytes(&certificate), canonical.bytes);
    assert_eq!(
        export_public_certificate_preserving_framing(&canonical.retained_bytes),
        Ok(canonical.bytes.clone()),
    );
    assert_canonical_certificate_round_trip(&canonical.bytes);
}

#[test]
fn v6_primary_and_key_revocation_only_is_transferable() {
    let secret = generated_v6_secret(0x5636_5245_564f_4e4c);
    let mut certificate = certificate_packet_set(&secret);
    let revocation = v6_key_revocation(&secret, 0x5636_5245_564f_5347);
    certificate.direct = attached_from([revocation.clone()]);
    certificate.identities.clear();
    certificate.identity_order.clear();
    certificate.subkeys.clear();
    certificate.subkey_order.clear();
    certificate.unknowns.clear();
    certificate.unknown_order.clear();

    let canonical = certificate
        .finalize()
        .expect("finalize v6 revocation certificate");
    let stream = RawPacketStream::parse(&canonical.bytes, MAX_MERGE_PACKETS)
        .expect("parse v6 revocation certificate");

    assert_eq!(
        stream
            .packets()
            .iter()
            .map(RawPacketSpan::tag)
            .collect::<Vec<_>>(),
        vec![PUBLIC_KEY_TAG, SIGNATURE_TAG],
    );
    assert!(contains_packet_body(&canonical.bytes, &certificate.primary));
    assert!(contains_packet_body(&canonical.bytes, &revocation));
    assert_eq!(raw_transferable_bytes(&certificate), canonical.bytes);
    assert_eq!(
        export_public_certificate_preserving_framing(&canonical.retained_bytes),
        Ok(canonical.bytes.clone()),
    );
    assert_canonical_certificate_round_trip(&canonical.bytes);
}

#[test]
fn v6_key_revocation_without_direct_signature_does_not_admit_identity_mixture() {
    let secret = generated_v6_secret(0x5636_5245_564d_4958);
    let mut certificate = certificate_packet_set(&secret);
    let revocation = v6_key_revocation(&secret, 0x5636_5245_564d_5347);
    let identity = certificate
        .identity_order
        .first()
        .cloned()
        .expect("generated v6 User ID");
    certificate.direct = attached_from([revocation.clone()]);

    let canonical = certificate
        .finalize()
        .expect("finalize malformed v6 revocation mixture");

    assert!(contains_packet_body(&canonical.retained_bytes, &revocation,));
    assert!(contains_packet_body(&canonical.retained_bytes, &identity));
    assert!(canonical.bytes.is_empty());
    assert!(raw_transferable_bytes(&certificate).is_empty());
    assert_eq!(
        export_public_certificate_preserving_framing(&canonical.retained_bytes),
        Ok(Vec::new()),
    );
}

fn mark_local(mut config: SignatureConfig) -> SignatureConfig {
    config.hashed_subpackets.push(
        Subpacket::critical(SubpacketData::ExportableCertification(false))
            .expect("build critical non-exportable subpacket"),
    );
    config
}

fn assert_subkey_export_requires_binding(version: KeyVersion, seed: u64) {
    let secret = generated_uniform_version_secret(version, seed);
    let certificate = certificate_packet_set(&secret);
    let primary = parse_primary_key(&certificate.primary).expect("parse primary key");
    let fingerprint = certificate
        .subkey_order
        .first()
        .cloned()
        .expect("generated subkey");
    let subkey_packet = certificate.subkeys[&fingerprint].packet.clone();
    let subkey = parse_public_subkey(&subkey_packet).expect("parse generated subkey");
    let binding = subkey_self_signature(
        &secret,
        &subkey,
        SignatureType::SubkeyBinding,
        seed ^ 0x4249_4e44_494e_4700,
        false,
    );
    let revocation = subkey_self_signature(
        &secret,
        &subkey,
        SignatureType::SubkeyRevocation,
        seed ^ 0x5245_564f_4b45_0000,
        false,
    );
    let local_binding = subkey_self_signature(
        &secret,
        &subkey,
        SignatureType::SubkeyBinding,
        seed ^ 0x4c4f_4341_4c00_0000,
        true,
    );

    assert!(
        is_exportable_subkey_binding_signature(
            &binding,
            &subkey,
            &primary,
            &BTreeSet::new(),
            &mut ExportClassificationBudget::default(),
        )
        .expect("classify valid subkey binding"),
    );
    assert!(
        !is_exportable_subkey_binding_signature(
            &revocation,
            &subkey,
            &primary,
            &BTreeSet::new(),
            &mut ExportClassificationBudget::default(),
        )
        .expect("classify valid subkey revocation"),
    );
    assert!(
        is_exportable_subkey_binding_signature(
            &local_binding,
            &subkey,
            &primary,
            &BTreeSet::new(),
            &mut ExportClassificationBudget::default(),
        )
        .expect("classify binding with inapplicable local flag"),
    );

    let mut revocation_only = certificate.clone();
    revocation_only
        .subkeys
        .get_mut(&fingerprint)
        .expect("generated subkey bundle")
        .attached = attached_from([revocation.clone()]);
    let canonical = revocation_only
        .finalize()
        .expect("finalize revocation-only subkey certificate");
    for retained in [&subkey_packet, &revocation] {
        assert!(contains_packet_body(&canonical.retained_bytes, retained));
        assert!(contains_packet_body(
            &canonical.local_public_bytes,
            retained
        ));
        assert!(!contains_packet_body(&canonical.bytes, retained));
    }
    let raw = export_public_certificate_preserving_framing(&canonical.retained_bytes)
        .expect("export revocation-only subkey certificate");
    assert_eq!(raw, canonical.bytes);
    for withheld in [&subkey_packet, &revocation] {
        assert!(!contains_packet_body(&raw, withheld));
    }
    assert_canonical_certificate_round_trip(&canonical.bytes);

    let mut bound_and_revoked = certificate.clone();
    bound_and_revoked
        .subkeys
        .get_mut(&fingerprint)
        .expect("generated subkey bundle")
        .attached = attached_from([binding.clone(), revocation.clone()]);
    let canonical = bound_and_revoked
        .finalize()
        .expect("finalize bound and revoked subkey certificate");
    let raw = export_public_certificate_preserving_framing(&canonical.retained_bytes)
        .expect("export bound and revoked subkey certificate");
    assert_eq!(raw, canonical.bytes);
    for exported in [&canonical.bytes, &raw] {
        for retained in [&subkey_packet, &binding, &revocation] {
            assert!(contains_packet_body(exported, retained));
        }
        assert_eq!(
            signature_types_after(exported, &subkey_packet),
            vec![
                SignatureType::SubkeyRevocation,
                SignatureType::SubkeyBinding,
            ],
        );
    }
    assert_canonical_certificate_round_trip(&canonical.bytes);

    let mut flagged_binding_and_revocation = certificate;
    flagged_binding_and_revocation
        .subkeys
        .get_mut(&fingerprint)
        .expect("generated subkey bundle")
        .attached = attached_from([local_binding.clone(), revocation.clone()]);
    let canonical = flagged_binding_and_revocation
        .finalize()
        .expect("finalize flagged-binding subkey certificate");
    for retained in [&subkey_packet, &local_binding, &revocation] {
        assert!(contains_packet_body(&canonical.retained_bytes, retained));
    }
    assert!(contains_packet_body(
        &canonical.local_public_bytes,
        &subkey_packet,
    ));
    assert!(contains_packet_body(
        &canonical.local_public_bytes,
        &revocation,
    ));
    assert!(contains_packet_body(
        &canonical.local_public_bytes,
        &local_binding,
    ));
    let raw = export_public_certificate_preserving_framing(&canonical.retained_bytes)
        .expect("export flagged-binding subkey certificate");
    assert_eq!(raw, canonical.bytes);
    for exported in [&canonical.bytes, &raw] {
        for retained in [&subkey_packet, &local_binding, &revocation] {
            assert!(contains_packet_body(exported, retained));
        }
    }
    assert_canonical_certificate_round_trip(&canonical.bytes);
}

#[test]
fn v4_subkey_export_requires_an_exportable_binding() {
    assert_subkey_export_requires_binding(KeyVersion::V4, 0x5634_5355_424b_4559);
}

#[test]
fn v6_subkey_export_requires_an_exportable_binding() {
    assert_subkey_export_requires_binding(KeyVersion::V6, 0x5636_5355_424b_4559);
}

#[test]
fn signing_subkey_transferable_export_requires_a_valid_back_signature() {
    let secret = generated_v4_signing_subkey_secret(0x4241_434b_5349_4701);
    let wrong_signer = generated_v4_signing_subkey_secret(0x4241_434b_5349_4702);
    let valid = test_primary_key_binding(&secret, &secret);
    let corrupt = {
        let packet = CanonicalPacket {
            tag: SIGNATURE_TAG,
            body: valid.to_bytes().expect("serialize valid backsig"),
        };
        parse_signature_packet(&signature_with_changed_material(&packet))
            .expect("parse corrupt backsig")
    };
    let wrong_key = test_primary_key_binding(&secret, &wrong_signer);
    let wrong_type = test_embedded_binding(&secret, &secret, SignatureType::SubkeyBinding);
    let cases = [
        ("valid hashed backsig", Some((valid.clone(), true)), true),
        ("valid unhashed backsig", Some((valid.clone(), false)), true),
        ("missing backsig", None, false),
        ("corrupt backsig", Some((corrupt, true)), false),
        ("wrong-key backsig", Some((wrong_key, true)), false),
        (
            "wrong-type embedded signature",
            Some((wrong_type, true)),
            false,
        ),
    ];

    for (case, embedded, expected_exported) in cases {
        let mut certificate = certificate_packet_set(&secret);
        let fingerprint = certificate
            .subkey_order
            .first()
            .cloned()
            .expect("generated signing subkey");
        let subkey_packet = certificate.subkeys[&fingerprint].packet.clone();
        let binding = test_subkey_binding(&secret, Some(signing_flags()), None, embedded);
        certificate
            .subkeys
            .get_mut(&fingerprint)
            .expect("generated signing subkey bundle")
            .attached = attached_from([binding.clone()]);

        let canonical = certificate.finalize().expect("finalize test certificate");
        for retained in [&subkey_packet, &binding] {
            assert!(
                contains_packet_body(&canonical.retained_bytes, retained),
                "retained: {case}",
            );
            assert!(
                contains_packet_body(&canonical.local_public_bytes, retained),
                "local public: {case}",
            );
        }
        let framing_preserving =
            export_public_certificate_preserving_framing(&canonical.retained_bytes)
                .expect("export original framing");
        let packet_set_preserving = raw_transferable_bytes(&certificate);
        for (label, exported) in [
            ("canonical", &canonical.bytes),
            ("framing-preserving", &framing_preserving),
            ("packet-set-preserving", &packet_set_preserving),
        ] {
            assert_eq!(
                contains_packet_body(exported, &subkey_packet),
                expected_exported,
                "{label} subkey packet: {case}",
            );
            assert_eq!(
                contains_packet_body(exported, &binding),
                expected_exported,
                "{label} binding: {case}",
            );
        }
    }
}

#[test]
fn signing_algorithm_without_key_flags_still_requires_a_back_signature() {
    let secret = generated_v4_signing_subkey_secret(0x4e4f_5f4b_4559_464c);
    let mut certificate = certificate_packet_set(&secret);
    let fingerprint = certificate
        .subkey_order
        .first()
        .cloned()
        .expect("generated signing subkey");
    let subkey_packet = certificate.subkeys[&fingerprint].packet.clone();
    let binding = test_subkey_binding(&secret, None, None, None);
    certificate
        .subkeys
        .get_mut(&fingerprint)
        .expect("generated signing subkey bundle")
        .attached = attached_from([binding.clone()]);

    let canonical = certificate.finalize().expect("finalize test certificate");
    for retained in [&subkey_packet, &binding] {
        assert!(contains_packet_body(&canonical.retained_bytes, retained));
        assert!(contains_packet_body(
            &canonical.local_public_bytes,
            retained,
        ));
        assert!(!contains_packet_body(&canonical.bytes, retained));
    }
    let framing_preserving =
        export_public_certificate_preserving_framing(&canonical.retained_bytes)
            .expect("export original framing");
    for withheld in [&subkey_packet, &binding] {
        assert!(!contains_packet_body(&framing_preserving, withheld));
    }
}

#[test]
fn encryption_only_subkey_does_not_require_or_inherit_a_back_signature() {
    let secret = generated_uniform_version_secret(KeyVersion::V4, 0x454e_4352_5950_544f);
    let certificate = certificate_packet_set(&secret);
    let fingerprint = certificate
        .subkey_order
        .first()
        .cloned()
        .expect("generated encryption subkey");
    let subkey_packet = certificate.subkeys[&fingerprint].packet.clone();
    let binding = certificate.subkeys[&fingerprint]
        .attached
        .values()
        .find(|packet| {
            parse_signature_packet(packet)
                .is_ok_and(|signature| signature.typ() == Some(SignatureType::SubkeyBinding))
        })
        .cloned()
        .expect("generated encryption-subkey binding");
    let parsed = parse_signature_packet(&binding).expect("parse encryption-subkey binding");
    assert!(parsed.config().is_some_and(|config| {
        config
            .hashed_subpackets()
            .chain(config.unhashed_subpackets())
            .all(|subpacket| !matches!(subpacket.data, SubpacketData::EmbeddedSignature(_)))
    }));

    let canonical = certificate
        .finalize()
        .expect("finalize encryption certificate");
    for exported in [&canonical.bytes, &raw_transferable_bytes(&certificate)] {
        assert!(contains_packet_body(exported, &subkey_packet));
        assert!(contains_packet_body(exported, &binding));
    }

    // An attacker-added unhashed Key Flags value is advisory and cannot turn
    // this encryption-only binding into a signing binding at export time.
    let binding_with_unhashed_signing = signature_with_unhashed_subpackets(
        &binding,
        [Subpacket::regular(SubpacketData::KeyFlags(signing_flags()))
            .expect("unhashed signing key flags")],
    );
    let mut with_unhashed_signing = certificate;
    with_unhashed_signing
        .subkeys
        .get_mut(&fingerprint)
        .expect("generated encryption subkey bundle")
        .attached = attached_from([binding_with_unhashed_signing.clone()]);
    let canonical = with_unhashed_signing
        .finalize()
        .expect("finalize unhashed-flags certificate");
    assert!(contains_packet_body(&canonical.bytes, &subkey_packet));
    assert!(contains_packet_body(
        &canonical.bytes,
        &binding_with_unhashed_signing,
    ));
}

fn exportable_certification_subpacket(exportable: bool) -> Subpacket {
    let data = SubpacketData::ExportableCertification(exportable);
    if exportable {
        Subpacket::regular(data).expect("build exportable certification subpacket")
    } else {
        Subpacket::critical(data).expect("build non-exportable certification subpacket")
    }
}

#[test]
fn last_hashed_exportable_certification_controls_only_certification_export() {
    let cases: &[(&str, &[bool], &[bool], bool)] = &[
        ("no occurrence", &[], &[], true),
        ("single true", &[true], &[], true),
        ("single false", &[false], &[], false),
        ("false then true", &[false, true], &[], true),
        ("true then false", &[true, false], &[], false),
        ("unhashed false then true", &[], &[false, true], true),
        ("hashed true with unhashed false", &[true], &[false], true),
        (
            "hashed false with unhashed true then false",
            &[false],
            &[true, false],
            false,
        ),
    ];

    for (index, &(case, hashed, unhashed, expected_exportable)) in cases.iter().enumerate() {
        let secret =
            generated_uniform_version_secret(KeyVersion::V4, 0x4558_504f_5254_0000 + index as u64);
        let mut certificate = certificate_packet_set(&secret);
        let identity_packet = certificate
            .identity_order
            .first()
            .cloned()
            .expect("generated user ID");
        let user_id = parse_user_id(&identity_packet).expect("parse generated user ID");
        let binding = signed_packet(&secret, SignatureType::CertPositive, |mut config| {
            config.hashed_subpackets.extend(
                hashed
                    .iter()
                    .copied()
                    .map(exportable_certification_subpacket),
            );
            config.unhashed_subpackets.extend(
                unhashed
                    .iter()
                    .copied()
                    .map(exportable_certification_subpacket),
            );
            config
                .sign_certification(
                    &secret.primary_key,
                    secret.primary_key.public_key(),
                    &Password::empty(),
                    Tag::UserId,
                    &user_id,
                )
                .expect("sign exportability test binding")
        });

        assert_eq!(
            !is_non_exportable_signature(&binding),
            expected_exportable,
            "signature predicate: {case}",
        );

        // Make the tested identity binding the only self-authenticated
        // identity evidence. RFC 9580 §10.1.3 still permits the V4 primary
        // when the local certification and its now-unbound identity are
        // omitted.
        certificate.direct = AttachedPackets::default();
        certificate.subkeys.clear();
        certificate.subkey_order.clear();
        *certificate
            .identities
            .get_mut(&identity_packet)
            .expect("generated user ID bundle") = attached_from([binding.clone()]);

        let canonical = certificate.finalize().expect("finalize test certificate");
        assert!(
            contains_packet_body(&canonical.retained_bytes, &identity_packet),
            "retained identity: {case}",
        );
        assert!(
            contains_packet_body(&canonical.retained_bytes, &binding),
            "retained binding: {case}",
        );
        assert!(!canonical.bytes.is_empty(), "canonical certificate: {case}");
        assert!(
            contains_packet_body(&canonical.bytes, &certificate.primary),
            "canonical primary key: {case}",
        );
        assert_eq!(
            contains_packet_body(&canonical.bytes, &identity_packet),
            expected_exportable,
            "canonical identity component: {case}",
        );
        assert_eq!(
            contains_packet_body(&canonical.bytes, &binding),
            expected_exportable,
            "canonical binding: {case}",
        );

        let raw = raw_transferable_bytes(&certificate);
        assert!(!raw.is_empty(), "raw certificate: {case}");
        assert!(
            contains_packet_body(&raw, &certificate.primary),
            "raw primary key: {case}",
        );
        assert_eq!(
            contains_packet_body(&raw, &identity_packet),
            expected_exportable,
            "raw identity component: {case}",
        );
        assert_eq!(
            contains_packet_body(&raw, &binding),
            expected_exportable,
            "raw binding: {case}",
        );

        let preserving_export =
            export_public_certificate_preserving_framing(&canonical.retained_bytes)
                .expect("export test certificate");
        assert!(
            contains_packet_body(&preserving_export, &certificate.primary),
            "framing-preserving primary key: {case}",
        );
        assert_eq!(
            contains_packet_body(&preserving_export, &identity_packet),
            expected_exportable,
            "framing-preserving identity component: {case}",
        );
        assert_eq!(
            contains_packet_body(&preserving_export, &binding),
            expected_exportable,
            "framing-preserving binding: {case}",
        );
        parse_single_certificate_packet_set(&preserving_export)
            .expect("reparse framing-preserving certificate");
    }
}

#[test]
fn malformed_critical_exportable_certification_value_fails_closed() {
    let secret = generated_uniform_version_secret(KeyVersion::V4, 0x4558_504f_5254_424f);
    let mut certificate = certificate_packet_set(&secret);
    let identity_packet = certificate
        .identity_order
        .first()
        .cloned()
        .expect("generated User ID");
    let user_id = parse_user_id(&identity_packet).expect("parse generated User ID");
    let malformed = signed_packet(&secret, SignatureType::CertPositive, |mut config| {
        // RFC 9580 specifies exactly 0 or 1. rPGP parses every other one-octet
        // value as false; preserve that fail-closed behavior even though the
        // packet is otherwise a cryptographically valid self-certification.
        config.hashed_subpackets.push(
            Subpacket::critical(SubpacketData::Experimental(4, Bytes::from_static(&[2])))
                .expect("build malformed Exportable Certification subpacket"),
        );
        config
            .sign_certification(
                &secret.primary_key,
                secret.primary_key.public_key(),
                &Password::empty(),
                Tag::UserId,
                &user_id,
            )
            .expect("sign malformed exportability binding")
    });
    assert!(is_non_exportable_signature(&malformed));
    *certificate
        .identities
        .get_mut(&identity_packet)
        .expect("generated User ID bundle") = attached_from([malformed.clone()]);

    let canonical = certificate
        .finalize()
        .expect("finalize malformed exportability certificate");
    assert!(contains_packet_body(&canonical.retained_bytes, &malformed));
    assert!(contains_packet_body(&canonical.bytes, &certificate.primary,));
    assert!(!contains_packet_body(&canonical.bytes, &identity_packet));
    assert!(!contains_packet_body(&canonical.bytes, &malformed));
    let raw = export_public_certificate_preserving_framing(&canonical.retained_bytes)
        .expect("export malformed local certification certificate");
    assert!(contains_packet_body(&raw, &certificate.primary));
    assert!(!contains_packet_body(&raw, &identity_packet));
    assert!(!contains_packet_body(&raw, &malformed));
}

#[test]
fn transferable_export_removes_only_local_certification_signatures() {
    let secret = generated_uniform_version_secret(KeyVersion::V4, 0x4c4f_4341_4c5f_5041);
    let mut certificate = certificate_packet_set(&secret);
    let primary = parse_primary_key(&certificate.primary).expect("parse primary key");
    let identity_packet = certificate
        .identity_order
        .first()
        .cloned()
        .expect("generated user ID");
    let user_id = parse_user_id(&identity_packet).expect("parse generated user ID");
    let subkey_fingerprint = certificate
        .subkey_order
        .first()
        .cloned()
        .expect("generated subkey");
    let subkey_packet = certificate.subkeys[&subkey_fingerprint].packet.clone();
    let subkey = parse_public_subkey(&subkey_packet).expect("parse generated subkey");

    let ordinary_direct = signed_packet(&secret, SignatureType::Key, |config| {
        config
            .sign_key(
                &secret.primary_key,
                &Password::empty(),
                secret.primary_key.public_key(),
            )
            .expect("sign ordinary Direct Key signature")
    });
    let local_direct = signed_packet(&secret, SignatureType::Key, |config| {
        mark_local(config)
            .sign_key(
                &secret.primary_key,
                &Password::empty(),
                secret.primary_key.public_key(),
            )
            .expect("sign local Direct Key signature")
    });
    let local_identity = signed_packet(&secret, SignatureType::CertPositive, |config| {
        mark_local(config)
            .sign_certification(
                &secret.primary_key,
                secret.primary_key.public_key(),
                &Password::empty(),
                Tag::UserId,
                &user_id,
            )
            .expect("sign local user ID binding")
    });
    let ordinary_identity = signed_packet(&secret, SignatureType::CertPositive, |config| {
        config
            .sign_certification(
                &secret.primary_key,
                secret.primary_key.public_key(),
                &Password::empty(),
                Tag::UserId,
                &user_id,
            )
            .expect("sign ordinary user ID binding")
    });
    let flagged_identity_revocation =
        signed_packet(&secret, SignatureType::CertRevocation, |config| {
            mark_local(config)
                .sign_certification(
                    &secret.primary_key,
                    secret.primary_key.public_key(),
                    &Password::empty(),
                    Tag::UserId,
                    &user_id,
                )
                .expect("sign identity revocation with inapplicable local flag")
        });
    let external = parse_secret(OTHER_SECRET_KEY);
    let external_certification = signed_packet(&external, SignatureType::CertPositive, |config| {
        config
            .sign_certification(
                &external.primary_key,
                &primary,
                &Password::empty(),
                Tag::UserId,
                &user_id,
            )
            .expect("sign external user ID certification")
    });
    let local_subkey_binding = signed_packet(&secret, SignatureType::SubkeyBinding, |config| {
        mark_local(config)
            .sign_subkey_binding(
                &secret.primary_key,
                secret.primary_key.public_key(),
                &Password::empty(),
                &subkey,
            )
            .expect("sign local subkey binding")
    });
    let ordinary_subkey_binding = signed_packet(&secret, SignatureType::SubkeyBinding, |config| {
        config
            .sign_subkey_binding(
                &secret.primary_key,
                secret.primary_key.public_key(),
                &Password::empty(),
                &subkey,
            )
            .expect("sign ordinary subkey binding")
    });

    certificate.direct = attached_from([ordinary_direct.clone(), local_direct.clone()]);
    *certificate
        .identities
        .get_mut(&identity_packet)
        .expect("generated user ID bundle") = attached_from([
        local_identity.clone(),
        ordinary_identity.clone(),
        flagged_identity_revocation.clone(),
        external_certification.clone(),
    ]);
    certificate
        .subkeys
        .get_mut(&subkey_fingerprint)
        .expect("generated subkey bundle")
        .attached = attached_from([
        local_subkey_binding.clone(),
        ordinary_subkey_binding.clone(),
    ]);
    assert!(!is_non_exportable_signature(&local_direct));
    assert!(is_non_exportable_signature(&local_identity));
    assert!(!is_non_exportable_signature(&flagged_identity_revocation));
    assert!(!is_non_exportable_signature(&local_subkey_binding));

    let canonical = certificate
        .finalize()
        .expect("finalize partial local certificate");
    for retained in [
        &local_direct,
        &identity_packet,
        &local_identity,
        &ordinary_identity,
        &flagged_identity_revocation,
        &external_certification,
        &subkey_packet,
        &local_subkey_binding,
        &ordinary_subkey_binding,
    ] {
        assert!(contains_packet_body(&canonical.retained_bytes, retained));
    }
    for retained in [
        &ordinary_direct,
        &local_direct,
        &identity_packet,
        &ordinary_identity,
        &flagged_identity_revocation,
        &external_certification,
        &subkey_packet,
        &local_subkey_binding,
        &ordinary_subkey_binding,
    ] {
        assert!(contains_packet_body(&canonical.bytes, retained));
    }
    assert!(!contains_packet_body(&canonical.bytes, &local_identity));
    let raw = raw_transferable_bytes(&certificate);
    for retained in [
        &ordinary_direct,
        &local_direct,
        &identity_packet,
        &ordinary_identity,
        &flagged_identity_revocation,
        &external_certification,
        &subkey_packet,
        &local_subkey_binding,
        &ordinary_subkey_binding,
    ] {
        assert!(contains_packet_body(&raw, retained));
    }
    assert!(!contains_packet_body(&raw, &local_identity));
    parse_single_certificate_packet_set(&canonical.bytes).expect("reparse canonical export");
    parse_single_certificate_packet_set(&raw).expect("reparse framing-preserving export");
}

#[test]
fn identity_with_only_local_self_certification_is_omitted() {
    let target = generated_uniform_version_secret(KeyVersion::V4, 0x4c4f_4341_4c5f_5042);
    let certifier = parse_secret(OTHER_SECRET_KEY);
    let mut certificate = certificate_packet_set(&target);
    let primary = parse_primary_key(&certificate.primary).expect("parse target primary key");
    let ordinary_direct = signed_packet(&target, SignatureType::Key, |config| {
        config
            .sign_key(
                &target.primary_key,
                &Password::empty(),
                target.primary_key.public_key(),
            )
            .expect("sign ordinary Direct Key signature")
    });
    certificate.direct = attached_from([ordinary_direct.clone()]);

    let user_id_packet = certificate
        .identity_order
        .first()
        .cloned()
        .expect("generated User ID");
    let user_id = parse_user_id(&user_id_packet).expect("parse generated User ID");
    let local_user_id = signed_packet(&target, SignatureType::CertPositive, |config| {
        mark_local(config)
            .sign_certification(
                &target.primary_key,
                target.primary_key.public_key(),
                &Password::empty(),
                Tag::UserId,
                &user_id,
            )
            .expect("sign local User ID binding")
    });
    let external_user_id = signed_packet_at(
        &certifier,
        SignatureType::CertPositive,
        1_782_541_300,
        |config| {
            config
                .sign_certification(
                    &certifier.primary_key,
                    &primary,
                    &Password::empty(),
                    Tag::UserId,
                    &user_id,
                )
                .expect("sign third-party User ID certification")
        },
    );
    let external_user_id_revocation = signed_packet_at(
        &certifier,
        SignatureType::CertRevocation,
        1_782_541_500,
        |config| {
            mark_local(config)
                .sign_certification(
                    &certifier.primary_key,
                    &primary,
                    &Password::empty(),
                    Tag::UserId,
                    &user_id,
                )
                .expect("sign third-party User ID certification revocation")
        },
    );
    *certificate
        .identities
        .get_mut(&user_id_packet)
        .expect("generated User ID bundle") = attached_from([
        local_user_id.clone(),
        external_user_id.clone(),
        external_user_id_revocation.clone(),
    ]);

    let attribute = UserAttribute::new_image(Bytes::from_static(b"local export test image"))
        .expect("build User Attribute");
    let attribute_packet = CanonicalPacket {
        tag: USER_ATTRIBUTE_TAG,
        body: attribute.to_bytes().expect("serialize User Attribute"),
    };
    let local_attribute = signed_packet(&target, SignatureType::CertPositive, |config| {
        mark_local(config)
            .sign_certification(
                &target.primary_key,
                target.primary_key.public_key(),
                &Password::empty(),
                Tag::UserAttribute,
                &attribute,
            )
            .expect("sign local User Attribute binding")
    });
    let external_attribute = signed_packet_at(
        &certifier,
        SignatureType::CertPositive,
        1_782_541_301,
        |config| {
            config
                .sign_certification(
                    &certifier.primary_key,
                    &primary,
                    &Password::empty(),
                    Tag::UserAttribute,
                    &attribute,
                )
                .expect("sign third-party User Attribute certification")
        },
    );
    let external_attribute_revocation = signed_packet_at(
        &certifier,
        SignatureType::CertRevocation,
        1_782_541_501,
        |config| {
            config
                .sign_certification(
                    &certifier.primary_key,
                    &primary,
                    &Password::empty(),
                    Tag::UserAttribute,
                    &attribute,
                )
                .expect("sign third-party User Attribute certification revocation")
        },
    );
    certificate.identity_order.push(attribute_packet.clone());
    certificate.identities.insert(
        attribute_packet.clone(),
        attached_from([
            local_attribute.clone(),
            external_attribute.clone(),
            external_attribute_revocation.clone(),
        ]),
    );

    let subkey_fingerprint = certificate
        .subkey_order
        .first()
        .cloned()
        .expect("generated subkey");
    let subkey_packet = certificate.subkeys[&subkey_fingerprint].packet.clone();
    let subkey = parse_public_subkey(&subkey_packet).expect("parse generated subkey");
    let local_subkey_binding = signed_packet(&target, SignatureType::SubkeyBinding, |config| {
        mark_local(config)
            .sign_subkey_binding(
                &target.primary_key,
                target.primary_key.public_key(),
                &Password::empty(),
                &subkey,
            )
            .expect("sign local subkey binding")
    });
    // The type-4 subpacket does not change a Subkey Binding signature's
    // exportability. The self-issued binding therefore admits the component;
    // the peer binding remains associated evidence but cannot do so itself.
    let external_subkey_binding =
        signed_packet(&certifier, SignatureType::SubkeyBinding, |config| {
            config
                .sign_subkey_binding(
                    &certifier.primary_key,
                    &primary,
                    &Password::empty(),
                    &subkey,
                )
                .expect("sign third-party subkey binding")
        });
    certificate
        .subkeys
        .get_mut(&subkey_fingerprint)
        .expect("generated subkey bundle")
        .attached = attached_from([
        local_subkey_binding.clone(),
        external_subkey_binding.clone(),
    ]);

    for signature in [&local_user_id, &local_attribute] {
        assert!(is_non_exportable_signature(signature));
    }
    for signature in [
        &external_user_id,
        &external_user_id_revocation,
        &external_attribute,
        &external_attribute_revocation,
        &local_subkey_binding,
        &external_subkey_binding,
    ] {
        assert!(!is_non_exportable_signature(signature));
    }

    let canonical = certificate.finalize().expect("finalize mixed certificate");
    let framing_preserving =
        export_public_certificate_preserving_framing(&canonical.retained_bytes)
            .expect("export original framing");
    for exported in [&canonical.bytes, &framing_preserving] {
        for retained in [
            &certificate.primary,
            &ordinary_direct,
            &subkey_packet,
            &local_subkey_binding,
            &external_subkey_binding,
        ] {
            assert!(contains_packet_body(exported, retained));
        }
        for withheld in [
            &user_id_packet,
            &local_user_id,
            &external_user_id,
            &external_user_id_revocation,
            &attribute_packet,
            &local_attribute,
            &external_attribute,
            &external_attribute_revocation,
        ] {
            assert!(!contains_packet_body(exported, withheld));
        }
        parse_single_certificate_packet_set(exported).expect("reparse exported certificate");
    }
}

#[test]
fn v4_local_certifications_are_removed_without_erasing_certificate() {
    let secret = generated_uniform_version_secret(KeyVersion::V4, 0x4c4f_4341_4c5f_414c);
    let mut certificate = certificate_packet_set(&secret);
    let identity_packet = certificate
        .identity_order
        .first()
        .cloned()
        .expect("generated user ID");
    let user_id = parse_user_id(&identity_packet).expect("parse generated user ID");
    let subkey_fingerprint = certificate
        .subkey_order
        .first()
        .cloned()
        .expect("generated subkey");
    let subkey_packet = certificate.subkeys[&subkey_fingerprint].packet.clone();
    let subkey = parse_public_subkey(&subkey_packet).expect("parse generated subkey");
    let local_direct = signed_packet(&secret, SignatureType::Key, |config| {
        mark_local(config)
            .sign_key(
                &secret.primary_key,
                &Password::empty(),
                secret.primary_key.public_key(),
            )
            .expect("sign local Direct Key signature")
    });
    let local_identity = signed_packet(&secret, SignatureType::CertPositive, |config| {
        mark_local(config)
            .sign_certification(
                &secret.primary_key,
                secret.primary_key.public_key(),
                &Password::empty(),
                Tag::UserId,
                &user_id,
            )
            .expect("sign local user ID binding")
    });
    let attribute = UserAttribute::new_image(Bytes::from_static(b"bare local-only image"))
        .expect("build local-only User Attribute");
    let attribute_packet = CanonicalPacket {
        tag: USER_ATTRIBUTE_TAG,
        body: attribute.to_bytes().expect("serialize User Attribute"),
    };
    let local_attribute = signed_packet(&secret, SignatureType::CertPositive, |config| {
        mark_local(config)
            .sign_certification(
                &secret.primary_key,
                secret.primary_key.public_key(),
                &Password::empty(),
                Tag::UserAttribute,
                &attribute,
            )
            .expect("sign local User Attribute binding")
    });
    let local_subkey_binding = signed_packet(&secret, SignatureType::SubkeyBinding, |config| {
        mark_local(config)
            .sign_subkey_binding(
                &secret.primary_key,
                secret.primary_key.public_key(),
                &Password::empty(),
                &subkey,
            )
            .expect("sign local subkey binding")
    });
    certificate.direct = attached_from([local_direct.clone()]);
    *certificate
        .identities
        .get_mut(&identity_packet)
        .expect("generated user ID bundle") = attached_from([local_identity.clone()]);
    certificate.identity_order.push(attribute_packet.clone());
    certificate.identities.insert(
        attribute_packet.clone(),
        attached_from([local_attribute.clone()]),
    );
    certificate
        .subkeys
        .get_mut(&subkey_fingerprint)
        .expect("generated subkey bundle")
        .attached = attached_from([local_subkey_binding.clone()]);
    assert!(!is_non_exportable_signature(&local_direct));
    assert!(is_non_exportable_signature(&local_identity));
    assert!(is_non_exportable_signature(&local_attribute));
    assert!(!is_non_exportable_signature(&local_subkey_binding));

    let canonical = certificate
        .finalize()
        .expect("finalize certificate with local certifications");
    for retained in [
        &certificate.primary,
        &local_direct,
        &identity_packet,
        &local_identity,
        &attribute_packet,
        &local_attribute,
        &subkey_packet,
        &local_subkey_binding,
    ] {
        assert!(contains_packet_body(&canonical.retained_bytes, retained));
    }
    for local_public in [
        &certificate.primary,
        &local_direct,
        &identity_packet,
        &attribute_packet,
        &subkey_packet,
        &local_subkey_binding,
    ] {
        assert!(contains_packet_body(
            &canonical.local_public_bytes,
            local_public,
        ));
    }
    for exported in [
        &certificate.primary,
        &local_direct,
        &subkey_packet,
        &local_subkey_binding,
    ] {
        assert!(contains_packet_body(&canonical.bytes, exported,));
    }
    for withheld in [&local_identity, &local_attribute] {
        assert!(!contains_packet_body(
            &canonical.local_public_bytes,
            withheld,
        ));
        assert!(!contains_packet_body(&canonical.bytes, withheld));
    }
    for withheld in [&identity_packet, &attribute_packet] {
        assert!(!contains_packet_body(&canonical.bytes, withheld));
    }
    let raw = raw_transferable_bytes(&certificate);
    assert_eq!(raw, canonical.bytes);
    let preserving = export_public_certificate_preserving_framing(&canonical.retained_bytes)
        .expect("export original framing");
    assert_eq!(preserving, canonical.bytes);
    parse_single_certificate_packet_set(&canonical.bytes).expect("reparse canonical export");
}

#[test]
fn non_exportable_certifications_are_retained_locally_but_never_exported() {
    let mut certificate = parse_fixture();
    let identity_packet = certificate
        .identity_order
        .first()
        .cloned()
        .expect("fixture user ID");
    let user_id = parse_user_id(&identity_packet).expect("parse fixture user ID");
    let primary = parse_primary_key(&certificate.primary).expect("parse primary key");
    let (certifier, _) = SignedSecretKey::from_reader_single(Cursor::new(OTHER_SECRET_KEY))
        .expect("parse external certifier");
    let local_certification =
        signed_packet(&certifier, SignatureType::CertPositive, |mut config| {
            config.hashed_subpackets.push(
                Subpacket::regular(SubpacketData::ExportableCertification(false))
                    .expect("build exportable subpacket"),
            );
            config
                .sign_certification(
                    &certifier.primary_key,
                    &primary,
                    &Password::empty(),
                    Tag::UserId,
                    &user_id,
                )
                .expect("sign local certification")
        });
    certificate
        .identities
        .get_mut(&identity_packet)
        .expect("fixture user ID bundle")
        .insert(local_certification.clone())
        .expect("attach local certification");

    // Retained in the in-memory packet set and retained serialization...
    assert!(
        certificate.identities[&identity_packet]
            .values()
            .any(|packet| packet == &local_certification)
    );
    let canonical = certificate.finalize().expect("finalize certificate");
    assert!(contains_packet_body(
        &canonical.retained_bytes,
        &local_certification,
    ));
    assert!(
        canonical.semantic.details.users[0]
            .signatures
            .iter()
            .any(|signature| signature.config().is_some_and(|config| {
                config.hashed_subpackets().any(|subpacket| {
                    matches!(
                        subpacket.data,
                        SubpacketData::ExportableCertification(false)
                    )
                })
            }))
    );

    // ...but absent from both canonical and original-framing exports.
    let exported = canonical.bytes;
    let stream =
        RawPacketStream::parse(&exported, MAX_MERGE_PACKETS).expect("parse exported bytes");
    assert!(
        stream
            .packets()
            .iter()
            .all(|packet| stream.body(packet).as_slice() != local_certification.body)
    );
    assert!(!contains_packet_body(
        &raw_transferable_bytes(&certificate),
        &local_certification,
    ));
    assert_eq!(
        exported,
        canonicalize_public_certificate(PUBLIC_KEY)
            .expect("canonicalize fixture")
            .0,
    );
}
