//! Wire protocol definitions shared by the WebSocket (extension <-> agent)
//! and the IPC channel (agent <-> Keyguard desktop app).
//!
//! Both transports use length-prefixed JSON frames; the WebSocket transport
//! additionally wraps every post-handshake frame in an AES-256-GCM envelope.

use serde::{Deserialize, Serialize};

// ---------------------------------------------------------------------------
// WebSocket handshake + envelope (extension <-> agent)
// ---------------------------------------------------------------------------

/// First message sent by the extension: its identity and X25519 public key.
#[derive(Serialize, Deserialize, Debug)]
pub struct WsClientHello {
    pub client_id: String,
    pub public_key: String,
}

/// Agent's reply to [`WsClientHello`] carrying its X25519 public key.
#[derive(Serialize, Deserialize, Debug)]
pub struct WsServerHello {
    pub public_key: String,
}

/// Every post-handshake WebSocket frame is an AES-256-GCM envelope.
#[derive(Serialize, Deserialize, Debug)]
pub struct WsEnvelope {
    pub nonce: String,
    pub payload: String,
}

// ---------------------------------------------------------------------------
// Inner requests/responses carried inside the envelope (extension <-> agent)
// ---------------------------------------------------------------------------

#[derive(Serialize, Deserialize, Debug)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ClientRequest {
    /// List logins matching a domain (no secrets returned yet).
    Query { domain: String, uri: Option<String> },
    /// Fetch the username/password/totp for a previously listed item.
    Secret { item_id: String },
    /// Ask the desktop app to bring its window to the foreground.
    /// The optional `token` carries an `XDG_ACTIVATION_TOKEN` (set by the
    /// NM host) for Wayland-aware compositors.
    RequestForeground {
        #[serde(default)]
        token: Option<String>,
    },
    /// HMAC challenge-response: extension sends the signed challenge.
    HmacResponse {
        /// Base64-encoded HMAC-SHA256(shared_secret, challenge).
        response: String,
    },
}

#[derive(Serialize, Deserialize, Debug)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum AgentResponse {
    Query(QueryResult),
    Secret(SecretResult),
    RequestForeground { success: bool },
    /// Agent sends a random challenge for HMAC verification.
    HmacChallenge {
        /// Base64-encoded 32-byte random challenge.
        challenge: String,
    },
    /// HMAC verification succeeded.
    HmacOk,
    /// HMAC verification failed.
    HmacFailed,
}

/// Result of a [`ClientRequest::Query`].
#[derive(Serialize, Deserialize, Debug, Default)]
pub struct QueryResult {
    /// True when the Keyguard vault is locked and cannot serve credentials.
    #[serde(default)]
    pub locked: bool,
    /// Matching logins (without secrets).
    #[serde(default)]
    pub items: Vec<AutofillItem>,
}

/// A login entry that matches the requested domain.
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct AutofillItem {
    pub item_id: String,
    pub name: String,
    pub username: String,
    #[serde(default)]
    pub has_totp: bool,
    #[serde(default)]
    pub has_passkey: bool,
}

/// Result of a [`ClientRequest::Secret`].
#[derive(Serialize, Deserialize, Debug, Default)]
pub struct SecretResult {
    #[serde(default)]
    pub locked: bool,
    #[serde(default)]
    pub username: Option<String>,
    #[serde(default)]
    pub password: Option<String>,
    #[serde(default)]
    pub totp: Option<String>,
}

// ---------------------------------------------------------------------------
// IPC channel messages (agent <-> Keyguard desktop app), JSON framed.
// ---------------------------------------------------------------------------

#[derive(Serialize, Deserialize, Debug)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum IpcRequest {
    Authenticate { token: String },
    Query { domain: String, uri: Option<String> },
    Secret { item_id: String },
    RequestForeground {
        #[serde(default)]
        token: Option<String>,
    },
}

#[derive(Serialize, Deserialize, Debug)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum IpcResponse {
    Authenticate { success: bool },
    Query(QueryResult),
    Secret(SecretResult),
    RequestForeground {
        #[serde(default)]
        success: bool
    },
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn parses_query_without_items() {
        let json = r#"{"type":"query","locked":true}"#;
        let r: IpcResponse = serde_json::from_str(json).unwrap();
        match r {
            IpcResponse::Query(q) => assert!(q.locked && q.items.is_empty()),
            _ => panic!("wrong variant"),
        }
    }
    #[test]
    fn parses_query_with_items() {
        let json = r#"{"type":"query","locked":false,"items":[{"item_id":"a","name":"n","username":"u"}]}"#;
        let r: IpcResponse = serde_json::from_str(json).unwrap();
        match r {
            IpcResponse::Query(q) => assert_eq!(q.items.len(), 1),
            _ => panic!("wrong variant"),
        }
    }
}
