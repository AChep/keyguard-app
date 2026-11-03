//! Timestamp ordering is explicit at each signing workflow; waiting belongs to callers.
//!
//! New signatures are never dated after the reference time, so the returned
//! certificate already expresses the requested change at that time.

use pgp::types::Timestamp;

/// Longest clock wait a signing workflow may ask its caller for. A later
/// conflict comes from a future-dated statement and is terminal. Matches the
/// client's clock-wait budget.
pub(in crate::openpgp) const MAX_SIGNATURE_RETRY_SECONDS: u64 = 5;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(in crate::openpgp) enum SignatureTimeError {
    RetryAt(u64),
    Unrepresentable,
    TooFarAhead,
}

pub(in crate::openpgp) fn next_signature_time(
    reference_time: u64,
    newest_superseded: Option<u64>,
) -> Result<Timestamp, SignatureTimeError> {
    let earliest = match newest_superseded {
        Some(time) => time
            .checked_add(1)
            .ok_or(SignatureTimeError::Unrepresentable)?,
        None => reference_time,
    };
    if earliest > reference_time {
        return Err(if earliest - reference_time > MAX_SIGNATURE_RETRY_SECONDS {
            SignatureTimeError::TooFarAhead
        } else {
            SignatureTimeError::RetryAt(earliest)
        });
    }
    let seconds = u32::try_from(reference_time).map_err(|_| SignatureTimeError::Unrepresentable)?;
    Ok(Timestamp::from_secs(seconds))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn signatures_wait_for_strict_ordering() {
        assert_eq!(
            next_signature_time(100, Some(100)),
            Err(SignatureTimeError::RetryAt(101))
        );
        assert_eq!(next_signature_time(101, Some(100)).unwrap().as_secs(), 101);
        assert_eq!(next_signature_time(100, None).unwrap().as_secs(), 100);
    }

    #[test]
    fn waits_are_bounded_and_overflow_is_terminal() {
        assert_eq!(
            next_signature_time(100, Some(99 + MAX_SIGNATURE_RETRY_SECONDS)),
            Err(SignatureTimeError::RetryAt(
                100 + MAX_SIGNATURE_RETRY_SECONDS
            ))
        );
        assert_eq!(
            next_signature_time(100, Some(100 + MAX_SIGNATURE_RETRY_SECONDS)),
            Err(SignatureTimeError::TooFarAhead)
        );
        assert_eq!(
            next_signature_time(u64::from(u32::MAX) + 1, None),
            Err(SignatureTimeError::Unrepresentable)
        );
        assert_eq!(
            next_signature_time(100, Some(u64::MAX)),
            Err(SignatureTimeError::Unrepresentable)
        );
    }
}
