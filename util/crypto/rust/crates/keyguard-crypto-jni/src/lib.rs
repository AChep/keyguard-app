//! JNI bridge for `com.artemchep.keyguard.nativecrypto.NativeCryptoJni`.

use std::{panic::AssertUnwindSafe, ptr};

use jni::{
    Env, EnvUnowned, Outcome,
    elements::{AutoElements, ReleaseMode},
    jni_str,
    objects::{JByteArray, JObject},
    sys::{jbyte, jbyteArray, jint, jlong},
};
use keyguard_crypto_core as core;
use keyguard_crypto_core::protocol::NativeErrorCode;
use zeroize::{Zeroize, Zeroizing};

const AES_BLOCK_BYTES: usize = 16;
const HMAC_SHA256_BYTES: usize = 32;
const ZERO_CHUNK: [jbyte; 8 * 1024] = [0; 8 * 1024];

/// Returns the native function ABI version.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_nativecrypto_NativeCryptoJni_abiVersion(
    _environment: EnvUnowned<'_>,
    _object: JObject<'_>,
) -> jint {
    std::panic::catch_unwind(AssertUnwindSafe(|| {
        core::install_redacting_panic_hook();
        core::ABI_VERSION as jint
    }))
    .unwrap_or(0)
}

/// Returns the native capability bit mask.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_nativecrypto_NativeCryptoJni_capabilities(
    _environment: EnvUnowned<'_>,
    _object: JObject<'_>,
) -> jlong {
    std::panic::catch_unwind(AssertUnwindSafe(|| {
        core::install_redacting_panic_hook();
        core::CAPABILITIES as jlong
    }))
    .unwrap_or(0)
}

/// Generates a secure random integer without a protobuf control envelope.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_nativecrypto_NativeCryptoJni_randomInt(
    _environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    exclusive_upper_bound: jint,
) -> jlong {
    scalar_boundary(|| {
        let exclusive_upper_bound =
            u32::try_from(exclusive_upper_bound).map_err(|_| NativeErrorCode::InvalidArgument)?;
        core::fast::random_int(exclusive_upper_bound)
    })
}

/// Executes a versioned one-shot protobuf request.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_nativecrypto_NativeCryptoJni_call(
    environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    input: JByteArray<'_>,
) -> jbyteArray {
    with_byte_array(
        environment,
        input,
        "call",
        core::MAX_CONTROL_ENVELOPE_BYTES,
        core::call,
    )
}

/// Opens a versioned streaming operation.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_nativecrypto_NativeCryptoJni_streamOpen(
    environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    input: JByteArray<'_>,
) -> jbyteArray {
    with_byte_array(
        environment,
        input,
        "stream.open",
        core::MAX_CONTROL_ENVELOPE_BYTES,
        core::stream_open,
    )
}

/// Adds a raw chunk to a streaming operation.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_nativecrypto_NativeCryptoJni_streamUpdate(
    environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    handle: jlong,
    input: JByteArray<'_>,
) -> jbyteArray {
    with_byte_array(
        environment,
        input,
        "stream.update",
        core::MAX_STREAM_CHUNK_BYTES,
        |input| core::stream_update(handle as u64, input),
    )
}

/// Finalizes and consumes a streaming operation.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_nativecrypto_NativeCryptoJni_streamFinish(
    environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    handle: jlong,
) -> jbyteArray {
    without_input(environment, "stream.finish", || {
        core::stream_finish(handle as u64)
    })
}

/// Closes a streaming operation.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_nativecrypto_NativeCryptoJni_streamClose(
    environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    handle: jlong,
) -> jbyteArray {
    without_input(environment, "stream.close", || {
        core::stream_close(handle as u64)
    })
}

