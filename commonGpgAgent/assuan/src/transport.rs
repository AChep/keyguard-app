//! Bounded Assuan line transport, deadlines, and response encoding.

use anyhow::Result;
use std::future::Future;
use std::io;
use std::pin::Pin;
use std::task::{Context as TaskContext, Poll};
use std::time::Duration;
use tokio::io::{AsyncBufRead, AsyncBufReadExt, AsyncWrite, AsyncWriteExt};
use tokio::time::{timeout, timeout_at, Instant, Sleep};

/// libassuan's fixed protocol line buffer size, including `[CR,]LF`.
const ASSUAN_LINE_LEN: usize = 1002;

/// Maximum length of a single inbound Assuan line, including the trailing
/// newline. The cap is enforced incrementally so a client cannot exhaust memory
/// with a single never-terminated line.
pub(super) const MAX_LINE_LEN: usize = ASSUAN_LINE_LEN;

/// Per-line cap for the INQUIRE CIPHERTEXT data lines.
pub(super) const MAX_INQUIRE_LINE_LEN: usize = ASSUAN_LINE_LEN;

/// Maximum Assuan protocol line payload before the trailing line ending.
/// libassuan exposes this as 1000 + `[CR,]LF`; keeping our written line content
/// at or below 1000 leaves room for the LF we append below.
const ASSUAN_MAX_LINE_CONTENT_LEN: usize = ASSUAN_LINE_LEN - 2;

// Keep authenticated clients from occupying a connection indefinitely without
// sending a command, completing a line, or reading the response. Operation
// deadlines deliberately remain in the IPC layer: cancelling an IPC exchange
// between its write and read would desynchronize the shared framed stream.
const CONNECTION_IDLE_TIMEOUT: Duration = Duration::from_secs(5 * 60);
const LINE_COMPLETION_TIMEOUT: Duration = Duration::from_secs(30);
const RESPONSE_WRITE_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Clone, Copy, Debug)]
pub(super) struct AssuanTimeouts {
    pub(super) idle: Duration,
    pub(super) line: Duration,
    pub(super) write: Duration,
}

impl Default for AssuanTimeouts {
    fn default() -> Self {
        Self {
            idle: CONNECTION_IDLE_TIMEOUT,
            line: LINE_COMPLETION_TIMEOUT,
            write: RESPONSE_WRITE_TIMEOUT,
        }
    }
}

/// Buffered reader with separate idle and absolute line-completion deadlines.
pub(super) struct DeadlineReader<R> {
    inner: R,
    idle_timeout: Duration,
    line_timeout: Duration,
}

impl<R> DeadlineReader<R> {
    pub(super) fn new(inner: R, timeouts: AssuanTimeouts) -> Self {
        Self {
            inner,
            idle_timeout: timeouts.idle,
            line_timeout: timeouts.line,
        }
    }
}

impl<R: AsyncBufRead + Unpin> DeadlineReader<R> {
    /// Reads one line with a strict memory cap. The completion deadline starts
    /// only after the first byte is available, so an idle client and a
    /// slow-drip partial line are reported independently.
    pub(super) async fn read_until_limited(
        &mut self,
        delim: u8,
        max_len: usize,
    ) -> Result<BoundedRead> {
        let mut buf = Vec::new();
        let mut line_deadline = None;
        loop {
            let available = match line_deadline {
                Some(deadline) => {
                    timeout_at(deadline, self.inner.fill_buf())
                        .await
                        .map_err(|_| {
                            anyhow::anyhow!(
                                "Assuan client did not complete a line within {:?}",
                                self.line_timeout
                            )
                        })??
                }
                None => timeout(self.idle_timeout, self.inner.fill_buf())
                    .await
                    .map_err(|_| {
                        anyhow::anyhow!(
                            "Assuan client was idle for longer than {:?}",
                            self.idle_timeout
                        )
                    })??,
            };
            if available.is_empty() {
                return Ok(if buf.is_empty() {
                    BoundedRead::Eof
                } else {
                    BoundedRead::Chunk(buf)
                });
            }
            if line_deadline.is_none() {
                line_deadline = Some(Instant::now() + self.line_timeout);
            }

            let (end, found) = match available.iter().position(|&byte| byte == delim) {
                Some(position) => (position + 1, true),
                None => (available.len(), false),
            };

            if buf.len() + end > max_len {
                let take = max_len - buf.len();
                self.inner.consume(take);
                return Ok(BoundedRead::TooLong);
            }

            buf.extend_from_slice(&available[..end]);
            self.inner.consume(end);
            if found {
                return Ok(BoundedRead::Chunk(buf));
            }
        }
    }
}

