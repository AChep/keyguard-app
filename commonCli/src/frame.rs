//! Frames of both sockets: a big-endian `u32` length, then the bytes.
use anyhow::{Result, ensure};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use zeroize::Zeroizing;

pub const MAX_FRAME: usize = 1024 * 1024;

pub async fn read_frame<R: AsyncRead + Unpin + ?Sized>(
    stream: &mut R,
) -> Result<Zeroizing<Vec<u8>>> {
    let size = stream.read_u32().await? as usize;
    ensure!((1..=MAX_FRAME).contains(&size), "invalid_frame");
    let mut bytes = Zeroizing::new(vec![0; size]);
    stream.read_exact(&mut bytes).await?;
    Ok(bytes)
}

pub async fn write_frame<W: AsyncWrite + Unpin + ?Sized>(
    stream: &mut W,
    bytes: &[u8],
) -> Result<()> {
    ensure!((1..=MAX_FRAME).contains(&bytes.len()), "invalid_frame");
    stream.write_u32(bytes.len() as u32).await?;
    stream.write_all(bytes).await?;
    stream.flush().await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn oversized_frame_is_rejected_before_allocating_payload() {
        let (mut reader, mut writer) = tokio::io::duplex(4);
        writer.write_u32((MAX_FRAME + 1) as u32).await.unwrap();
        assert!(read_frame(&mut reader).await.is_err());
    }
}
