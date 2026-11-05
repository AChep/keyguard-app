//! Configuration read from environment variables at startup.

use std::fmt;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::str::FromStr;
use std::time::Duration;

use crate::MAX_FRAME_BYTES;

/// Relay configuration. Every field but `timeouts` maps to one environment variable.
#[derive(Clone)]
pub struct Config {
    /// Listen address (`RELAY_BIND`).
    pub bind: SocketAddr,
    /// Separate, unauthenticated metrics listener (`RELAY_METRICS_BIND`). `None` disables
    /// it. Bind to loopback or protect it with a private network or authenticating proxy.
    pub metrics_bind: Option<SocketAddr>,
    /// Access token required on the WebSocket and ICE endpoints (`RELAY_TOKEN`).
    /// `None` makes the relay public.
    pub token: Option<String>,
    /// Maximum number of peers in one room (`RELAY_MAX_ROOM_PEERS`).
    pub max_room_peers: usize,
    /// Messages a connection may send per second (`RELAY_MSGS_PER_SEC`).
    pub msgs_per_sec: u32,
    /// Bytes a connection may send per second (`RELAY_BYTES_PER_SEC`).
    pub bytes_per_sec: u64,
    /// Concurrent WebSocket connections per client address (`RELAY_MAX_CONNS_PER_IP`).
    pub max_conns_per_ip: u32,
    /// Concurrent WebSocket connections on the relay (`RELAY_MAX_CONNS`).
    pub max_conns: usize,
    /// Bytes per second the relay forwards in `msg` and binary frames, across all
    /// connections (`RELAY_EGRESS_BYTES_PER_SEC`).
    pub egress_bytes_per_sec: u64,
    /// A socket that sends nothing, not even a pong, for this long is closed (`RELAY_IDLE_SECS`).
    pub idle_timeout: Duration,
    /// Maximum number of rooms on the relay (`RELAY_MAX_ROOMS`).
    pub max_rooms: usize,
    /// TURN credentials per minute per client address (`ICE_REQUESTS_PER_MINUTE`).
    /// Past it, the ICE endpoint answers with STUN only.
    pub ice_requests_per_minute: u32,
    /// Of those, credentials per minute for requests without a live ticket
    /// (`ICE_TICKETLESS_PER_MINUTE`). `0` gives such requests STUN only.
    pub ice_ticketless_per_minute: u32,
    /// TURN credentials per minute on the whole relay (`ICE_TOTAL_PER_MINUTE`). Requests
    /// without a live ticket can't take the last half.
    pub ice_total_per_minute: u32,
    /// STUN URLs handed out by the ICE endpoint (`STUN_URLS`).
    pub stun_urls: Vec<String>,
    /// TURN URLs and shared secret (`TURN_URLS`, `TURN_SECRET`). `None` disables TURN.
    pub turn: Option<TurnConfig>,
    /// Lifetime of minted TURN credentials in seconds (`TURN_TTL_SECONDS`).
    pub turn_ttl_seconds: u64,
    /// Proxies whose `X-Forwarded-For` header names the client (`TRUST_PROXY`).
    pub trust_proxy: Vec<IpNet>,
    /// Not configurable: tests shorten these instead of waiting them out.
    #[doc(hidden)]
    pub timeouts: Timeouts,
}

/// Deadlines for slow clients.
#[doc(hidden)]
#[derive(Debug, Clone, Copy)]
pub struct Timeouts {
    /// Time to receive a complete HTTP request header.
    pub http_header: Duration,
    /// Time to receive a request body once its header is in.
    pub http_body: Duration,
    /// A WebSocket client that does not take a frame within this time is dropped.
    pub send: Duration,
}

impl Default for Timeouts {
    fn default() -> Self {
        Self {
            http_header: Duration::from_secs(30),
            http_body: Duration::from_secs(10),
            send: Duration::from_secs(10),
        }
    }
}

