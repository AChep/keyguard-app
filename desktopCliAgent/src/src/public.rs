//! The public socket. Private resolution is never reachable from here: each
//! frame is one of the operations that `keyguard_cli_protocol` defines.
use crate::{
    app_client::AppClient,
    mounts::Mounts,
    protocol::{request, response},
    transport::{Accepted, Endpoint},
};
use anyhow::{Context, Result};
use keyguard_agent_identity::ConnectionPrincipal;
use keyguard_cli_protocol::{
    errors::{UNAVAILABLE, UNSUPPORTED_COMMAND, failure},
    frame::{read_frame, write_frame},
    public::{ParsedFrame, PublicFrame, parse_public_frame},
};
use serde_json::Value;
use std::{sync::Arc, time::Duration};
use tokio::{io::AsyncReadExt, sync::Mutex};

mod browser;
mod cli;
mod manage;

pub(crate) async fn serve(
    mut accepted: Accepted,
    app: AppClient,
    mounts: Arc<Mutex<Mounts>>,
    endpoint: Endpoint,
) -> Result<()> {
    let connection_id = ConnectionPrincipal::generate()?;
    // Clients send their first request at once. Afterwards an idle MCP client
    // keeps its connection, and with it its approvals, for as long as it runs.
    let mut first_request = Some(Duration::from_secs(30));
    loop {
        let frame = read_frame(&mut *accepted.stream);
        let bytes = match first_request.take() {
            Some(limit) => tokio::time::timeout(limit, frame).await??,
            None => frame.await?,
        };
        let request = match parse_public_frame(&bytes).context("invalid_request")? {
            ParsedFrame::Frame(PublicFrame::Browser(request)) => {
                return browser::serve(accepted, app, request, endpoint).await;
            }
            // A Flatpak browser's socket serves only its connector.
            _ if endpoint == Endpoint::FlatpakBrowser => return Ok(()),
            ParsedFrame::Frame(PublicFrame::Manage(request)) => request,
            ParsedFrame::Frame(PublicFrame::Cli(request)) => {
                return cli::serve(accepted, app, request).await;
            }
            // A newer client: say so, rather than drop the connection.
            ParsedFrame::Unsupported => {
                return reply(&mut accepted, &failure(UNSUPPORTED_COMMAND)).await;
            }
        };
        if !manage::serve(&mut accepted, &app, &mounts, &connection_id, request).await? {
            return Ok(());
        }
    }
}

async fn reply(accepted: &mut Accepted, response: &Value) -> Result<()> {
    write_frame(&mut *accepted.stream, &serde_json::to_vec(response)?).await
}

/// Asks the app, then answers with `answer` of its response: `None`, as the
/// client went away, see [`while_connected`].
async fn ask(
    accepted: &mut Accepted,
    app: &AppClient,
    request: request::Request,
    answer: impl FnOnce(&mut response::Response) -> Value,
) -> Result<Option<Value>> {
    let Some(response) = while_connected(accepted, app.request(request)).await? else {
        return Ok(None);
    };
    Ok(Some(match response {
        Ok(mut response) => answer(&mut response.0),
        Err(_) => failure(UNAVAILABLE),
    }))
}

/// Runs `app_call`, a call to the app, while the client is connected.
/// Disconnect, unexpected input, or a client that exits or runs other code
/// drops it, which closes its private connection and cancels its approval
/// prompt: `None`. Otherwise the client is checked again before the result
/// is acted on.
async fn while_connected<T>(
    accepted: &mut Accepted,
    app_call: impl Future<Output = T>,
) -> Result<Option<T>> {
    let result = tokio::select! {
        result = app_call => result,
        _ = accepted.stream.read_u8() => return Ok(None),
        _ = accepted.guard.changed() => return Ok(None),
    };
    accepted.guard.revalidate().await?;
    Ok(Some(result))
}
