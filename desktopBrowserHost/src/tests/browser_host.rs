//! The host as a browser starts it. The test that relays serves the CLI
//! agent's socket as a Unix socket.

use serde_json::{Value, json};
use std::{process::Stdio, time::Duration};
#[cfg(unix)]
use tokio::{io::AsyncWrite, net::UnixListener};
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWriteExt},
    process::Command,
};

const HOST: &str = env!("CARGO_BIN_EXE_keyguard-browser-host");
const FIREFOX: &str = "keyguard@artemchep.com";
/// An absolute path of the Firefox manifest on this platform.
const MANIFEST: &str = if cfg!(windows) {
    r"C:\Users\me\AppData\Roaming\Keyguard\com.artemchep.keyguard.firefox.json"
} else {
    "/home/me/.mozilla/native-messaging-hosts/com.artemchep.keyguard.json"
};

async fn timeout<T>(future: impl Future<Output = T>) -> T {
    tokio::time::timeout(Duration::from_secs(10), future)
        .await
        .expect("timed out")
}

/// A frame of the agent's socket: a big-endian length.
#[cfg(unix)]
async fn read_frame(stream: &mut (impl AsyncRead + Unpin)) -> Vec<u8> {
    let length = stream.read_u32().await.expect("frame header");
    let mut bytes = vec![0; length as usize];
    stream.read_exact(&mut bytes).await.expect("frame body");
    bytes
}

#[cfg(unix)]
async fn write_frame(stream: &mut (impl AsyncWrite + Unpin), bytes: &[u8]) {
    stream.write_u32(bytes.len() as u32).await.unwrap();
    stream.write_all(bytes).await.unwrap();
}

/// A native message: a native-endian length.
async fn read_message(stream: &mut (impl AsyncRead + Unpin)) -> Value {
    let mut length = [0; 4];
    stream
        .read_exact(&mut length)
        .await
        .expect("message header");
    let mut json = vec![0; u32::from_ne_bytes(length) as usize];
    stream.read_exact(&mut json).await.expect("message body");
    serde_json::from_slice(&json).expect("JSON")
}

#[cfg(unix)]
async fn write_message(stream: &mut (impl AsyncWrite + Unpin), value: Value) {
    let json = serde_json::to_vec(&value).unwrap();
    stream
        .write_all(&(json.len() as u32).to_ne_bytes())
        .await
        .unwrap();
    stream.write_all(&json).await.unwrap();
}

#[cfg(unix)]
#[tokio::test]
async fn the_host_relays_for_the_extension_that_firefox_names() {
    let directory = tempfile::tempdir().unwrap();
    let socket = directory.path().join("s");
    let listener = UnixListener::bind(&socket).unwrap();
    let mut host = Command::new(HOST)
        .args([MANIFEST, FIREFOX])
        .env("KEYGUARD_CLI_SOCKET", &socket)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .unwrap();
    let mut stdin = host.stdin.take().unwrap();
    let mut stdout = host.stdout.take().unwrap();

    let (mut agent, _) = timeout(listener.accept()).await.unwrap();
    let frame = timeout(read_frame(&mut agent)).await;
    assert_eq!(
        serde_json::from_slice::<Value>(&frame).unwrap(),
        json!({"type":"browser","origin":FIREFOX,"hostRevision":1})
    );
    write_frame(&mut agent, br#"{"ok":true}"#).await;
    assert_eq!(
        timeout(read_message(&mut stdout)).await,
        json!({"type":"status","status":"ready"})
    );
    write_message(&mut stdin, json!({"type":"noise","data":"AQID"})).await;
    assert_eq!(timeout(read_frame(&mut agent)).await, [1, 2, 3]);
    write_frame(&mut agent, &[4, 5]).await;
    assert_eq!(
        timeout(read_message(&mut stdout)).await,
        json!({"type":"noise","data":"BAU="})
    );

    // The browser closing stdin stops the host.
    drop(stdin);
    assert!(timeout(host.wait()).await.unwrap().success());
}

#[tokio::test]
async fn an_oversized_message_stops_the_host() {
    let directory = tempfile::tempdir().unwrap();
    let mut host = Command::new(HOST)
        .arg("chrome-extension://abcdefghijklmnopabcdefghijklmnop/")
        .arg("--parent-window=0")
        .env("KEYGUARD_CLI_SOCKET", directory.path().join("absent"))
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true)
        .spawn()
        .unwrap();
    let mut stdout = host.stdout.take().unwrap();
    assert_eq!(
        timeout(read_message(&mut stdout)).await,
        json!({"type":"status","status":"app_unavailable"})
    );
    let mut stdin = host.stdin.take().unwrap();
    stdin
        .write_all(&(128 * 1024 + 1_u32).to_ne_bytes())
        .await
        .unwrap();
    let output = timeout(host.wait_with_output()).await.unwrap();
    assert_eq!(output.status.code(), Some(1));
    let stderr = String::from_utf8(output.stderr).unwrap();
    assert!(stderr.contains("too large"), "{stderr}");
}

#[test]
fn arguments_that_no_browser_passes_are_refused() {
    for args in [
        &[][..],
        &["--help"],
        &["chrome-extension://x/"],
        &["--help", "--version"],
        &["manifest.json", FIREFOX],
        &[MANIFEST, "moz-extension://x/"],
    ] {
        let output = std::process::Command::new(HOST)
            .args(args)
            .stdin(Stdio::null())
            .output()
            .unwrap();
        assert_eq!(output.status.code(), Some(2), "{args:?}");
        assert!(output.stdout.is_empty(), "{args:?}");
        assert!(!output.stderr.is_empty(), "{args:?}");
    }
}
