use anyhow::{anyhow, bail, Context, Result};
use keyguard_android_agent_bridge::secure_transport::read_exact_or_eof;
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt};

pub async fn read_ssh_agent_packet<S>(
    stream: &mut S,
    max_payload_size: usize,
) -> Result<Option<Vec<u8>>>
where
    S: AsyncRead + Unpin,
{
    let mut len_buf = [0u8; 4];
    let Some(()) = read_exact_or_eof(stream, &mut len_buf, "Truncated SSH agent packet length")
        .await
        .context("Failed to read SSH agent packet length")?
    else {
        return Ok(None);
    };

    let len = u32::from_be_bytes(len_buf) as usize;
    if len == 0 || len > max_payload_size {
        bail!("Invalid SSH agent packet size={len}");
    }

    let mut body = vec![0u8; len];
    let Some(()) = read_exact_or_eof(stream, &mut body, "Truncated SSH agent packet body")
        .await
        .context("Failed to read SSH agent packet body")?
    else {
        return Err(anyhow!("Truncated SSH agent packet body"));
    };

    Ok(Some(body))
}

pub async fn write_ssh_agent_packet<S>(
    stream: &mut S,
    packet: &[u8],
    max_payload_size: usize,
) -> Result<()>
where
    S: AsyncWrite + Unpin,
{
    if packet.is_empty() || packet.len() > max_payload_size {
        bail!("Invalid SSH agent packet size={}", packet.len());
    }

    stream
        .write_all(&(packet.len() as u32).to_be_bytes())
        .await
        .context("Failed to write SSH agent packet length")?;
    stream
        .write_all(packet)
        .await
        .context("Failed to write SSH agent packet body")?;
    stream
        .flush()
        .await
        .context("Failed to flush SSH agent packet")?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::duplex;

    const MAX_PAYLOAD_SIZE: usize = 1024 * 1024;

    #[tokio::test]
    async fn read_packet_returns_none_on_clean_eof_before_header() {
        let (mut reader, writer) = duplex(64);
        drop(writer);

        assert_eq!(
            read_ssh_agent_packet(&mut reader, MAX_PAYLOAD_SIZE)
                .await
                .unwrap(),
            None,
        );
    }

    #[tokio::test]
    async fn read_packet_rejects_partial_header_eof() {
        let (mut reader, mut writer) = duplex(64);
        writer.write_all(&[0x01, 0x02, 0x03]).await.unwrap();
        drop(writer);

        let err = read_ssh_agent_packet(&mut reader, MAX_PAYLOAD_SIZE)
            .await
            .unwrap_err();
        assert!(err
            .to_string()
            .contains("Failed to read SSH agent packet length"));
    }

    #[tokio::test]
    async fn read_packet_rejects_truncated_body() {
        let (mut reader, mut writer) = duplex(64);
        writer.write_all(&4u32.to_be_bytes()).await.unwrap();
        writer.write_all(&[0x11, 0x22]).await.unwrap();
        drop(writer);

        let err = read_ssh_agent_packet(&mut reader, MAX_PAYLOAD_SIZE)
            .await
            .unwrap_err();
        assert!(err
            .to_string()
            .contains("Failed to read SSH agent packet body"));
    }
}
