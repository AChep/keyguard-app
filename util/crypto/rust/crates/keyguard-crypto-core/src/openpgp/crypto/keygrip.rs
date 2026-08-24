//! Concrete libgcrypt keygrip computation and algorithm naming.
//!
//! These helpers reproduce GnuPG's public-parameter digests exactly; which
//! components are indexed or exported is decided by higher layers.

use pgp::{
    crypto::{ecc_curve::ECCCurve, public_key::PublicKeyAlgorithm},
    types::PublicParams,
};

use crate::openpgp::{
    format::hex_upper,
    packet::{serialize_params, take_mpi},
};

pub(crate) fn algorithm_name(algorithm: PublicKeyAlgorithm) -> String {
    match algorithm {
        PublicKeyAlgorithm::RSA | PublicKeyAlgorithm::RSAEncrypt | PublicKeyAlgorithm::RSASign => {
            "RSA".to_owned()
        }
        PublicKeyAlgorithm::Elgamal | PublicKeyAlgorithm::ElgamalEncrypt => "ELGAMAL".to_owned(),
        PublicKeyAlgorithm::DSA => "DSA".to_owned(),
        PublicKeyAlgorithm::ECDH => "ECDH".to_owned(),
        PublicKeyAlgorithm::ECDSA => "ECDSA".to_owned(),
        PublicKeyAlgorithm::EdDSALegacy => "EDDSA".to_owned(),
        PublicKeyAlgorithm::X25519 => "X25519".to_owned(),
        PublicKeyAlgorithm::X448 => "X448".to_owned(),
        PublicKeyAlgorithm::Ed25519 => "ED25519".to_owned(),
        PublicKeyAlgorithm::Ed448 => "ED448".to_owned(),
        _ => format!("ALGO_{}", u8::from(algorithm)),
    }
}

/// Computes the libgcrypt/GnuPG keygrip for supported public parameters.
pub(crate) fn keygrip(params: &PublicParams) -> Option<String> {
    let input = match params {
        PublicParams::RSA(_) => {
            let bytes = serialize_params(params)?;
            let (modulus, _) = take_mpi(&bytes)?;
            if modulus.first().is_some_and(|byte| byte & 0x80 != 0) {
                let mut signed = Vec::with_capacity(modulus.len() + 1);
                signed.push(0);
                signed.extend_from_slice(modulus);
                signed
            } else {
                modulus.to_vec()
            }
        }
        PublicParams::Ed25519(params) => {
            ecc_keygrip_input(ECCCurve::Ed25519Legacy, params.key.as_bytes())?
        }
        PublicParams::X25519(params) => {
            ecc_keygrip_input(ECCCurve::Curve25519Legacy, params.key.as_bytes())?
        }
        PublicParams::EdDSALegacy(params) => {
            let bytes = serialize_params(params)?;
            let q = serialized_ecc_point(&bytes)?;
            let q = q.strip_prefix(&[0x40]).unwrap_or(q);
            ecc_keygrip_input(params.curve(), q)?
        }
        PublicParams::ECDH(params) => {
            let bytes = serialize_params(params)?;
            let q = serialized_ecc_point(&bytes)?;
            let q = if params.curve() == ECCCurve::Curve25519Legacy {
                q.strip_prefix(&[0x40]).unwrap_or(q)
            } else {
                q
            };
            ecc_keygrip_input(params.curve(), q)?
        }
        PublicParams::ECDSA(params) => {
            let bytes = serialize_params(params)?;
            ecc_keygrip_input(params.curve(), serialized_ecc_point(&bytes)?)?
        }
        _ => return None,
    };
    let digest = aws_lc_rs::digest::digest(&aws_lc_rs::digest::SHA1_FOR_LEGACY_USE_ONLY, &input);
    Some(hex_upper(digest.as_ref()))
}

fn serialized_ecc_point(bytes: &[u8]) -> Option<&[u8]> {
    let oid_length = usize::from(*bytes.first()?);
    let mpi = bytes.get(1 + oid_length..)?;
    take_mpi(mpi).map(|(value, _)| value)
}

fn ecc_keygrip_input(curve: ECCCurve, q: &[u8]) -> Option<Vec<u8>> {
    let (p, a, b, g, n) = decoded_curve_constants(&curve)?;
    let mut output = Vec::new();
    for (name, value) in [
        (b'p', p.as_slice()),
        (b'a', a.as_slice()),
        (b'b', b.as_slice()),
        (b'g', g.as_slice()),
        (b'n', n.as_slice()),
        (b'q', q),
    ] {
        output.push(b'(');
        output.extend_from_slice(b"1:");
        output.push(name);
        output.extend_from_slice(value.len().to_string().as_bytes());
        output.push(b':');
        output.extend_from_slice(value);
        output.push(b')');
    }
    Some(output)
}

type CurveConstants = (Vec<u8>, Vec<u8>, Vec<u8>, Vec<u8>, Vec<u8>);

