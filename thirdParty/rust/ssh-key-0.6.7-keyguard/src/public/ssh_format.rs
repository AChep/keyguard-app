//! Support for OpenSSH-formatted public keys, a.k.a. `SSH-format`.
//!
//! These keys have the form:
//!
//! ```text
//! <algorithm id> <base64 key data> <comment>
//! ```
//!
//! ## Example
//!
//! ```text
//! ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILM+rvN+ot98qgEN796jTiQfZfG1KaT0PtFDJ/XFSqti user@example.com
//! ```

use crate::Result;
use core::str;
use encoding::{Base64Writer, Encode};

#[cfg(feature = "alloc")]
use {alloc::string::String, encoding::CheckedSum};

/// OpenSSH public key (a.k.a. `SSH-format`) decoder/encoder.
#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct SshFormat<'a> {
    /// Algorithm identifier
    pub(crate) algorithm_id: &'a str,

    /// Base64-encoded key data
    pub(crate) base64_data: &'a [u8],

    /// Comment
    #[cfg_attr(not(feature = "alloc"), allow(dead_code))]
    pub(crate) comment: &'a str,
}

impl<'a> SshFormat<'a> {
    /// Parse the given binary data.
    pub(crate) fn decode(mut bytes: &'a [u8]) -> Result<Self> {
        let algorithm_id = decode_segment_str(&mut bytes)?;
        let base64_data = decode_segment(&mut bytes)?;
        let comment = str::from_utf8(bytes)?.trim_end();

        if algorithm_id.is_empty() || base64_data.is_empty() {
            // TODO(tarcieri): better errors for these cases?
            return Err(encoding::Error::Length.into());
        }

        Ok(Self {
            algorithm_id,
            base64_data,
            comment,
        })
    }

    /// Encode data with OpenSSH public key encapsulation.
    pub(crate) fn encode<'o, K>(
        algorithm_id: &str,
        key: &K,
        comment: &str,
        out: &'o mut [u8],
    ) -> Result<&'o str>
    where
        K: Encode,
    {
        let mut offset = 0;
        encode_str(out, &mut offset, algorithm_id)?;
        encode_str(out, &mut offset, " ")?;

        let mut writer = Base64Writer::new(&mut out[offset..])?;
        key.encode(&mut writer)?;
        let base64_len = writer.finish()?.len();

        offset = offset
            .checked_add(base64_len)
            .ok_or(encoding::Error::Length)?;

        if !comment.is_empty() {
            encode_str(out, &mut offset, " ")?;
            encode_str(out, &mut offset, comment)?;
        }

        Ok(str::from_utf8(&out[..offset])?)
    }

    /// Encode string with OpenSSH public key encapsulation.
    #[cfg(feature = "alloc")]
    pub(crate) fn encode_string<K>(algorithm_id: &str, key: &K, comment: &str) -> Result<String>
    where
        K: Encode,
    {
        let encoded_len = [
            2, // interstitial spaces
            algorithm_id.len(),
            base64_len_approx(key.encoded_len()?),
            comment.len(),
        ]
        .checked_sum()?;

        let mut out = vec![0u8; encoded_len];
        let actual_len = Self::encode(algorithm_id, key, comment, &mut out)?.len();
        out.truncate(actual_len);
        Ok(String::from_utf8(out)?)
    }
}

/// Get the estimated length of data when encoded as Base64.
///
/// This is an upper bound where the actual length might be slightly shorter,
/// and can be used to estimate the capacity of an output buffer. However, the
/// final result may need to be sliced and should use the actual encoded length
/// rather than this estimate.
#[cfg(feature = "alloc")]
fn base64_len_approx(input_len: usize) -> usize {
    (((input_len.saturating_mul(4)) / 3).saturating_add(3)) & !3
}

/// Parse a segment of the public key.
fn decode_segment<'a>(bytes: &mut &'a [u8]) -> Result<&'a [u8]> {
    let start = *bytes;
    let mut len = 0usize;

    loop {
        match *bytes {
            [b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'+' | b'-' | b'/' | b'=' | b'@' | b'.', rest @ ..] =>
            {
                // Valid character; continue
                *bytes = rest;
                len = len.checked_add(1).ok_or(encoding::Error::Length)?;
            }
            [b' ', rest @ ..] => {
                // Encountered space; we're done
                *bytes = rest;
                return start
                    .get(..len)
                    .ok_or_else(|| encoding::Error::Length.into());
            }
            [_, ..] => {
                // Invalid character
                return Err(encoding::Error::CharacterEncoding.into());
            }
            [] => {
                // End of input, could be truncated or could be no comment
                return start
                    .get(..len)
                    .ok_or_else(|| encoding::Error::Length.into());
            }
        }
    }
}

/// Parse a segment of the public key as a `&str`.
fn decode_segment_str<'a>(bytes: &mut &'a [u8]) -> Result<&'a str> {
    str::from_utf8(decode_segment(bytes)?).map_err(|_| encoding::Error::CharacterEncoding.into())
}

/// Encode a segment of the public key.
fn encode_str(out: &mut [u8], offset: &mut usize, s: &str) -> Result<()> {
    let bytes = s.as_bytes();

    if out.len()
        < offset
            .checked_add(bytes.len())
            .ok_or(encoding::Error::Length)?
    {
        return Err(encoding::Error::Length.into());
    }

    out[*offset..][..bytes.len()].copy_from_slice(bytes);
    *offset = offset
        .checked_add(bytes.len())
        .ok_or(encoding::Error::Length)?;

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::SshFormat;

    const EXAMPLE_KEY: &str = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILM+rvN+ot98qgEN796jTiQfZfG1KaT0PtFDJ/XFSqti user@example.com";

    #[test]
    fn decode() {
        let encapsulation = SshFormat::decode(EXAMPLE_KEY.as_bytes()).unwrap();
        assert_eq!(encapsulation.algorithm_id, "ssh-ed25519");
        assert_eq!(
            encapsulation.base64_data,
            b"AAAAC3NzaC1lZDI1NTE5AAAAILM+rvN+ot98qgEN796jTiQfZfG1KaT0PtFDJ/XFSqti"
        );
        assert_eq!(encapsulation.comment, "user@example.com");
    }
}
