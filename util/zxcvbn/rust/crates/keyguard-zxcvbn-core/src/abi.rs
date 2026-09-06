//! Stable wire layouts shared by the JNI and C bridges.
//!
//! Both bridges return an `i64` scalar. Zero means the caller-owned result
//! buffer was filled; a negative value is a packed [`BridgeError`] and leaves
//! the buffer untouched. No positive value is defined.
//!
//! Failure layout (`bit63 = 1`):
//!
//! | bits    | content                                |
//! |---------|----------------------------------------|
//! | 0..=7   | [`Operation`] (always `Bridge` = 0)    |
//! | 8..=15  | [`FailureKind`]                        |
//! | 16..=23 | [`ErrorDomain`] (always `Bridge` = 3)  |
//! | 24..=55 | bridge error code (`u32`)              |
//! | 56..=62 | reserved, zero                         |
//!
//! The reserved bits are what keep `-1` unrepresentable as a failure, matching
//! `util/io`'s scalar contract even though this ABI defines no `-1` marker.

use std::mem::size_of;

use zxcvbn::{
    Entropy,
    time_estimates::{CrackTimeSeconds, CrackTimes},
};

use crate::{
    error::BridgeError,
    feedback::{WARNING_NONE, suggestion_bit, warning_code},
};

const FAILURE_MARKER: u64 = 1 << 63;
const KIND_SHIFT: u32 = 8;
const DOMAIN_SHIFT: u32 = 16;
const RAW_CODE_SHIFT: u32 = 24;
const OPERATION_MASK: u64 = 0xff;

/// Version of [`ResultWire`].
pub const RESULT_WIRE_VERSION: u32 = 1;

/// Size- and version-tagged estimation result shared with native callers.
///
/// C callers set `size` to `sizeof(struct keyguard_zxcvbn_result_v1)` before
/// the call; the bridge rejects anything smaller than its own record and
/// overwrites the field with the size it actually wrote. Every other field
/// may be uninitialized on entry.
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ResultWire {
    /// Byte size of this structure.
    pub size: u32,
    /// [`RESULT_WIRE_VERSION`].
    pub version: u32,
    /// Overall strength score, `0..=4`.
    pub score: u32,
    /// Warning wire code, or [`WARNING_NONE`] when upstream reported none.
    pub warning: i32,
    /// Bitmask of suggestion bits; see `crate::feedback::suggestion_bit`.
    pub suggestions: u32,
    /// Reserved for future compatible extensions; always zero.
    pub reserved0: u32,
    /// Estimated guesses needed to crack the password.
    pub guesses: u64,
    /// Order of magnitude of `guesses`; negative infinity for an empty
    /// password.
    pub guesses_log10: f64,
    /// Seconds of an online attack against a rate-limited service.
    pub online_throttling_100_per_hour: f64,
    /// Seconds of an online attack without rate limiting.
    pub online_no_throttling_10_per_second: f64,
    /// Seconds of an offline attack against a slow hash.
    pub offline_slow_hashing_1e4_per_second: f64,
    /// Seconds of an offline attack against a fast hash.
    pub offline_fast_hashing_1e10_per_second: f64,
    /// Reserved for future compatible extensions; always zero.
    pub reserved: [u64; 2],
}

const fn crack_time_seconds(seconds: CrackTimeSeconds) -> f64 {
    match seconds {
        CrackTimeSeconds::Integer(value) => value as f64,
        CrackTimeSeconds::Float(value) => value,
    }
}

impl ResultWire {
    /// Number of 64-bit fields used by the JNI `LongArray` bridge.
    pub const JNI_FIELD_COUNT: usize = 11;