/// Async writer that applies one absolute deadline from the first byte of a
/// response through its flush. All Assuan response helpers flush before
/// returning, so a peer cannot extend the deadline by accepting one small
/// partial write at a time.
pub(super) struct DeadlineWriter<W> {
    inner: W,
    write_timeout: Duration,
    deadline: Option<Pin<Box<Sleep>>>,
}

impl<W> DeadlineWriter<W> {
    pub(super) fn new(inner: W, write_timeout: Duration) -> Self {
        Self {
            inner,
            write_timeout,
            deadline: None,
        }
    }

    fn ensure_deadline(&mut self) {
        if self.deadline.is_none() {
            self.deadline = Some(Box::pin(tokio::time::sleep(self.write_timeout)));
        }
    }

    fn poll_deadline(&mut self, cx: &mut TaskContext<'_>) -> Option<io::Error> {
        self.ensure_deadline();
        let expired = self
            .deadline
            .as_mut()
            .expect("deadline was initialized above")
            .as_mut()
            .poll(cx)
            .is_ready();
        if expired {
            self.deadline = None;
            Some(io::Error::new(
                io::ErrorKind::TimedOut,
                format!(
                    "Assuan client did not read response within {:?}",
                    self.write_timeout
                ),
            ))
        } else {
            None
        }
    }

    fn finish_response(&mut self) {
        self.deadline = None;
    }
}

impl<W: AsyncWrite + Unpin> AsyncWrite for DeadlineWriter<W> {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut TaskContext<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        let this = self.get_mut();
        if let Some(error) = this.poll_deadline(cx) {
            return Poll::Ready(Err(error));
        }
        match Pin::new(&mut this.inner).poll_write(cx, buf) {
            Poll::Ready(Err(error)) => {
                this.finish_response();
                Poll::Ready(Err(error))
            }
            result => result,
        }
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut TaskContext<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if let Some(error) = this.poll_deadline(cx) {
            return Poll::Ready(Err(error));
        }
        match Pin::new(&mut this.inner).poll_flush(cx) {
            Poll::Ready(result) => {
                this.finish_response();
                Poll::Ready(result)
            }
            Poll::Pending => Poll::Pending,
        }
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut TaskContext<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if let Some(error) = this.poll_deadline(cx) {
            return Poll::Ready(Err(error));
        }
        match Pin::new(&mut this.inner).poll_shutdown(cx) {
            Poll::Ready(result) => {
                this.finish_response();
                Poll::Ready(result)
            }
            Poll::Pending => Poll::Pending,
        }
    }
}

/// Outcome of a length-bounded read up to a delimiter byte.
#[derive(Debug)]
pub(super) enum BoundedRead {
    /// A complete chunk: either terminated by the delimiter, or the trailing
    /// bytes that preceded EOF.
    Chunk(Vec<u8>),
    /// A clean EOF with no buffered bytes.
    Eof,
    /// The byte cap was hit before the delimiter. The stream can no longer be
    /// framed reliably, so the caller must close the connection.
    TooLong,
}

pub(super) async fn write_ok<W: AsyncWriteExt + Unpin>(write: &mut W, message: &str) -> Result<()> {
    if message.is_empty() {
        write_response(write, "OK").await
    } else {
        write_response(write, &format!("OK {message}")).await
    }
}

