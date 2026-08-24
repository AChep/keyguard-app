//! Bounded ASCII-armor decoding for packet streams.
//!
//! Decoding retains the existing tolerant checksum behavior and concatenated
//! block handling while ensuring secret-bearing buffers never reallocate.

use std::io::{self, BufRead, BufReader, Cursor, Read};

use pgp::armor::{BlockType, Dearmor, DearmorOptions};
use zeroize::{Zeroize, Zeroizing};

use crate::MAX_CONTROL_ENVELOPE_BYTES;

use super::types::RawPacketError;

pub(super) const DEARMOR_SCRATCH_BYTES: usize = 8 * 1024;
pub(super) const MAX_ARMOR_INPUT_LINE_BYTES: usize = 64 * 1024;
const MAX_MALFORMED_CHECKSUM_FOOTER_BYTES: usize = 64 * 1024;
const MAX_ARMOR_FILTER_OUTPUT_BYTES: usize =
    MAX_ARMOR_INPUT_LINE_BYTES + MAX_MALFORMED_CHECKSUM_FOOTER_BYTES;

/// Streaming armor normalizer that removes only structural CRC24 footer lines.
///
/// rPGP already ignores a syntactically valid checksum value, but its footer
/// parser rejects malformed checksum base64 before it can apply RFC 9580's
/// ignore policy. This filter withholds checksum-like lines until their
/// structural role is known. They are omitted only when the next line is an
/// armor END boundary; otherwise they are passed through unchanged so
/// malformed payload base64 and misplaced checksum lines remain errors.
///
/// Input lines and consecutive malformed checksum variants are independently
/// bounded. The reader therefore preserves streaming and backpressure instead
/// of accumulating the armored message.
#[derive(Debug)]
pub(crate) struct TolerantArmorReader<R> {
    inner: R,
    line: Zeroizing<Vec<u8>>,
    withheld_footer: Zeroizing<Vec<u8>>,
    output: Zeroizing<Vec<u8>>,
    output_offset: usize,
    finished: bool,
}

impl<R> TolerantArmorReader<R> {
    pub(crate) fn new(inner: R) -> Self {
        Self {
            inner,
            line: Zeroizing::new(Vec::with_capacity(128)),
            withheld_footer: Zeroizing::new(Vec::with_capacity(128)),
            output: Zeroizing::new(Vec::with_capacity(128)),
            output_offset: 0,
            finished: false,
        }
    }

    fn clear_line(&mut self) {
        self.line.as_mut_slice().zeroize();
        self.line.clear();
    }

    fn clear_withheld_footer(&mut self) {
        self.withheld_footer.as_mut_slice().zeroize();
        self.withheld_footer.clear();
    }

    fn clear_output(&mut self) {
        self.output.as_mut_slice().zeroize();
        self.output.clear();
        self.output_offset = 0;
    }
}

impl<R: BufRead> TolerantArmorReader<R> {
    fn read_line(&mut self) -> io::Result<bool> {
        self.clear_line();
        loop {
            let (consumed, terminated) = {
                let available = self.inner.fill_buf()?;
                if available.is_empty() {
                    return Ok(!self.line.is_empty());
                }
                let consumed = available
                    .iter()
                    .position(|byte| *byte == b'\n')
                    .map_or(available.len(), |index| index + 1);
                extend_io_buffer(
                    &mut self.line,
                    &available[..consumed],
                    MAX_ARMOR_INPUT_LINE_BYTES,
                )?;
                (consumed, available[consumed - 1] == b'\n')
            };
            self.inner.consume(consumed);
            if terminated {
                return Ok(true);
            }
        }
    }

