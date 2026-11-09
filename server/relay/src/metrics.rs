//! Fixed, aggregate Prometheus metrics. Labels never contain client-controlled values.

use std::fmt::{Display, Write};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

use axum::Router;
use axum::extract::State;
use axum::extract::ws::Message;
use axum::http::header::{CACHE_CONTROL, CONTENT_TYPE};
use axum::routing::get;

use crate::AppState;
use crate::limits::{ConnectionRefused, RequestRefused};
use crate::mailbox::{Mailbox, OPS, RESULTS};
use crate::protocol::ErrorCode;
use crate::store::{Eviction, Kind};

/// A process-lifetime counter, independent of the limiter's expiring buckets.
#[derive(Default)]
pub(crate) struct Counter(AtomicU64);

impl Counter {
    pub(crate) fn inc(&self) {
        self.add(1);
    }

    pub(crate) fn add(&self, value: u64) {
        self.0.fetch_add(value, Ordering::Relaxed);
    }

    fn value(&self) -> u64 {
        self.0.load(Ordering::Relaxed)
    }
}

/// The endpoints HTTP admission meters. Fixed values label metrics.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Endpoint {
    WebSocket = 0,
    Ice = 1,
    Mailbox = 2,
}

impl Endpoint {
    const LABELS: [&str; 3] = ["websocket", "ice", "mailbox"];
}

#[derive(Default)]
struct Traffic {
    frames: [Counter; 2],
    bytes: [Counter; 2],
}

/// Size and format captured before moving a message into the socket writer.
pub(crate) struct MessageSize {
    format: usize,
    bytes: usize,
}

impl MessageSize {
    pub(crate) fn of(message: &Message) -> Option<Self> {
        match message {
            Message::Text(text) => Some(Self {
                format: 0,
                bytes: text.len(),
            }),
            Message::Binary(data) => Some(Self {
                format: 1,
                bytes: data.len(),
            }),
            _ => None,
        }
    }
}

impl Traffic {
    fn record(&self, size: Option<MessageSize>) {
        if let Some(size) = size {
            self.frames[size.format].inc();
            self.bytes[size.format].add(size.bytes as u64);
        }
    }
}

#[derive(Default)]
pub(crate) struct Metrics {
    received: Traffic,
    sent: Traffic,
    control_received: Traffic,
    pub(crate) heartbeat_replies: Counter,
    pub(crate) request_rate_limits:
        [[Counter; RequestRefused::LABELS.len()]; Endpoint::LABELS.len()],
    pub(crate) errors: [Counter; ErrorCode::FRAME.len()],
    pub(crate) http_refusals: [Counter; ConnectionRefused::LABELS.len()],
    pub(crate) websocket_refusals: [Counter; ConnectionRefused::LABELS.len()],
    pub(crate) auth_rejections: [Counter; 2],
    pub(crate) connection_rate_limits: Counter,
    pub(crate) egress_waits: Counter,
    pub(crate) room_limits: Counter,
    pub(crate) address_room_limits: Counter,
    pub(crate) network_room_limits: Counter,
    pub(crate) idle_closes: Counter,
    pub(crate) slow_consumer_closes: Counter,
    pub(crate) shutdown_closes: Counter,
    pub(crate) send_errors: Counter,
    pub(crate) send_timeouts: Counter,
    pub(crate) turn_issued: [Counter; 2],
    pub(crate) turn_limited: [Counter; 2],
}

impl Metrics {
    pub(crate) fn received(&self, message: &Message) {
        self.received.record(MessageSize::of(message));
        let control = match message {
            Message::Ping(data) => Some((0, data.len())),
            Message::Pong(data) => Some((1, data.len())),
            _ => None,
        };
        if let Some((format, bytes)) = control {
            self.control_received
                .record(Some(MessageSize { format, bytes }));
        }
    }

    pub(crate) fn sent(&self, size: Option<MessageSize>) {
        self.sent.record(size);
    }