pub(super) async fn write_error<W: AsyncWriteExt + Unpin>(
    write: &mut W,
    code: u32,
    message: &str,
) -> Result<()> {
    write_response(write, &format!("ERR {code} {message}")).await
}

pub(super) async fn write_status<W: AsyncWriteExt + Unpin>(
    write: &mut W,
    keyword: &str,
    value: &str,
) -> Result<()> {
    write_response(write, &format!("S {keyword} {value}")).await
}

pub(super) async fn write_data<W: AsyncWriteExt + Unpin>(write: &mut W, data: &[u8]) -> Result<()> {
    // A D-line carries raw (escaped) bytes, NOT UTF-8 text: a PKDECRYPT result is
    // a binary canonical S-expression containing arbitrary bytes >= 0x80. Build
    // and write lines as bytes so those high bytes are not silently widened into
    // 2-byte UTF-8 sequences. Assuan data is one stream across multiple D-lines
    // until the following OK/ERR, so chunk long responses to libassuan's line cap.
    let mut line = Vec::with_capacity(ASSUAN_MAX_LINE_CONTENT_LEN + 1);
    line.extend_from_slice(b"D ");

    for &byte in data {
        let escaped_len = assuan_escaped_len(byte);
        if line.len() + escaped_len > ASSUAN_MAX_LINE_CONTENT_LEN {
            line.push(b'\n');
            write.write_all(&line).await?;
            line.clear();
            line.extend_from_slice(b"D ");
        }
        assuan_push_escaped_byte(&mut line, byte);
    }

    line.push(b'\n');
    write.write_all(&line).await?;
    write.flush().await?;
    Ok(())
}

pub(super) async fn write_response<W: AsyncWriteExt + Unpin>(
    write: &mut W,
    line: &str,
) -> Result<()> {
    write.write_all(line.as_bytes()).await?;
    write.write_all(b"\n").await?;
    write.flush().await?;
    Ok(())
}

/// Percent-escapes the bytes that are not allowed verbatim on an Assuan D-line
/// (`%`, CR, LF). Returns raw bytes — every other byte, including high bytes of a
/// binary S-expression, is passed through untouched and MUST be written as bytes
/// (not via a UTF-8 `String`, which would corrupt bytes >= 0x80).
#[cfg(test)]
fn assuan_escape(data: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(data.len());
    for &byte in data {
        assuan_push_escaped_byte(&mut out, byte);
    }
    out
}

fn assuan_escaped_len(byte: u8) -> usize {
    match byte {
        b'%' | b'\r' | b'\n' => 3,
        _ => 1,
    }
}

fn assuan_push_escaped_byte(out: &mut Vec<u8>, byte: u8) {
    match byte {
        b'%' => out.extend_from_slice(b"%25"),
        b'\r' => out.extend_from_slice(b"%0D"),
        b'\n' => out.extend_from_slice(b"%0A"),
        _ => out.push(byte),
    }
}

/// Reverses Assuan's percent-encoding on an inbound D-line payload. `%XX` (two
/// hex digits) becomes the byte 0xXX; every other byte is taken literally. A
/// trailing or malformed `%` sequence is passed through verbatim. This is
/// byte-accurate so binary canonical S-expressions survive the round-trip.
pub(super) fn assuan_unescape(data: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(data.len());
    let mut i = 0;
    while i < data.len() {
        if data[i] == b'%' {
            if let (Some(hi), Some(lo)) = (
                data.get(i + 1).and_then(|b| hex_value(*b)),
                data.get(i + 2).and_then(|b| hex_value(*b)),
            ) {
                out.push((hi << 4) | lo);
                i += 3;
                continue;
            }
        }
        out.push(data[i]);
        i += 1;
    }
    out
}