    /// Projects an upstream entropy result onto the wire record.
    #[must_use]
    pub fn from_entropy(entropy: &Entropy) -> Self {
        let feedback = entropy.feedback();
        let warning = feedback
            .and_then(zxcvbn::feedback::Feedback::warning)
            .map_or(WARNING_NONE, warning_code);
        let suggestions = feedback.map_or(0, |feedback| {
            feedback
                .suggestions()
                .iter()
                .copied()
                .map(suggestion_bit)
                .fold(0, |mask, bit| mask | bit)
        });
        let crack_times: CrackTimes = entropy.crack_times();
        Self {
            size: size_of::<Self>() as u32,
            version: RESULT_WIRE_VERSION,
            score: u8::from(entropy.score()) as u32,
            warning,
            suggestions,
            reserved0: 0,
            guesses: entropy.guesses(),
            guesses_log10: entropy.guesses_log10(),
            online_throttling_100_per_hour: crack_time_seconds(
                crack_times.online_throttling_100_per_hour(),
            ),
            online_no_throttling_10_per_second: crack_time_seconds(
                crack_times.online_no_throttling_10_per_second(),
            ),
            offline_slow_hashing_1e4_per_second: crack_time_seconds(
                crack_times.offline_slow_hashing_1e4_per_second(),
            ),
            offline_fast_hashing_1e10_per_second: crack_time_seconds(
                crack_times.offline_fast_hashing_1e10_per_second(),
            ),
            reserved: [0; 2],
        }
    }

    /// Returns the fields in the order used by the JNI `LongArray` bridge.
    ///
    /// `guesses` saturates at [`i64::MAX`] so the slot is always a
    /// non-negative Kotlin `Long`; every double travels as its IEEE-754 bit
    /// pattern and is restored with `Double.fromBits`.
    #[must_use]
    pub const fn as_jni_fields(self) -> [i64; Self::JNI_FIELD_COUNT] {
        [
            self.size as i64,
            self.version as i64,
            self.score as i64,
            self.warning as i64,
            self.suggestions as i64,
            if self.guesses > i64::MAX as u64 {
                i64::MAX
            } else {
                self.guesses as i64
            },
            self.guesses_log10.to_bits() as i64,
            self.online_throttling_100_per_hour.to_bits() as i64,
            self.online_no_throttling_10_per_second.to_bits() as i64,
            self.offline_slow_hashing_1e4_per_second.to_bits() as i64,
            self.offline_fast_hashing_1e10_per_second.to_bits() as i64,
        ]
    }
}

/// Packs a bridge failure into the negative scalar representation.
#[must_use]
pub const fn pack_bridge_error(error: BridgeError) -> i64 {
    let (operation, kind, domain, raw_code) = error.wire_parts();
    (FAILURE_MARKER
        | (operation as u64 & OPERATION_MASK)
        | ((kind as u64) << KIND_SHIFT)
        | ((domain as u64) << DOMAIN_SHIFT)
        | ((raw_code as u64) << RAW_CODE_SHIFT)) as i64
}

/// Packs an invalid-argument failure for a native ABI boundary.
#[must_use]
pub const fn pack_bridge_invalid_argument() -> i64 {
    pack_bridge_error(BridgeError::InvalidArgument)
}

/// Packs a contained-panic failure for a native ABI boundary.
#[must_use]
pub const fn pack_bridge_panic() -> i64 {
    pack_bridge_error(BridgeError::Panic)
}

/// Packs an input-too-long failure for a native ABI boundary.
#[must_use]
pub const fn pack_bridge_input_too_long() -> i64 {
    pack_bridge_error(BridgeError::InputTooLong)
}

/// Packs an internal-adapter failure for a native ABI boundary.
#[must_use]
pub const fn pack_bridge_internal() -> i64 {
    pack_bridge_error(BridgeError::Internal)
}

/// Golden wire vectors asserted byte-identically by the Kotlin
/// `NativeZxcvbnWireTest`; changing any value is an ABI break.
#[cfg(test)]
mod golden {
    /// `BridgeError::InvalidArgument` (`Bridge`, `InvalidInput`, code 1).
    pub const BRIDGE_INVALID_ARGUMENT: i64 = 0x8000_0000_0103_0800_u64 as i64;
    /// `BridgeError::Panic` (`Bridge`, `Internal`, code 2).
    pub const BRIDGE_PANIC: i64 = 0x8000_0000_0203_0C00_u64 as i64;
    /// `BridgeError::Internal` (`Bridge`, `Internal`, code 3).
    pub const BRIDGE_INTERNAL: i64 = 0x8000_0000_0303_0C00_u64 as i64;
    /// `BridgeError::InputTooLong` (`Bridge`, `InvalidInput`, code 4).
    pub const BRIDGE_INPUT_TOO_LONG: i64 = 0x8000_0000_0403_0800_u64 as i64;
}

