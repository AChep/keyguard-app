//! Failures of commands and COM calls. Kotlin reads their codes, so keep the codes stable;
//! the reply fixtures pin the ones that Kotlin tells apart.
use std::sync::PoisonError;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Error {
    /// A malformed command, or an OS structure outside its bounds.
    Invalid,
    /// The bridge failed internally, e.g. on a poisoned lock.
    Internal,
    /// No server serves Windows' requests: none started, or its start failed. A stopped or
    /// retired one ends its event waits as closed instead.
    NotServing,
    /// Keyguard answered a request without verifying the user, so the request ended instead.
    Unverified,
    /// Windows has no passkey provider API.
    Unsupported,
    /// The process does not run from its MSIX package.
    Unpackaged,
    /// The ceremony was canceled, expired, or never existed.
    Cancelled,
    /// The user dismissed the ceremony.
    Dismissed,
    /// The vault holds a credential that the request excludes.
    Excluded,
    /// The vault holds none of the requested credentials.
    NotFound,
    /// Another ceremony or verification is in progress.
    Busy,
    /// A failure HRESULT from Windows.
    Os(i32),
}

impl Error {
    /// The code in the `error` field of a reply.
    pub(crate) const fn code(self) -> i32 {
        match self {
            Self::Invalid | Self::Internal | Self::NotServing | Self::Unverified => -1,
            Self::Unsupported => -2,
            Self::Unpackaged => -3,
            // HRESULT_FROM_WIN32(ERROR_CANCELLED)
            Self::Cancelled => 0x8007_04C7_u32.cast_signed(),
            // NTE_USER_CANCELLED, which Windows tells apart from other failures
            Self::Dismissed => 0x8009_0036_u32.cast_signed(),
            // NTE_EXISTS, which webauthn.h maps to InvalidStateError
            Self::Excluded => 0x8009_000F_u32.cast_signed(),
            // NTE_NOT_FOUND, which webauthn.h maps to NotAllowedError
            Self::NotFound => 0x8009_0011_u32.cast_signed(),
            // HRESULT_FROM_WIN32(ERROR_BUSY)
            Self::Busy => 0x8007_00AA_u32.cast_signed(),
            Self::Os(code) => code,
        }
    }
}

impl<T> From<PoisonError<T>> for Error {
    fn from(_: PoisonError<T>) -> Self {
        Self::Internal
    }
}

#[cfg(windows)]
impl From<windows::core::Error> for Error {
    fn from(error: windows::core::Error) -> Self {
        let code = error.code();
        // Anything but a failure HRESULT would read as success at the COM boundary.
        if code.is_err() {
            Self::Os(code.0)
        } else {
            Self::Internal
        }
    }
}

#[cfg(windows)]
impl Error {
    /// The HRESULT a COM method returns.
    pub(crate) fn hresult(self) -> windows::core::HRESULT {
        use windows::Win32::Foundation::{E_FAIL, E_INVALIDARG};
        match self {
            Self::Invalid => E_INVALIDARG,
            Self::Internal
            | Self::NotServing
            | Self::Unverified
            | Self::Unsupported
            | Self::Unpackaged => E_FAIL,
            Self::Cancelled
            | Self::Dismissed
            | Self::Excluded
            | Self::NotFound
            | Self::Busy
            | Self::Os(_) => windows::core::HRESULT(self.code()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes_are_stable() {
        assert_eq!(Error::Invalid.code(), -1);
        assert_eq!(Error::Internal.code(), -1);
        assert_eq!(Error::NotServing.code(), -1);
        assert_eq!(Error::Unverified.code(), -1);
        assert_eq!(Error::Unsupported.code(), -2);
        assert_eq!(Error::Unpackaged.code(), -3);
        assert_eq!(Error::Cancelled.code(), -2_147_023_673);
        assert_eq!(Error::Dismissed.code(), -2_146_893_770);
        assert_eq!(Error::Excluded.code(), -2_146_893_809);
        assert_eq!(Error::NotFound.code(), -2_146_893_807);
        assert_eq!(Error::Busy.code(), -2_147_024_726);
        assert_eq!(Error::Os(-5).code(), -5);
    }

    #[cfg(windows)]
    #[test]
    fn com_failures_are_failure_hresults() {
        use windows::{
            Win32::Foundation::{
                E_FAIL, E_INVALIDARG, ERROR_BUSY, ERROR_CANCELLED, NTE_EXISTS, NTE_NOT_FOUND,
                NTE_USER_CANCELLED, S_OK,
            },
            core::HRESULT,
        };
        assert_eq!(
            Error::Cancelled.hresult(),
            HRESULT::from_win32(ERROR_CANCELLED.0)
        );
        assert_eq!(Error::Dismissed.hresult(), NTE_USER_CANCELLED);
        assert_eq!(Error::Excluded.hresult(), NTE_EXISTS);
        assert_eq!(Error::NotFound.hresult(), NTE_NOT_FOUND);
        assert_eq!(Error::Busy.hresult(), HRESULT::from_win32(ERROR_BUSY.0));
        assert_eq!(Error::Invalid.hresult(), E_INVALIDARG);
        assert_eq!(Error::Internal.hresult(), E_FAIL);
        assert_eq!(
            Error::from(windows::core::Error::from(E_INVALIDARG)),
            Error::Os(E_INVALIDARG.0)
        );
        assert_eq!(
            Error::from(windows::core::Error::from(S_OK)),
            Error::Internal
        );
    }
}
