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
    secret_certificate_with_gnu_primary(source, usage, 0, 0, 1, trailing)
}

fn secret_certificate_with_gnu_primary(
    source: &SignedSecretKey,
    usage: u8,
    cipher: u8,
    hash: u8,
    mode: u8,
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
    dummy_body.extend_from_slice(&[usage, cipher, 101, hash, b'G', b'N', b'U', mode]);
    dummy_body.extend_from_slice(trailing);

    let mut output = Vec::new();
    write_fixed_packet(SECRET_KEY_TAG, &dummy_body, &mut output)
        .expect("serialize GNU dummy primary");
    for packet in stream.packets().iter().skip(1) {
        output.extend_from_slice(stream.raw(packet));
    }
    output
}

fn secret_certificate_with_gnu_subkey(
    source: &SignedSecretKey,
    usage: u8,
    mode: u8,
    trailing: &[u8],
) -> Vec<u8> {
    assert!(matches!(usage, 254 | 255));
    let source = source
        .to_bytes()
        .expect("serialize source secret certificate");
    let stream =
        RawPacketStream::parse(&source, MAX_KEY_PACKETS).expect("parse source secret certificate");
    let mut output = Vec::new();
    let mut subkey_replaced = false;
    for (index, packet) in stream.packets().iter().enumerate() {
        match packet.tag() {
            SECRET_KEY_TAG if index == 0 => {
                let body = stream.body(packet);
                let parsed = parse_secret_key_body(body.as_slice()).expect("parse source primary");
                let public_body =
                    serialize_packet_body(parsed.public_key()).expect("serialize public primary");
                write_fixed_packet(PUBLIC_KEY_TAG, &public_body, &mut output)
                    .expect("serialize public primary");
            }
            SECRET_SUBKEY_TAG if !subkey_replaced => {
                let body = stream.body(packet);
                let parsed =
                    parse_secret_subkey_body(body.as_slice()).expect("parse source secret subkey");
                let public_body =
                    serialize_packet_body(parsed.public_key()).expect("serialize public subkey");
                let mut dummy_body = Vec::with_capacity(public_body.len() + 8 + trailing.len());
                dummy_body.extend_from_slice(&public_body);
                dummy_body.extend_from_slice(&[usage, 0, 101, 0, b'G', b'N', b'U', mode]);
                dummy_body.extend_from_slice(trailing);
                write_fixed_packet(SECRET_SUBKEY_TAG, &dummy_body, &mut output)
                    .expect("serialize GNU secret subkey");
                subkey_replaced = true;
            }
            _ => output.extend_from_slice(stream.raw(packet)),
        }
    }
    assert!(subkey_replaced, "source contains a secret subkey");
    output
}

#[test]
fn gnu_private_s2k_is_classified_before_portable_secret_material() {
    for usage in [254, 255] {
        for (cipher, hash) in [(0, 0), (0, 8), (9, 0)] {
            assert_eq!(
                classify_gnu_secret_s2k(
                    KeyVersion::V4,
                    &[usage, cipher, 101, hash, b'G', b'N', b'U', 1],
                ),
                Ok(Some(GnuSecretS2k::Dummy)),
            );
        }
    }

    for suffix in [
        &[254, 0, 101, 0, b'G', b'N', b'U', 1, 0][..],
        &[254, 0, 101, 0, b'G', b'N', b'U', 2, 0][..],
        &[254, 0, 101, 0, b'G', b'N', b'U', 3, 0][..],
        &[254, 0, 101, 0, b'B', b'A', b'D', 1][..],
    ] {
        assert_eq!(
            classify_gnu_secret_s2k(KeyVersion::V4, suffix),
            Err(MutationMaterialError::UnsupportedTskLayout),
        );
    }
    assert_eq!(
        classify_gnu_secret_s2k(KeyVersion::V6, &[254, 0, 101, 0, b'G', b'N', b'U', 1],),
        Err(MutationMaterialError::UnsupportedTskLayout),
    );
    assert_eq!(
        classify_gnu_secret_s2k(KeyVersion::V4, &[254, 9, 3, 8]),
        Ok(None),
    );
}

