//! `keyguard-relay`: reads its configuration from the environment and serves
//! until SIGINT or SIGTERM. An invalid configuration exits with code 2 before
//! listening.

use std::io::IsTerminal;
use std::process::ExitCode;

use keyguard_relay::Config;
use tokio::net::TcpListener;
use tracing_subscriber::EnvFilter;
use tracing_subscriber::filter::LevelFilter;

#[tokio::main]
async fn main() -> ExitCode {
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
        max_rooms = config.max_rooms,
        egress_bytes_per_sec = config.egress_bytes_per_sec,
        ice_requests_per_minute = config.ice_requests_per_minute,
        ice_ticketless_per_minute = config.ice_ticketless_per_minute,
        ice_total_per_minute = config.ice_total_per_minute,
        "keyguard-relay listening",
    );
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
