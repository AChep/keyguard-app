//! Versioned protobuf messages shared by the JNI and C boundaries.
//!
//! [`generated`] is checked in so production and cross-target builds do not
//! require `protoc`. `util/crypto/schema/native_crypto.proto` remains the wire
//! source of truth, and CI verifies that these declarations match it.
//!
//! Secret erasure remains handwritten because it expresses security policy
//! that cannot be inferred from the protobuf schema.

#[allow(missing_docs)]
#[rustfmt::skip]
mod generated;
mod secret_erasure;

pub use generated::*;

#[cfg(test)]
pub(crate) use secret_erasure::{
    reset_zeroized_secret_output_drops, reset_zeroized_secret_request_drops,
    zeroized_secret_output_drops, zeroized_secret_request_drops,
};