    fn refill_output(&mut self) -> io::Result<()> {
        self.clear_output();
        loop {
            if !self.read_line()? {
                self.finished = true;
                extend_io_buffer(
                    &mut self.output,
                    &self.withheld_footer,
                    MAX_ARMOR_FILTER_OUTPUT_BYTES,
                )?;
                self.clear_withheld_footer();
                return Ok(());
            }

            let content = armor_line_content(&self.line);
            if is_armor_checksum_line(content) {
                extend_io_buffer(
                    &mut self.withheld_footer,
                    &self.line,
                    MAX_MALFORMED_CHECKSUM_FOOTER_BYTES,
                )?;
                continue;
            }
            if content.starts_with(b"-----END PGP ") {
                // One or more checksum-like lines immediately before the END
                // boundary form a malformed optional checksum footer. RFC 9580
                // section 6.1 requires ignoring it.
                self.clear_withheld_footer();
            } else if !self.withheld_footer.is_empty() {
                extend_io_buffer(
                    &mut self.output,
                    &self.withheld_footer,
                    MAX_ARMOR_FILTER_OUTPUT_BYTES,
                )?;
                self.clear_withheld_footer();
            }

            extend_io_buffer(&mut self.output, &self.line, MAX_ARMOR_FILTER_OUTPUT_BYTES)?;
            return Ok(());
        }
    }
}

impl<R: BufRead> Read for TolerantArmorReader<R> {
    fn read(&mut self, destination: &mut [u8]) -> io::Result<usize> {
        if destination.is_empty() {
            return Ok(0);
        }
        while self.output_offset == self.output.len() && !self.finished {
            self.refill_output()?;
        }
        if self.output_offset == self.output.len() {
            return Ok(0);
        }
        let count = destination
            .len()
            .min(self.output.len() - self.output_offset);
        destination[..count]
            .copy_from_slice(&self.output[self.output_offset..self.output_offset + count]);
        self.output_offset += count;
        Ok(count)
    }
}

fn extend_io_buffer(target: &mut Vec<u8>, source: &[u8], limit: usize) -> io::Result<()> {
    let required = target
        .len()
        .checked_add(source.len())
        .filter(|length| *length <= limit)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "armor line limit exceeded"))?;
    target
        .try_reserve_exact(required - target.len())
        .map_err(|_| {
            io::Error::new(io::ErrorKind::OutOfMemory, "armor buffer allocation failed")
        })?;
    target.extend_from_slice(source);
    Ok(())
}

fn armor_line_content(mut line: &[u8]) -> &[u8] {
    if line.last() == Some(&b'\n') {
        line = &line[..line.len() - 1];
    }
    if line.last() == Some(&b'\r') {
        line = &line[..line.len() - 1];
    }
    line
}

pub(super) fn decode_bounded(input: &[u8]) -> Result<Zeroizing<Vec<u8>>, RawPacketError> {
    if input.len() > MAX_CONTROL_ENVELOPE_BYTES {
        return Err(RawPacketError::ResourceLimit);
    }
    let first = input.first().copied().ok_or(RawPacketError::Malformed)?;
    if first & 0x80 != 0 {
        return Ok(Zeroizing::new(input.to_vec()));
    }

    dearmor_bounded(input, None)
}

/// Decodes bounded ASCII armor, optionally requiring one block type.
///
/// RFC 9580 section 6.1 forbids rejecting an object because its optional
/// CRC24 footer is malformed. The block normalizer therefore omits only a
/// checksum-position line before handing the otherwise unchanged armor to
/// rPGP. Header syntax, base64 payloads, and matching BEGIN/END boundaries
/// remain rPGP-validated.
pub(crate) fn dearmor_bounded(
    input: &[u8],
    expected_type: Option<&BlockType>,
) -> Result<Zeroizing<Vec<u8>>, RawPacketError> {
    if input.len() > MAX_CONTROL_ENVELOPE_BYTES {
        return Err(RawPacketError::ResourceLimit);
    }
    if let Some(bytes) = decode_concatenated_armor(input, expected_type)? {
        return Ok(bytes);
    }

    // Keep rPGP's ordinary single-block parser as a fallback for armor styles
    // that do not expose a conventional BEGIN line to the block splitter.
    dearmor_single(input, expected_type)
}

