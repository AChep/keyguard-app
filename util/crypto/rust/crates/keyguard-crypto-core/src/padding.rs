//! Fixed-work PKCS#7 validation for the 16-byte CBC ciphers exposed by the ABI.

pub(crate) const CBC_BLOCK_BYTES: usize = 16;

/// Returns the unpadded length of a final CBC block.
///
/// Every byte in the block is inspected before validity is reported. The mask
/// selects only the claimed padding suffix, while the separate range bit
/// rejects padding lengths outside `1..=16`.
pub(crate) fn pkcs7_unpadded_block_length(block: &[u8]) -> Option<usize> {
    if block.len() != CBC_BLOCK_BYTES {
        return None;
    }

    let padding = block[CBC_BLOCK_BYTES - 1];
    let padding_u16 = u16::from(padding);
    let mut mismatch = 0_u8;
    for distance in 1..=CBC_BLOCK_BYTES {
        let distance_u16 = distance as u16;
        // A subtraction underflow produces 0xff in the high byte. Inverting
        // it yields 0xff exactly when this byte belongs to the claimed suffix.
        let suffix_mask = !((padding_u16.wrapping_sub(distance_u16) >> 8) as u8);
        mismatch |= (block[CBC_BLOCK_BYTES - distance] ^ padding) & suffix_mask;
    }

    let padding_in_range = (padding.wrapping_sub(1) < CBC_BLOCK_BYTES as u8) as u8;
    let invalid = mismatch | (padding_in_range ^ 1);
    if invalid == 0 {
        Some(CBC_BLOCK_BYTES - usize::from(padding))
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::{CBC_BLOCK_BYTES, pkcs7_unpadded_block_length};

    #[test]
    fn accepts_every_valid_padding_length() {
        for padding in 1..=CBC_BLOCK_BYTES {
            let mut block = [0x5a; CBC_BLOCK_BYTES];
            block[CBC_BLOCK_BYTES - padding..].fill(padding as u8);
            assert_eq!(
                pkcs7_unpadded_block_length(&block),
                Some(CBC_BLOCK_BYTES - padding)
            );
        }
    }

    #[test]
    fn rejects_out_of_range_and_every_mismatched_suffix_position() {
        let mut zero = [0x5a; CBC_BLOCK_BYTES];
        zero[CBC_BLOCK_BYTES - 1] = 0;
        assert_eq!(pkcs7_unpadded_block_length(&zero), None);

        let out_of_range = [0x11; CBC_BLOCK_BYTES];
        assert_eq!(pkcs7_unpadded_block_length(&out_of_range), None);

        for padding in 2..=CBC_BLOCK_BYTES {
            for mismatch_distance in 2..=padding {
                let mut block = [0x5a; CBC_BLOCK_BYTES];
                block[CBC_BLOCK_BYTES - padding..].fill(padding as u8);
                block[CBC_BLOCK_BYTES - mismatch_distance] ^= 1;
                assert_eq!(pkcs7_unpadded_block_length(&block), None);
            }
        }
    }
}