/// Encrypts and authenticates AES-CBC/HMAC data into caller-owned JVM arrays.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_artemchep_keyguard_nativecrypto_NativeCryptoJni_aesCbcPkcs7HmacSha256Encrypt(
    environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    encryption_key: JByteArray<'_>,
    mac_key: JByteArray<'_>,
    iv: JByteArray<'_>,
    plaintext: JByteArray<'_>,
    ciphertext_output: JByteArray<'_>,
    mac_output: JByteArray<'_>,
) -> jlong {
    fast_array_boundary(
        environment,
        &[&ciphertext_output, &mac_output],
        |environment| {
            ensure_fast_array_ownership(
                environment,
                &[&ciphertext_output, &mac_output],
                &[&encryption_key, &mac_key, &iv, &plaintext],
            )?;
            let encryption_key = read_java_bytes(environment, &encryption_key)?;
            let mac_key = read_java_bytes(environment, &mac_key)?;
            let iv = read_java_bytes(environment, &iv)?;
            let plaintext = read_java_bytes(environment, &plaintext)?;
            let ciphertext_len = java_array_len(environment, &ciphertext_output)?;
            let mac_len = java_array_len(environment, &mac_output)?;
            let expected_ciphertext_len = encrypted_size(plaintext.len())?;
            if ciphertext_len != expected_ciphertext_len || mac_len != HMAC_SHA256_BYTES {
                return Err(NativeErrorCode::InvalidArgument);
            }

            let mut ciphertext = SensitiveOutputElements::new(environment, &ciphertext_output)?;
            let mut mac = SensitiveOutputElements::new(environment, &mac_output)?;
            let actual_len = core::fast::encrypt_into(
                &encryption_key,
                &mac_key,
                &iv,
                &plaintext,
                ciphertext.as_bytes_mut(),
                mac.as_bytes_mut(),
            )?;
            if actual_len != ciphertext_len {
                return Err(NativeErrorCode::Internal);
            }
            ciphertext.commit_success()?;
            mac.commit_success()?;
            Ok(actual_len)
        },
    )
}

/// Authenticates and decrypts AES-CBC/HMAC data into a caller-owned JVM array.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_artemchep_keyguard_nativecrypto_NativeCryptoJni_aesCbcPkcs7HmacSha256Decrypt(
    environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    encryption_key: JByteArray<'_>,
    mac_key: JByteArray<'_>,
    iv: JByteArray<'_>,
    ciphertext: JByteArray<'_>,
    expected_mac: JByteArray<'_>,
    plaintext_output: JByteArray<'_>,
) -> jlong {
    fast_array_boundary(environment, &[&plaintext_output], |environment| {
        ensure_fast_array_ownership(
            environment,
            &[&plaintext_output],
            &[&encryption_key, &mac_key, &iv, &ciphertext, &expected_mac],
        )?;
        let encryption_key = read_java_bytes(environment, &encryption_key)?;
        let mac_key = read_java_bytes(environment, &mac_key)?;
        let iv = read_java_bytes(environment, &iv)?;
        let ciphertext = read_java_bytes(environment, &ciphertext)?;
        let expected_mac = read_java_bytes(environment, &expected_mac)?;
        let plaintext_capacity = java_array_len(environment, &plaintext_output)?;
        if plaintext_capacity != ciphertext.len() {
            return Err(NativeErrorCode::InvalidArgument);
        }

        let mut plaintext = SensitiveOutputElements::new(environment, &plaintext_output)?;
        let actual_len = core::fast::decrypt_into(
            &encryption_key,
            &mac_key,
            &iv,
            &ciphertext,
            &expected_mac,
            plaintext.as_bytes_mut(),
        )?;
        if actual_len > plaintext_capacity {
            return Err(NativeErrorCode::Internal);
        }
        plaintext.commit_success()?;
        Ok(actual_len)
    })
}

fn with_byte_array(
    environment: EnvUnowned<'_>,
    input: JByteArray<'_>,
    operation: &'static str,
    maximum_input_len: usize,
    function: impl FnOnce(&[u8]) -> Vec<u8>,
) -> jbyteArray {
    byte_array_boundary(environment, operation, |environment| {
        let length = match input.len(environment) {
            Ok(length) => length,
            Err(_) => return core::internal_response(operation),
        };
        if length > maximum_input_len {
            return core::resource_limit_response(operation);
        }
        let input = match environment.convert_byte_array(input) {
            Ok(input) => input,
            Err(_) => return core::internal_response(operation),
        };
        let input = Zeroizing::new(input);
        function(&input)
    })
}

