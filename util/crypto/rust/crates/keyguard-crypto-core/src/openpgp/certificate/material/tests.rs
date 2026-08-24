use super::*;
use pgp::{
    composed::{EncryptionCaps, KeyType, SecretKeyParamsBuilder, SubkeyParamsBuilder},
    ser::Serialize,
    types::Timestamp,
};
use rand::{SeedableRng, rngs::StdRng};

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
            "Versioned secret {seed} <versioned-secret-{seed}@example.test>"
        ))
        .passphrase(None)
        .subkey(subkey)
        .build()
        .expect("build versioned secret certificate")
        .generate(StdRng::seed_from_u64(seed))
        .expect("generate versioned secret certificate")
}

fn secret_certificate_with_subkeys_from(
    primary_source: &SignedSecretKey,
    subkey_source: &SignedSecretKey,
) -> Vec<u8> {
    let primary_bytes = primary_source
        .to_bytes()
        .expect("serialize primary-source secret certificate");
    let subkey_bytes = subkey_source
        .to_bytes()
        .expect("serialize subkey-source secret certificate");
    let primary_stream = RawPacketStream::parse(&primary_bytes, MAX_KEY_PACKETS)
        .expect("parse primary-source secret certificate");
    let subkey_stream = RawPacketStream::parse(&subkey_bytes, MAX_KEY_PACKETS)
        .expect("parse subkey-source secret certificate");
    let primary_subkey = primary_stream
        .packets()
        .iter()
        .position(|packet| packet.tag() == SECRET_SUBKEY_TAG)
        .expect("primary source has a secret subkey");
    let donor_subkey = subkey_stream
        .packets()
        .iter()
        .position(|packet| packet.tag() == SECRET_SUBKEY_TAG)
        .expect("subkey source has a secret subkey");
    let mut certificate = Vec::new();
    for packet in primary_stream.packets().iter().take(primary_subkey) {
        certificate.extend_from_slice(primary_stream.raw(packet));
    }
    for packet in subkey_stream.packets().iter().skip(donor_subkey) {
        certificate.extend_from_slice(subkey_stream.raw(packet));
    }
    certificate
}

fn secret_certificate_with_gnu_dummy_primary(
    source: &SignedSecretKey,
    usage: u8,
    trailing: &[u8],
) -> Vec<u8> {
    assert!(matches!(usage, 254 | 255));
    let source = source
        .to_bytes()
        .expect("serialize source secret certificate");
    let stream =
        RawPacketStream::parse(&source, MAX_KEY_PACKETS).expect("parse source secret certificate");
    let primary = stream
        .packets()
        .first()
        .filter(|packet| packet.tag() == SECRET_KEY_TAG)
        .expect("source starts with a secret primary");
    let primary_body = stream.body(primary);
    let parsed = parse_secret_key_body(primary_body.as_slice()).expect("parse source primary");
    let public_body = serialize_packet_body(parsed.public_key()).expect("serialize public primary");
    let mut dummy_body = Vec::with_capacity(public_body.len() + 8 + trailing.len());
    dummy_body.extend_from_slice(&public_body);
    dummy_body.extend_from_slice(&[usage, 0, 101, 0, b'G', b'N', b'U', 1]);
    dummy_body.extend_from_slice(trailing);

    let mut output = Vec::new();
    write_fixed_packet(SECRET_KEY_TAG, &dummy_body, &mut output)
        .expect("serialize GNU dummy primary");
    for packet in stream.packets().iter().skip(1) {
        output.extend_from_slice(stream.raw(packet));
    }
    output
}

fn packet(public_body: &[u8], secret_suffix: u8) -> SecretPacketOverlay {
    let mut secret_body = public_body.to_vec();
    secret_body.push(secret_suffix);
    SecretPacketOverlay {
        public_body: public_body.to_vec(),
        secret_packet: Zeroizing::new(secret_body),
    }
}

fn overlay(subkeys: &[(&str, u8)]) -> SecretCertificateOverlay {
    SecretCertificateOverlay {
        primary: Some(PrimarySecretPacketOverlay::Material(packet(&[1], 10))),
        subkey_order: subkeys
            .iter()
            .map(|(fingerprint, _)| (*fingerprint).to_owned())
            .collect(),
        subkeys: subkeys
            .iter()
            .map(|(fingerprint, secret_suffix)| {
                (
                    (*fingerprint).to_owned(),
                    packet(fingerprint.as_bytes(), *secret_suffix),
                )
            })
            .collect(),
    }
}

