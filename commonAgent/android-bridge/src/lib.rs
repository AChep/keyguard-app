//! Termux-side plumbing shared by the Keyguard Android agent helpers.
//!
//! Android's SELinux policy forbids Unix socket connections between apps, so
//! a helper inside Termux opens a loopback TCP listener, wakes the Keyguard
//! app with `am broadcast`, and talks to it over an encrypted channel.

#![cfg(unix)]

pub mod bridge;
pub mod broadcast;
pub mod caller_identity;
pub mod process;
pub mod secure_transport;
pub mod server;
pub mod socket_guard;

/// Writes `message` to stderr when debug logging is on.
pub fn log_debug(enabled: bool, message: &str) {
    if enabled {
        eprintln!("{message}");
    }
}