/// TURN servers that accept credentials derived from a shared secret.
#[derive(Clone)]
pub struct TurnConfig {
    /// `turn:` and `turns:` URLs handed out with each credential.
    pub urls: Vec<String>,
    /// Secret shared with the TURN server (coturn `static-auth-secret`).
    pub secret: String,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            bind: SocketAddr::from(([0, 0, 0, 0], 7400)),
            metrics_bind: None,
            token: None,
            max_room_peers: 16,
            msgs_per_sec: 50,
            bytes_per_sec: 2_097_152,
            max_conns_per_ip: 32,
            max_conns: 10_000,
            egress_bytes_per_sec: 52_428_800,
            idle_timeout: Duration::from_secs(60),
            max_rooms: 10_000,
            ice_requests_per_minute: 10,
            ice_ticketless_per_minute: 1,
            ice_total_per_minute: 300,
            stun_urls: Vec::new(),
            turn: None,
            // A relayed connection lasts until its credential expires. Issuance, not the
            // lifetime, is what the allowances limit.
            turn_ttl_seconds: 43_200,
            trust_proxy: Vec::new(),
            timeouts: Timeouts::default(),
        }
    }
}

impl Config {
    /// Reads the configuration from the process environment.
    pub fn from_env() -> Result<Self, ConfigError> {
        Self::from_lookup(|name| std::env::var(name).ok())
    }

    /// Reads the configuration through `lookup`, which returns the value of a variable.
    /// Empty values count as unset.
    pub fn from_lookup(lookup: impl Fn(&str) -> Option<String>) -> Result<Self, ConfigError> {
        let get = |name: &str| {
            lookup(name)
                .map(|value| value.trim().to_owned())
                .filter(|value| !value.is_empty())
        };
        let defaults = Self::default();

        let bind = match get("RELAY_BIND") {
            Some(value) => value.parse().map_err(|_| {
                ConfigError::new("RELAY_BIND", "must be an address like 0.0.0.0:7400")
            })?,
            None => defaults.bind,
        };
        let token = get("RELAY_TOKEN").map(validate_token).transpose()?;
        let metrics_bind = get("RELAY_METRICS_BIND")
            .map(|value| {
                value.parse().map_err(|_| {
                    ConfigError::new(
                        "RELAY_METRICS_BIND",
                        "must be an address like 127.0.0.1:7401",
                    )
                })
            })
            .transpose()?;
        // At least one message of the largest size per second.
        let bytes_per_sec = number(
            &get,
            "RELAY_BYTES_PER_SEC",
            defaults.bytes_per_sec,
            MAX_FRAME_BYTES as u64,
            1 << 30,
        )?;
        let idle_secs = number(
            &get,
            "RELAY_IDLE_SECS",
            defaults.idle_timeout.as_secs(),
            1,
            86_400,
        )?;
        let stun_urls = urls(&get, "STUN_URLS", &["stun", "stuns"])?;
        let turn_urls = urls(&get, "TURN_URLS", &["turn", "turns"])?;
        // Secrets must reach coturn unchanged; unlike other settings, do not trim them.
        let turn_secret = lookup("TURN_SECRET").filter(|value| !value.trim().is_empty());
        let turn = match (turn_urls.is_empty(), turn_secret) {
            (true, None) => None,
            (false, Some(secret)) => Some(TurnConfig {
                urls: turn_urls,
                secret: validate_turn_secret(secret)?,
            }),
            (false, None) => {
                return Err(ConfigError::new(
                    "TURN_SECRET",
                    "must be set when TURN_URLS is set",
                ));
            }
            (true, Some(_)) => {
                return Err(ConfigError::new(
                    "TURN_URLS",
                    "must be set when TURN_SECRET is set",
                ));
            }
        };
        let trust_proxy = match get("TRUST_PROXY") {
            Some(value) => list(&value)
                .map(|item| {
                    item.parse().map_err(|_| {
                        ConfigError::new("TRUST_PROXY", "must list IP addresses or CIDR ranges")
                    })
                })
                .collect::<Result<_, _>>()?,
            None => Vec::new(),
        };

        Ok(Self {
            bind,
            metrics_bind,
            token,
            max_room_peers: number(
                &get,
                "RELAY_MAX_ROOM_PEERS",
                defaults.max_room_peers,
                2,
                1024,
            )?,
            msgs_per_sec: number(
                &get,
                "RELAY_MSGS_PER_SEC",
                defaults.msgs_per_sec,
                1,
                100_000,
            )?,
            bytes_per_sec,
            max_conns_per_ip: number(
                &get,
                "RELAY_MAX_CONNS_PER_IP",
                defaults.max_conns_per_ip,
                1,
                65_535,
            )?,
            max_conns: number(&get, "RELAY_MAX_CONNS", defaults.max_conns, 1, 1_000_000)?,
            // A broadcast may overdraw the budget, so one message of the largest size is
            // enough to forward anything.
            egress_bytes_per_sec: number(
                &get,
                "RELAY_EGRESS_BYTES_PER_SEC",
                defaults.egress_bytes_per_sec,
                MAX_FRAME_BYTES as u64,
                1 << 40,
            )?,
            idle_timeout: Duration::from_secs(idle_secs),
            max_rooms: number(&get, "RELAY_MAX_ROOMS", defaults.max_rooms, 1, 10_000_000)?,
            ice_requests_per_minute: number(
                &get,
                "ICE_REQUESTS_PER_MINUTE",
                defaults.ice_requests_per_minute,
                1,
                10_000,
            )?,
            ice_ticketless_per_minute: number(
                &get,
                "ICE_TICKETLESS_PER_MINUTE",
                defaults.ice_ticketless_per_minute,
                0,
                10_000,
            )?,
            // Requests without a ticket need a first half to take.
            ice_total_per_minute: number(
                &get,
                "ICE_TOTAL_PER_MINUTE",
                defaults.ice_total_per_minute,
                2,
                1_000_000,
            )?,
            stun_urls,
            turn,
            turn_ttl_seconds: number(
                &get,
                "TURN_TTL_SECONDS",
                defaults.turn_ttl_seconds,
                60,
                86_400,
            )?,
            trust_proxy,
            timeouts: defaults.timeouts,
        })
    }
}