fn hex_value(byte: u8) -> Option<u8> {
    match byte {
        b'0'..=b'9' => Some(byte - b'0'),
        b'a'..=b'f' => Some(byte - b'a' + 10),
        b'A'..=b'F' => Some(byte - b'A' + 10),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncWriteExt, BufReader};

    #[test]
    fn assuan_escape_escapes_required_bytes() {
        assert_eq!(assuan_escape(b"a%b\r\nc"), b"a%25b%0D%0Ac".to_vec());
    }

    #[test]
    fn assuan_escape_preserves_high_bytes_verbatim() {
        // High bytes (>= 0x80) of a binary S-expression must pass through as single
        // raw bytes, NOT be widened into 2-byte UTF-8 sequences.
        let input = [0x00u8, 0x80, 0xff, b'(', b')'];
        assert_eq!(assuan_escape(&input), input.to_vec());
        // Round-trips through the inbound unescaper too.
        assert_eq!(assuan_unescape(&assuan_escape(&input)), input.to_vec());
    }

    #[tokio::test]
    async fn write_data_chunks_long_d_lines() {
        let input = vec![b'A'; ASSUAN_MAX_LINE_CONTENT_LEN * 2];
        let mut output = Vec::new();

        write_data(&mut output, &input).await.unwrap();

        let (decoded, line_count) = decode_data_lines(&output);
        assert_eq!(decoded, input);
        assert!(line_count > 1, "expected chunked D-lines: {output:?}");
    }

    #[tokio::test]
    async fn write_data_does_not_split_percent_escapes() {
        let mut input = vec![b'A'; ASSUAN_MAX_LINE_CONTENT_LEN - b"D ".len() - 1];
        input.extend_from_slice(b"%\r\n");
        let mut output = Vec::new();

        write_data(&mut output, &input).await.unwrap();

        let (decoded, line_count) = decode_data_lines(&output);
        assert_eq!(decoded, input);
        assert_eq!(
            line_count, 2,
            "escape boundary should force a second D-line"
        );
    }

    #[tokio::test]
    async fn write_data_preserves_binary_high_bytes_across_chunks() {
        let mut input = vec![0xff; ASSUAN_MAX_LINE_CONTENT_LEN - b"D ".len()];
        input.extend_from_slice(&[0x00, 0x80, b'%', b'\n', b'\r']);
        let mut output = Vec::new();

        write_data(&mut output, &input).await.unwrap();

        let (decoded, line_count) = decode_data_lines(&output);
        assert_eq!(decoded, input);
        assert_eq!(
            line_count, 2,
            "boundary payload should force a second D-line"
        );
    }

    #[test]
    fn assuan_unescape_decodes_hex_and_literals() {
        // %XX hex pairs decode to bytes; %% decodes to a literal '%'; other
        // bytes pass through unchanged.
        assert_eq!(assuan_unescape(b"a%25b%0D%0Ac"), b"a%b\r\nc");
        assert_eq!(assuan_unescape(b"%00%FF%ff"), &[0x00, 0xff, 0xff]);
        assert_eq!(assuan_unescape(b"hello"), b"hello");
    }

    #[test]
    fn assuan_unescape_passes_through_malformed_sequences() {
        // A lone or truncated '%' is not a valid escape and is kept verbatim.
        assert_eq!(assuan_unescape(b"%"), b"%");
        assert_eq!(assuan_unescape(b"%2"), b"%2");
        assert_eq!(assuan_unescape(b"%zz"), b"%zz");
    }

    #[test]
    fn assuan_unescape_decodes_all_byte_values() {
        // gpg escapes binary D-line payloads as explicit %XX pairs; verify every
        // byte value round-trips through the unescaper.
        for b in 0u16..=255 {
            let escaped = format!("%{:02X}", b);
            assert_eq!(assuan_unescape(escaped.as_bytes()), vec![b as u8]);
        }
    }

    #[tokio::test]
    async fn read_until_limited_splits_lines_and_reports_eof() {
        let data = b"hello\nworld";
        let mut reader = DeadlineReader::new(BufReader::new(&data[..]), AssuanTimeouts::default());
        match reader.read_until_limited(b'\n', 64).await.unwrap() {
            BoundedRead::Chunk(b) => assert_eq!(b, b"hello\n"),
            _ => panic!("expected first line"),
        }
        match reader.read_until_limited(b'\n', 64).await.unwrap() {
            BoundedRead::Chunk(b) => assert_eq!(b, b"world"),
            _ => panic!("expected trailing chunk"),
        }
        assert!(matches!(
            reader.read_until_limited(b'\n', 64).await.unwrap(),
            BoundedRead::Eof
        ));
    }

    #[tokio::test]
    async fn read_until_limited_rejects_overlong_line() {
        // A 100-byte unterminated line must be rejected against a 16-byte cap
        // rather than being buffered in full.
        let data = [b'A'; 100];
        let mut reader = DeadlineReader::new(BufReader::new(&data[..]), AssuanTimeouts::default());
        assert!(matches!(
            reader.read_until_limited(b'\n', 16).await.unwrap(),
            BoundedRead::TooLong
        ));
    }

    #[tokio::test(start_paused = true)]
    async fn authenticated_session_idle_read_times_out() {
        let (_client, server) = tokio::io::duplex(16);
        let idle_timeout = Duration::from_secs(60);
        let mut reader = DeadlineReader::new(
            BufReader::new(server),
            AssuanTimeouts {
                idle: idle_timeout,
                line: Duration::from_secs(10),
                write: Duration::from_secs(10),
            },
        );

        let error = reader
            .read_until_limited(b'\n', MAX_LINE_LEN)
            .await
            .expect_err("silent authenticated client must time out");

        assert!(error.to_string().contains("idle"));
        assert!(error.to_string().contains("60s"));
    }

    #[tokio::test(start_paused = true)]
    async fn partial_assuan_line_has_an_absolute_completion_deadline() {
        let (mut client, server) = tokio::io::duplex(16);
        client.write_all(b"N").await.unwrap();
        let line_timeout = Duration::from_secs(10);
        let mut reader = DeadlineReader::new(
            BufReader::new(server),
            AssuanTimeouts {
                idle: Duration::from_secs(60),
                line: line_timeout,
                write: Duration::from_secs(10),
            },
        );

        let error = reader
            .read_until_limited(b'\n', MAX_LINE_LEN)
            .await
            .expect_err("partial Assuan line must time out");

        assert!(error.to_string().contains("complete a line"));
        assert!(error.to_string().contains("10s"));
    }

    #[tokio::test(start_paused = true)]
    async fn blocked_assuan_response_write_times_out() {
        let (_client, server) = tokio::io::duplex(1);
        let mut writer = DeadlineWriter::new(server, Duration::from_secs(10));

        let error = write_response(&mut writer, "response that cannot fit")
            .await
            .expect_err("client that does not read must time out");
        let io_error = error
            .downcast_ref::<io::Error>()
            .expect("write timeout should retain its I/O error");

        assert_eq!(io_error.kind(), io::ErrorKind::TimedOut);
    }

    fn decode_data_lines(output: &[u8]) -> (Vec<u8>, usize) {
        let mut decoded = Vec::new();
        let mut line_count = 0;
        for line in output.split(|&byte| byte == b'\n') {
            if line.is_empty() {
                continue;
            }
            line_count += 1;
            assert!(
                line.starts_with(b"D "),
                "expected D-line, got {:?}",
                String::from_utf8_lossy(line),
            );
            assert!(
                line.len() <= ASSUAN_MAX_LINE_CONTENT_LEN,
                "D-line is too long: {} bytes",
                line.len(),
            );
            decoded.extend_from_slice(&assuan_unescape(&line[2..]));
        }
        assert!(line_count > 0, "expected at least one D-line");
        (decoded, line_count)
    }
}