    fn render(&self, state: &AppState) -> String {
        let mut out = String::with_capacity(8192);
        let (rooms, peers) = state.rooms.counts();
        for (name, help, value) in [
            ("rooms", "Current nonempty rooms.", rooms),
            (
                "joined_peers",
                "Current room memberships: a connection in two rooms counts twice.",
                peers,
            ),
            (
                "websocket_connections",
                "Current WebSockets, including pending upgrades.",
                state.connections.len(),
            ),
            (
                "http_connections",
                "Current public HTTP connections, excluding upgraded WebSockets.",
                state.http_connections.len(),
            ),
        ] {
            single(&mut out, name, help, "gauge", value);
        }
        single(
            &mut out,
            "uptime_seconds",
            "Seconds since relay startup.",
            "gauge",
            state.started.elapsed().as_secs_f64(),
        );
        family(&mut out, "build_info", "Relay package version.", "gauge");
        _ = writeln!(
            out,
            "keyguard_relay_build_info{{version=\"{}\"}} 1",
            env!("CARGO_PKG_VERSION")
        );

        for (direction, traffic) in [("received", &self.received), ("sent", &self.sent)] {
            for (unit, counters) in [("frames", &traffic.frames), ("bytes", &traffic.bytes)] {
                counters_by(
                    &mut out,
                    &format!("websocket_{direction}_{unit}_total"),
                    "Text and binary WebSocket messages; sent counts successful writes only. Bytes exclude WebSocket and network headers.",
                    "format",
                    ["text", "binary"].into_iter().zip(counters),
                );
            }
        }
        for (unit, counters) in [
            ("frames", &self.control_received.frames),
            ("bytes", &self.control_received.bytes),
        ] {
            counters_by(
                &mut out,
                &format!("websocket_received_control_{unit}_total"),
                "WebSocket ping and pong receipts, including exempt heartbeat replies; retries do not count again.",
                "type",
                ["ping", "pong"].into_iter().zip(counters),
            );
        }
        single(
            &mut out,
            "websocket_heartbeat_replies_total",
            "Matching heartbeat replies exempted from the connection budget, at most one per heartbeat.",
            "counter",
            self.heartbeat_replies.value(),
        );
        family(
            &mut out,
            "http_rate_limits_total",
            "Endpoint requests refused before authentication, extraction or body reads.",
            "counter",
        );
        for (endpoint, counters) in Endpoint::LABELS.into_iter().zip(&self.request_rate_limits) {
            for (reason, counter) in RequestRefused::LABELS.into_iter().zip(counters) {
                sample(
                    &mut out,
                    "http_rate_limits_total",
                    &format!("endpoint=\"{endpoint}\",reason=\"{reason}\""),
                    counter,
                );
            }
        }
        counters_by(
            &mut out,
            "protocol_errors_total",
            "WebSocket protocol errors detected, including errors whose reply cannot be sent.",
            "reason",
            ErrorCode::FRAME.map(|code| (code.as_str(), &self.errors[code as usize])),
        );
        family(
            &mut out,
            "connection_refusals_total",
            "Connections refused before service or WebSocket upgrade.",
            "counter",
        );
        for (transport, counters) in [
            ("http", &self.http_refusals),
            ("websocket", &self.websocket_refusals),
        ] {
            for (reason, counter) in ConnectionRefused::LABELS.into_iter().zip(counters) {
                sample(
                    &mut out,
                    "connection_refusals_total",
                    &format!("transport=\"{transport}\",reason=\"{reason}\""),
                    counter,
                );
            }
        }
        counters_by(
            &mut out,
            "auth_rejections_total",
            "Rejected client token checks.",
            "reason",
            ["unauthorized", "locked"]
                .into_iter()
                .zip(&self.auth_rejections),
        );
        counters_by(
            &mut out,
            "rate_limits_total",
            "Client frames first delayed by the connection or egress budget, and joins refused by the room cap of the relay, an address or an IPv6 /48. Retries do not count again.",
            "reason",
            [
                ("connection", &self.connection_rate_limits),
                ("egress", &self.egress_waits),
                ("rooms", &self.room_limits),
                ("address_rooms", &self.address_room_limits),
                ("network_rooms", &self.network_room_limits),
            ],
        );
        counters_by(
            &mut out,
            "websocket_closes_total",
            "Server initiated closes for idle clients, slow consumers or shutdown.",
            "reason",
            [
                ("idle", &self.idle_closes),
                ("slow_consumer", &self.slow_consumer_closes),
                ("shutdown", &self.shutdown_closes),
            ],
        );
        counters_by(
            &mut out,
            "websocket_send_failures_total",
            "Failed or timed out WebSocket send attempts, including control frames.",
            "reason",
            [
                ("error", &self.send_errors),
                ("timeout", &self.send_timeouts),
            ],
        );
        family(
            &mut out,
            "turn_credentials_total",
            "TURN credentials minted or withheld by allowance. Issuance does not imply TURN use or response delivery.",
            "counter",
        );
        for (result, counters) in [
            ("issued", &self.turn_issued),
            ("limited", &self.turn_limited),
        ] {
            for (ticketed, counter) in ["false", "true"].into_iter().zip(counters) {
                sample(
                    &mut out,
                    "turn_credentials_total",
                    &format!("result=\"{result}\",ticketed=\"{ticketed}\""),
                    counter,
                );
            }
        }
        if let Some(mailbox) = &state.mailbox {
            render_mailbox(&mut out, mailbox);
        }
        out
    }
}