#[test]
fn secret_overlay_merge_unions_complementary_subkeys() {
    let result = merge_secret_certificate_overlays(
        Some(overlay(&[("existing", 20)])),
        Some(overlay(&[("incoming", 30)])),
    )
    .expect("complementary secret components should merge")
    .expect("two secret inputs should produce an overlay");

    assert!(result.existing_contributed);
    assert!(result.incoming_contributed);
    assert_eq!(
        result.overlay.subkeys.keys().cloned().collect::<Vec<_>>(),
        vec!["existing".to_owned(), "incoming".to_owned()],
    );
}

#[test]
fn secret_overlay_merge_unions_primary_and_subkey_only_material() {
    let primary_only = overlay(&[]);
    let mut subkey_only = overlay(&[("selected", 20)]);
    subkey_only.primary = None;

    let result = merge_secret_certificate_overlays(Some(primary_only), Some(subkey_only))
        .expect("component-wise secret material should merge")
        .expect("two secret inputs should produce an overlay");

    assert!(result.overlay.primary.is_some());
    assert_eq!(
        result.overlay.subkeys.keys().cloned().collect::<Vec<_>>(),
        vec!["selected".to_owned()],
    );
    assert!(result.existing_contributed);
    assert!(result.incoming_contributed);
}

#[test]
fn secret_overlay_merge_rejects_different_public_component_bodies() {
    let existing = overlay(&[("shared", 20)]);
    let mut incoming = overlay(&[("shared", 30)]);
    incoming.primary = Some(PrimarySecretPacketOverlay::Material(packet(&[2], 40)));
    let error = match merge_secret_certificate_overlays(Some(existing), Some(incoming)) {
        Err(error) => error,
        Ok(_) => panic!("different public bodies for one component must fail closed"),
    };

    assert_eq!(error, SecretOverlayMergeError::ComponentMismatch);
}

#[test]
fn secret_projection_requires_subkeys_to_match_the_primary_version() {
    let v4 = generated_uniform_version_secret(KeyVersion::V4, 0x5345_4352_4554_5634);
    let v6 = generated_uniform_version_secret(KeyVersion::V6, 0x5345_4352_4554_5636);

    for (primary, subkey, case) in [
        (&v4, &v6, "V4 primary with V6 subkey"),
        (&v6, &v4, "V6 primary with V4 subkey"),
    ] {
        let mixed = secret_certificate_with_subkeys_from(primary, subkey);
        assert_eq!(
            project_secret_certificate(&mixed)
                .err()
                .expect("mixed-version secret certificate must fail"),
            MutationMaterialError::MalformedKey,
            "reject {case}",
        );
    }

    for (certificate, case) in [(&v4, "uniform V4"), (&v6, "uniform V6")] {
        let secret = certificate
            .to_bytes()
            .expect("serialize uniform secret certificate");
        project_secret_certificate(&secret).unwrap_or_else(|error| {
            panic!("accept {case} secret certificate: {error}");
        });
    }
}

#[test]
fn secret_projection_ignores_only_exact_marker_bodies() {
    const SECRET_KEY: &[u8] =
        include_bytes!("../../../../tests/fixtures/openpgp/cv25519-secret.asc");
    let fixture = RawPacketStream::parse(SECRET_KEY, MAX_KEY_PACKETS)
        .expect("parse secret fixture packet stream");

    for (body, expected, case) in [
        (b"PGP".as_slice(), Ok(()), "exact Marker"),
        (
            b"PGX".as_slice(),
            Err(MutationMaterialError::MalformedKey),
            "malformed Marker",
        ),
    ] {
        let mut marker = Vec::new();
        write_fixed_packet(MARKER_TAG, body, &mut marker).expect("write Marker packet");
        let mut certificate = Vec::with_capacity(fixture.bytes().len() + marker.len());
        for (index, packet) in fixture.packets().iter().enumerate() {
            certificate.extend_from_slice(fixture.raw(packet));
            if index == 0 {
                certificate.extend_from_slice(&marker);
            }
        }
        let result = project_secret_certificate(&certificate).map(|_| ());
        assert_eq!(result, expected, "{case}");
    }
}

