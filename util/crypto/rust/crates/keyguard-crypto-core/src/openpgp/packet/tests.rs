use pgp::{
    armor::{self, BlockType},
    ser::Serialize,
};
use std::io::{BufReader, Cursor, Read};

use super::*;

const MINIMAL_V4_PRIMARY: [u8; 8] = [0xc6, 0x06, 0x04, 0, 0, 0, 0, 1];
const OTHER_MINIMAL_V4_PRIMARY: [u8; 8] = [0xc6, 0x06, 0x04, 0, 0, 0, 1, 1];
const REVOKED_PUBLIC_KEY: &[u8] =
    include_bytes!("../../../tests/fixtures/openpgp/designated-revoked-public.asc");

struct RawPackets<'a>(&'a [u8]);

impl Serialize for RawPackets<'_> {
    fn to_writer<W: std::io::Write>(&self, writer: &mut W) -> pgp::errors::Result<()> {
        writer.write_all(self.0)?;
        Ok(())
    }

    fn write_len(&self) -> usize {
        self.0.len()
    }
}

fn armor_packets(packets: &[u8], block_type: BlockType) -> Vec<u8> {
    let mut output = Vec::new();
    armor::write(&RawPackets(packets), block_type, &mut output, None, true).expect("armor packets");
    output
}

fn replace_armor_checksum(input: &[u8], replacement: Option<&[u8]>) -> Vec<u8> {
    let start = find_bytes(input, b"\n=").expect("armor checksum line") + 1;
    let end = start
        + input[start..]
            .iter()
            .position(|byte| *byte == b'\n')
            .expect("checksum line ending");
    let mut output = Vec::with_capacity(input.len() + replacement.map_or(0, <[u8]>::len));
    output.extend_from_slice(&input[..start]);
    if let Some(replacement) = replacement {
        output.extend_from_slice(replacement);
        output.extend_from_slice(&input[end..]);
    } else {
        output.extend_from_slice(&input[end + 1..]);
    }
    output
}

fn revocation_signature_packet() -> Vec<u8> {
    let stream = RawPacketStream::parse(REVOKED_PUBLIC_KEY, MAX_CERTIFICATE_PACKETS)
        .expect("parse revoked certificate fixture");
    let packet = stream
        .packets()
        .iter()
        .find(|packet| {
            packet.tag() == SIGNATURE_TAG
                && stream
                    .body(packet)
                    .get(1)
                    .copied()
                    .is_some_and(|signature_type| matches!(signature_type, 0x20 | 0x28 | 0x30))
        })
        .expect("fixture contains revocation signature evidence");
    stream.raw(packet).to_vec()
}

#[test]
fn scanner_preserves_partial_packet_framing() {
    let mut data = vec![0xcb, 0xe9];
    data.extend(std::iter::repeat_n(b'a', 512));
    // Only the first partial chunk has the 512-octet minimum. A later
    // one-octet partial chunk is valid, and a fixed chunk terminates it.
    data.extend_from_slice(&[0xe0, b'b', 0x01, b'c']);

    let stream = RawPacketStream::parse(&data, 1).expect("parse partial packet");
    let packet = &stream.packets()[0];
    assert_eq!(packet.tag(), 11);
    assert_eq!(stream.raw(packet), data.as_slice());
    assert_eq!(
        stream.body(packet).as_slice(),
        [vec![b'a'; 512], vec![b'b', b'c']].concat()
    );
}

#[test]
fn scanner_rejects_excessive_partial_body_chunks() {
    let mut data = Vec::with_capacity(2 + 512 + MAX_PARTIAL_BODY_CHUNKS * 2 + 1);
    data.extend_from_slice(&[0xcb, 0xe9]);
    data.extend(std::iter::repeat_n(b'a', 512));
    for _ in 1..MAX_PARTIAL_BODY_CHUNKS {
        data.extend_from_slice(&[0xe0, b'a']);
    }
    data.push(0x00);

    assert_eq!(
        RawPacketStream::parse(&data, 1).expect_err("partial chunk limit must be enforced"),
        RawPacketError::ResourceLimit,
    );
}

#[test]
fn scanner_rejects_partial_lengths_for_certificate_packet_tags() {
    for tag in [2, 5, 6, 7, 10, 12, 13, 14, 17, 21, 40, 63] {
        let mut data = vec![0xc0 | tag, 0xe9];
        data.extend(std::iter::repeat_n(0, 512));
        data.push(0x00);

        assert_eq!(
            RawPacketStream::parse(&data, 1)
                .expect_err("certificate packet must reject a partial body length"),
            RawPacketError::Malformed,
            "tag {tag}",
        );
    }
}

