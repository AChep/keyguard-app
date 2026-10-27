//! The private channel of the Keyguard agents to the app that launched them,
//! shared by the SSH, GPG and CLI agents: its frames, the connection to the
//! app's server, the launch token, the protobuf handshake, and a persistent
//! client of the SSH and GPG agents.

#[cfg(feature = "proto")]
pub mod client;
#[cfg(feature = "proto")]
pub mod error;
pub mod frame;
#[cfg(feature = "proto")]
pub mod handshake;
pub mod launch;
pub mod stream;
