//! The data-plane operations of `fast.rs` in a build without the `full`
//! feature. Each answers [`NativeErrorCode::UnsupportedOperation`], as the
//! envelope does for an operation the build leaves out, so the bridges keep
//! one ABI.

use zeroize::Zeroize;

use crate::protocol::NativeErrorCode;

/// Unsupported in this build; see `fast.rs`.
///
/// # Errors
///
/// Always returns [`NativeErrorCode::UnsupportedOperation`].
pub fn random_int(_exclusive_upper_bound: u32) -> Result<i32, NativeErrorCode> {
    Err(NativeErrorCode::UnsupportedOperation)
}

/// Unsupported in this build; see `fast.rs`. Both outputs are zeroed.
///
/// # Errors
///
/// Always returns [`NativeErrorCode::UnsupportedOperation`].
pub fn encrypt_into(
    _enc_key: &[u8],
    _mac_key: &[u8],
    _iv: &[u8],
    _plaintext: &[u8],
    ciphertext_out: &mut [u8],
    mac_out: &mut [u8],
) -> Result<usize, NativeErrorCode> {
    ciphertext_out.zeroize();
    mac_out.zeroize();
    Err(NativeErrorCode::UnsupportedOperation)
}

/// Unsupported in this build; see `fast.rs`. The output is zeroed.
///
/// # Errors
///
/// Always returns [`NativeErrorCode::UnsupportedOperation`].
pub fn decrypt_into(
    _enc_key: &[u8],
    _mac_key: &[u8],
    _iv: &[u8],
    _ciphertext: &[u8],
    _expected_mac: &[u8],
    plaintext_out: &mut [u8],
) -> Result<usize, NativeErrorCode> {
    plaintext_out.zeroize();
    Err(NativeErrorCode::UnsupportedOperation)
}