#[test]
fn scanner_rejects_first_partial_chunks_below_512_octets() {
    for tag in [8, 9, 11, 18, 20] {
        for exponent in 0..9 {
            let chunk_len = 1usize << exponent;
            let mut data = vec![0xc0 | tag, 0xe0 | exponent];
            data.extend(std::iter::repeat_n(0, chunk_len));
            data.push(0x00);

            assert_eq!(
                RawPacketStream::parse(&data, 1)
                    .expect_err("first partial chunk must contain at least 512 octets"),
                RawPacketError::Malformed,
                "tag {tag}, chunk {chunk_len}",
            );
        }
    }
}

#[test]
fn scanner_accepts_partial_lengths_for_streaming_data_packet_tags() {
    for tag in [8, 9, 11, 18, 20] {
        let mut data = vec![0xc0 | tag, 0xe9];
        data.extend(std::iter::repeat_n(b'a', 512));
        data.extend_from_slice(&[0xe0, b'b', 0x01, b'c']);

        let stream = RawPacketStream::parse(&data, 1)
            .expect("streaming data packet may use partial body lengths");
        let packet = &stream.packets()[0];
        assert_eq!(packet.tag(), tag);
        assert_eq!(packet.body_len(), 514);
        assert_eq!(stream.raw(packet), data.as_slice());
    }
}

#[test]
fn scanner_only_accepts_legacy_indeterminate_lengths_for_data_packets() {
    for tag in [8, 9, 11] {
        let data = [0x80 | (tag << 2) | 0x03, b'a', b'b'];
        let stream = RawPacketStream::parse(&data, 1)
            .expect("historic data packet may use an indeterminate length");
        let packet = &stream.packets()[0];
        assert_eq!(packet.tag(), tag);
        assert_eq!(stream.body(packet).as_slice(), b"ab");
    }

    for tag in [2, 5, 6, 7, 10, 12, 13, 14] {
        let data = [0x80 | (tag << 2) | 0x03, b'a', b'b'];
        assert_eq!(
            RawPacketStream::parse(&data, 1)
                .expect_err("certificate packet must reject an indeterminate length"),
            RawPacketError::Malformed,
            "tag {tag}",
        );
    }
}

#[test]
fn scanner_rejects_unknown_critical_packet() {
    let data = [0xd6, 0x00];
    assert_eq!(
        RawPacketStream::parse(&data, 1).expect_err("unknown critical packet"),
        RawPacketError::Malformed,
    );
}

#[test]
fn parser_concatenates_multiple_armored_packet_blocks() {
    let first = [0xcd, 0x01, b'a'];
    let second = [0xcd, 0x01, b'b'];
    let first_armor = armor_packets(&first, BlockType::PublicKey);
    let mut document = replace_armor_checksum(&first_armor, Some(b"=A"));
    document.extend_from_slice(b"\ninter-block prose\n");
    document.extend_from_slice(&armor_packets(&second, BlockType::PublicKey));

    let stream = RawPacketStream::parse(&document, 2).expect("parse concatenated armor");
    assert_eq!(stream.packets().len(), 2);
    assert_eq!(stream.body(&stream.packets()[0]).as_slice(), b"a");
    assert_eq!(stream.body(&stream.packets()[1]).as_slice(), b"b");
}

#[test]
fn parser_accepts_consistently_quoted_armor_inside_prose() {
    let packet = [0xcd, 0x06, b'q', b'u', b'o', b't', b'e', b'd'];
    let armor = armor_packets(&packet, BlockType::PublicKey);
    let armor = replace_armor_checksum(&armor, Some(b"=A"));
    let mut quoted = b"forwarded key follows\n".to_vec();
    for line in armor.split_inclusive(|byte| *byte == b'\n') {
        quoted.extend_from_slice(b"> ");
        quoted.extend_from_slice(line);
    }
    quoted.extend_from_slice(b"end forwarded key\n");

    let stream = RawPacketStream::parse(&quoted, 1).expect("parse quoted armor");
    assert_eq!(stream.packets().len(), 1);
    assert_eq!(stream.body(&stream.packets()[0]).as_slice(), b"quoted");
}