/// A configuration variable with an invalid value. The message never contains the value.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConfigError {
    variable: &'static str,
    message: String,
}

impl ConfigError {
    fn new(variable: &'static str, message: impl Into<String>) -> Self {
        Self {
            variable,
            message: message.into(),
        }
    }

    /// Name of the offending environment variable.
    pub fn variable(&self) -> &'static str {
        self.variable
    }
}

impl fmt::Display for ConfigError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{} {}", self.variable, self.message)
    }
}

impl std::error::Error for ConfigError {}

/// An IP address range in CIDR notation; a bare address is a single-address range.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct IpNet {
    addr: IpAddr,
    prefix: u8,
}

impl IpNet {
    /// Whether `ip` is inside the range. IPv4-mapped IPv6 addresses match IPv4 ranges.
    pub fn contains(&self, ip: IpAddr) -> bool {
        match (self.addr, ip.to_canonical()) {
            (IpAddr::V4(net), IpAddr::V4(ip)) => {
                let mask = u32::MAX
                    .checked_shl(32 - u32::from(self.prefix))
                    .unwrap_or(0);
                u32::from(net) & mask == u32::from(ip) & mask
            }
            (IpAddr::V6(net), IpAddr::V6(ip)) => {
                let mask = u128::MAX
                    .checked_shl(128 - u32::from(self.prefix))
                    .unwrap_or(0);
                u128::from(net) & mask == u128::from(ip) & mask
            }
            _ => false,
        }
    }
}

impl FromStr for IpNet {
    type Err = ();

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        let (addr, prefix) = match value.split_once('/') {
            Some((addr, prefix)) => (addr, Some(prefix)),
            None => (value, None),
        };
        let addr = addr.parse::<IpAddr>().map_err(|_| ())?.to_canonical();
        let max = if addr.is_ipv4() { 32 } else { 128 };
        let prefix = match prefix {
            Some(prefix) => prefix.parse::<u8>().map_err(|_| ())?,
            None => max,
        };
        if prefix > max {
            return Err(());
        }
        Ok(Self { addr, prefix })
    }
}

/// The non-empty items of a comma-separated list.
fn list(value: &str) -> impl Iterator<Item = &str> {
    value
        .split(',')
        .map(str::trim)
        .filter(|item| !item.is_empty())
}

fn number<T>(
    get: &impl Fn(&str) -> Option<String>,
    name: &'static str,
    default: T,
    min: T,
    max: T,
) -> Result<T, ConfigError>
where
    T: FromStr + PartialOrd + fmt::Display + Copy,
{
    let Some(value) = get(name) else {
        return Ok(default);
    };
    value
        .parse::<T>()
        .ok()
        .filter(|parsed| *parsed >= min && *parsed <= max)
        .ok_or_else(|| ConfigError::new(name, format!("must be an integer from {min} to {max}")))
}

