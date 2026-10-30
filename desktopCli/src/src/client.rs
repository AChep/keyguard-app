//! Asks Keyguard, through its CLI agent, to answer one command.
use anyhow::Context;
use keyguard_cli_protocol::{
    cli::{CliCommand, CliRequest},
    errors::error_code,
    frame::{read_frame, write_frame},
    json::PrivateJson,
    public::PublicFrameRef,
    socket::client_socket,
    transport,
};
use std::{
    path::{Path, PathBuf},
    time::Duration,
};
use zeroize::Zeroizing;

/// Connects to the CLI agent at `socket`; a busy Windows pipe waits.
pub(crate) async fn connect(socket: &Path) -> anyhow::Result<transport::BoxStream> {
    tokio::time::timeout(transport::CONNECT_TIMEOUT, transport::connect(socket, None))
        .await
        .context("connect_timeout")?
}

/// Sends `request` and returns its successful result, or the error code.
/// Tells the user to look at Keyguard while it waits for approval.
pub(crate) async fn request<C: CliCommand>(
    socket: Option<PathBuf>,
    request: C,
) -> Result<PrivateJson, String> {
    request
        .validate()
        .map_err(|_| "invalid_arguments".to_owned())?;
    let request: CliRequest = request.into();
    let socket = client_socket(socket).map_err(|_| "unavailable".to_owned())?;
    let bytes = async {
        let mut stream = connect(&socket).await?;
        let frame = Zeroizing::new(serde_json::to_vec(&PublicFrameRef::Cli(&request))?);
        write_frame(&mut *stream, &frame).await?;
        // Keyguard bounds the wait for an approval, and Ctrl+C cancels it.
        let response = read_frame(&mut *stream);
        tokio::pin!(response);
        tokio::select! {
            response = &mut response => response,
            _ = tokio::time::sleep(Duration::from_millis(500)) => {
                eprintln!("keyguard: waiting for approval in Keyguard…");
                response.await
            }
        }
    }
    .await
    .map_err(|_| "unavailable".to_owned())?;
    let response =
        PrivateJson(serde_json::from_slice(&bytes).map_err(|_| "invalid_response".to_owned())?);
    if !C::is_valid_result(&response.0) {
        return Err(error_code(&response.0).to_owned());
    }
    Ok(response)
}
