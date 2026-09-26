//! Fixed-size wire frames. Acknowledgement means accepted and queued, not focused.

use crate::{Error, Result};

pub(crate) const TOKEN_LEN: usize = 32;
pub(crate) const REQUEST_LEN: usize = 40;
pub(crate) const ACK_LEN: usize = 8;
pub(crate) const ACK: [u8; ACK_LEN] = *b"KGI1ACK!";
const REQUEST_HEADER: &[u8; 8] = b"KGI1ACT!";
pub(crate) const MAX_METADATA: usize = 1024;

pub(crate) fn request(token: &[u8; TOKEN_LEN]) -> [u8; REQUEST_LEN] {
    let mut frame = [0; REQUEST_LEN];
    frame[..8].copy_from_slice(REQUEST_HEADER);
    frame[8..].copy_from_slice(token);
    frame
}

pub(crate) fn valid_request(frame: &[u8; REQUEST_LEN], token: &[u8; TOKEN_LEN]) -> bool {
    // Do not reveal a partially matching token through an early return.
    request(token)
        .iter()
        .zip(frame)
        .fold(0_u8, |difference, (a, b)| difference | (a ^ b))
        == 0
}

pub(crate) fn hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut text = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        text.push(DIGITS[(byte >> 4) as usize] as char);
        text.push(DIGITS[(byte & 15) as usize] as char);
    }
    text
}

pub(crate) fn encode_endpoint(endpoint: &str, token: &[u8; TOKEN_LEN]) -> Result<Vec<u8>> {
    if endpoint.is_empty() || endpoint.contains(['\n', '\r', '\0']) {
        return Err(Error::InvalidArgument.into());
    }
    let record = format!("KGI1\n{}\n{endpoint}\n", hex(token)).into_bytes();
    if record.len() > MAX_METADATA {
        return Err(Error::InvalidArgument.into());
    }
    Ok(record)
}

pub(crate) fn decode_endpoint(record: &[u8]) -> Result<(&str, [u8; TOKEN_LEN])> {
    if record.len() > MAX_METADATA {
        return Err(Error::Protocol.into());
    }
    let text = std::str::from_utf8(record).map_err(|_| Error::Protocol)?;
    let mut lines = text.split('\n');
    if lines.next() != Some("KGI1") {
        return Err(Error::Protocol.into());
    }
    let token_text = lines.next().ok_or(Error::Protocol)?;
    let endpoint = lines.next().ok_or(Error::Protocol)?;
    if token_text.len() != TOKEN_LEN * 2
        || endpoint.is_empty()
        || endpoint.contains(['\r', '\0'])
        || lines.next() != Some("")
        || lines.next().is_some()
    {
        return Err(Error::Protocol.into());
    }
    let mut token = [0; TOKEN_LEN];
    for (output, pair) in token
        .iter_mut()
        .zip(token_text.as_bytes().as_chunks::<2>().0)
    {
        let digit = |value: u8| -> Result<u8> {
            match value {
                b'0'..=b'9' => Ok(value - b'0'),
                b'a'..=b'f' => Ok(value - b'a' + 10),
                _ => Err(Error::Protocol.into()),
            }
        };
        *output = (digit(pair[0])? << 4) | digit(pair[1])?;
    }
    Ok((endpoint, token))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn endpoint_parser_rejects_oversized_and_ambiguous_records() {
        assert_eq!(
            decode_endpoint(&vec![b'x'; 1025]),
            Err(Error::Protocol.into())
        );
        let record = encode_endpoint("/tmp/socket", &[42; TOKEN_LEN]).unwrap();
        assert_eq!(
            decode_endpoint(&record),
            Ok(("/tmp/socket", [42; TOKEN_LEN]))
        );
        let mut trailing = record.clone();
        trailing.push(b'\n');
        assert_eq!(decode_endpoint(&trailing), Err(Error::Protocol.into()));
        assert_eq!(
            decode_endpoint(&record[..record.len() - 1]),
            Err(Error::Protocol.into())
        );
    }

    #[test]
    fn activation_requires_whole_correct_frame() {
        let token = [42; TOKEN_LEN];
        let mut frame = request(&token);
        assert!(valid_request(&frame, &token));
        for index in 0..REQUEST_LEN {
            frame[index] ^= 1;
            assert!(!valid_request(&frame, &token));
            frame[index] ^= 1;
        }
    }
}