#[test]
fn parser_ignores_valid_wrong_malformed_and_missing_crc24_footers() {
    let packet = [0xcd, 0x03, b'c', b'r', b'c'];
    let valid = armor_packets(&packet, BlockType::PublicKey);
    let cases = [
        ("valid", valid.clone()),
        ("wrong", replace_armor_checksum(&valid, Some(b"=AAAA"))),
        ("malformed", replace_armor_checksum(&valid, Some(b"=A"))),
        ("missing", replace_armor_checksum(&valid, None)),
        (
            "malformed with whitespace",
            replace_armor_checksum(&valid, Some(b" \t=not-base64")),
        ),
    ];

    for (label, armor) in cases {
        let stream = RawPacketStream::parse(&armor, 1)
            .unwrap_or_else(|error| panic!("{label} CRC24 footer must be ignored: {error:?}"));
        assert_eq!(stream.packets().len(), 1, "{label}");
        assert_eq!(stream.raw(&stream.packets()[0]), packet, "{label}");
    }
}

#[test]
fn crc24_tolerance_keeps_payload_headers_boundaries_and_types_strict() {
    let packet = [0xcd, 0x03, b'c', b'r', b'c'];
    let valid = armor_packets(&packet, BlockType::PublicKey);

    let mut malformed_payload = valid.clone();
    let payload_start =
        find_bytes(&malformed_payload, b"\n\n").expect("armor header separator") + 2;
    malformed_payload[payload_start] = b'!';
    assert_eq!(
        RawPacketStream::parse(&malformed_payload, 1).expect_err("malformed base64 payload"),
        RawPacketError::Malformed,
    );

    let misplaced_checksum = replace_armor_checksum(&valid, Some(b"=A\nAAAA"));
    assert_eq!(
        RawPacketStream::parse(&misplaced_checksum, 1)
            .expect_err("only the line adjacent to END can be a checksum"),
        RawPacketError::Malformed,
    );

    let mut malformed_header = valid.clone();
    let header_end = find_bytes(&malformed_header, b"\n\n").expect("armor header separator");
    malformed_header.splice(
        header_end + 1..header_end + 1,
        b"Malformed header\n".iter().copied(),
    );
    assert_eq!(
        RawPacketStream::parse(&malformed_header, 1).expect_err("malformed armor header"),
        RawPacketError::Malformed,
    );

    let mut mismatched_boundary = valid.clone();
    let footer_type = find_bytes(&mismatched_boundary, b"END PGP PUBLIC")
        .expect("public-key armor footer")
        + b"END PGP ".len();
    mismatched_boundary[footer_type] = b'X';
    assert_eq!(
        RawPacketStream::parse(&mismatched_boundary, 1).expect_err("mismatched armor boundary"),
        RawPacketError::Malformed,
    );

    assert_eq!(
        dearmor_bounded(&valid, Some(&BlockType::Signature)).expect_err("unexpected armor type"),
        RawPacketError::Malformed,
    );
}

#[test]
fn streaming_crc24_normalizer_rejects_oversized_footer_candidates() {
    let mut armor = b"-----BEGIN PGP MESSAGE-----\n\nYQ==\n=".to_vec();
    armor.extend(std::iter::repeat_n(
        b'A',
        dearmor::MAX_ARMOR_INPUT_LINE_BYTES,
    ));
    armor.extend_from_slice(b"\n-----END PGP MESSAGE-----\n");

    let mut reader = TolerantArmorReader::new(BufReader::new(Cursor::new(armor)));
    let mut output = Vec::new();
    let error = reader
        .read_to_end(&mut output)
        .expect_err("oversized checksum footer must be bounded");
    assert_eq!(error.kind(), std::io::ErrorKind::InvalidData);
}

#[test]
fn private_armor_decode_preallocates_for_secret_packet_bytes() {
    let packet = [0xc5, 0x01, 0x04];
    let armor = armor_packets(&packet, BlockType::PrivateKey);

    let decoded = decode_bounded(&armor).expect("decode private-key armor");
    let dearmored = dearmor_single(&armor, None).expect("dearmor private key directly");

    assert_eq!(decoded.as_slice(), packet);
    assert!(decoded.capacity() >= armor.len());
    assert_eq!(dearmored.as_slice(), packet);
    assert!(dearmored.capacity() >= armor.len());
}

