//! The wire contract between the `keyguard` CLI, its MCP adapter, and
//! Keyguard's CLI agent, and the client side of their transport.
//!
//! Public management and secret delivery are deliberately separate
//! contracts: management never returns values, and a CLI command that does
//! is only answered to the bundled CLI.
pub mod bounds;
pub mod cli;
pub mod errors;
pub mod frame;
pub mod json;
mod limits;
pub mod manage;
pub mod public;
pub mod socket;
pub mod transport;
