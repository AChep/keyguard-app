//! keyguard-android-gpg-agent: gpg-agent for GnuPG in Termux, backed by the
//! Keyguard Android app.

mod daemon;
mod home;
mod transport;

use anyhow::{Context, Result};
use clap::Parser;
use daemon::run_blocking;
use home::{user_home, ManagedHome};
use keyguard_android_agent_bridge::bridge::{BridgeConfig, DEFAULT_CONNECT_TIMEOUT_MS};
use keyguard_android_agent_bridge::broadcast::{BridgeContract, LegacyAcknowledgement};
use keyguard_android_agent_bridge::process::{
    exit_with, fork_daemon, prefers_csh, render_export, shell_quote,
};
use keyguard_android_agent_bridge::socket_guard::{EnsureSocket, SocketGuard};
use keyguard_gpg_agent_assuan::gpgconf::{gpg_version, gpgconf_agent_socket_path};
use keyguard_gpg_agent_assuan::version::resolve_gnupg_version;
use keyguard_gpg_agent_assuan::AgentInfo;
use std::path::{Path, PathBuf};
use tracing::level_filters::LevelFilter;

const GNUPGHOME_ENV: &str = "GNUPGHOME";

const DEFAULT_COMPONENT: &str =
    "com.artemchep.keyguard/com.artemchep.keyguard.android.gpgagent.GpgAgentReceiver";

/// Must match `AgentBridge.GPG` in the Keyguard app.
pub(crate) const GPG_AGENT_CONTRACT: BridgeContract = BridgeContract {
    action: "com.artemchep.keyguard.action.RUN_ANDROID_GPG_AGENT",
    extra_protocol_version: "com.artemchep.keyguard.extra.GPG_AGENT_PROTOCOL_VERSION",
    extra_proxy_port: "com.artemchep.keyguard.extra.GPG_AGENT_PROXY_PORT",
    extra_session_id: "com.artemchep.keyguard.extra.GPG_AGENT_SESSION_ID",
    extra_session_secret: "com.artemchep.keyguard.extra.GPG_AGENT_SESSION_SECRET",
    result_prefix: "keyguard.gpg-agent.broadcast.v1:",
    // Unlike SSH, no Keyguard release handles the GPG agent without
    // acknowledging it, so a missing answer means no receiver.
    legacy_acknowledgement: LegacyAcknowledgement::Reject,
};

/// Starts the agent for the managed GnuPG home unless it is already running,
/// then prints the `GNUPGHOME` export for `eval`.
#[derive(Parser, Debug)]
#[command(name = "keyguard-android-gpg-agent", version, about)]
struct Args {
    /// GnuPG home to serve. Default: ~/.keyguard/gnupg.
    #[arg(long, value_name = "dir")]
    homedir: Option<PathBuf>,

    /// Print csh-style exports.
    #[arg(short = 'c', conflicts_with = "bash")]
    csh: bool,

    /// Print Bourne shell exports.
    #[arg(short = 's', conflicts_with = "csh")]
    bash: bool,

    /// Stay in the foreground.
    #[arg(short = 'D')]
    foreground: bool,

    /// Stay in the foreground and log to stderr.
    #[arg(short = 'd')]
    debug: bool,

    #[arg(long, default_value = DEFAULT_COMPONENT)]
    android_component: String,

    #[arg(long, default_value_t = DEFAULT_CONNECT_TIMEOUT_MS)]
    connect_timeout_ms: u64,
}

fn main() {
    exit_with(try_main())
}

fn try_main() -> Result<i32> {
    let args = Args::parse();
    let is_csh = prefers_csh(args.csh, args.bash);

    let user_home = user_home()?;
    let home = match args.homedir {
        Some(path) => ManagedHome::explicit(path),
        None => ManagedHome::default_for(&user_home),
    };
    home.prepare(&user_home)?;
    let socket_path = gpgconf_agent_socket_path(&home.path).with_context(|| {
        format!(
            "Failed to resolve the GnuPG agent socket of {}; install GnuPG with `pkg install gnupg`",
            home.path.display()
        )
    })?;

    let mut socket_guard = match SocketGuard::ensure(socket_path.clone())? {
        EnsureSocket::Existing(_) => {
            print_home_export(is_csh, &home.path);
            return Ok(0);
        }
        EnsureSocket::Start(socket_guard) => socket_guard,
    };
    let listener = socket_guard.bind_listener()?;
    let info = AgentInfo::new(
        socket_path.to_string_lossy().into_owned(),
        resolve_gnupg_version(gpg_version(&home.path)),
    );
    let config = BridgeConfig::new(args.android_component, args.connect_timeout_ms, args.debug);

    if args.foreground || args.debug {
        print_home_export(is_csh, &home.path);
        init_logging(args.debug);
        return run_blocking(listener, config, info);
    }

    // SAFETY: called once during startup, before any extra threads are created.
    match unsafe { fork_daemon() }? {
        None => run_blocking(listener, config, info),
        Some(_) => {
            socket_guard.disarm();
            print_home_export(is_csh, &home.path);
            Ok(0)
        }
    }
}

fn print_home_export(is_csh: bool, home: &Path) {
    println!(
        "{}",
        render_export(is_csh, GNUPGHOME_ENV, &shell_quote(&home.to_string_lossy()))
    );
}

fn init_logging(debug: bool) {
    let level = if debug {
        LevelFilter::DEBUG
    } else {
        LevelFilter::WARN
    };
    tracing_subscriber::fmt()
        .with_max_level(level)
        .with_writer(std::io::stderr)
        .init();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn args_default_android_component_targets_keyguard_release_package() {
        let args = Args::parse_from(["keyguard-android-gpg-agent"]);

        assert_eq!(
            args.android_component,
            "com.artemchep.keyguard/com.artemchep.keyguard.android.gpgagent.GpgAgentReceiver",
        );
    }

    #[test]
    fn contract_is_separate_from_the_ssh_agent() {
        assert!(GPG_AGENT_CONTRACT.action.ends_with("RUN_ANDROID_GPG_AGENT"));
        for extra in [
            GPG_AGENT_CONTRACT.extra_protocol_version,
            GPG_AGENT_CONTRACT.extra_proxy_port,
            GPG_AGENT_CONTRACT.extra_session_id,
            GPG_AGENT_CONTRACT.extra_session_secret,
        ] {
            assert!(extra.contains(".GPG_AGENT_"), "{extra}");
        }
    }
}
