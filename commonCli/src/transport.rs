//! The client side of the owner-only local transport. The CLI connects to
//! the agent, and the agent to the app, through it.
pub use keyguard_agent_ipc::stream::{BoxStream, Stream, connect};

#[cfg(windows)]
pub mod windows;