fn validate_token(token: String) -> Result<String, ConfigError> {
    let url_safe = token
        .bytes()
        .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'.' | b'_' | b'~'));
    if token.len() < 16 || !url_safe {
        return Err(ConfigError::new(
            "RELAY_TOKEN",
            "must be at least 16 characters from A-Z, a-z, 0-9, '-', '.', '_', '~'",
        ));
    }
    Ok(token)
}

fn validate_turn_secret(secret: String) -> Result<String, ConfigError> {
    // Match Compose's validation before it renders coturn's bounded config lines.
    let safe = secret.bytes().all(|byte| {
        byte.is_ascii_alphanumeric()
            || matches!(byte, b'+' | b'/' | b'=' | b'-' | b'.' | b'_' | b'~')
    });
    if !(16..=256).contains(&secret.len()) || !safe {
        return Err(ConfigError::new(
            "TURN_SECRET",
            "must be 16 to 256 characters from A-Z, a-z, 0-9, '+', '/', '=', '-', '.', '_', '~'",
        ));
    }
    Ok(secret)
}

fn urls(
    get: &impl Fn(&str) -> Option<String>,
    name: &'static str,
    schemes: &[&str],
) -> Result<Vec<String>, ConfigError> {
    let Some(value) = get(name) else {
        return Ok(Vec::new());
    };
    list(&value)
        .enumerate()
        .map(|(index, url)| {
            check_ice_url(url, schemes)
                .map(|()| url.to_owned())
                .map_err(|reason| {
                    ConfigError::new(
                        name,
                        format!(
                            "must be a comma-separated list of {}: URLs; URL {} {reason}",
                            schemes.join(": or "),
                            index + 1,
                        ),
                    )
                })
        })
        .collect()
}

/// Checks a `stun:`/`stuns:` URL (RFC 7064) or a `turn:`/`turns:` URL (RFC 7065) the
/// way WebRTC stacks read it: a lowercase scheme, a host name, IPv4 address or bracketed
/// IPv6 address, an optional port, and on TURN URLs an optional `transport`. One bad URL
/// can fail a client's whole ICE configuration. Returns the rule the URL breaks.
fn check_ice_url(url: &str, schemes: &[&str]) -> Result<(), &'static str> {
    const HOST: &str = "has an invalid host";
    const PORT: &str = "has an invalid port, ports are 1 to 65535";
    let (scheme, rest) = url
        .split_once(':')
        .filter(|(scheme, _)| schemes.contains(scheme))
        .ok_or("has a different scheme")?;
    let (authority, query) = match rest.split_once('?') {
        Some((authority, query)) => (authority, Some(query)),
        None => (rest, None),
    };
    match (scheme, query) {
        // `turns:` is TLS over TCP: WebRTC stacks don't run TURN over DTLS.
        (_, None)
        | ("turn", Some("transport=udp" | "transport=tcp"))
        | ("turns", Some("transport=tcp")) => {}
        ("turn", Some(_)) => {
            return Err("has a query other than ?transport=udp or ?transport=tcp");
        }
        ("turns", Some(_)) => return Err("has a query other than ?transport=tcp"),
        (_, Some(_)) => return Err("has a query"),
    }
    let (host_is_valid, port) = match authority.strip_prefix('[') {
        Some(bracketed) => {
            let (address, after) = bracketed.split_once(']').ok_or(HOST)?;
            let port = match after {
                "" => None,
                after => Some(after.strip_prefix(':').ok_or(HOST)?),
            };
            (address.parse::<Ipv6Addr>().is_ok(), port)
        }
        None => match authority.split_once(':') {
            Some((host, port)) => (is_host(host), Some(port)),
            None => (is_host(authority), None),
        },
    };
    if !host_is_valid {
        return Err(HOST);
    }
    // Digits only, without a sign or a leading zero.
    let port_is_valid = |port: &str| {
        !port.starts_with('0')
            && port.bytes().all(|byte| byte.is_ascii_digit())
            && port.parse::<u16>().is_ok()
    };
    if port.is_some_and(|port| !port_is_valid(port)) {
        return Err(PORT);
    }
    Ok(())
}

