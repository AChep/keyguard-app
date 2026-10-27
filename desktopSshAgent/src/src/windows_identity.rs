//! Windows helpers used to secure the public SSH named pipe.
//!
//! Process-based approval reuse is explicitly best effort: the named-pipe PID
//! identifies its original opener, not necessarily the current handle holder.

mod process;
pub(crate) use process::WindowsCallerIdentity;
