//! Asks Keyguard, through its CLI agent, to answer one command.
use keyguard_cli_protocol::{
    cli::{CliCommand, CliRequest},
    errors::error_code,
    frame::{read_frame, write_frame},
    json::PrivateJson,
    public::PublicFrameRef,
    socket::client_socket,
    transport,
};
use std::{path::PathBuf, time::Duration};
use zeroize::Zeroizing;

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
    let bytes = tokio::time::timeout(Duration::from_secs(90), async {
        let mut stream = transport::connect(&socket, None).await?;
        let frame = Zeroizing::new(serde_json::to_vec(&PublicFrameRef::Cli(&request))?);
        write_frame(&mut *stream, &frame).await?;
        let response = read_frame(&mut *stream);
        tokio::pin!(response);
        tokio::select! {
            response = &mut response => response,
            _ = tokio::time::sleep(Duration::from_millis(500)) => {
                eprintln!("keyguard: waiting for approval in Keyguard…");
                response.await
            }
        }
    })
    .await
    .map_err(|_| "request_timeout".to_owned())?
    .map_err(|_| "unavailable".to_owned())?;
    let response =
        PrivateJson(serde_json::from_slice(&bytes).map_err(|_| "invalid_response".to_owned())?);
    if !C::is_valid_result(&response.0) {
        return Err(error_code(&response.0).to_owned());
    }
    Ok(response)
}
