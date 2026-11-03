//! The launch contract of an agent. The app writes the token as 64 hex digits
//! and a newline to the agent's standard input, then keeps it open: its end
//! or a read error means that the app is gone, and any further input is
//! ignored.
use anyhow::{Context, Result};
use std::fmt;
use std::io::Read;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::oneshot;
use tracing::warn;
use zeroize::Zeroizing;

/// The size of the launch token.
pub const AUTH_TOKEN_LEN: usize = 32;

/// How long the app has to write the token after launching the agent.
pub const LAUNCH_TOKEN_TIMEOUT: Duration = Duration::from_secs(5);

/// The launch token. Its single buffer is wiped once the last clone drops.
#[derive(Clone)]
pub struct AuthToken(Arc<Zeroizing<[u8; AUTH_TOKEN_LEN]>>);

impl AuthToken {
    /// A token of `bytes`.
    pub fn from_bytes(bytes: [u8; AUTH_TOKEN_LEN]) -> Self {
        Self(Arc::new(Zeroizing::new(bytes)))
    }

    /// The bytes of the token.
    pub fn as_bytes(&self) -> &[u8; AUTH_TOKEN_LEN] {
        &self.0
    }
}

impl fmt::Debug for AuthToken {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("AuthToken(..)")
    }
}

/// What the app passed to the agent at launch.
pub struct Launch {
    /// The token that authenticates the agent to the app.
    pub token: AuthToken,
    /// Resolves once the app is gone.
    pub parent_gone: oneshot::Receiver<()>,
}

/// Reads the launch token from standard input, and watches it for the app's
/// exit from then on.
///
/// # Errors
///
/// Fails when the token is malformed or does not arrive in time.
pub async fn read_launch() -> Result<Launch> {
    read_launch_from(std::io::stdin(), LAUNCH_TOKEN_TIMEOUT).await
}

/// Writes the record that tells the app the agent's public endpoint is ready.
///
/// # Errors
///
/// Fails when standard output fails.
pub fn write_ready_record() -> std::io::Result<()> {
    keyguard_agent_identity::write_startup_ready_record_to(std::io::stdout().lock())
}

async fn read_launch_from(input: impl Read + Send + 'static, timeout: Duration) -> Result<Launch> {
    let (token_tx, token_rx) = oneshot::channel();
    let (gone_tx, gone_rx) = oneshot::channel();
    // A standard thread, not `spawn_blocking`: the runtime would wait for
    // this uncancellable read at shutdown.
    std::thread::Builder::new()
        .name("keyguard-parent-stdin".to_owned())
        .spawn(move || {
            let mut input = input;
            let _ = token_tx.send(read_token(&mut input));
            // Only the end of the input, or a read error, means that the app
            // is gone. The buffer is wiped in case the input repeats the token.
            let mut trailing = Zeroizing::new([0u8; 256]);
            let mut warned = false;
            while let Ok(1..) = input.read(&mut trailing[..]) {
                if !warned {
                    warned = true;
                    warn!("Ignoring unexpected input after the launch token");
                }
            }
            let _ = gone_tx.send(());
        })
        .context("failed to start the launch token reader")?;
    let token = tokio::time::timeout(timeout, token_rx)
        .await
        .context("the launch token did not arrive in time")?
        .context("the launch token reader stopped")?
        .context("the launch token is malformed")?;
    Ok(Launch {
        token,
        parent_gone: gone_rx,
    })
}

/// Reads exactly one line of 64 hex digits. The token is decoded in place, so
/// no unwiped copy of it is left behind.
fn read_token(input: &mut impl Read) -> Option<AuthToken> {
    let mut line = Zeroizing::new([0u8; 2 * AUTH_TOKEN_LEN + 1]);
    input.read_exact(&mut line[..]).ok()?;
    if line[2 * AUTH_TOKEN_LEN] != b'\n' {
        return None;
    }
    let mut token = Arc::new(Zeroizing::new([0u8; AUTH_TOKEN_LEN]));
    let bytes = Arc::get_mut(&mut token)?;
    hex::decode_to_slice(&line[..2 * AUTH_TOKEN_LEN], &mut bytes[..]).ok()?;
    Some(AuthToken(token))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    const TOKEN_LINE: &str = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f\n";

    /// A reader that blocks until the test ends: the app keeps stdin open.
    struct OpenInput(Cursor<Vec<u8>>, std::sync::mpsc::Receiver<()>);

    impl Read for OpenInput {
        fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
            match self.0.read(buf)? {
                0 => {
                    let _ = self.1.recv();
                    Ok(0)
                }
                read => Ok(read),
            }
        }
    }

    #[tokio::test]
    async fn the_token_arrives_and_the_end_of_input_means_the_app_is_gone() {
        let launch = read_launch_from(
            Cursor::new(TOKEN_LINE.as_bytes().to_vec()),
            LAUNCH_TOKEN_TIMEOUT,
        )
        .await
        .unwrap();
        let expected: [u8; AUTH_TOKEN_LEN] = std::array::from_fn(|i| i as u8);
        assert_eq!(launch.token.as_bytes(), &expected);
        launch.parent_gone.await.unwrap();
    }

    #[tokio::test]
    async fn trailing_input_is_ignored_until_the_end_of_input() {
        let (release, released) = std::sync::mpsc::channel();
        let input = OpenInput(Cursor::new(format!("{TOKEN_LINE}x").into_bytes()), released);
        let mut launch = read_launch_from(input, LAUNCH_TOKEN_TIMEOUT).await.unwrap();
        let waited = tokio::time::timeout(Duration::from_millis(50), &mut launch.parent_gone).await;
        assert!(waited.is_err());
        release.send(()).unwrap();
        launch.parent_gone.await.unwrap();
    }

    #[tokio::test]
    async fn an_open_input_keeps_the_app_alive() {
        let (release, released) = std::sync::mpsc::channel();
        let input = OpenInput(Cursor::new(TOKEN_LINE.as_bytes().to_vec()), released);
        let mut launch = read_launch_from(input, LAUNCH_TOKEN_TIMEOUT).await.unwrap();
        let waited = tokio::time::timeout(Duration::from_millis(50), &mut launch.parent_gone).await;
        assert!(waited.is_err());
        release.send(()).unwrap();
        launch.parent_gone.await.unwrap();
    }

    #[tokio::test]
    async fn malformed_tokens_are_rejected() {
        let lines = [
            TOKEN_LINE.replace('\n', "\r\n"),
            TOKEN_LINE[1..].to_owned(),
            TOKEN_LINE.replace('0', "g"),
            TOKEN_LINE.trim_end().to_owned(),
            String::new(),
        ];
        for line in lines {
            let result =
                read_launch_from(Cursor::new(line.clone().into_bytes()), LAUNCH_TOKEN_TIMEOUT)
                    .await;
            assert!(result.is_err(), "{line:?}");
        }
    }

    #[tokio::test]
    async fn a_missing_token_times_out() {
        let (_release, released) = std::sync::mpsc::channel();
        let input = OpenInput(Cursor::new(Vec::new()), released);
        let error = read_launch_from(input, Duration::from_millis(50))
            .await
            .err()
            .expect("no token");
        assert!(error.to_string().contains("in time"), "{error}");
    }

    #[test]
    fn the_token_is_not_printed() {
        let token = AuthToken::from_bytes([0xAB; AUTH_TOKEN_LEN]);
        assert_eq!(format!("{token:?}"), "AuthToken(..)");
    }
}
