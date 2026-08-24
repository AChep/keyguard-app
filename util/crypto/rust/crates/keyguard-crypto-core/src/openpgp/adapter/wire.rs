//! OpenPGP protobuf types visible to native-facing workflow adapters.
//!
//! Keeping these re-exports under the adapter prevents packet, certificate,
//! crypto, and policy modules from importing the generated protocol directly.

pub(crate) use crate::protocol::*;
pub(crate) use prost::Message;