#[test]
fn projection_and_rebuild_support_filtered_tsk_secret_subkeys() {
    let filtered = filtered_tsk_fixture();
    let filtered_stream =
        RawPacketStream::parse(&filtered, MAX_KEY_PACKETS).expect("parse filtered TSK fixture");
    let filtered_primary = filtered_stream
        .packets()
        .first()
        .expect("filtered TSK has a primary key");

    let (projection, overlay) =
        project_secret_certificate(&filtered).expect("project filtered TSK");
    assert!(overlay.primary.is_none());
    assert!(!overlay.subkeys.is_empty());
    let projected_stream = RawPacketStream::parse(&projection, MAX_KEY_PACKETS)
        .expect("parse filtered TSK projection");
    assert_eq!(projected_stream.packets()[0].tag(), 6);
    assert_eq!(
        projected_stream.raw(&projected_stream.packets()[0]),
        filtered_stream.raw(filtered_primary),
    );
    assert!(
        projected_stream
            .packets()
            .iter()
            .any(|packet| packet.tag() == 14)
    );

    let rebuilt =
        rebuild_secret_certificate(&projection, &overlay).expect("restore selected secret subkeys");
    let rebuilt_stream =
        RawPacketStream::parse(&rebuilt, MAX_KEY_PACKETS).expect("parse rebuilt filtered TSK");
    assert_eq!(rebuilt_stream.packets()[0].tag(), 6);
    assert!(
        !rebuilt_stream
            .packets()
            .iter()
            .any(|packet| packet.tag() == 5)
    );
    assert!(
        rebuilt_stream
            .packets()
            .iter()
            .any(|packet| packet.tag() == 7)
    );
    let original_secret_bodies = filtered_stream
        .packets()
        .iter()
        .filter(|packet| packet.tag() == 7)
        .map(|packet| filtered_stream.body_to_vec(packet))
        .collect::<Vec<_>>();
    let rebuilt_secret_bodies = rebuilt_stream
        .packets()
        .iter()
        .filter(|packet| packet.tag() == 7)
        .map(|packet| rebuilt_stream.body_to_vec(packet))
        .collect::<Vec<_>>();
    assert_eq!(rebuilt_secret_bodies, original_secret_bodies);

    let (rebuilt_projection, _) =
        project_secret_certificate(&rebuilt).expect("reproject rebuilt filtered TSK");
    assert_eq!(rebuilt_projection, projection);
}

#[test]
fn projection_and_rebuild_preserve_gnu_dummy_primary_packet() {
    let source = generated_uniform_version_secret(KeyVersion::V4, 0x474e_5553_5455_4253);

    for usage in [254, 255] {
        let filtered = secret_certificate_with_gnu_dummy_primary(&source, usage, &[]);
        let filtered_stream = RawPacketStream::parse(&filtered, MAX_KEY_PACKETS)
            .expect("parse GNU dummy-primary TSK");
        let original_primary = filtered_stream.raw(&filtered_stream.packets()[0]);

        let (projection, overlay) =
            project_secret_certificate(&filtered).expect("project GNU dummy-primary TSK");
        assert!(!overlay.has_secret_primary());
        assert!(matches!(
            &overlay.primary,
            Some(PrimarySecretPacketOverlay::GnuDummyStub(_))
        ));
        let projected_stream = RawPacketStream::parse(&projection, MAX_KEY_PACKETS)
            .expect("parse GNU dummy public projection");
        assert_eq!(projected_stream.packets()[0].tag(), PUBLIC_KEY_TAG);

        let rebuilt = rebuild_secret_certificate(&projection, &overlay)
            .expect("rebuild GNU dummy-primary TSK");
        let rebuilt_stream = RawPacketStream::parse(&rebuilt, MAX_KEY_PACKETS)
            .expect("parse rebuilt GNU dummy-primary TSK");
        assert_eq!(rebuilt_stream.packets()[0].tag(), SECRET_KEY_TAG);
        assert_eq!(
            rebuilt_stream.raw(&rebuilt_stream.packets()[0]),
            original_primary
        );
    }
}

#[test]
fn candidate_packet_limit_is_fatal() {
    let mut document = Vec::with_capacity((MAX_KEY_PACKETS + 1) * 2);
    for _ in 0..=MAX_KEY_PACKETS {
        document.extend_from_slice(&[0xca, 0x00]);
    }

    let error = match parse_mutation_candidates(&[document]) {
        Ok(_) => panic!("candidate document over the packet limit must fail"),
        Err(error) => error,
    };
    assert_eq!(error, MutationMaterialError::ResourceLimit);
}
