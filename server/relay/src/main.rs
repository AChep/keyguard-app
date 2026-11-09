//! `keyguard-relay`: reads its configuration from the environment and serves
//! until SIGINT or SIGTERM. An invalid configuration exits with code 2 before
//! listening.

use std::io::IsTerminal;
use std::process::ExitCode;
use std::time::Duration;

use keyguard_relay::Config;
use tokio::net::TcpListener;
use tracing_subscriber::EnvFilter;
use tracing_subscriber::filter::LevelFilter;

/// How long storage work that outlived `serve` may hold up the exit, such as a write
/// to a disk that hangs.
const EXIT_TIMEOUT: Duration = Duration::from_secs(1);

fn main() -> ExitCode {
    let runtime = match tokio::runtime::Runtime::new() {
        Ok(runtime) => runtime,
        Err(error) => {
            eprintln!("keyguard-relay: cannot start the runtime: {error}");
            return ExitCode::FAILURE;
        }
    };
    let code = runtime.block_on(run());
    runtime.shutdown_timeout(EXIT_TIMEOUT);
    code
}

async fn run() -> ExitCode {
    let directives = std::env::var("RUST_LOG").unwrap_or_default();
    let filter = match EnvFilter::builder()
        .with_default_directive(LevelFilter::INFO.into())
        .parse(directives.trim())
    {
        Ok(filter) => filter,
        Err(error) => {
            eprintln!("keyguard-relay: RUST_LOG is invalid: {error}");
            return ExitCode::from(2);
        }
    };
    tracing_subscriber::fmt()
        .with_env_filter(filter)
        .with_ansi(std::io::stdout().is_terminal())
        .init();

    let config = match Config::from_env() {
        Ok(config) => config,
        Err(error) => {
            eprintln!("keyguard-relay: {error}");
            return ExitCode::from(2);
        }
    };
    let listener = match TcpListener::bind(config.bind).await {
        Ok(listener) => listener,
        Err(error) => {
            eprintln!("keyguard-relay: cannot listen on {}: {error}", config.bind);
            return ExitCode::FAILURE;
        }
    };
    let metrics_listener = if let Some(bind) = config.metrics_bind {
        match TcpListener::bind(bind).await {
            Ok(listener) => {
                tracing::info!(%bind, "metrics listening; restrict access to trusted scrapers");
                Some(listener)
            }
            Err(error) => {
                eprintln!("keyguard-relay: cannot listen for metrics on {bind}: {error}");
                return ExitCode::FAILURE;
            }
        }
    } else {
        None
    };
    tracing::info!(
        bind = %config.bind,
        private = config.token.is_some(),
        stun_urls = config.stun_urls.len(),
        turn = config.turn.is_some(),
        trusted_proxies = config.trust_proxy.len(),
        max_conns = config.max_conns,
        max_conns_per_ip = config.max_conns_per_ip,
        ws_requests_per_sec = config.ws_requests_per_sec,
        ws_total_per_sec = config.ws_total_per_sec,
        ice_requests_per_sec = config.ice_requests_per_sec,
        ice_total_per_sec = config.ice_total_per_sec,
        max_rooms = config.max_rooms,
        max_rooms_per_conn = config.max_rooms_per_conn,
        max_rooms_per_ip = config.max_rooms_per_ip,
        egress_bytes_per_sec = config.egress_bytes_per_sec,
        ice_requests_per_minute = config.ice_requests_per_minute,
        ice_ticketless_per_minute = config.ice_ticketless_per_minute,
        ice_total_per_minute = config.ice_total_per_minute,
        mailbox = config.mailbox.is_some(),
        "keyguard-relay listening",
    );
    if let Some(mailbox) = &config.mailbox {
        tracing::info!(
            dir = %mailbox.dir.display(),
            max_bytes = mailbox.max_bytes,
            min_free_bytes = mailbox.min_free_bytes,
            max_items = mailbox.max_items,
            ttl_seconds = mailbox.ttl_seconds,
            bytes_per_ip = mailbox.bytes_per_ip,
            items_per_ip = mailbox.items_per_ip,
            requests_per_minute = mailbox.requests_per_minute,
            upload_bytes_per_minute = mailbox.upload_bytes_per_minute,
            "relay storage on",
        );
    }
    keyguard_relay::serve(listener, metrics_listener, config, shutdown_signal()).await;
    tracing::info!("keyguard-relay stopped");
    ExitCode::SUCCESS
}

async fn shutdown_signal() {
    let interrupt = async {
        if tokio::signal::ctrl_c().await.is_err() {
            std::future::pending::<()>().await;
        }
    };
    #[cfg(unix)]
    let terminate = async {
        match tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            Ok(mut signal) => {
                signal.recv().await;
            }
            Err(_) => std::future::pending().await,
        }
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();
    tokio::select! {
        () = interrupt => {}
        () = terminate => {}
    }
}
