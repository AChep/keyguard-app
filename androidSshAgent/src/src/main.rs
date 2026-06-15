mod broadcast;
mod caller_identity;
mod daemon;
mod packet_io;
mod process;
mod rpc;
mod secure_transport;
mod socket_guard;

use anyhow::{bail, Result};
use broadcast::{BroadcastCommandSpec, DEFAULT_COMPONENT};
use clap::Parser;
use daemon::{run_blocking, Config};
use process::{kill_existing_agent, print_env_exports, redirect_null};
use rand::RngCore;
use socket_guard::SocketGuard;
use std::ffi::OsString;
use std::path::PathBuf;
use tokio::time::Duration;

const DEFAULT_CONNECT_TIMEOUT_MS: u64 = 60_000;

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
    std::process::exit(match try_main() {
        Ok(code) => code,
        Err(err) => {
            eprintln!("{err:#}");
            1
        }
    });
}

fn try_main() -> Result<i32> {
    let args = Args::parse();
    let is_csh = args.csh
        || (!args.bash && std::env::var("SHELL").is_ok_and(|shell| shell.ends_with("csh")));
    let mut pid = std::process::id() as i32;

    if args.kill {
        kill_existing_agent(is_csh)?;
        return Ok(0);
    }

    let mut socket_guard = SocketGuard::new(args.addr, pid, random_hex(8))?;
    let socket_file = socket_guard.path().to_path_buf();
    let listener = socket_guard.bind_listener()?;
    let config = Config {
        android_component: args.android_component,
        connect_timeout: Duration::from_millis(args.connect_timeout_ms),
        debug: args.debug,
        socket_path: socket_file.clone(),
        broadcast_command: BroadcastCommandSpec::default(),
    };

    let has_cmd = !args.cmd.is_empty();
    let is_foreground = has_cmd || args.foreground || args.debug;

    if !is_foreground {
        // SAFETY: `fork(2)` is called once during startup before any extra threads are created.
        // Both branches immediately either return to the parent or continue the child bootstrap.
        match unsafe { libc::fork() } {
            -1 => {
                bail!(
                    "Failed to fork background agent: {}",
                    std::io::Error::last_os_error()
                );
            }
            0 => {
                redirect_null(libc::STDIN_FILENO, false)?;
                redirect_null(libc::STDOUT_FILENO, true)?;
                redirect_null(libc::STDERR_FILENO, true)?;
                return run_blocking(listener, None, config);
            }
            child_pid => {
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
}
