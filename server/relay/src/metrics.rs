//! Fixed, aggregate Prometheus metrics. Labels never contain client-controlled values.

use std::fmt::Write;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

use axum::Router;
use axum::extract::State;
use axum::extract::ws::Message;
use axum::http::header::{CACHE_CONTROL, CONTENT_TYPE};
use axum::routing::get;

use crate::AppState;
use crate::protocol::ErrorCode;

/// A process-lifetime counter, independent of the limiter's expiring buckets.
#[derive(Default)]
pub(crate) struct Counter(AtomicU64);

impl Counter {
    pub(crate) fn inc(&self) {
        self.add(1);
    }

    fn add(&self, value: u64) {
        self.0.fetch_add(value, Ordering::Relaxed);
    }

    fn value(&self) -> u64 {
        self.0.load(Ordering::Relaxed)
    }
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
    pub(crate) errors: [Counter; ErrorCode::ALL.len()],
    pub(crate) http_refusals: [Counter; 2],
    pub(crate) websocket_refusals: [Counter; 2],
    pub(crate) auth_rejections: [Counter; 2],
    pub(crate) connection_rate_limits: Counter,
    pub(crate) egress_waits: Counter,
    pub(crate) room_limits: Counter,
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
    }

    pub(crate) fn sent(&self, size: Option<MessageSize>) {
        self.sent.record(size);
    }

    fn render(&self, state: &AppState) -> String {
        let mut out = String::with_capacity(8192);
        let (rooms, peers) = state.rooms.counts();
        for (name, help, value) in [
            ("rooms", "Current nonempty rooms.", rooms),
            ("joined_peers", "Current peers in rooms.", peers),
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
            family(&mut out, name, help, "gauge");
            _ = writeln!(out, "keyguard_relay_{name} {value}");
        }
        family(
            &mut out,
            "uptime_seconds",
            "Seconds since relay startup.",
            "gauge",
        );
        _ = writeln!(
            out,
            "keyguard_relay_uptime_seconds {}",
            state.started.elapsed().as_secs_f64()
        );
        family(&mut out, "build_info", "Relay package version.", "gauge");
        _ = writeln!(
            out,
            "keyguard_relay_build_info{{version=\"{}\"}} 1",
            env!("CARGO_PKG_VERSION")
        );

        for (direction, traffic) in [("received", &self.received), ("sent", &self.sent)] {
            for (unit, counters) in [("frames", &traffic.frames), ("bytes", &traffic.bytes)] {
                let name = format!("websocket_{direction}_{unit}_total");
                family(
                    &mut out,
                    &name,
                    "Text and binary WebSocket messages; sent counts successful writes only. Bytes exclude WebSocket and network headers.",
                    "counter",
                );
                for (format, counter) in ["text", "binary"].into_iter().zip(counters) {
                    sample(&mut out, &name, &format!("format=\"{format}\""), counter);
                }
            }
        }
        family(
            &mut out,
            "protocol_errors_total",
            "WebSocket protocol errors detected, including errors whose reply cannot be sent.",
            "counter",
        );
        for code in ErrorCode::ALL {
            sample(
                &mut out,
                "protocol_errors_total",
                &format!("reason=\"{}\"", code.as_str()),
                &self.errors[code as usize],
            );
        }
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
            for (reason, counter) in ["per_address", "total"].into_iter().zip(counters) {
                sample(
                    &mut out,
                    "connection_refusals_total",
                    &format!("transport=\"{transport}\",reason=\"{reason}\""),
                    counter,
                );
            }
        }
        family(
            &mut out,
            "auth_rejections_total",
            "Rejected client token checks.",
            "counter",
        );
        for (reason, counter) in ["unauthorized", "locked"]
            .into_iter()
            .zip(&self.auth_rejections)
        {
            sample(
                &mut out,
                "auth_rejections_total",
                &format!("reason=\"{reason}\""),
                counter,
            );
        }
        family(
            &mut out,
            "rate_limits_total",
            "Client frames first delayed by the connection or egress budget, and refused room creations. Retries do not count again.",
            "counter",
        );
        for (reason, counter) in [
            ("connection", &self.connection_rate_limits),
            ("egress", &self.egress_waits),
            ("rooms", &self.room_limits),
        ] {
            sample(
                &mut out,
                "rate_limits_total",
                &format!("reason=\"{reason}\""),
                counter,
            );
        }
        family(
            &mut out,
            "websocket_closes_total",
            "Server initiated closes for idle clients, slow consumers or shutdown.",
            "counter",
        );
        for (reason, counter) in [
            ("idle", &self.idle_closes),
            ("slow_consumer", &self.slow_consumer_closes),
            ("shutdown", &self.shutdown_closes),
        ] {
            sample(
                &mut out,
                "websocket_closes_total",
                &format!("reason=\"{reason}\""),
                counter,
            );
        }
        family(
            &mut out,
            "websocket_send_failures_total",
            "Failed or timed out WebSocket send attempts, including control frames.",
            "counter",
        );
        for (reason, counter) in [
            ("error", &self.send_errors),
            ("timeout", &self.send_timeouts),
        ] {
            sample(
                &mut out,
                "websocket_send_failures_total",
                &format!("reason=\"{reason}\""),
                counter,
            );
        }
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
        out
    }
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
