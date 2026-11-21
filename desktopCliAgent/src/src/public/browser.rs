//! The stream of a browser extension. The agent checks that the browser
//! connector `keyguard-browser-host` connected, opens one private connection
//! to the app for it, and then relays opaque frames both ways: Noise
//! messages that only the extension and the app can read.
use crate::{
    app_client::AppClient,
    protocol::{BrowserOpenRequest, request, response},
    transport::{Accepted, BundledPeer, Endpoint},
};
use anyhow::{Result, ensure};
use keyguard_cli_protocol::{
    browser::BrowserRequest,
    errors::{INVALID_RESPONSE, UNAVAILABLE, UNVERIFIED_HOST, failure, stable_code},
    frame::BROWSER_MAX_FRAME,
};
use serde_json::{Value, json};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use zeroize::Zeroizing;

/// Opens the stream, then relays it until either side closes it. The
/// connector waits for the agent's answer before it sends a frame.
pub(super) async fn serve(
    mut accepted: Accepted,
    app: AppClient,
    request: BrowserRequest,
    endpoint: Endpoint,
) -> Result<()> {
    request.validate()?;
    // As for a CLI command: the connector is checked before the app hears of
    // it, while the app answers, and once more before the agent answers it.
    accepted.guard.revalidate().await?;
    let verified_host = accepted.bundled_peer == Some(BundledPeer::BrowserHost);
    if !verified_host {
        let executable_hidden = endpoint == Endpoint::FlatpakBrowser
            && accepted.bundled_peer.is_none()
            && accepted.guard.executable_hidden().await;
        if !accepts_unverified(endpoint, executable_hidden) {
            return super::reply(&mut accepted, &failure(UNVERIFIED_HOST)).await;
        }
    }
    let open = request::Request::BrowserOpen(BrowserOpenRequest {
        // The browser that started the connector. Without it, the caller is
        // the connector itself, which the app must not show as the browser.
        caller: accepted
            .relayed
            .then(|| std::mem::take(&mut accepted.caller)),
        origin: request.origin,
        verified_host,
        host_revision: request.host_revision,
    });
    let Some(opened) = super::while_connected(&mut accepted, app.open(open)).await? else {
        return Ok(());
    };
    let Ok((stream, response)) = opened else {
        return super::reply(&mut accepted, &failure(UNAVAILABLE)).await;
    };
    if let Some(refusal) = refusal(&response.0) {
        return super::reply(&mut accepted, &refusal).await;
    }
    // The relay does not watch the connector: from here on, Noise
    // authenticates the extension end to end.
    super::reply(&mut accepted, &json!({"ok":true})).await?;
    relay(accepted.stream, stream).await;
    Ok(())
}

/// The public answer to a refused stream, or `None` once the app opened it.
fn refusal(response: &response::Response) -> Option<Value> {
    match response {
        response::Response::BrowserOpen(_) => None,
        response::Response::Error(error) => Some(failure(stable_code(&error.code))),
        _ => Some(failure(INVALID_RESPONSE)),
    }
}

/// Whether a connector that the agent could not verify may open a stream.
/// The app is told that it is not verified; the extension's pairing stays
/// the gate.
fn accepts_unverified(endpoint: Endpoint, executable_hidden: bool) -> bool {
    match endpoint {
        // Inside Flatpak, the connector runs in a sandbox of its own: the
        // agent can not see its process.
        Endpoint::Public => is_flatpak(),
        // The copy in a Flatpak browser's folder. Its process may be in a
        // PID namespace that the agent can't see into; a copy that the agent
        // can read must be the bundled connector.
        Endpoint::FlatpakBrowser => executable_hidden,
    }
}

/// Whether this agent runs inside Flatpak, as the app that started it does.
/// Only the sandbox has `/.flatpak-info`. The `container` variable is not
/// checked: any process can set it.
fn is_flatpak() -> bool {
    cfg!(target_os = "linux") && std::path::Path::new("/.flatpak-info").exists()
}

/// Relays frames both ways until either side closes or sends a frame that
/// is not one Noise message, then closes both.
async fn relay(connector: impl AsyncRead + AsyncWrite, app: impl AsyncRead + AsyncWrite) {
    let (mut connector_read, mut connector_write) = tokio::io::split(connector);
    let (mut app_read, mut app_write) = tokio::io::split(app);
    // A frame read in part is lost when its read is canceled, so each
    // direction reads in a loop of its own and only ends with the relay.
    tokio::select! {
        _ = forward(&mut connector_read, &mut app_write) => {},
        _ = forward(&mut app_read, &mut connector_write) => {},
    }
}

/// Forwards whole frames, as `read_browser_frame` and `write_browser_frame`
/// would, until either side fails. One buffer holds a frame with its length,
/// so that each frame is one write; it is wiped once the relay ends.
async fn forward(
    from: &mut (impl AsyncRead + Unpin),
    to: &mut (impl AsyncWrite + Unpin),
) -> Result<()> {
    let mut buffer = Zeroizing::new(vec![0; LENGTH_BYTES + BROWSER_MAX_FRAME]);
    loop {
        let (length, _) = buffer.split_at_mut(LENGTH_BYTES);
        from.read_exact(length).await?;
        let size = u32::from_be_bytes([length[0], length[1], length[2], length[3]]) as usize;
        ensure!((1..=BROWSER_MAX_FRAME).contains(&size), "invalid_frame");
        let frame = &mut buffer[..LENGTH_BYTES + size];
        from.read_exact(&mut frame[LENGTH_BYTES..]).await?;
        to.write_all(frame).await?;
    }
}