fn without_input(
    environment: EnvUnowned<'_>,
    operation: &'static str,
    function: impl FnOnce() -> Vec<u8>,
) -> jbyteArray {
    byte_array_boundary(environment, operation, |_| function())
}

/// Runs `body` with the environment of the current native method call. Panics
/// are not caught here, so callers run it inside their own unwind boundary.
///
/// This mirrors `keyguard_ffi::with_java_env`. The exact-pinned crypto
/// workspace is a dependency leaf and does not depend on `keyguard-ffi`, so
/// keep the two in sync across jni upgrades.
fn with_env<'local, R>(
    environment: &mut EnvUnowned<'local>,
    body: impl FnOnce(&mut Env<'local>) -> R,
) -> R {
    let outcome = environment
        .with_env_no_catch(|environment| Ok::<_, jni::errors::Error>(body(environment)))
        .into_outcome();
    match outcome {
        Outcome::Ok(value) => value,
        Outcome::Err(_) | Outcome::Panic(_) => {
            unreachable!("the body cannot fail, and panics are not caught")
        }
    }
}

fn byte_array_boundary(
    mut environment: EnvUnowned<'_>,
    operation: &'static str,
    function: impl FnOnce(&mut Env<'_>) -> Vec<u8>,
) -> jbyteArray {
    match std::panic::catch_unwind(AssertUnwindSafe(|| {
        core::install_redacting_panic_hook();
        with_env(&mut environment, |environment| {
            let response = function(environment);
            return_response(environment, response)
        })
    })) {
        Ok(output) => output,
        Err(_) => std::panic::catch_unwind(AssertUnwindSafe(|| {
            with_env(&mut environment, |environment| {
                return_response(environment, core::panic_response(operation))
            })
        }))
        .unwrap_or_else(|_| {
            std::panic::catch_unwind(AssertUnwindSafe(|| {
                with_env(&mut environment, throw_bridge_failure)
            }))
            .unwrap_or(ptr::null_mut())
        }),
    }
}

fn fast_array_boundary(
    mut environment: EnvUnowned<'_>,
    outputs: &[&JByteArray<'_>],
    function: impl FnOnce(&mut Env<'_>) -> Result<usize, NativeErrorCode>,
) -> jlong {
    let result = std::panic::catch_unwind(AssertUnwindSafe(|| {
        core::install_redacting_panic_hook();
        with_env(&mut environment, function)
    }));
    match result {
        Ok(Ok(output_len)) => pack_fast_result(NativeErrorCode::Ok, output_len),
        Ok(Err(code)) => {
            if clear_outputs(&mut environment, outputs).is_err() {
                pack_fast_result(NativeErrorCode::Internal, 0)
            } else {
                pack_fast_result(normalize_fast_error(code), 0)
            }
        }
        Err(_) => {
            if clear_outputs(&mut environment, outputs).is_err() {
                pack_fast_result(NativeErrorCode::Internal, 0)
            } else {
                pack_fast_result(NativeErrorCode::Panic, 0)
            }
        }
    }
}

fn clear_outputs(
    environment: &mut EnvUnowned<'_>,
    outputs: &[&JByteArray<'_>],
) -> Result<(), NativeErrorCode> {
    std::panic::catch_unwind(AssertUnwindSafe(|| {
        with_env(environment, |environment| {
            clear_java_arrays(environment, outputs)
        })
    }))
    .unwrap_or(Err(NativeErrorCode::Internal))
}

fn scalar_boundary(function: impl FnOnce() -> Result<i32, NativeErrorCode>) -> jlong {
    let result = std::panic::catch_unwind(AssertUnwindSafe(|| {
        core::install_redacting_panic_hook();
        function()
    }));
    match result {
        Ok(Ok(value)) => pack_int_result(NativeErrorCode::Ok, value),
        Ok(Err(code)) => pack_int_result(normalize_fast_error(code), 0),
        Err(_) => pack_int_result(NativeErrorCode::Panic, 0),
    }
}

fn read_java_bytes(
    environment: &Env<'_>,
    input: &JByteArray<'_>,
) -> Result<Zeroizing<Vec<u8>>, NativeErrorCode> {
    environment
        .convert_byte_array(input)
        .map(Zeroizing::new)
        .map_err(|_| NativeErrorCode::Internal)
}

fn java_array_len(environment: &Env<'_>, input: &JByteArray<'_>) -> Result<usize, NativeErrorCode> {
    input
        .len(environment)
        .map_err(|_| NativeErrorCode::Internal)
}

fn encrypted_size(plaintext_len: usize) -> Result<usize, NativeErrorCode> {
    let length = plaintext_len
        .checked_div(AES_BLOCK_BYTES)
        .and_then(|blocks| blocks.checked_add(1))
        .and_then(|blocks| blocks.checked_mul(AES_BLOCK_BYTES))
        .ok_or(NativeErrorCode::ResourceLimit)?;
    if length > jint::MAX as usize {
        Err(NativeErrorCode::ResourceLimit)
    } else {
        Ok(length)
    }
}

fn ensure_fast_array_ownership(
    environment: &Env<'_>,
    outputs: &[&JByteArray<'_>],
    inputs: &[&JByteArray<'_>],
) -> Result<(), NativeErrorCode> {
    if outputs
        .iter()
        .chain(inputs.iter())
        .any(|array| array.is_null())
    {
        return Err(NativeErrorCode::InvalidArgument);
    }

    for (index, output) in outputs.iter().enumerate() {
        for other_output in outputs.iter().skip(index + 1) {
            if environment
                .is_same_object(output, other_output)
                .map_err(|_| NativeErrorCode::Internal)?
            {
                return Err(NativeErrorCode::InvalidArgument);
            }
        }
        for input in inputs {
            if environment
                .is_same_object(output, input)
                .map_err(|_| NativeErrorCode::Internal)?
            {
                return Err(NativeErrorCode::InvalidArgument);
            }
        }
    }
    Ok(())
}

struct SensitiveOutputElements<'local, 'array> {
    /// Taken only when a committed copy is discarded.
    inner: Option<AutoElements<'local, jbyte, &'array JByteArray<'local>>>,
    committed: bool,
}

impl<'local, 'array> SensitiveOutputElements<'local, 'array> {
    fn new(
        environment: &Env<'_>,
        output: &'array JByteArray<'local>,
    ) -> Result<Self, NativeErrorCode> {
        // SAFETY: the fast JNI entry points verify that outputs are distinct
        // from every simultaneously acquired input/output array. Kotlin owns
        // these caller-allocated arrays for the duration of this native call.
        let inner = unsafe { output.get_elements(environment, ReleaseMode::CopyBack) }
            .map_err(|_| NativeErrorCode::Internal)?;
        Ok(Self {
            inner: Some(inner),
            committed: false,
        })
    }

    fn as_bytes_mut(&mut self) -> &mut [u8] {
        self.inner
            .as_deref_mut()
            .map(jbyte_as_u8_slice_mut)
            .unwrap_or_default()
    }

    fn commit_success(&mut self) -> Result<(), NativeErrorCode> {
        let inner = self.inner.as_mut().ok_or(NativeErrorCode::Internal)?;
        inner.commit().map_err(|_| NativeErrorCode::Internal)?;
        if inner.is_copy() {
            inner[..].zeroize();
            if let Some(inner) = self.inner.take() {
                inner.discard();
            }
        }
        self.committed = true;
        Ok(())
    }
}

impl Drop for SensitiveOutputElements<'_, '_> {
    fn drop(&mut self) {
        if !self.committed
            && let Some(inner) = &mut self.inner
        {
            // `CopyBack` propagates this wipe to Java for both copied and
            // directly pinned array elements before the outer boundary returns.
            inner[..].zeroize();
        }
    }
}

fn clear_java_arrays(
    environment: &Env<'_>,
    outputs: &[&JByteArray<'_>],
) -> Result<(), NativeErrorCode> {
    // A null output holds nothing to wipe, and `ensure_fast_array_ownership`
    // already reported it as an invalid argument.
    for output in outputs.iter().filter(|output| !output.is_null()) {
        let length = java_array_len(environment, output)?;
        let mut offset = 0;
        while offset < length {
            let chunk_len = (length - offset).min(ZERO_CHUNK.len());
            let start = i32::try_from(offset).map_err(|_| NativeErrorCode::Internal)?;
            output
                .set_region(environment, start, &ZERO_CHUNK[..chunk_len])
                .map_err(|_| NativeErrorCode::Internal)?;
            offset += chunk_len;
        }
    }
    Ok(())
}

fn jbyte_as_u8_slice_mut(value: &mut [jbyte]) -> &mut [u8] {
    // SAFETY: JNI `jbyte` and `u8` are one-byte integer types with identical
    // alignment. The returned slice is the only mutable view for this borrow.
    unsafe { std::slice::from_raw_parts_mut(value.as_mut_ptr().cast(), value.len()) }
}

const fn normalize_fast_error(code: NativeErrorCode) -> NativeErrorCode {
    if matches!(code, NativeErrorCode::Ok) {
        NativeErrorCode::Internal
    } else {
        code
    }
}

fn pack_fast_result(code: NativeErrorCode, output_len: usize) -> jlong {
    let output_len = match u32::try_from(output_len) {
        Ok(output_len) => output_len,
        Err(_) => return pack_fast_result(NativeErrorCode::Internal, 0),
    };
    let status = u64::from(code as u32);
    ((status << 32) | u64::from(output_len)) as jlong
}

fn pack_int_result(code: NativeErrorCode, value: i32) -> jlong {
    let status = u64::from(code as u32);
    ((status << 32) | u64::from(value as u32)) as jlong
}

fn return_response(environment: &mut Env<'_>, response: Vec<u8>) -> jbyteArray {
    let response = Zeroizing::new(response);
    match environment.byte_array_from_slice(&response) {
        Ok(output) => output.into_raw(),
        Err(_) => throw_bridge_failure(environment),
    }
}

fn throw_bridge_failure(environment: &mut Env<'_>) -> jbyteArray {
    let _ = environment.throw_new(
        jni_str!("java/lang/IllegalStateException"),
        jni_str!("native crypto JNI bridge failed"),
    );
    ptr::null_mut()
}

#[cfg(test)]
mod tests {
    use super::*;
    use keyguard_crypto_core::protocol::{NativeErrorCode, NativeResponse};
    use prost::Message;

    #[test]
    fn panic_payload_is_replaced_by_typed_response() {
        core::install_redacting_panic_hook();
        let response = std::panic::catch_unwind(AssertUnwindSafe(|| -> Vec<u8> {
            panic!("test-only secret-like panic payload")
        }))
        .unwrap_or_else(|_| core::panic_response("jni_test"));
        let response = NativeResponse::decode(response.as_slice()).expect("response must decode");
        assert_eq!(
            response.status.map(|status| status.code),
            Some(NativeErrorCode::Panic as i32)
        );
    }

    #[test]
    fn fast_result_packs_stable_status_and_output_length() {
        let packed = pack_fast_result(NativeErrorCode::AuthenticationFailed, 1234) as u64;
        assert_eq!(
            (packed >> 32) as u32,
            NativeErrorCode::AuthenticationFailed as u32
        );
        assert_eq!(packed as u32, 1234);
    }

    #[test]
    fn int_result_preserves_signed_value_bits() {
        let packed = pack_int_result(NativeErrorCode::Ok, i32::MIN) as u64;
        assert_eq!((packed >> 32) as u32, NativeErrorCode::Ok as u32);
        assert_eq!(packed as u32 as i32, i32::MIN);

        let failure = scalar_boundary(|| Err(NativeErrorCode::CryptoFailure)) as u64;
        assert_eq!(
            (failure >> 32) as u32,
            NativeErrorCode::CryptoFailure as u32
        );
        assert_eq!(failure as u32, 0);
    }
}