/// An IPv4 address or a DNS name. A name never ends in an all-digit label, so such a
/// host must be an IPv4 address.
fn is_host(host: &str) -> bool {
    let last = host.rsplit('.').next().unwrap_or_default();
    if !last.is_empty() && last.bytes().all(|byte| byte.is_ascii_digit()) {
        return host.parse::<Ipv4Addr>().is_ok();
    }
    host.len() <= 253
        && host.split('.').all(|label| {
            (1..=63).contains(&label.len())
                && label
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
                && !label.starts_with('-')
                && !label.ends_with('-')
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn parse(vars: &[(&str, &str)]) -> Result<Config, ConfigError> {
        let vars: HashMap<String, String> = vars
            .iter()
            .map(|(name, value)| ((*name).to_owned(), (*value).to_owned()))
            .collect();
        Config::from_lookup(|name| vars.get(name).cloned())
    }

    #[test]
    fn defaults_match_the_documented_values() {
        let config = parse(&[]).unwrap();
        assert_eq!(config.bind, "0.0.0.0:7400".parse().unwrap());
        assert!(config.metrics_bind.is_none());
        assert!(config.token.is_none());
        assert_eq!(config.max_room_peers, 16);
        assert_eq!(config.msgs_per_sec, 50);
        assert_eq!(config.bytes_per_sec, 2_097_152);
        assert_eq!(config.max_conns_per_ip, 32);
        assert_eq!(config.max_conns, 10_000);
        assert_eq!(config.egress_bytes_per_sec, 52_428_800);
        assert_eq!(config.idle_timeout, Duration::from_secs(60));
        assert_eq!(config.max_rooms, 10_000);
        assert_eq!(config.ice_requests_per_minute, 10);
        assert_eq!(config.ice_ticketless_per_minute, 1);
        assert_eq!(config.ice_total_per_minute, 300);
        assert!(config.stun_urls.is_empty());
        assert!(config.turn.is_none());
        assert_eq!(config.turn_ttl_seconds, 43_200);
        assert!(config.trust_proxy.is_empty());
    }

    #[test]
    fn reads_every_variable() {
        let config = parse(&[
            ("RELAY_BIND", "127.0.0.1:9000"),
            ("RELAY_METRICS_BIND", "[::1]:9001"),
            ("RELAY_TOKEN", "abcdefghijklmnop"),
            ("RELAY_MAX_ROOM_PEERS", "4"),
            ("RELAY_MSGS_PER_SEC", "5"),
            ("RELAY_BYTES_PER_SEC", "131072"),
            ("RELAY_MAX_CONNS_PER_IP", "3"),
            ("RELAY_MAX_CONNS", "300"),
            ("RELAY_EGRESS_BYTES_PER_SEC", "1048576"),
            ("RELAY_IDLE_SECS", "30"),
            ("RELAY_MAX_ROOMS", "7"),
            ("ICE_REQUESTS_PER_MINUTE", "2"),
            ("ICE_TICKETLESS_PER_MINUTE", "0"),
            ("ICE_TOTAL_PER_MINUTE", "60"),
            (
                "STUN_URLS",
                "stun:stun.example.com:3478, stuns:stun.example.com:5349",
            ),
            ("TURN_URLS", "turn:turn.example.com:3478?transport=udp"),
            ("TURN_SECRET", "0123456789abcdef"),
            ("TURN_TTL_SECONDS", "600"),
            ("TRUST_PROXY", "127.0.0.1, 172.16.0.0/12,::1"),
        ])
        .unwrap();
        assert_eq!(config.bind, "127.0.0.1:9000".parse().unwrap());
        assert_eq!(config.metrics_bind, Some("[::1]:9001".parse().unwrap()));
        assert_eq!(config.token.as_deref(), Some("abcdefghijklmnop"));
        assert_eq!(config.max_room_peers, 4);
        assert_eq!(config.msgs_per_sec, 5);
        assert_eq!(config.bytes_per_sec, 131_072);
        assert_eq!(config.max_conns_per_ip, 3);
        assert_eq!(config.max_conns, 300);
        assert_eq!(config.egress_bytes_per_sec, 1_048_576);
        assert_eq!(config.idle_timeout, Duration::from_secs(30));
        assert_eq!(config.max_rooms, 7);
        assert_eq!(config.ice_requests_per_minute, 2);
        assert_eq!(config.ice_ticketless_per_minute, 0);
        assert_eq!(config.ice_total_per_minute, 60);
        assert_eq!(
            config.stun_urls,
            ["stun:stun.example.com:3478", "stuns:stun.example.com:5349"]
        );
        let turn = config.turn.unwrap();
        assert_eq!(turn.urls, ["turn:turn.example.com:3478?transport=udp"]);
        assert_eq!(turn.secret, "0123456789abcdef");
        assert_eq!(config.turn_ttl_seconds, 600);
        assert_eq!(config.trust_proxy.len(), 3);
    }

    #[test]
    fn empty_values_count_as_unset() {
        let config = parse(&[
            ("RELAY_TOKEN", ""),
            ("TURN_SECRET", " "),
            ("TURN_URLS", ""),
            ("RELAY_METRICS_BIND", " "),
        ])
        .unwrap();
        assert!(config.token.is_none());
        assert!(config.metrics_bind.is_none());
        assert!(config.turn.is_none());
    }

    fn turn_secret_cases() -> Vec<(String, bool)> {
        let valid = [
            "0123456789abcdef".to_owned(),
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=-._~".to_owned(),
            "=0123456789abcdef".to_owned(),
            "a".repeat(256),
        ];
        let invalid = [
            "too-short".to_owned(),
            "a".repeat(257),
            "\"0123456789abcdef".to_owned(),
            "0123456789abcdef\"".to_owned(),
            "0123456789abcdef;".to_owned(),
            "0123456789abcdef\nno-auth".to_owned(),
            "0123456789abcdef\rno-auth".to_owned(),
            "0123456789abcdef\\".to_owned(),
            "0123456789abcdef${TURN_REALM}".to_owned(),
            " 0123456789abcdef".to_owned(),
            "0123456789abcdef ".to_owned(),
            "01234567 89abcdef".to_owned(),
            "é".repeat(16),
        ];
        valid
            .into_iter()
            .map(|secret| (secret, true))
            .chain(invalid.into_iter().map(|secret| (secret, false)))
            .collect()
    }

    #[test]
    fn turn_secrets_are_validated_without_echoing_them() {
        for (secret, valid) in turn_secret_cases() {
            let result = parse(&[
                ("TURN_URLS", "turn:turn.example.com"),
                ("TURN_SECRET", &secret),
            ]);
            if valid {
                assert_eq!(result.unwrap().turn.unwrap().secret, secret);
            } else {
                let error = result.err().expect("invalid TURN secret is rejected");
                assert_eq!(error.variable(), "TURN_SECRET");
                assert!(!error.to_string().contains(&secret));
            }
        }
    }

    #[cfg(unix)]
    #[test]
    fn coturn_startup_uses_the_same_turn_secret_rules() {
        // Run the actual Compose validation, stopping before it writes configuration.
        let compose = include_str!("../docker-compose.yml");
        let (_, command) = compose.split_once("      - |\n").unwrap();
        let (validation, _) = command.split_once("        umask 077\n").unwrap();
        let validation = validation.replace("$$", "$");
        for (secret, valid) in turn_secret_cases() {
            let output = std::process::Command::new("bash")
                .args(["-ec", &validation])
                .env("TURN_SECRET", &secret)
                .output()
                .unwrap();
            assert_eq!(output.status.code(), Some(if valid { 0 } else { 2 }));
            assert!(output.stdout.is_empty());
            let error = String::from_utf8(output.stderr).unwrap();
            if valid {
                assert!(error.is_empty());
            } else {
                assert!(error.contains("TURN_SECRET"));
                assert!(!error.contains(&secret));
            }
        }
    }

    #[test]
    fn rejects_invalid_values_without_echoing_them() {
        let cases: &[(&[(&str, &str)], &str)] = &[
            (&[("RELAY_BIND", "nowhere")], "RELAY_BIND"),
            (&[("RELAY_METRICS_BIND", "nowhere")], "RELAY_METRICS_BIND"),
            (&[("RELAY_TOKEN", "short")], "RELAY_TOKEN"),
            (&[("RELAY_TOKEN", "has spaces in the token")], "RELAY_TOKEN"),
            (&[("RELAY_MAX_ROOM_PEERS", "1")], "RELAY_MAX_ROOM_PEERS"),
            (&[("RELAY_MSGS_PER_SEC", "0")], "RELAY_MSGS_PER_SEC"),
            // Below one message of the largest size.
            (&[("RELAY_BYTES_PER_SEC", "65535")], "RELAY_BYTES_PER_SEC"),
            (
                &[("RELAY_MAX_CONNS_PER_IP", "-1")],
                "RELAY_MAX_CONNS_PER_IP",
            ),
            (&[("RELAY_MAX_CONNS", "0")], "RELAY_MAX_CONNS"),
            // Below one message of the largest size.
            (
                &[("RELAY_EGRESS_BYTES_PER_SEC", "65535")],
                "RELAY_EGRESS_BYTES_PER_SEC",
            ),
            (&[("RELAY_IDLE_SECS", "0")], "RELAY_IDLE_SECS"),
            (&[("RELAY_MAX_ROOMS", "0")], "RELAY_MAX_ROOMS"),
            (
                &[("ICE_REQUESTS_PER_MINUTE", "0")],
                "ICE_REQUESTS_PER_MINUTE",
            ),
            (
                &[("ICE_TICKETLESS_PER_MINUTE", "-1")],
                "ICE_TICKETLESS_PER_MINUTE",
            ),
            // Without a ticket, no request could take the only credential.
            (&[("ICE_TOTAL_PER_MINUTE", "1")], "ICE_TOTAL_PER_MINUTE"),
            (&[("STUN_URLS", "https://stun.example.com")], "STUN_URLS"),
            (
                &[
                    ("TURN_URLS", "stun:turn.example.com"),
                    ("TURN_SECRET", "0123456789abcdef"),
                ],
                "TURN_URLS",
            ),
            (&[("TURN_URLS", "turn:turn.example.com")], "TURN_SECRET"),
            (&[("TURN_SECRET", "0123456789abcdef")], "TURN_URLS"),
            (
                &[
                    ("TURN_URLS", "turn:turn.example.com"),
                    ("TURN_SECRET", "too-short"),
                ],
                "TURN_SECRET",
            ),
            (&[("TURN_TTL_SECONDS", "10")], "TURN_TTL_SECONDS"),
            (&[("TRUST_PROXY", "proxy.example.com")], "TRUST_PROXY"),
            (&[("TRUST_PROXY", "10.0.0.0/33")], "TRUST_PROXY"),
        ];
        for (vars, variable) in cases {
            let error = parse(vars)
                .err()
                .unwrap_or_else(|| panic!("{vars:?} must be rejected"));
            assert_eq!(error.variable(), *variable, "{vars:?}");
            // Short or numeric values can appear in the allowed range by coincidence.
            let echoable = vars
                .iter()
                .map(|(_, value)| *value)
                .filter(|value| value.len() >= 5 && !value.bytes().all(|b| b.is_ascii_digit()));
            for value in echoable {
                assert!(!error.to_string().contains(value), "{error} echoes {value}");
            }
        }
    }

    #[test]
    fn accepts_ice_urls_that_webrtc_reads() {
        let stun = [
            "stun:stun.example.com",
            "stun:stun.example.com:3478",
            "stuns:stun.example.com:5349",
            "stun:Stun-1.Example.com:65535",
            "stun:203.0.113.1:3478",
            "stun:[2001:db8::1]",
            "stun:[2001:db8::1]:3478",
            "stun:[::ffff:203.0.113.1]:1",
        ];
        let turn = [
            "turn:turn.example.com",
            "turn:turn.example.com:3478?transport=udp",
            "turn:turn.example.com?transport=tcp",
            "turn:[2001:db8::1]:3478?transport=udp",
            "turns:turn.example.com",
            "turns:turn.example.com:5349?transport=tcp",
        ];
        let config = parse(&[
            ("STUN_URLS", &stun.join(",")),
            ("TURN_URLS", &turn.join(",")),
            ("TURN_SECRET", "0123456789abcdef"),
        ])
        .unwrap();
        assert_eq!(config.stun_urls, stun);
        assert_eq!(config.turn.unwrap().urls, turn);
    }

    #[test]
    fn rejects_ice_urls_that_webrtc_refuses_without_echoing_them() {
        let long_label = format!("stun:{}.example.com", "a".repeat(64));
        let long_name = format!("stun:{}example.com", "abcdefghi.".repeat(25));
        let cases = [
            ("STUN_URLS", "stun.example.com", "scheme"),
            ("STUN_URLS", "STUN:stun.example.com", "scheme"),
            ("STUN_URLS", "turn:stun.example.com", "scheme"),
            ("STUN_URLS", "https://stun.example.com", "scheme"),
            ("STUN_URLS", "stun://stun.example.com", "host"),
            ("STUN_URLS", "stun:", "host"),
            ("STUN_URLS", "stun::3478", "host"),
            ("STUN_URLS", "stun:user@stun.example.com", "host"),
            ("STUN_URLS", "stun:stun.example.com/path", "host"),
            ("STUN_URLS", "stun:stun.example.com#part", "host"),
            ("STUN_URLS", "stun:stun_1.example.com", "host"),
            ("STUN_URLS", "stun:-stun.example.com", "host"),
            ("STUN_URLS", "stun:stun-.example.com", "host"),
            ("STUN_URLS", "stun:stun..example.com", "host"),
            ("STUN_URLS", "stun:stun.example.com.", "host"),
            ("STUN_URLS", "stun:stun example.com", "host"),
            ("STUN_URLS", &long_label, "host"),
            ("STUN_URLS", &long_name, "host"),
            ("STUN_URLS", "stun:999.0.113.1", "host"),
            ("STUN_URLS", "stun:203.0.113", "host"),
            ("STUN_URLS", "stun:2001:db8::1", "host"),
            ("STUN_URLS", "stun:[2001:db8::1", "host"),
            ("STUN_URLS", "stun:[2001:db8::1]3478", "host"),
            ("STUN_URLS", "stun:[fe80::1%25eth0]", "host"),
            ("STUN_URLS", "stun:[stun.example.com]", "host"),
            ("STUN_URLS", "stun:stun.example.com:", "port"),
            ("STUN_URLS", "stun:stun.example.com:0", "port"),
            ("STUN_URLS", "stun:stun.example.com:03478", "port"),
            ("STUN_URLS", "stun:stun.example.com:65536", "port"),
            ("STUN_URLS", "stun:stun.example.com:+3478", "port"),
            ("STUN_URLS", "stun:stun.example.com:3478:1", "port"),
            ("STUN_URLS", "stun:[2001:db8::1]:", "port"),
            ("STUN_URLS", "stun:stun.example.com?transport=udp", "query"),
            ("STUN_URLS", "stuns:stun.example.com?transport=tcp", "query"),
            ("TURN_URLS", "stun:turn.example.com", "scheme"),
            ("TURN_URLS", "turn:turn.example.com?", "query"),
            ("TURN_URLS", "turn:turn.example.com?transport=UDP", "query"),
            ("TURN_URLS", "turn:turn.example.com?transport=sctp", "query"),
            ("TURN_URLS", "turn:turn.example.com?Transport=udp", "query"),
            (
                "TURN_URLS",
                "turn:turn.example.com?transport=udp&transport=tcp",
                "query",
            ),
            (
                "TURN_URLS",
                "turn:turn.example.com?transport=udp#x",
                "query",
            ),
            ("TURN_URLS", "turns:turn.example.com?transport=udp", "query"),
            (
                "TURN_URLS",
                "turn:turn.example.com:99999?transport=udp",
                "port",
            ),
        ];
        for (variable, url, reason) in cases {
            // The bad URL comes second, after one that is fine.
            let scheme = if variable == "STUN_URLS" {
                "stun"
            } else {
                "turn"
            };
            let list = format!("{scheme}:ok.example.org, {url}");
            let mut vars = vec![(variable, list.as_str())];
            if variable == "TURN_URLS" {
                vars.push(("TURN_SECRET", "0123456789abcdef"));
            }
            let error = parse(&vars)
                .err()
                .unwrap_or_else(|| panic!("{url} must be rejected"));
            assert_eq!(error.variable(), variable, "{url}");
            let message = error.to_string();
            assert!(message.contains("URL 2 "), "{url}: {message}");
            assert!(message.contains(reason), "{url}: {message}");
            assert!(!message.contains("example"), "{message} echoes {url}");
        }
    }

    #[test]
    fn ip_ranges_match_addresses() {
        let net: IpNet = "172.16.0.0/12".parse().unwrap();
        assert!(net.contains("172.31.255.1".parse().unwrap()));
        assert!(net.contains("::ffff:172.20.0.1".parse().unwrap()));
        assert!(!net.contains("172.32.0.1".parse().unwrap()));
        assert!(!net.contains("::1".parse().unwrap()));

        let single: IpNet = "::1".parse().unwrap();
        assert!(single.contains("::1".parse().unwrap()));
        assert!(!single.contains("::2".parse().unwrap()));

        let all: IpNet = "0.0.0.0/0".parse().unwrap();
        assert!(all.contains("203.0.113.9".parse().unwrap()));
    }
}
