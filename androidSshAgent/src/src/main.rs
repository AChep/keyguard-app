mod daemon;
mod packet_io;
mod process;
mod rpc;

use anyhow::{Context, Result};
use clap::Parser;
use daemon::{run_blocking, Config};
use keyguard_android_agent_bridge::bridge::{BridgeConfig, DEFAULT_CONNECT_TIMEOUT_MS};
use keyguard_android_agent_bridge::broadcast::{BridgeContract, LegacyAcknowledgement};
use keyguard_android_agent_bridge::process::{exit_with, fork_daemon, prefers_csh};
use keyguard_android_agent_bridge::socket_guard::{EnsureSocket, SocketGuard};
use process::{kill_existing_agent, print_auth_socket_export, print_env_exports};
use rand::RngCore;
use std::ffi::OsString;
use std::path::PathBuf;

const DEFAULT_COMPONENT: &str =
    "com.artemchep.keyguard/com.artemchep.keyguard.android.sshagent.SshAgentReceiver";

/// Must match `AgentBridge.SSH` in the Keyguard app.
pub(crate) const SSH_AGENT_CONTRACT: BridgeContract = BridgeContract {
    action: "com.artemchep.keyguard.action.RUN_ANDROID_SSH_AGENT",
    extra_protocol_version: "com.artemchep.keyguard.extra.SSH_AGENT_PROTOCOL_VERSION",
    extra_proxy_port: "com.artemchep.keyguard.extra.SSH_AGENT_PROXY_PORT",
    extra_session_id: "com.artemchep.keyguard.extra.SSH_AGENT_SESSION_ID",
    extra_session_secret: "com.artemchep.keyguard.extra.SSH_AGENT_SESSION_SECRET",
    result_prefix: "keyguard.ssh-agent.broadcast.v1:",
    // Older Keyguard releases handled the SSH broadcast without acknowledging it.
    legacy_acknowledgement: LegacyAcknowledgement::Tolerate,
};

#[derive(Parser, Debug)]
#[command(name = "keyguard-android-ssh-agent", version, about)]
struct Args {
    #[arg(short = 'a', value_name = "bind_address")]
    addr: Option<PathBuf>,

    #[arg(short = 'c', conflicts_with = "bash")]
    csh: bool,

    #[arg(short = 's', conflicts_with = "csh")]
    bash: bool,

    #[arg(short = 'D')]
    foreground: bool,

    #[arg(short = 'd')]
    debug: bool,

    #[arg(
        long,
        requires = "addr",
        conflicts_with_all = ["foreground", "debug", "kill", "cmd"]
    )]
    ensure: bool,

    #[arg(short = 'k', conflicts_with_all = ["addr", "foreground", "debug", "cmd"])]
    kill: bool,

    #[arg(long, default_value = DEFAULT_COMPONENT)]
    android_component: String,

    #[arg(long, default_value_t = DEFAULT_CONNECT_TIMEOUT_MS)]
    connect_timeout_ms: u64,

    #[arg(trailing_var_arg = true)]
    cmd: Vec<OsString>,
}

fn main() {
    exit_with(try_main())
}

fn try_main() -> Result<i32> {
    let args = Args::parse();
    let is_csh = prefers_csh(args.csh, args.bash);
    let mut pid = std::process::id() as i32;

    if args.kill {
        kill_existing_agent(is_csh)?;
        return Ok(0);
    }

    let mut socket_guard = if args.ensure {
        let socket_path = args.addr.context("--ensure requires -a")?;
        match SocketGuard::ensure(socket_path)? {
            EnsureSocket::Existing(socket_path) => {
                print_auth_socket_export(is_csh, &socket_path);
                return Ok(0);
            }
            EnsureSocket::Start(socket_guard) => socket_guard,
        }
    } else {
        SocketGuard::new(args.addr, pid, random_hex(8))?
    };
    let socket_file = socket_guard.path().to_path_buf();
    let listener = socket_guard.bind_listener()?;
    let config = Config {
        bridge: BridgeConfig::new(args.android_component, args.connect_timeout_ms, args.debug),
        socket_path: socket_file.clone(),
    };

    let has_cmd = !args.cmd.is_empty();
    let is_foreground = has_cmd || args.foreground || args.debug;

    if !is_foreground {
        // SAFETY: called once during startup, before any extra threads are created.
        match unsafe { fork_daemon() }? {
            None => {
                return run_blocking(listener, None, config);
            }
            Some(child_pid) => {
                pid = child_pid;
                socket_guard.disarm();
                print_env_exports(is_csh, &socket_file, Some(pid));
                return Ok(0);
            }
        }
    }

    if !has_cmd {
        print_env_exports(is_csh, &socket_file, (!is_foreground).then_some(pid));
    }

    if is_foreground {
        let command = has_cmd.then_some(args.cmd);
        return run_blocking(listener, command, config);
    }

    Ok(0)
}

fn random_hex(bytes: usize) -> String {
    let mut data = vec![0u8; bytes];
    rand::thread_rng().fill_bytes(&mut data);
    data.iter().map(|byte| format!("{byte:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn args_default_android_component_targets_keyguard_release_package() {
        let args = Args::parse_from(["keyguard-android-ssh-agent"]);

        assert_eq!(
            args.android_component,
            "com.artemchep.keyguard/com.artemchep.keyguard.android.sshagent.SshAgentReceiver",
        );
    }

    #[test]
    fn ensure_requires_explicit_socket_address() {
        assert!(Args::try_parse_from(["keyguard-android-ssh-agent", "--ensure"]).is_err());
        assert!(Args::try_parse_from([
            "keyguard-android-ssh-agent",
            "--ensure",
            "-a",
            "/tmp/agent.sock",
        ])
        .is_ok());
    }
}