#[test]
fn concatenated_private_armor_decode_does_not_grow_secret_accumulator() {
    let first = [0xc5, 0x01, 0x04];
    let second = [0xc5, 0x01, 0x05];
    let mut document = armor_packets(&first, BlockType::PrivateKey);
    document.extend_from_slice(b"\ninter-block prose\n");
    document.extend_from_slice(&armor_packets(&second, BlockType::PrivateKey));

    let decoded = decode_bounded(&document).expect("decode concatenated private-key armor");

    assert_eq!(
        decoded.as_slice(),
        [first.as_slice(), second.as_slice()].concat()
    );
    assert!(decoded.capacity() >= document.len());
}

#[test]
fn private_armor_larger_than_scratch_decodes_without_destination_growth() {
    let packets = vec![0x5a; DEARMOR_SCRATCH_BYTES * 2 + 17];
    let armor = armor_packets(&packets, BlockType::PrivateKey);

    let decoded = dearmor_single(&armor, None).expect("dearmor multi-chunk private key");

    assert_eq!(decoded.as_slice(), packets);
    assert!(decoded.capacity() >= armor.len());
}

#[test]
fn keyring_parser_recovers_at_the_next_primary_packet_after_junk() {
    let mut keyring = MINIMAL_V4_PRIMARY.to_vec();
    keyring.extend_from_slice(b"not an OpenPGP packet");
    keyring.extend_from_slice(&OTHER_MINIMAL_V4_PRIMARY);

    assert_eq!(
        RawPacketStream::parse(&keyring, 2).expect_err("strict parser rejects junk"),
        RawPacketError::Malformed,
    );
    let recovered =
        RawPacketStream::parse_transferable_keyring(&keyring, 2).expect("keyring parser recovers");
    assert_eq!(
        recovered
            .packets()
            .iter()
            .map(RawPacketSpan::tag)
            .collect::<Vec<_>>(),
        vec![PUBLIC_KEY_TAG],
    );
    assert_eq!(
        recovered.raw(&recovered.packets()[0]),
        OTHER_MINIMAL_V4_PRIMARY
    );
    assert!(recovered.packets()[0].recovered_after_tainted_certificate());
    assert_eq!(recovered.skipped_tainted_certificates(), 1);
}

#[test]
fn keyring_parser_rejects_terminal_corruption_before_revocation_evidence() {
    let mut keyring = MINIMAL_V4_PRIMARY.to_vec();
    keyring.push(0);
    keyring.extend_from_slice(&revocation_signature_packet());

    assert_eq!(
        RawPacketStream::parse_transferable_keyring(&keyring, 2)
            .expect_err("corrupt certificate tail must not be truncated"),
        RawPacketError::Malformed,
    );
}

#[test]
fn keyring_parser_rejects_terminal_unknown_critical_before_revocation_evidence() {
    let mut keyring = MINIMAL_V4_PRIMARY.to_vec();
    // RFC 9580 reserves packet tags 22 through 39 as unknown critical
    // packets.  Tag 22 must invalidate this certificate sequence.
    keyring.extend_from_slice(&[0xd6, 0x00]);
    keyring.extend_from_slice(&revocation_signature_packet());

    assert_eq!(
        RawPacketStream::parse_transferable_keyring(&keyring, 3)
            .expect_err("unknown critical packet must not hide revocation evidence"),
        RawPacketError::Malformed,
    );
}

#[test]
fn keyring_parser_recovers_after_unknown_critical_at_a_later_primary() {
    let mut keyring = MINIMAL_V4_PRIMARY.to_vec();
    keyring.extend_from_slice(&[0xd6, 0x00]);
    // Evidence after the critical packet may still revoke the damaged
    // certificate. Recovery must not publish the retained prefix without
    // this signature.
    keyring.extend_from_slice(&revocation_signature_packet());
    keyring.extend_from_slice(&OTHER_MINIMAL_V4_PRIMARY);

    let recovered = RawPacketStream::parse_transferable_keyring(&keyring, 2)
        .expect("later independent certificate must remain recoverable");
    assert_eq!(recovered.packets().len(), 1);
    assert_eq!(
        recovered.raw(&recovered.packets()[0]),
        OTHER_MINIMAL_V4_PRIMARY
    );
    assert!(recovered.packets()[0].recovered_after_tainted_certificate());
    assert_eq!(recovered.skipped_tainted_certificates(), 1);
}