#[cfg(test)]
mod tests {
    use std::mem::offset_of;

    use super::*;

    #[test]
    fn packed_bridge_failures_match_the_golden_vectors() {
        assert_eq!(
            pack_bridge_invalid_argument(),
            golden::BRIDGE_INVALID_ARGUMENT
        );
        assert_eq!(pack_bridge_panic(), golden::BRIDGE_PANIC);
        assert_eq!(pack_bridge_internal(), golden::BRIDGE_INTERNAL);
        assert_eq!(pack_bridge_input_too_long(), golden::BRIDGE_INPUT_TOO_LONG);
    }

    #[test]
    fn every_packed_failure_is_negative_and_never_the_reserved_marker() {
        for packed in [
            pack_bridge_invalid_argument(),
            pack_bridge_panic(),
            pack_bridge_internal(),
            pack_bridge_input_too_long(),
        ] {
            assert!(packed < 0, "{packed:#x} must be negative");
            assert_ne!(packed, -1, "{packed:#x} must not collide with -1");
        }
    }

    #[test]
    fn result_wire_layout_is_pinned_to_the_abi_v1_offsets() {
        assert_eq!(size_of::<ResultWire>(), 88);
        assert_eq!(
            [
                offset_of!(ResultWire, size),
                offset_of!(ResultWire, version),
                offset_of!(ResultWire, score),
                offset_of!(ResultWire, warning),
                offset_of!(ResultWire, suggestions),
                offset_of!(ResultWire, reserved0),
                offset_of!(ResultWire, guesses),
                offset_of!(ResultWire, guesses_log10),
                offset_of!(ResultWire, online_throttling_100_per_hour),
                offset_of!(ResultWire, online_no_throttling_10_per_second),
                offset_of!(ResultWire, offline_slow_hashing_1e4_per_second),
                offset_of!(ResultWire, offline_fast_hashing_1e10_per_second),
                offset_of!(ResultWire, reserved),
            ],
            [0, 4, 8, 12, 16, 20, 24, 32, 40, 48, 56, 64, 72],
        );
    }

    #[test]
    fn jni_fields_saturate_guesses_and_round_trip_every_double() {
        let wire = ResultWire {
            size: size_of::<ResultWire>() as u32,
            version: RESULT_WIRE_VERSION,
            score: 4,
            warning: WARNING_NONE,
            suggestions: 0,
            reserved0: 0,
            guesses: u64::MAX,
            guesses_log10: f64::NEG_INFINITY,
            online_throttling_100_per_hour: 1.5,
            online_no_throttling_10_per_second: f64::INFINITY,
            offline_slow_hashing_1e4_per_second: 1.844_674_407_370_955e15,
            offline_fast_hashing_1e10_per_second: 0.0,
            reserved: [0; 2],
        };
        let fields = wire.as_jni_fields();
        assert_eq!(fields.len(), ResultWire::JNI_FIELD_COUNT);
        assert_eq!(fields[0], size_of::<ResultWire>() as i64);
        assert_eq!(fields[1], i64::from(RESULT_WIRE_VERSION));
        assert_eq!(fields[2], 4);
        assert_eq!(fields[3], i64::from(WARNING_NONE));
        assert_eq!(fields[4], 0);
        assert_eq!(fields[5], i64::MAX);
        assert!(fields[5] >= 0);
        assert_eq!(f64::from_bits(fields[6] as u64), f64::NEG_INFINITY);
        assert_eq!(f64::from_bits(fields[7] as u64), 1.5);
        assert_eq!(f64::from_bits(fields[8] as u64), f64::INFINITY);
        assert_eq!(f64::from_bits(fields[9] as u64), 1.844_674_407_370_955e15);
        assert_eq!(f64::from_bits(fields[10] as u64), 0.0);
    }

    #[test]
    fn guesses_below_the_saturation_point_are_widened_exactly() {
        let entropy = zxcvbn::zxcvbn("password", &[]);
        let wire = ResultWire::from_entropy(&entropy);
        assert!(wire.guesses < i64::MAX as u64);
        assert_eq!(wire.as_jni_fields()[5], wire.guesses as i64);
    }
}
