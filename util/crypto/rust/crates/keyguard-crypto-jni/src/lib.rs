//! JNI bridge for `com.artemchep.keyguard.nativecrypto.NativeCryptoJni`.

use std::{panic::AssertUnwindSafe, ptr};

use jni::{
    JNIEnv,
    objects::{AutoElements, JByteArray, JObject, ReleaseMode},
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
    _environment: JNIEnv<'_>,
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
    _environment: JNIEnv<'_>,
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
    _environment: JNIEnv<'_>,
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
    environment: JNIEnv<'_>,
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
    environment: JNIEnv<'_>,
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
    environment: JNIEnv<'_>,
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
    environment: JNIEnv<'_>,
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
    environment: JNIEnv<'_>,
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
    environment: JNIEnv<'_>,
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
    environment: JNIEnv<'_>,
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
    environment: JNIEnv<'_>,
    input: JByteArray<'_>,
    operation: &'static str,
    maximum_input_len: usize,
    function: impl FnOnce(&[u8]) -> Vec<u8>,
) -> jbyteArray {
    byte_array_boundary(environment, operation, |environment| {
        let length = match environment.get_array_length(&input) {
            Ok(length) => length,
            Err(_) => return core::internal_response(operation),
        };
        if usize::try_from(length).map_or(true, |length| length > maximum_input_len) {
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
    environment: JNIEnv<'_>,
    operation: &'static str,
    function: impl FnOnce() -> Vec<u8>,
) -> jbyteArray {
    byte_array_boundary(environment, operation, |_| function())
}

fn byte_array_boundary(
    mut environment: JNIEnv<'_>,
    operation: &'static str,
    function: impl FnOnce(&mut JNIEnv<'_>) -> Vec<u8>,
) -> jbyteArray {
    match std::panic::catch_unwind(AssertUnwindSafe(|| {
        core::install_redacting_panic_hook();
        let response = function(&mut environment);
        return_response(&mut environment, response)
    })) {
        Ok(output) => output,
        Err(_) => std::panic::catch_unwind(AssertUnwindSafe(|| {
            return_response(&mut environment, core::panic_response(operation))
        }))
        .unwrap_or_else(|_| {
            std::panic::catch_unwind(AssertUnwindSafe(|| throw_bridge_failure(&mut environment)))
                .unwrap_or(ptr::null_mut())
        }),
    }
}

fn fast_array_boundary(
    mut environment: JNIEnv<'_>,
    outputs: &[&JByteArray<'_>],
    function: impl FnOnce(&mut JNIEnv<'_>) -> Result<usize, NativeErrorCode>,
) -> jlong {
    let result = std::panic::catch_unwind(AssertUnwindSafe(|| {
        core::install_redacting_panic_hook();
        function(&mut environment)
    }));
    match result {
        Ok(Ok(output_len)) => pack_fast_result(NativeErrorCode::Ok, output_len),
        Ok(Err(code)) => {
            if clear_java_arrays(&environment, outputs).is_err() {
                pack_fast_result(NativeErrorCode::Internal, 0)
            } else {
                pack_fast_result(normalize_fast_error(code), 0)
            }
        }
        Err(_) => {
            if clear_java_arrays(&environment, outputs).is_err() {
                pack_fast_result(NativeErrorCode::Internal, 0)
            } else {
                pack_fast_result(NativeErrorCode::Panic, 0)
            }
        }
    }
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
    environment: &JNIEnv<'_>,
    input: &JByteArray<'_>,
) -> Result<Zeroizing<Vec<u8>>, NativeErrorCode> {
    environment
        .convert_byte_array(input)
        .map(Zeroizing::new)
        .map_err(|_| NativeErrorCode::Internal)
}

fn java_array_len(
    environment: &JNIEnv<'_>,
    input: &JByteArray<'_>,
) -> Result<usize, NativeErrorCode> {
    let length = environment
        .get_array_length(input)
        .map_err(|_| NativeErrorCode::Internal)?;
    usize::try_from(length).map_err(|_| NativeErrorCode::Internal)
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
    environment: &JNIEnv<'_>,
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

struct SensitiveOutputElements<'local, 'other_local, 'array> {
    inner: AutoElements<'local, 'other_local, 'array, jbyte>,
    committed: bool,
}

impl<'local, 'other_local, 'array> SensitiveOutputElements<'local, 'other_local, 'array> {
    fn new(
        environment: &mut JNIEnv<'local>,
        output: &'array JByteArray<'other_local>,
    ) -> Result<Self, NativeErrorCode> {
        // SAFETY: the fast JNI entry points verify that outputs are distinct
        // from every simultaneously acquired input/output array. Kotlin owns
        // these caller-allocated arrays for the duration of this native call.
        let inner = unsafe { environment.get_array_elements(output, ReleaseMode::CopyBack) }
            .map_err(|_| NativeErrorCode::Internal)?;
        Ok(Self {
            inner,
            committed: false,
        })
    }

    fn as_bytes_mut(&mut self) -> &mut [u8] {
        jbyte_as_u8_slice_mut(&mut self.inner)
    }

    fn commit_success(&mut self) -> Result<(), NativeErrorCode> {
        self.inner.commit().map_err(|_| NativeErrorCode::Internal)?;
        if self.inner.is_copy() {
            self.inner[..].zeroize();
            self.inner.discard();
        }
        self.committed = true;
        Ok(())
    }
}

impl Drop for SensitiveOutputElements<'_, '_, '_> {
    fn drop(&mut self) {
        if !self.committed {
            // `CopyBack` propagates this wipe to Java for both copied and
            // directly pinned array elements before the outer boundary returns.
            self.inner[..].zeroize();
        }
    }
}

fn clear_java_arrays(
    environment: &JNIEnv<'_>,
    outputs: &[&JByteArray<'_>],
) -> Result<(), NativeErrorCode> {
    for output in outputs {
        let length = java_array_len(environment, output)?;
        let mut offset = 0;
        while offset < length {
            let chunk_len = (length - offset).min(ZERO_CHUNK.len());
            let start = i32::try_from(offset).map_err(|_| NativeErrorCode::Internal)?;
            environment
                .set_byte_array_region(output, start, &ZERO_CHUNK[..chunk_len])
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

fn return_response(environment: &mut JNIEnv<'_>, response: Vec<u8>) -> jbyteArray {
    let response = Zeroizing::new(response);
    match environment.byte_array_from_slice(&response) {
        Ok(output) => output.into_raw(),
        Err(_) => throw_bridge_failure(environment),
    }
}

fn throw_bridge_failure(environment: &mut JNIEnv<'_>) -> jbyteArray {
    let _ = environment.throw_new(
        "java/lang/IllegalStateException",
        "native crypto JNI bridge failed",
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
