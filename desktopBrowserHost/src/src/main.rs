//! `keyguard-browser-host`, the native messaging host of Keyguard's browser
//! extension. The browser starts it for the extension. It connects to the
//! app through the CLI agent and relays the extension's Noise frames, which
//! only the extension and the app can read.
mod args;
#[cfg(any(target_os = "linux", test))]
mod flatpak;
mod host;
mod native;
mod status;

use keyguard_cli_protocol::browser::HOST_EXECUTABLE;
use std::io::Write;

fn main() {
    let Some(origin) = args::extension_origin(std::env::args_os().skip(1)) else {
        exit(
            2,
            "expected the arguments of a browser that starts \
             Keyguard's browser extension connector",
        );
    };
    let runtime = match tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
    {
        Ok(runtime) => runtime,
        Err(error) => exit(1, &error.to_string()),
    };
    let host = host::Host::new(origin);
    let result = runtime.block_on(host::run(&host, tokio::io::stdin(), tokio::io::stdout()));
    // The process exits without dropping the runtime, which would wait for
    // its read of stdin: that read can not be canceled.
    match result {
        Ok(()) => std::process::exit(0),
        // Errors never quote a message.
        Err(error) => exit(1, &error.to_string()),
    }
}

fn exit(code: i32, error: &str) -> ! {
    log(error);
    std::process::exit(code);
}

/// Writes a line to stderr. Unlike `eprintln!`, it ignores a failed write: a
/// panic would drop the runtime, which waits for its read of stdin.
fn log(message: &str) {
    let _ = writeln!(std::io::stderr(), "{HOST_EXECUTABLE}: {message}");
}
