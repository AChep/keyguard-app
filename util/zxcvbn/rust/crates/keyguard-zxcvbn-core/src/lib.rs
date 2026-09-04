//! Native password strength core shared by the Keyguard JNI and C bridges.
//!
//! The crate is a thin, allocation-light projection of the `zxcvbn` crate onto
//! a fixed-size wire record: one call in, one [`abi::ResultWire`] out. It owns
//! the input limits and the stable feedback codes, and it is deliberately not
//! the panic boundary — each bridge installs that itself so a contained panic
//! can be reported in the bridge's own return type.

pub mod abi;
pub mod error;
pub mod feedback;

use std::sync::Once;

pub use abi::{RESULT_WIRE_VERSION, ResultWire};
pub use error::{BridgeError, ErrorDomain, FailureKind, Operation};
pub use feedback::{SUGGESTION_MASK_ALL, WARNING_NONE, suggestion_bit, warning_code};

/// Version of the direct native function ABI.
pub const ABI_VERSION: u32 = 1;

/// Reserved error code returned for an invalid native ABI argument.
pub const BRIDGE_ERROR_INVALID_ARGUMENT: u32 = 1;

/// Reserved error code returned when a panic reaches a native ABI boundary.
pub const BRIDGE_ERROR_PANIC: u32 = 2;

/// Reserved error code returned when a native ABI adapter fails internally.
pub const BRIDGE_ERROR_INTERNAL: u32 = 3;

/// Reserved error code returned when an input exceeds its accepted size.
pub const BRIDGE_ERROR_INPUT_TOO_LONG: u32 = 4;

/// Largest accepted password, in UTF-8 bytes.
///
/// The estimator is superlinear in input length, so the bridge caps what it
/// will look at. Upstream additionally evaluates only the first 100
/// characters, which makes anything past that indistinguishable anyway; this
/// limit exists to bound the marshalling cost, not the scoring cost.
pub const MAX_PASSWORD_BYTES: usize = 256;

/// Largest accepted number of user inputs.
pub const MAX_USER_INPUTS: usize = 64;

/// Largest accepted user input, in UTF-8 bytes.
pub const MAX_USER_INPUT_BYTES: usize = 256;

static PANIC_HOOK: Once = Once::new();

/// Installs a process-wide panic hook that does not disclose passwords.
///
/// Rust's default hook prints panic payloads before [`std::panic::catch_unwind`]
/// runs, and an upstream scoring panic could carry password-derived tokens in
/// its message. Native bridges install this hook before entering their panic
/// boundary and communicate only stable status codes to Kotlin.
pub fn install_redacting_panic_hook() {
    PANIC_HOOK.call_once(|| std::panic::set_hook(Box::new(|_| {})));
}