/// The big-endian `u32` length in front of each frame.
const LENGTH_BYTES: usize = 4;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::{BrowserOpenResponse, CliResponse, ErrorResponse};
    use keyguard_cli_protocol::frame::{read_browser_frame, write_browser_frame};
    use tokio::io::{DuplexStream, duplex};

    /// A relay between a connector and the app, and their ends of it.
    fn relayed() -> (DuplexStream, DuplexStream, tokio::task::JoinHandle<()>) {
        let (connector, agent_connector) = duplex(4 * BROWSER_MAX_FRAME);
        let (app, agent_app) = duplex(4 * BROWSER_MAX_FRAME);
        let relay = tokio::spawn(relay(agent_connector, agent_app));
        (connector, app, relay)
    }

    async fn assert_closed(stream: &mut DuplexStream) {
        assert!(stream.read_u8().await.is_err());
    }

    #[tokio::test]
    async fn frames_are_relayed_both_ways_unchanged() {
        let (mut connector, mut app, relay) = relayed();
        // The app writes first: it pushes events as well as it answers.
        write_browser_frame(&mut app, b"event").await.unwrap();
        assert_eq!(
            &read_browser_frame(&mut connector).await.unwrap()[..],
            b"event"
        );
        let largest = vec![7; BROWSER_MAX_FRAME];
        write_browser_frame(&mut connector, &largest).await.unwrap();
        write_browser_frame(&mut connector, b"second")
            .await
            .unwrap();
        assert_eq!(read_browser_frame(&mut app).await.unwrap()[..], largest);
        assert_eq!(&read_browser_frame(&mut app).await.unwrap()[..], b"second");
        write_browser_frame(&mut app, b"answer").await.unwrap();
        assert_eq!(
            &read_browser_frame(&mut connector).await.unwrap()[..],
            b"answer"
        );
        // Either side closing closes the other.
        drop(connector);
        assert_closed(&mut app).await;
        relay.await.unwrap();
    }

    #[tokio::test]
    async fn a_frame_read_in_part_survives_frames_the_other_way() {
        let frame = b"partial";
        let mut bytes = (frame.len() as u32).to_be_bytes().to_vec();
        bytes.extend_from_slice(frame);
        // Part of the length, then the length and part of the frame.
        for split in [2, 4 + 3] {
            for from_connector in [true, false] {
                let (mut connector, mut app, relay) = relayed();
                let (from, to) = if from_connector {
                    (&mut connector, &mut app)
                } else {
                    (&mut app, &mut connector)
                };
                from.write_all(&bytes[..split]).await.unwrap();
                // Frames the other way still pass. However a relay polls its
                // two directions, the second frame comes while the first
                // frame is read in part.
                for other in [&b"first"[..], b"second"] {
                    write_browser_frame(to, other).await.unwrap();
                    assert_eq!(&read_browser_frame(from).await.unwrap()[..], other);
                }
                from.write_all(&bytes[split..]).await.unwrap();
                write_browser_frame(from, b"next").await.unwrap();
                assert_eq!(&read_browser_frame(to).await.unwrap()[..], frame);
                assert_eq!(&read_browser_frame(to).await.unwrap()[..], b"next");
                drop(connector);
                assert_closed(&mut app).await;
                relay.await.unwrap();
            }
        }
    }

    #[tokio::test]
    async fn an_oversized_frame_closes_both_sides() {
        for from_connector in [true, false] {
            let (mut connector, mut app, relay) = relayed();
            let (from, to) = if from_connector {
                (&mut connector, &mut app)
            } else {
                (&mut app, &mut connector)
            };
            from.write_u32(BROWSER_MAX_FRAME as u32 + 1).await.unwrap();
            assert_closed(to).await;
            assert_closed(from).await;
            relay.await.unwrap();
        }
    }

    #[tokio::test]
    async fn the_app_closing_closes_the_connector() {
        let (mut connector, app, relay) = relayed();
        drop(app);
        assert_closed(&mut connector).await;
        relay.await.unwrap();
    }

    #[test]
    fn unverified_connectors_need_a_hidden_process_in_a_flatpak_browser() {
        assert!(accepts_unverified(Endpoint::FlatpakBrowser, true));
        // Its executable differs from the bundled connector.
        assert!(!accepts_unverified(Endpoint::FlatpakBrowser, false));
        // Never on the agent's own socket, unless the agent runs in Flatpak.
        for hidden in [true, false] {
            assert_eq!(accepts_unverified(Endpoint::Public, hidden), is_flatpak());
        }
    }

    #[test]
    fn only_an_opened_stream_is_relayed() {
        let opened = response::Response::BrowserOpen(BrowserOpenResponse {});
        assert_eq!(refusal(&opened), None);
        let error = |code: &str| response::Response::Error(ErrorResponse { code: code.into() });
        assert_eq!(
            refusal(&error("integration_disabled")),
            Some(failure("integration_disabled"))
        );
        assert_eq!(
            refusal(&error("too_many_sessions")),
            Some(failure("too_many_sessions"))
        );
        assert_eq!(
            refusal(&error("Secret /path")),
            Some(failure("request_failed"))
        );
        let other = response::Response::Cli(CliResponse { result: Vec::new() });
        assert_eq!(refusal(&other), Some(failure(INVALID_RESPONSE)));
    }
}