pub(super) fn dearmor_single(
    input: &[u8],
    expected_type: Option<&BlockType>,
) -> Result<Zeroizing<Vec<u8>>, RawPacketError> {
    let reader = BufReader::new(Cursor::new(input));
    let mut dearmor = Dearmor::with_options(
        reader,
        DearmorOptions::default().set_limit(MAX_CONTROL_ENVELOPE_BYTES),
    );
    dearmor
        .read_header()
        .map_err(|_| RawPacketError::Malformed)?;
    if expected_type.is_some_and(|expected| dearmor.typ.as_ref() != Some(expected)) {
        return Err(RawPacketError::Malformed);
    }
    // Armor decoding cannot produce more bytes than its encoded input. Reserve
    // that upper bound before copying packet bytes so this Zeroizing-owned
    // destination never reallocates after it contains decoded key material.
    let mut bytes = zeroizing_vec_with_capacity(input.len())?;
    let allocation = bytes.as_ptr();
    let capacity = bytes.capacity();
    let mut scratch = Zeroizing::new([0_u8; DEARMOR_SCRATCH_BYTES]);
    loop {
        let read = dearmor
            .read(&mut scratch[..])
            .map_err(|_| RawPacketError::Malformed)?;
        if read == 0 {
            break;
        }
        let required = bytes
            .len()
            .checked_add(read)
            .filter(|length| *length <= input.len())
            .filter(|length| *length <= MAX_CONTROL_ENVELOPE_BYTES)
            .ok_or(RawPacketError::ResourceLimit)?;
        if required > bytes.capacity() {
            return Err(RawPacketError::ResourceLimit);
        }
        bytes.extend_from_slice(&scratch[..read]);
        scratch[..read].zeroize();
    }
    debug_assert_eq!(bytes.as_ptr(), allocation);
    debug_assert_eq!(bytes.capacity(), capacity);
    Ok(bytes)
}

/// Decodes every conventional armor block in a document and concatenates the
/// resulting packet streams.
///
/// Accept blocks embedded in prose and consistently line-prefixed armor (most
/// commonly email quoting with `> `). Normalize the prefix per block, omit
/// only its optional checksum-position line, and leave the header, payload,
/// and boundary validation to rPGP.
fn decode_concatenated_armor(
    input: &[u8],
    expected_type: Option<&BlockType>,
) -> Result<Option<Zeroizing<Vec<u8>>>, RawPacketError> {
    const BEGIN: &[u8] = b"-----BEGIN PGP ";

    let mut output = None;
    let mut cursor = 0usize;
    while cursor < input.len() {
        let (line, next) = next_line(input, cursor);
        let Some(begin_at) = find_bytes(line, BEGIN) else {
            cursor = next;
            continue;
        };
        let prefix = &line[..begin_at];
        let (normalized, end_cursor) = normalize_armor_block(input, cursor, prefix)?;
        let decoded = dearmor_single(&normalized, expected_type)?;
        let output = match &mut output {
            Some(output) => output,
            None => output.insert(zeroizing_vec_with_capacity(input.len())?),
        };
        let required = output
            .len()
            .checked_add(decoded.len())
            .filter(|length| *length <= MAX_CONTROL_ENVELOPE_BYTES)
            .ok_or(RawPacketError::ResourceLimit)?;
        // The concatenated decoded blocks are smaller than their armored
        // source. Keep the explicit capacity check so this remains true even
        // if the dearmor implementation changes.
        if required > output.capacity() {
            return Err(RawPacketError::ResourceLimit);
        }
        let allocation = output.as_ptr();
        let capacity = output.capacity();
        output.extend_from_slice(&decoded);
        debug_assert_eq!(output.as_ptr(), allocation);
        debug_assert_eq!(output.capacity(), capacity);
        cursor = end_cursor;
    }
    Ok(output)
}

