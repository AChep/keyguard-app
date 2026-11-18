//! Frames of both sockets: a big-endian `u32` length, then the bytes.
use anyhow::Result;
use keyguard_agent_ipc::frame::{self, InvalidFrameSize};
use tokio::io::{AsyncRead, AsyncWrite};
use zeroize::Zeroizing;

pub const MAX_FRAME: usize = 1024 * 1024;

/// The largest frame of a browser extension's stream: one Noise message.
pub const BROWSER_MAX_FRAME: usize = 65_535;

pub async fn read_frame<R: AsyncRead + Unpin + ?Sized>(
    stream: &mut R,
) -> Result<Zeroizing<Vec<u8>>> {
    frame::read_frame(stream, MAX_FRAME)
        .await
        .map_err(stable_error)
}

pub async fn write_frame<W: AsyncWrite + Unpin + ?Sized>(
    stream: &mut W,
    bytes: &[u8],
) -> Result<()> {
    frame::write_frame(stream, bytes, MAX_FRAME)
        .await
        .map_err(stable_error)
}

/// Reads one opaque frame of a browser extension's stream. Not cancel safe:
/// a frame read in part is lost.
pub async fn read_browser_frame<R: AsyncRead + Unpin + ?Sized>(
    stream: &mut R,
) -> Result<Zeroizing<Vec<u8>>> {
    frame::read_frame(stream, BROWSER_MAX_FRAME)
        .await
        .map_err(stable_error)
}

/// Writes one opaque frame of a browser extension's stream.
pub async fn write_browser_frame<W: AsyncWrite + Unpin + ?Sized>(
    stream: &mut W,
    bytes: &[u8],
) -> Result<()> {
    frame::write_frame(stream, bytes, BROWSER_MAX_FRAME)
        .await
        .map_err(stable_error)
}

/// An invalid length is the stable `invalid_frame` of the public socket.
fn stable_error(error: anyhow::Error) -> anyhow::Error {
    if error.is::<InvalidFrameSize>() {
        anyhow::anyhow!("invalid_frame")
    } else {
        error
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::AsyncWriteExt;

    #[tokio::test]
    async fn oversized_frame_is_rejected_before_allocating_payload() {
        let (mut reader, mut writer) = tokio::io::duplex(4);
        writer.write_u32((MAX_FRAME + 1) as u32).await.unwrap();
        let error = read_frame(&mut reader).await.unwrap_err();
        assert_eq!(error.to_string(), "invalid_frame");
    }

    #[tokio::test]
    async fn browser_frames_hold_one_noise_message() {
        let (mut reader, mut writer) = tokio::io::duplex(2 * BROWSER_MAX_FRAME);
        let largest = vec![7; BROWSER_MAX_FRAME];
        write_browser_frame(&mut writer, &largest).await.unwrap();
        assert_eq!(read_browser_frame(&mut reader).await.unwrap()[..], largest);
        assert_eq!(
            write_browser_frame(&mut writer, &[7; BROWSER_MAX_FRAME + 1])
                .await
                .unwrap_err()
                .to_string(),
            "invalid_frame"
        );
        writer
            .write_u32((BROWSER_MAX_FRAME + 1) as u32)
            .await
            .unwrap();
        let error = read_browser_frame(&mut reader).await.unwrap_err();
        assert_eq!(error.to_string(), "invalid_frame");
    }

    #[tokio::test]
    async fn empty_frames_are_invalid() {
        let (mut reader, _writer) = tokio::io::duplex(4);
        assert_eq!(
            write_frame(&mut reader, &[]).await.unwrap_err().to_string(),
            "invalid_frame"
        );
    }
}
