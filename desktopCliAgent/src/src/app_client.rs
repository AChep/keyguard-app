//! The private channel to the app that started this agent.
use crate::protocol::{
    AuthenticateRequest, MountStatus, MountStatusRequest, PROTOCOL_REVISION, Private, Request,
    Response, request, response,
};
use anyhow::{Context, Result, ensure};
use keyguard_cli_protocol::{
    frame::{read_frame, write_frame},
    transport,
};
use prost::Message;
use std::{path::PathBuf, sync::Arc, time::Duration};
use zeroize::Zeroizing;

#[derive(Clone)]
pub(crate) struct AppClient {
    pub(crate) socket: PathBuf,
    pub(crate) pid: u32,
    pub(crate) token: Arc<Zeroizing<[u8; 32]>>,
}

impl AppClient {
    /// Sends one request over a connection of its own. The answer can hold
    /// values, which are wiped once it is dropped.
    pub(crate) async fn request(
        &self,
        request: request::Request,
    ) -> Result<Private<response::Response>> {
        let request = Private(Request {
            request: Some(request),
        });
        tokio::time::timeout(Duration::from_secs(70), async {
            let mut stream = transport::connect(&self.socket, Some(self.pid)).await?;
            let auth = Private(Request {
                request: Some(request::Request::Authenticate(AuthenticateRequest {
                    token: self.token.to_vec(),
                    protocol_revision: PROTOCOL_REVISION,
                })),
            });
            write_frame(&mut *stream, &Zeroizing::new(auth.0.encode_to_vec())).await?;
            let response = read_response(&mut *stream).await?;
            ensure!(
                matches!(response.0, response::Response::Authenticate(_)),
                "authentication_failed"
            );
            write_frame(&mut *stream, &Zeroizing::new(request.0.encode_to_vec())).await?;
            read_response(&mut *stream).await
        })
        .await
        .context("request_timeout")?
    }

    /// Reports a mount status to the app.
    pub(crate) async fn mount_status(&self, id: &str, status: MountStatus) {
        let _ = self
            .request(request::Request::MountStatus(MountStatusRequest {
                mount_id: id.to_owned(),
                status: status.into(),
            }))
            .await;
    }
}

async fn read_response(
    stream: &mut (impl tokio::io::AsyncRead + Unpin + ?Sized),
) -> Result<Private<response::Response>> {
    let response = Response::decode(read_frame(stream).await?.as_slice())?;
    Ok(Private(response.response.context("invalid_response")?))
}