/// Decodes each supported curve's constants once per process instead of on
/// every keygrip computation.
fn decoded_curve_constants(curve: &ECCCurve) -> Option<&'static CurveConstants> {
    static SUPPORTED: &[ECCCurve] = &[
        ECCCurve::Ed25519Legacy,
        ECCCurve::Curve25519Legacy,
        ECCCurve::P256,
        ECCCurve::P384,
        ECCCurve::P521,
    ];
    static DECODED: std::sync::LazyLock<Vec<(ECCCurve, CurveConstants)>> =
        std::sync::LazyLock::new(|| {
            SUPPORTED
                .iter()
                .filter_map(|curve| Some((curve.clone(), curve_constants(curve)?)))
                .collect()
        });
    DECODED
        .iter()
        .find(|(supported, _)| supported == curve)
        .map(|(_, constants)| constants)
}

fn curve_constants(curve: &ECCCurve) -> Option<CurveConstants> {
    let values = match curve {
        ECCCurve::Ed25519Legacy => (
            "7FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFED",
            "01",
            "2DFC9311D490018C7338BF8688861767FF8FF5B2BEBE27548A14B235ECA6874A",
            concat!(
                "04",
                "216936D3CD6E53FEC0A4E231FDD6DC5C692CC7609525A7B2C9562D608F25D51A",
                "6666666666666666666666666666666666666666666666666666666666666658"
            ),
            "1000000000000000000000000000000014DEF9DEA2F79CD65812631A5CF5D3ED",
        ),
        ECCCurve::Curve25519Legacy => (
            "7FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFED",
            "01DB41",
            "01",
            concat!(
                "04",
                "0000000000000000000000000000000000000000000000000000000000000009",
                "20AE19A1B8A086B4E01EDD2C7748D14C923D4D7E6D7C61B229E9C5A27ECED3D9"
            ),
            "1000000000000000000000000000000014DEF9DEA2F79CD65812631A5CF5D3ED",
        ),
        ECCCurve::P256 => (
            "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF",
            "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC",
            "5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B",
            concat!(
                "04",
                "6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296",
                "4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5"
            ),
            "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551",
        ),
        ECCCurve::P384 => (
            concat!(
                "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
                "FFFFFFFFFFFFFFFEFFFFFFFF0000000000000000FFFFFFFF"
            ),
            concat!(
                "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
                "FFFFFFFFFFFFFFFEFFFFFFFF0000000000000000FFFFFFFC"
            ),
            concat!(
                "B3312FA7E23EE7E4988E056BE3F82D19181D9C6EFE814112",
                "0314088F5013875AC656398D8A2ED19D2A85C8EDD3EC2AEF"
            ),
            concat!(
                "04",
                "AA87CA22BE8B05378EB1C71EF320AD746E1D3B628BA79B9859F741E082542A38",
                "5502F25DBF55296C3A545E3872760AB73617DE4A96262C6F5D9E98BF9292DC29",
                "F8F41DBD289A147CE9DA3113B5F0B8C00A60B1CE1D7E819D7A431D7C90EA0E5F"
            ),
            concat!(
                "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
                "C7634D81F4372DDF581A0DB248B0A77AECEC196ACCC52973"
            ),
        ),
        ECCCurve::P521 => (
            "01FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
            "01FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFC",
            concat!(
                "51953EB9618E1C9A1F929A21A0B68540EEA2DA725B99B315F3B8B489918E",
                "F109E156193951EC7E937B1652C0BD3BB1BF073573DF883D2C34F1EF451FD46",
                "B503F00"
            ),
            concat!(
                "04",
                "00C6858E06B70404E9CD9E3ECB662395B4429C648139053FB521F828AF606B",
                "4D3DBAA14B5E77EFE75928FE1DC127A2FFA8DE3348B3C1856A429BF97E7E31C",
                "2E5BD66",
                "011839296A789A3BC0045C8A5FB42C7D1BD998F54449579B446817AFBD1727",
                "3E662C97EE72995EF42640C550B9013FAD0761353C7086A272C24088BE9476",
                "9FD16650"
            ),
            "01FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFA51868783BF2F966B7FCC0148F709A5D03BB5C9B8899C47AEBB6FB71E91386409",
        ),
        _ => return None,
    };
    Some((
        decode_hex(values.0)?,
        decode_hex(values.1)?,
        decode_hex(values.2)?,
        decode_hex(values.3)?,
        decode_hex(values.4)?,
    ))
}

fn decode_hex(value: &str) -> Option<Vec<u8>> {
    if !value.len().is_multiple_of(2) {
        return None;
    }
    value
        .as_bytes()
        .as_chunks::<2>()
        .0
        .iter()
        .map(|pair| {
            let high = hex_nibble(pair[0])?;
            let low = hex_nibble(pair[1])?;
            Some((high << 4) | low)
        })
        .collect()
}

fn hex_nibble(value: u8) -> Option<u8> {
    match value {
        b'0'..=b'9' => Some(value - b'0'),
        b'a'..=b'f' => Some(value - b'a' + 10),
        b'A'..=b'F' => Some(value - b'A' + 10),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::decode_hex;

    #[test]
    fn keygrip_hex_decoder_rejects_malformed_input() {
        assert_eq!(decode_hex("0A10"), Some(vec![0x0a, 0x10]));
        assert_eq!(decode_hex("0"), None);
        assert_eq!(decode_hex("GG"), None);
    }
}