/// The families of relay storage, present only when it is configured.
fn render_mailbox(out: &mut String, mailbox: &Mailbox) {
    let stats = mailbox.store.stats();
    for (name, help, values) in [
        (
            "mailbox_items",
            "Stored mailbox items.",
            stats.per_kind.map(|usage| usage.items as u64),
        ),
        (
            "mailbox_bytes",
            "Stored mailbox bytes, without file headers.",
            stats.per_kind.map(|usage| usage.bytes),
        ),
    ] {
        family(out, name, help, "gauge");
        for kind in Kind::ALL {
            _ = writeln!(
                out,
                "keyguard_relay_{name}{{kind=\"{}\"}} {}",
                kind.as_str(),
                values[kind as usize]
            );
        }
    }
    for (name, help, value) in [
        (
            "mailbox_limit_bytes",
            "The effective cap of stored bytes: RELAY_MAILBOX_MAX_BYTES, lowered while free disk space is short.",
            stats.limit_bytes,
        ),
        (
            "mailbox_available",
            "1 while the data directory works; 0 while mailbox requests get 503.",
            u64::from(stats.available),
        ),
    ] {
        single(out, name, help, "gauge", value);
    }
    family(
        out,
        "mailbox_requests_total",
        "Mailbox requests past admission, by operation and result.",
        "counter",
    );
    for (op, counters) in OPS.into_iter().zip(&mailbox.results) {
        for (result, counter) in RESULTS.into_iter().zip(counters) {
            sample(
                out,
                "mailbox_requests_total",
                &format!("op=\"{op}\",result=\"{result}\""),
                counter,
            );
        }
    }
    counters_by(
        out,
        "mailbox_evictions_total",
        "Items the relay deleted before a client did: expired, or trimmed for disk space or the item cap.",
        "reason",
        Eviction::LABELS.into_iter().zip(&mailbox.store.evictions),
    );
    single(
        out,
        "mailbox_write_failures_total",
        "Uploads that failed to create, write, sync or rename their file.",
        "counter",
        mailbox.store.write_failures.value(),
    );
}

fn family(out: &mut String, name: &str, help: &str, kind: &str) {
    _ = writeln!(
        out,
        "# HELP keyguard_relay_{name} {help}\n# TYPE keyguard_relay_{name} {kind}"
    );
}

fn sample(out: &mut String, name: &str, labels: &str, counter: &Counter) {
    _ = writeln!(out, "keyguard_relay_{name}{{{labels}}} {}", counter.value());
}

/// A family of one sample without labels.
fn single(out: &mut String, name: &str, help: &str, kind: &str, value: impl Display) {
    family(out, name, help, kind);
    _ = writeln!(out, "keyguard_relay_{name} {value}");
}

/// A counter family with the one label `key`.
fn counters_by<'a>(
    out: &mut String,
    name: &str,
    help: &str,
    key: &str,
    counters: impl IntoIterator<Item = (&'a str, &'a Counter)>,
) {
    family(out, name, help, "counter");
    for (label, counter) in counters {
        sample(out, name, &format!("{key}=\"{label}\""), counter);
    }
}

/// Only installed on the separately bound metrics listener, never on the public router.
pub(crate) fn router(state: Arc<AppState>) -> Router {
    Router::new()
        .route("/metrics", get(scrape))
        .with_state(state)
}

async fn scrape(State(state): State<Arc<AppState>>) -> impl axum::response::IntoResponse {
    (
        [
            (CONTENT_TYPE, "text/plain; version=0.0.4; charset=utf-8"),
            (CACHE_CONTROL, "no-store"),
        ],
        state.metrics.render(&state),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn traffic_counts_only_text_and_binary_message_bytes() {
        let traffic = Traffic::default();
        traffic.record(MessageSize::of(&Message::Text("é".into())));
        traffic.record(MessageSize::of(&Message::Binary(vec![1, 2, 3].into())));
        traffic.record(MessageSize::of(&Message::Ping(vec![4].into())));
        assert_eq!(traffic.frames[0].value(), 1);
        assert_eq!(traffic.bytes[0].value(), 2);
        assert_eq!(traffic.frames[1].value(), 1);
        assert_eq!(traffic.bytes[1].value(), 3);
    }
}