fn packet(public_body: &[u8], secret_suffix: u8) -> SecretPacketOverlay {
    let mut secret_body = public_body.to_vec();
    secret_body.push(secret_suffix);
    SecretPacketOverlay {
        public_body: public_body.to_vec(),
        secret_body: Zeroizing::new(secret_body.clone()),
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
                    SubkeySecretPacketOverlay::Material(packet(
                        fingerprint.as_bytes(),
                        *secret_suffix,
                    )),
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
fn secret_overlay_merge_does_not_count_dummy_primary_as_secret_capability() {
    fn with_dummy_primary() -> SecretCertificateOverlay {
        let mut value = overlay(&[("shared", 20)]);
        value.primary = Some(PrimarySecretPacketOverlay::GnuDummyStub(packet(&[1], 10)));
        value
    }

    fn without_primary() -> SecretCertificateOverlay {
        let mut value = overlay(&[("shared", 20)]);
        value.primary = None;
        value
    }

    let forward =
        merge_secret_certificate_overlays(Some(with_dummy_primary()), Some(without_primary()))
            .expect("dummy primary and filtered TSK should merge")
            .expect("two secret inputs should produce an overlay");
    let reverse =
        merge_secret_certificate_overlays(Some(without_primary()), Some(with_dummy_primary()))
            .expect("filtered TSK and dummy primary should merge")
            .expect("two secret inputs should produce an overlay");

    assert!(forward.overlay.primary.is_some());
    assert!(reverse.overlay.primary.is_some());
    assert!(!forward.existing_contributed);
    assert!(!forward.incoming_contributed);
    assert!(!reverse.existing_contributed);
    assert!(!reverse.incoming_contributed);
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
fn secret_overlay_merge_rejects_conflicting_primary_material_in_both_orders() {
    for (existing_suffix, incoming_suffix) in [(10, 11), (11, 10)] {
        let mut existing = overlay(&[]);
        existing.primary = Some(PrimarySecretPacketOverlay::Material(packet(
            &[1],
            existing_suffix,
        )));
        let mut incoming = overlay(&[]);
        incoming.primary = Some(PrimarySecretPacketOverlay::Material(packet(
            &[1],
            incoming_suffix,
        )));

        let error = match merge_secret_certificate_overlays(Some(existing), Some(incoming)) {
            Err(error) => error,
            Ok(_) => panic!("different primary secret bodies must fail closed"),
        };
        assert_eq!(error, SecretOverlayMergeError::ConflictingSecretMaterial);
    }
}

#[test]
fn secret_overlay_merge_ignores_equivalent_packet_framing() {
    fn with_alternate_framing() -> SecretCertificateOverlay {
        let mut value = overlay(&[]);
        let Some(PrimarySecretPacketOverlay::Material(packet)) = value.primary.as_mut() else {
            panic!("test overlay has material primary");
        };
        packet.secret_packet = Zeroizing::new(vec![0xc5, 0x02, 1, 10]);
        value
    }

    let forward =
        merge_secret_certificate_overlays(Some(overlay(&[])), Some(with_alternate_framing()))
            .expect("equivalent packet bodies with different framing must merge")
            .expect("two secret inputs produce an overlay");
    let reverse =
        merge_secret_certificate_overlays(Some(with_alternate_framing()), Some(overlay(&[])))
            .expect("equivalent packet bodies with different framing must merge")
            .expect("two secret inputs produce an overlay");

    assert!(!forward.existing_contributed);
    assert!(!forward.incoming_contributed);
    let forward_packet = forward.overlay.primary.expect("primary retained");
    let reverse_packet = reverse.overlay.primary.expect("primary retained");
    assert_eq!(
        forward_packet.packet().secret_packet.as_slice(),
        reverse_packet.packet().secret_packet.as_slice(),
        "equivalent packet framing must use a stable representation",
    );

    fn with_alternate_subkey_framing() -> SecretCertificateOverlay {
        let mut value = overlay(&[("shared", 20)]);
        let Some(SubkeySecretPacketOverlay::Material(packet)) = value.subkeys.get_mut("shared")
        else {
            panic!("test overlay has material subkey");
        };
        packet.secret_packet =
            Zeroizing::new(vec![0xc7, 0x07, b's', b'h', b'a', b'r', b'e', b'd', 20]);
        value
    }

    let forward = merge_secret_certificate_overlays(
        Some(overlay(&[("shared", 20)])),
        Some(with_alternate_subkey_framing()),
    )
    .expect("equivalent subkey packet bodies with different framing must merge")
    .expect("two secret inputs produce an overlay");
    let reverse = merge_secret_certificate_overlays(
        Some(with_alternate_subkey_framing()),
        Some(overlay(&[("shared", 20)])),
    )
    .expect("equivalent subkey packet bodies with different framing must merge")
    .expect("two secret inputs produce an overlay");
    assert_eq!(
        forward
            .overlay
            .subkeys
            .get("shared")
            .expect("subkey retained")
            .packet()
            .secret_packet
            .as_slice(),
        reverse
            .overlay
            .subkeys
            .get("shared")
            .expect("subkey retained")
            .packet()
            .secret_packet
            .as_slice(),
        "equivalent subkey packet framing must use a stable representation",
    );
}

#[test]
fn secret_overlay_merge_rejects_conflicting_subkey_material_in_both_orders() {
    for (existing_suffix, incoming_suffix) in [(20, 30), (30, 20)] {
        let error = match merge_secret_certificate_overlays(
            Some(overlay(&[("shared", existing_suffix)])),
            Some(overlay(&[("shared", incoming_suffix)])),
        ) {
            Err(error) => error,
            Ok(_) => panic!("different subkey secret bodies must fail closed"),
        };
        assert_eq!(error, SecretOverlayMergeError::ConflictingSecretMaterial);
    }
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
fn projection_and_local_rebuild_preserve_gnu_dummy_subkey_packet() {
    let source = generated_uniform_version_secret(KeyVersion::V4, 0x474e_5553_5542_4b59);

    for usage in [254, 255] {
        let input = secret_certificate_with_gnu_subkey(&source, usage, 1, &[]);
        let input_stream =
            RawPacketStream::parse(&input, MAX_KEY_PACKETS).expect("parse GNU dummy-subkey TSK");
        let original_subkey = input_stream
            .packets()
            .iter()
            .find(|packet| packet.tag() == SECRET_SUBKEY_TAG)
            .map(|packet| input_stream.raw(packet).to_vec())
            .expect("input contains a GNU dummy secret subkey");

        let (projection, overlay) =
            project_secret_certificate(&input).expect("project GNU dummy-subkey TSK");
        assert!(!overlay.has_secret_primary());
        assert_eq!(overlay.subkeys.len(), 1);
        assert_eq!(overlay.secret_subkey_fingerprints().count(), 0);

        let rebuilt = rebuild_secret_certificate(&projection, &overlay)
            .expect("rebuild GNU dummy-subkey TSK");
        let rebuilt_stream = RawPacketStream::parse(&rebuilt, MAX_KEY_PACKETS)
            .expect("parse rebuilt GNU dummy-subkey TSK");
        let rebuilt_subkey = rebuilt_stream
            .packets()
            .iter()
            .find(|packet| packet.tag() == SECRET_SUBKEY_TAG)
            .map(|packet| rebuilt_stream.raw(packet))
            .expect("rebuilt output contains the GNU dummy subkey");
        assert_eq!(rebuilt_subkey, original_subkey);

        assert!(
            rebuild_transferable_secret_certificate(&projection, &overlay)
                .expect("filter GNU dummy subkey from transferable output")
                .is_none()
        );
    }
}

#[test]
fn projection_rejects_unsupported_gnu_primary_layouts() {
    let source = generated_uniform_version_secret(KeyVersion::V4, 0x474e_5555_4e53_5550);

    for (mode, trailing, case) in [
        (1, &[0][..], "mode 1001 with trailing data"),
        (2, &[1, b'1'][..], "mode 1002 card reference"),
        (3, &[1, b'('][..], "mode 1003 internal representation"),
        (4, &[][..], "unknown GNU mode"),
    ] {
        let input = secret_certificate_with_gnu_primary(&source, 254, 0, 0, mode, trailing);
        assert_eq!(
            project_secret_certificate(&input)
                .err()
                .unwrap_or_else(|| panic!("{case} must not be portable secret material")),
            MutationMaterialError::UnsupportedTskLayout,
            "{case}",
        );
    }
}

#[test]
fn projection_rejects_unsupported_gnu_subkey_layouts() {
    let source = generated_uniform_version_secret(KeyVersion::V4, 0x474e_5553_5542_554e);

    for (mode, trailing, case) in [
        (1, &[0][..], "mode 1001 with trailing data"),
        (2, &[1, b'1'][..], "mode 1002 card reference"),
        (3, &[1, b'('][..], "mode 1003 internal representation"),
        (4, &[][..], "unknown GNU mode"),
    ] {
        let input = secret_certificate_with_gnu_subkey(&source, 254, mode, trailing);
        assert_eq!(
            project_secret_certificate(&input)
                .err()
                .unwrap_or_else(|| panic!("{case} must not be portable secret material")),
            MutationMaterialError::UnsupportedTskLayout,
            "{case}",
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
