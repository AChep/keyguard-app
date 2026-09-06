//! Canonical textual formatting for OpenPGP identifiers.
//!
//! These helpers perform representation only. They deliberately carry no
//! certificate-policy meaning, so packet and certificate layers can use them
//! without depending on policy evaluation.

use pgp::types::KeyDetails;

pub(crate) fn normalize_fingerprint(value: &str) -> String {
    value
        .bytes()
        .filter(u8::is_ascii_alphanumeric)
        .map(|byte| char::from(byte.to_ascii_uppercase()))
        .collect()
}

pub(crate) fn fingerprint_hex(key: &impl KeyDetails) -> String {
    hex_upper(key.fingerprint().as_bytes())
}

pub(crate) fn hex_upper(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    let mut output = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        output.push(char::from(HEX[usize::from(byte >> 4)]));
        output.push(char::from(HEX[usize::from(byte & 0x0f)]));
    }
    output
}

/// Writer that refuses to grow beyond a caller-planned allocation.
pub(crate) struct FixedCapacityWriter<'a>(pub(crate) &'a mut Vec<u8>);

impl std::io::Write for FixedCapacityWriter<'_> {
    fn write(&mut self, buffer: &[u8]) -> std::io::Result<usize> {
        if buffer.len() > self.0.capacity().saturating_sub(self.0.len()) {
            return Err(std::io::Error::other("output exceeded planned capacity"));
        }
        self.0.extend_from_slice(buffer);
        Ok(buffer.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fingerprint_normalization_matches_gnupg_style_input() {
        assert_eq!(
            normalize_fingerprint("d0bb cfbb-250d:3bb0"),
            "D0BBCFBB250D3BB0"
        );
        assert_eq!(normalize_fingerprint("d0bg"), "D0BG");
    }

    #[test]
    fn hexadecimal_output_is_uppercase_and_fixed_width() {
        assert_eq!(hex_upper(&[0x00, 0x0f, 0xa5, 0xff]), "000FA5FF");
    }
}
