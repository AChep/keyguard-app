//! Unix timestamps.

use crate::{Error, Result};
use core::fmt;
use core::fmt::Formatter;
use encoding::{Decode, Encode, Reader, Writer};

#[cfg(feature = "std")]
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// Unix timestamps as used in OpenSSH certificates.
///
/// Preserve the full unsigned wire value, including `u64::MAX` ("forever").
/// Decoding and signature verification must not depend on platform time limits.
#[derive(Copy, Clone, Eq, PartialEq, PartialOrd, Ord)]
pub(super) struct UnixTime {
    /// Number of seconds since the Unix epoch
    secs: u64,
}

impl UnixTime {
    /// Create a new Unix timestamp.
    ///
    /// `secs` is the number of seconds since the Unix epoch.
    pub const fn new(secs: u64) -> Self {
        Self { secs }
    }

    /// Get the current time as a Unix timestamp.
    #[cfg(feature = "std")]
    pub fn now() -> Result<Self> {
        SystemTime::now().try_into()
    }
}

impl Decode for UnixTime {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        Ok(Self::new(u64::decode(reader)?))
    }
}

impl Encode for UnixTime {
    fn encoded_len(&self) -> encoding::Result<usize> {
        self.secs.encoded_len()
    }

    fn encode(&self, writer: &mut impl Writer) -> encoding::Result<()> {
        self.secs.encode(writer)?;
        Ok(())
    }
}

impl From<UnixTime> for u64 {
    fn from(unix_time: UnixTime) -> u64 {
        unix_time.secs
    }
}

#[cfg(feature = "std")]
impl TryFrom<UnixTime> for SystemTime {
    type Error = Error;

    fn try_from(unix_time: UnixTime) -> Result<SystemTime> {
        if unix_time.secs == u64::MAX {
            return Err(Error::Time);
        }
        UNIX_EPOCH
            .checked_add(Duration::from_secs(unix_time.secs))
            .ok_or(Error::Time)
    }
}

#[cfg(feature = "std")]
impl TryFrom<SystemTime> for UnixTime {
    type Error = Error;

    fn try_from(time: SystemTime) -> Result<UnixTime> {
        Ok(Self::new(time.duration_since(UNIX_EPOCH)?.as_secs()))
    }
}

impl fmt::Debug for UnixTime {
    fn fmt(&self, f: &mut Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.secs)
    }
}

#[cfg(test)]
mod tests {
    use super::UnixTime;
    use alloc::vec::Vec;
    use encoding::{Decode, Encode};

    #[test]
    fn unsigned_timestamps_round_trip() {
        for seconds in [0, i64::MAX as u64, 1u64 << 63, u64::MAX] {
            let bytes = seconds.to_be_bytes();
            let time = UnixTime::decode(&mut bytes.as_slice()).expect("unsigned wire timestamp");
            assert_eq!(u64::from(time), seconds);
            let mut encoded = Vec::new();
            time.encode(&mut encoded).expect("encode timestamp");
            assert_eq!(encoded, bytes);
        }
    }

    #[cfg(feature = "std")]
    #[test]
    fn system_time_conversion_is_checked() {
        use super::{Duration, SystemTime, UNIX_EPOCH};
        use crate::Error;

        let time = UNIX_EPOCH
            .checked_add(Duration::from_secs(4_000_000_000))
            .expect("finite time");
        let unix_time = UnixTime::try_from(time).expect("time after epoch");
        assert_eq!(u64::from(unix_time), 4_000_000_000);
        assert_eq!(SystemTime::try_from(unix_time), Ok(time));
        assert_eq!(
            SystemTime::try_from(UnixTime::new(u64::MAX)),
            Err(Error::Time)
        );
        assert_eq!(
            UnixTime::try_from(
                UNIX_EPOCH
                    .checked_sub(Duration::from_secs(1))
                    .expect("time before epoch")
            ),
            Err(Error::Time)
        );
    }
}
