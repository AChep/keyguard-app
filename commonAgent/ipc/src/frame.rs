//! Frames of the private channels: a big-endian `u32` length, then that many
//! bytes. An empty frame is never valid, in either direction.
use anyhow::Result;
use std::fmt;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use zeroize::Zeroizing;

/// The largest frame of the SSH and GPG agents' private channels.
pub const MAX_AGENT_FRAME: usize = 16 * 1024 * 1024;

/// A frame length that the channel must not carry.
#[derive(Debug)]
pub struct InvalidFrameSize {
    size: usize,
    max: usize,
}

impl fmt::Display for InvalidFrameSize {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if self.size == 0 {
            f.write_str("IPC frame is empty")
        } else {
            write!(
                f,
                "IPC frame too large: {} bytes (max {})",
                self.size, self.max
            )
        }
    }
}

impl std::error::Error for InvalidFrameSize {}

/// Reads one frame of at most `max` bytes. The length is checked before the
/// payload is allocated, and the payload is wiped when dropped.
///
/// # Errors
///
/// Fails on an invalid length, with [`InvalidFrameSize`], or when the stream fails or ends.
pub async fn read_frame<R: AsyncRead + Unpin + ?Sized>(
    stream: &mut R,
    max: usize,
) -> Result<Zeroizing<Vec<u8>>> {
    let size = stream.read_u32().await? as usize;
    check_size(size, max)?;
    let mut bytes = Zeroizing::new(vec![0; size]);
    stream.read_exact(&mut bytes).await?;
    Ok(bytes)
}

/// Writes `body` as one frame of at most `max` bytes, then flushes.
///
/// # Errors
///
/// Fails on an invalid length, with [`InvalidFrameSize`], or when the stream fails.
pub async fn write_frame<W: AsyncWrite + Unpin + ?Sized>(
    stream: &mut W,
    body: &[u8],
    max: usize,
) -> Result<()> {
    check_size(body.len(), max)?;
    let size = u32::try_from(body.len()).map_err(|_| InvalidFrameSize {
        size: body.len(),
        max,
    })?;
    stream.write_all(&size.to_be_bytes()).await?;
    stream.write_all(body).await?;
    stream.flush().await?;
    Ok(())
}

pub(crate) fn check_size(size: usize, max: usize) -> Result<(), InvalidFrameSize> {
    if size == 0 || size > max {
        return Err(InvalidFrameSize { size, max });
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::duplex;

    #[tokio::test]
    async fn frames_round_trip_in_sequence() {
        let (mut writer, mut reader) = duplex(1024);
        write_frame(&mut writer, b"one", 16).await.unwrap();
        write_frame(&mut writer, b"second", 16).await.unwrap();
        assert_eq!(
            read_frame(&mut reader, 16).await.unwrap().as_slice(),
            b"one"
        );
        assert_eq!(
            read_frame(&mut reader, 16).await.unwrap().as_slice(),
            b"second"
        );
    }

    #[tokio::test]
    async fn the_length_is_big_endian() {
        let (mut writer, mut reader) = duplex(1024);
        write_frame(&mut writer, &[7; 3], 16).await.unwrap();
        drop(writer);
        let mut raw = Vec::new();
        reader.read_to_end(&mut raw).await.unwrap();
        assert_eq!(raw, [0, 0, 0, 3, 7, 7, 7]);
    }

    #[tokio::test]
    async fn empty_frames_are_rejected_in_both_directions() {
        let (mut writer, mut reader) = duplex(1024);
        let error = write_frame(&mut writer, &[], 16).await.unwrap_err();
        assert!(error.is::<InvalidFrameSize>());
        assert_eq!(error.to_string(), "IPC frame is empty");

        writer.write_u32(0).await.unwrap();
        let error = read_frame(&mut reader, 16).await.unwrap_err();
        assert_eq!(error.to_string(), "IPC frame is empty");
    }

    #[tokio::test]
    async fn oversize_frames_are_rejected_before_allocating() {
        let (mut writer, mut reader) = duplex(1024);
        let error = write_frame(&mut writer, &[0; 17], 16).await.unwrap_err();
        assert!(error.to_string().contains("too large"), "{error}");

        writer.write_u32(u32::MAX).await.unwrap();
        let error = read_frame(&mut reader, MAX_AGENT_FRAME).await.unwrap_err();
        assert!(error.is::<InvalidFrameSize>());
        assert!(error.to_string().contains("too large"), "{error}");
    }

    #[tokio::test]
    async fn a_stream_that_ends_inside_a_frame_fails() {
        for partial in [&[0, 0][..], &[0, 0, 0, 4, 1, 2][..]] {
            let (mut writer, mut reader) = duplex(1024);
            writer.write_all(partial).await.unwrap();
            drop(writer);
            assert!(read_frame(&mut reader, 16).await.is_err());
        }
    }
}