/// Estimates the strength of `password`, biased by `user_inputs`.
///
/// The password may be empty; upstream then reports score zero with negative
/// infinity `guesses_log10`. Inputs beyond the module's limits are rejected
/// rather than truncated, so a caller never gets a score for a password it did
/// not ask about.
///
/// # Errors
///
/// Returns [`BridgeError::InputTooLong`] when the password or any user input
/// exceeds its byte limit, and [`BridgeError::InvalidArgument`] when there are
/// more than [`MAX_USER_INPUTS`] user inputs.
pub fn estimate(password: &str, user_inputs: &[&str]) -> Result<ResultWire, BridgeError> {
    if password.len() > MAX_PASSWORD_BYTES {
        return Err(BridgeError::InputTooLong);
    }
    if user_inputs.len() > MAX_USER_INPUTS {
        return Err(BridgeError::InvalidArgument);
    }
    if user_inputs
        .iter()
        .any(|input| input.len() > MAX_USER_INPUT_BYTES)
    {
        return Err(BridgeError::InputTooLong);
    }
    let entropy = zxcvbn::zxcvbn(password, user_inputs);
    Ok(ResultWire::from_entropy(&entropy))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_top_ten_password_scores_zero_with_the_top_ten_warning() {
        let wire = estimate("password", &[]).expect("a short password must be accepted");
        assert_eq!(wire.score, 0);
        assert_eq!(wire.warning, 4);
        assert_eq!(wire.size, size_of::<ResultWire>() as u32);
        assert_eq!(wire.version, RESULT_WIRE_VERSION);
        assert_eq!(wire.reserved0, 0);
        assert_eq!(wire.reserved, [0; 2]);
        assert_eq!(wire.suggestions & !SUGGESTION_MASK_ALL, 0);
    }

    #[test]
    fn a_long_passphrase_scores_strongly_with_no_warning() {
        let wire =
            estimate("correcthorsebatterystaple", &[]).expect("a passphrase must be accepted");
        assert!(wire.score >= 3, "score was {}", wire.score);
        assert!(
            wire.guesses_log10 > 10.0,
            "guesses_log10 was {}",
            wire.guesses_log10
        );
        assert_eq!(wire.warning, WARNING_NONE);
        assert_eq!(wire.suggestions, 0);
    }

    #[test]
    fn crack_times_scale_with_the_guess_count() {
        let wire =
            estimate("correcthorsebatterystaple", &[]).expect("a passphrase must be accepted");
        let guesses = wire.guesses as f64;
        assert_eq!(wire.online_throttling_100_per_hour, guesses * 36.0);
        assert_eq!(wire.online_no_throttling_10_per_second, guesses / 10.0);
        assert_eq!(wire.offline_slow_hashing_1e4_per_second, guesses / 1e4);
        assert_eq!(wire.offline_fast_hashing_1e10_per_second, guesses / 1e10);
    }

    #[test]
    fn a_matching_user_input_strictly_reduces_the_guess_count() {
        let without = estimate("keyguardvault2026", &[]).expect("baseline must be accepted");
        let with = estimate("keyguardvault2026", &["keyguardvault"])
            .expect("biased estimate must be accepted");
        assert!(
            with.guesses < without.guesses,
            "{} must be below {}",
            with.guesses,
            without.guesses
        );
    }

    #[test]
    fn a_password_at_the_byte_limit_is_accepted_and_one_past_it_is_not() {
        let accepted = "a".repeat(MAX_PASSWORD_BYTES);
        assert!(estimate(&accepted, &[]).is_ok());

        let rejected = "a".repeat(MAX_PASSWORD_BYTES + 1);
        assert_eq!(
            estimate(&rejected, &[]),
            Err(BridgeError::InputTooLong),
            "a {}-byte password must be rejected",
            MAX_PASSWORD_BYTES + 1
        );
    }

    #[test]
    fn the_password_limit_counts_utf8_bytes_rather_than_characters() {
        // Two bytes each, so 129 characters overrun the 256-byte limit.
        let rejected = "é".repeat(MAX_PASSWORD_BYTES / 2 + 1);
        assert_eq!(rejected.chars().count(), 129);
        assert_eq!(estimate(&rejected, &[]), Err(BridgeError::InputTooLong));
    }

    #[test]
    fn user_input_counts_above_the_limit_are_invalid_arguments() {
        let inputs = vec!["keyguard"; MAX_USER_INPUTS];
        assert!(estimate("keyguardvault2026", &inputs).is_ok());

        let inputs = vec!["keyguard"; MAX_USER_INPUTS + 1];
        assert_eq!(
            estimate("keyguardvault2026", &inputs),
            Err(BridgeError::InvalidArgument)
        );
    }

    #[test]
    fn an_oversized_user_input_is_too_long() {
        let input = "a".repeat(MAX_USER_INPUT_BYTES + 1);
        assert_eq!(
            estimate("keyguardvault2026", &[input.as_str()]),
            Err(BridgeError::InputTooLong)
        );
        let input = "a".repeat(MAX_USER_INPUT_BYTES);
        assert!(estimate("keyguardvault2026", &[input.as_str()]).is_ok());
    }

    #[test]
    fn an_empty_password_scores_zero_with_negative_infinite_magnitude() {
        let wire = estimate("", &[]).expect("an empty password must be accepted");
        assert_eq!(wire.score, 0);
        assert_eq!(wire.guesses, 0);
        assert_eq!(wire.guesses_log10, f64::NEG_INFINITY);
        assert!(wire.guesses_log10.is_infinite());
        assert_eq!(wire.warning, WARNING_NONE);
        // Upstream's default feedback for an empty match sequence.
        assert_eq!(
            wire.suggestions,
            suggestion_bit(zxcvbn::feedback::Suggestion::UseAFewWordsAvoidCommonPhrases)
                | suggestion_bit(
                    zxcvbn::feedback::Suggestion::NoNeedForSymbolsDigitsOrUppercaseLetters
                )
        );
        assert_eq!(wire.online_throttling_100_per_hour, 0.0);
    }

    #[test]
    fn the_bridge_never_reports_an_unknown_suggestion_bit_or_out_of_range_score() {
        for password in [
            "password",
            "qwerty",
            "aaaaaaaa",
            "abcabcabc",
            "P@ssw0rd!",
            "19951995",
            "michael",
            "correcthorsebatterystaple",
            "",
        ] {
            let wire = estimate(password, &[]).expect("every sample must be accepted");
            assert!(wire.score <= 4, "score was {}", wire.score);
            assert_eq!(wire.suggestions & !SUGGESTION_MASK_ALL, 0);
            assert!(
                wire.warning == WARNING_NONE || (0..14).contains(&wire.warning),
                "warning was {}",
                wire.warning
            );
        }
    }

    #[test]
    fn estimation_is_stable_under_concurrent_callers() {
        let expected = estimate("password", &[]).expect("baseline must be accepted");
        let threads: Vec<_> = (0..8)
            .map(|_| {
                std::thread::spawn(move || {
                    for _ in 0..200 {
                        let wire = estimate("password", &[]).expect("estimate must succeed");
                        assert_eq!(wire, expected);
                    }
                })
            })
            .collect();
        for thread in threads {
            thread.join().expect("estimation thread must not panic");
        }
    }
}
