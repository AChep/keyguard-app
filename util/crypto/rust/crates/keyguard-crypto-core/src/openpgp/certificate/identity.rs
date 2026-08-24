//! Stable identifiers for exact OpenPGP identity packet bodies.

use crate::openpgp::format::hex_upper;

const ID_DOMAIN: &[u8] = b"Keyguard OpenPGP identity v1\0";

/// Returns a stable, display-independent identifier for an exact User ID or
/// User Attribute packet body.
pub(crate) fn identity_id(tag: u8, body: &[u8]) -> String {
    let mut input = Vec::with_capacity(ID_DOMAIN.len() + 1 + 4 + body.len());
    input.extend_from_slice(ID_DOMAIN);
    input.push(tag);
    input.extend_from_slice(&u32::try_from(body.len()).unwrap_or(u32::MAX).to_be_bytes());
    input.extend_from_slice(body);
    let digest = aws_lc_rs::digest::digest(&aws_lc_rs::digest::SHA256, &input);
    format!("v1:{}", hex_upper(digest.as_ref()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_id_is_exact_and_packet_type_scoped() {
        assert_eq!(identity_id(13, b"alice"), identity_id(13, b"alice"));
        assert_ne!(identity_id(13, b"alice"), identity_id(13, b"Alice"));
        assert_ne!(identity_id(13, b"alice"), identity_id(17, b"alice"));
    }
}