fn normalize_armor_block(
    input: &[u8],
    start: usize,
    prefix: &[u8],
) -> Result<(Zeroizing<Vec<u8>>, usize), RawPacketError> {
    const END: &[u8] = b"-----END PGP ";

    // Size the buffer in a read-only pass. Growing a Vec after copying armor
    // could leave an encoded private-key copy in a retired heap allocation.
    let mut normalized_len = 0usize;
    let mut block_cursor = start;
    let mut previous_line = None;
    let mut checksum_line = None;
    let end_cursor = loop {
        let (block_line, block_next) = next_line(input, block_cursor);
        let content =
            strip_consistent_line_prefix(block_line, prefix).ok_or(RawPacketError::Malformed)?;
        normalized_len = normalized_len
            .checked_add(content.len())
            .and_then(|length| length.checked_add(1))
            .ok_or(RawPacketError::ResourceLimit)?;
        if content.starts_with(END) {
            if let Some((cursor, length, true)) = previous_line {
                checksum_line = Some((cursor, length));
            }
            break block_next;
        }
        if block_next == input.len() {
            return Err(RawPacketError::Malformed);
        }
        previous_line = Some((block_cursor, content.len(), is_armor_checksum_line(content)));
        block_cursor = block_next;
    };
    if let Some((_, checksum_length)) = checksum_line {
        normalized_len = normalized_len
            .checked_sub(checksum_length + 1)
            .ok_or(RawPacketError::Malformed)?;
    }

    let mut normalized = zeroizing_vec_with_capacity(normalized_len)?;
    let allocation = normalized.as_ptr();
    let capacity = normalized.capacity();
    block_cursor = start;
    loop {
        let (block_line, block_next) = next_line(input, block_cursor);
        let content =
            strip_consistent_line_prefix(block_line, prefix).ok_or(RawPacketError::Malformed)?;
        if checksum_line.is_none_or(|(cursor, _)| cursor != block_cursor) {
            normalized.extend_from_slice(content);
            normalized.push(b'\n');
        }
        if block_next == end_cursor {
            break;
        }
        block_cursor = block_next;
    }
    debug_assert_eq!(normalized.len(), normalized_len);
    debug_assert_eq!(normalized.as_ptr(), allocation);
    debug_assert_eq!(normalized.capacity(), capacity);
    Ok((normalized, end_cursor))
}

/// A valid base64 payload line cannot start with padding. At the structural
/// checksum position immediately before the END boundary, a first non-space
/// `=` therefore identifies the optional CRC24 footer even when the remaining
/// checksum syntax is malformed.
fn is_armor_checksum_line(line: &[u8]) -> bool {
    line.iter()
        .copied()
        .find(|byte| !matches!(byte, b' ' | b'\t'))
        == Some(b'=')
}

fn zeroizing_vec_with_capacity(capacity: usize) -> Result<Zeroizing<Vec<u8>>, RawPacketError> {
    let mut bytes = Vec::new();
    bytes
        .try_reserve_exact(capacity)
        .map_err(|_| RawPacketError::ResourceLimit)?;
    Ok(Zeroizing::new(bytes))
}

fn next_line(input: &[u8], start: usize) -> (&[u8], usize) {
    let tail = &input[start..];
    let relative_end = tail.iter().position(|byte| *byte == b'\n');
    let next = relative_end
        .map(|end| start + end + 1)
        .unwrap_or(input.len());
    let mut line = &input[start..relative_end.map(|end| start + end).unwrap_or(input.len())];
    if line.last() == Some(&b'\r') {
        line = &line[..line.len() - 1];
    }
    (line, next)
}

fn strip_consistent_line_prefix<'a>(line: &'a [u8], prefix: &[u8]) -> Option<&'a [u8]> {
    if prefix.is_empty() {
        return Some(line);
    }
    if let Some(content) = line.strip_prefix(prefix) {
        return Some(content);
    }
    // Email clients commonly quote an empty armor line as just `>` even when
    // nonempty lines use `> `.
    (line == prefix.trim_ascii_end()).then_some(&[])
}

pub(super) fn find_bytes(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack
        .windows(needle.len())
        .position(|window| window == needle)
}