#[test]
fn keyring_parser_does_not_recover_a_primary_from_unknown_critical_body() {
    let mut keyring = MINIMAL_V4_PRIMARY.to_vec();
    // The embedded primary-looking bytes belong to the complete declared
    // body of this unknown critical packet. Recovery may only start after
    // that body, at the independently framed primary that follows it.
    keyring.extend_from_slice(&[0xd6, MINIMAL_V4_PRIMARY.len() as u8]);
    keyring.extend_from_slice(&MINIMAL_V4_PRIMARY);
    keyring.extend_from_slice(&OTHER_MINIMAL_V4_PRIMARY);

    let recovered = RawPacketStream::parse_transferable_keyring(&keyring, 2)
        .expect("next primary after unknown critical body must remain recoverable");
    assert_eq!(recovered.packets().len(), 1);
    assert_eq!(
        recovered.raw(&recovered.packets()[0]),
        OTHER_MINIMAL_V4_PRIMARY
    );
    assert!(recovered.packets()[0].recovered_after_tainted_certificate());
    assert_eq!(recovered.skipped_tainted_certificates(), 1);
}

#[test]
fn keyring_parser_does_not_recover_a_certificate_from_truncated_packet_body() {
    let embedded_certificate =
        decode_bounded(REVOKED_PUBLIC_KEY).expect("decode embedded certificate fixture");
    let declared_body_len = u32::try_from(embedded_certificate.len() + 1)
        .expect("test certificate length fits packet header");
    let mut keyring = vec![0xcb, 0xff];
    keyring.extend_from_slice(&declared_body_len.to_be_bytes());
    keyring.extend_from_slice(&embedded_certificate);

    assert_eq!(
        RawPacketStream::parse_transferable_keyring(&keyring, MAX_CERTIFICATE_PACKETS)
            .expect_err("truncated literal body must not expose embedded certificate bytes"),
        RawPacketError::Malformed,
    );
}

#[test]
fn keyring_packet_limit_remains_cumulative_across_recovery() {
    let mut keyring = MINIMAL_V4_PRIMARY.to_vec();
    keyring.push(0);
    keyring.extend_from_slice(&OTHER_MINIMAL_V4_PRIMARY);
    keyring.extend_from_slice(&[0xcd, 0x00]);

    assert_eq!(
        RawPacketStream::parse_transferable_keyring(&keyring, 2)
            .expect_err("discarding a tainted certificate must not refund packet budget"),
        RawPacketError::ResourceLimit,
    );
}

#[test]
fn keyring_recovery_rejects_partial_length_primary_candidates() {
    let mut keyring = MINIMAL_V4_PRIMARY.to_vec();
    keyring.push(0);
    // RFC 9580 permits partial lengths only for streaming data packets.
    // Treating this as a primary boundary would also make recovery walk
    // every partial chunk before it has established the candidate's tag.
    keyring.extend_from_slice(&[0xc6, 0xe0, 0x04, 0x00]);
    keyring.extend_from_slice(&OTHER_MINIMAL_V4_PRIMARY);

    let recovered = RawPacketStream::parse_transferable_keyring(&keyring, 2)
        .expect("invalid partial primary must not hide the next certificate");
    assert_eq!(recovered.packets().len(), 1);
    assert_eq!(
        recovered.raw(&recovered.packets()[0]),
        OTHER_MINIMAL_V4_PRIMARY
    );
    assert_eq!(recovered.skipped_tainted_certificates(), 1);
}

#[test]
fn keyring_recovery_rejects_implausible_fixed_primary_candidates() {
    let mut keyring = MINIMAL_V4_PRIMARY.to_vec();
    keyring.push(0);
    // This fixed-length candidate encloses the real primary packet, but
    // its body starts with an impossible key version.
    keyring.extend_from_slice(&[0xc6, 0x09, 0xff]);
    keyring.extend_from_slice(&OTHER_MINIMAL_V4_PRIMARY);

    let recovered = RawPacketStream::parse_transferable_keyring(&keyring, 2)
        .expect("implausible primary must not swallow the real certificate");
    assert_eq!(recovered.packets().len(), 1);
    assert_eq!(
        recovered.raw(&recovered.packets()[0]),
        OTHER_MINIMAL_V4_PRIMARY
    );
    assert_eq!(recovered.skipped_tainted_certificates(), 1);
}

#[test]
fn keyring_recovery_window_is_bounded() {
    let mut keyring = MINIMAL_V4_PRIMARY.to_vec();
    keyring.extend(std::iter::repeat_n(0, MAX_KEYRING_RECOVERY_BYTES + 1));
    keyring.extend_from_slice(&MINIMAL_V4_PRIMARY);

    assert_eq!(
        RawPacketStream::parse_transferable_keyring(&keyring, 2)
            .expect_err("recovery must not scan the full request envelope"),
        RawPacketError::ResourceLimit,
    );
}
