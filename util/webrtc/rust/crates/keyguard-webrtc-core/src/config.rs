//! The JSON peer configuration and the WebRTC settings derived from it.
//!
//! ```json
//! {
//!   "iceServers": [{"urls": ["turn:relay.example:3478"], "username": "u", "credential": "c"}],
//!   "iceTransportPolicy": "all",
//!   "bindAddresses": ["0.0.0.0:0", "[::]:0"]
//! }
//! ```
//!
//! Every field is optional. Unknown fields are ignored, so a newer caller can
//! talk to an older library. `bindAddresses` defaults to every interface of
//! both families; tests pin it to loopback.

use std::net::SocketAddr;

use serde::Deserialize;
use webrtc::peer_connection::{
    RTCConfiguration, RTCConfigurationBuilder, RTCIceServer, RTCIceTransportPolicy, SettingEngine,
    SettingEngineBuilder,
};

use crate::{MAX_CONFIG_BYTES, WebrtcError};

/// Most ICE servers one peer accepts.
const MAX_ICE_SERVERS: usize = 16;
/// Most local addresses one peer binds.
const MAX_BIND_ADDRESSES: usize = 16;
/// Addresses bound when the configuration names none: every interface, both families.
const DEFAULT_BIND_ADDRESSES: [&str; 2] = ["0.0.0.0:0", "[::]:0"];

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PeerConfigJson {
    #[serde(default)]
    ice_servers: Vec<IceServerJson>,
    #[serde(default)]
    ice_transport_policy: Option<String>,
    #[serde(default)]
    bind_addresses: Option<Vec<String>>,
}

#[derive(Deserialize)]
struct IceServerJson {
    urls: Vec<String>,
    #[serde(default)]
    username: Option<String>,
    #[serde(default)]
    credential: Option<String>,
}

/// A validated peer configuration.
pub(crate) struct PeerConfig {
    pub(crate) configuration: RTCConfiguration,
    pub(crate) bind_addresses: Vec<String>,
}

impl PeerConfig {
    /// Parses and validates the configuration JSON.
    pub(crate) fn parse(json: &str) -> Result<Self, WebrtcError> {
        if json.len() > MAX_CONFIG_BYTES {
            return Err(WebrtcError::InputTooLong);
        }
        let value: serde_json::Value =
            serde_json::from_str(json).map_err(|_| WebrtcError::InvalidArgument)?;
        // A derived struct would also accept a JSON array.
        if !value.is_object() {
            return Err(WebrtcError::InvalidArgument);
        }
        let parsed: PeerConfigJson =
            serde_json::from_value(value).map_err(|_| WebrtcError::InvalidArgument)?;
        if parsed.ice_servers.len() > MAX_ICE_SERVERS {
            return Err(WebrtcError::InvalidArgument);
        }
        let ice_servers = parsed
            .ice_servers
            .into_iter()
            .map(|server| {
                let server = RTCIceServer {
                    urls: server.urls,
                    username: server.username.unwrap_or_default(),
                    credential: server.credential.unwrap_or_default(),
                };
                // Parsing here turns a malformed URL into an argument error
                // rather than a stack error at build time.
                match server.urls() {
                    Ok(urls) if !urls.is_empty() => Ok(server),
                    _ => Err(WebrtcError::InvalidArgument),
                }
            })
            .collect::<Result<Vec<_>, _>>()?;
        let policy = match parsed.ice_transport_policy.as_deref() {
            None | Some("all") => RTCIceTransportPolicy::All,
            Some("relay") => RTCIceTransportPolicy::Relay,
            Some(_) => return Err(WebrtcError::InvalidArgument),
        };
        let bind_addresses = match parsed.bind_addresses {
            None => DEFAULT_BIND_ADDRESSES.map(str::to_owned).to_vec(),
            Some(addresses) => {
                if addresses.is_empty() || addresses.len() > MAX_BIND_ADDRESSES {
                    return Err(WebrtcError::InvalidArgument);
                }
                // Only literal socket addresses: a host name would make every
                // bind, and every ICE-restart rebind, a DNS lookup.
                if addresses
                    .iter()
                    .any(|address| address.parse::<SocketAddr>().is_err())
                {
                    return Err(WebrtcError::InvalidArgument);
                }
                addresses
            }
        };
        let configuration = RTCConfigurationBuilder::default()
            .with_ice_servers(ice_servers)
            .with_ice_transport_policy(policy)
            .build();
        Ok(Self {
            configuration,
            bind_addresses,
        })
    }
}

/// The settings every peer uses.
///
/// mDNS is off in both directions: no `.local` host candidates are published,
/// remote ones are dropped, and no socket is bound to port 5353, so iOS needs
/// no Bonjour service declaration.
pub(crate) fn setting_engine() -> SettingEngine {
    SettingEngineBuilder::new()
        .with_multicast_dns_mode(rtc::ice::mdns::MulticastDnsMode::Disabled)
        .build()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_empty_object_binds_every_interface_with_no_ice_servers() {
        let config = PeerConfig::parse("{}").expect("an empty object is a valid configuration");
        assert_eq!(config.bind_addresses, ["0.0.0.0:0", "[::]:0"]);
        assert!(config.configuration.ice_servers().is_empty());
        assert_eq!(
            config.configuration.ice_transport_policy(),
            RTCIceTransportPolicy::All
        );
    }

    #[test]
    fn a_full_configuration_is_accepted() {
        let config = PeerConfig::parse(
            r#"{
                "iceServers": [
                    {"urls": ["stun:stun.example.org:3478"]},
                    {"urls": ["turn:turn.example.org:3478?transport=udp"], "username": "1:a", "credential": "c"}
                ],
                "iceTransportPolicy": "relay",
                "bindAddresses": ["127.0.0.1:0"],
                "futureField": true
            }"#,
        )
        .expect("a full configuration must be accepted");
        assert_eq!(config.bind_addresses, ["127.0.0.1:0"]);
        assert_eq!(config.configuration.ice_servers().len(), 2);
        assert_eq!(
            config.configuration.ice_transport_policy(),
            RTCIceTransportPolicy::Relay
        );
    }

    #[test]
    fn invalid_configurations_are_argument_errors() {
        for json in [
            "",
            "[]",
            "not json",
            r#"{"iceTransportPolicy": "none"}"#,
            r#"{"iceServers": [{"urls": ["http://example.org"]}]}"#,
            r#"{"iceServers": [{"urls": []}]}"#,
            r#"{"iceServers": [{}]}"#,
            r#"{"bindAddresses": []}"#,
            r#"{"bindAddresses": ["localhost:0"]}"#,
        ] {
            assert_eq!(
                PeerConfig::parse(json).err(),
                Some(WebrtcError::InvalidArgument),
                "{json}"
            );
        }
    }

    #[test]
    fn an_oversized_configuration_is_too_long() {
        let json = format!("{{\"pad\":\"{}\"}}", "a".repeat(MAX_CONFIG_BYTES));
        assert_eq!(
            PeerConfig::parse(&json).err(),
            Some(WebrtcError::InputTooLong)
        );
    }
}
