//! JNI adapter for `com.artemchep.keyguard.util.zxcvbn.NativeZxcvbnJni`.
//!
//! Each function unmarshals its Java arguments, delegates to
//! [`keyguard_zxcvbn_core`], and returns ABI v1 wire values shared with the C
//! adapter. Panics never cross the boundary: they are contained and reported
//! as a bridge failure with no password disclosure.

use jni::{
    JNIEnv,
    objects::{JLongArray, JObject, JObjectArray, JString},
    sys::{jint, jlong},
};
use keyguard_ffi::{PanicHook, flatten as unwrap, java_string_within};
use keyguard_zxcvbn_core::{
    MAX_PASSWORD_BYTES, MAX_USER_INPUT_BYTES, MAX_USER_INPUTS, ResultWire,
    abi::{pack_bridge_error, pack_bridge_input_too_long, pack_bridge_invalid_argument},
};
use zeroize::Zeroizing;

/// Unit tests keep Rust's default hook so a caught assertion still reports
/// its payload and source location in CI logs.
const PANIC_HOOK: PanicHook = if cfg!(test) {
    PanicHook::Keep
} else {
    PanicHook::Redact
};

fn contained<R>(body: impl FnOnce() -> R) -> Result<R, i64> {
    keyguard_ffi::contained(PANIC_HOOK, body)
}

/// Copies a nullable `java.lang.String[]` into zeroized owned strings, or
/// returns `None` when an element is over [`MAX_USER_INPUT_BYTES`].
///
/// A null array is the hot path's "no user inputs" and is equivalent to an
/// empty one. The element count is rejected before any element is read, and
/// each element is bounded before it is copied, so an absurd array never
/// causes a large allocation. An over-long element does not end the walk, so
/// a later invalid element is still reported.
fn java_string_array(
    environment: &mut JNIEnv<'_>,
    values: &JObjectArray<'_>,
) -> Result<Option<Vec<Zeroizing<String>>>, i64> {
    if values.is_null() {
        return Ok(Some(Vec::new()));
    }
    let length = environment
        .get_array_length(values)
        .map_err(|_| pack_bridge_invalid_argument())?;
    let length = usize::try_from(length).map_err(|_| pack_bridge_invalid_argument())?;
    if length > MAX_USER_INPUTS {
        return Err(pack_bridge_invalid_argument());
    }
    let mut inputs = Vec::with_capacity(length);
    let mut too_long = false;
    for index in 0..length {
        let index = jint::try_from(index).map_err(|_| pack_bridge_invalid_argument())?;
        let element = environment
            .get_object_array_element(values, index)
            .map_err(|_| pack_bridge_invalid_argument())?;
        let element = JString::from(element);
        match java_string_within(environment, &element, MAX_USER_INPUT_BYTES)? {
            Some(input) => inputs.push(input),
            None => too_long = true,
        }
    }
    Ok((!too_long).then_some(inputs))
}

/// Validates that the caller-allocated output array has the ABI v1 length.
fn java_result_array(environment: &JNIEnv<'_>, out: &JLongArray<'_>) -> Result<(), i64> {
    if out.is_null() {
        return Err(pack_bridge_invalid_argument());
    }
    let length = environment
        .get_array_length(out)
        .map_err(|_| pack_bridge_invalid_argument())?;
    if usize::try_from(length).ok() != Some(ResultWire::JNI_FIELD_COUNT) {
        return Err(pack_bridge_invalid_argument());
    }
    Ok(())
}

/// Returns the native function ABI version.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_zxcvbn_NativeZxcvbnJni_abiVersion(
    _environment: JNIEnv<'_>,
    _object: JObject<'_>,
) -> jint {
    contained(|| keyguard_zxcvbn_core::ABI_VERSION as jint).unwrap_or(0)
}

/// Estimates a password's strength into a caller-allocated 11-slot array.
///
/// Returns zero after filling `out`, or a packed failure that leaves `out`
/// untouched. `user_inputs` may be null, which means "no user inputs".
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_zxcvbn_NativeZxcvbnJni_estimate(
    mut environment: JNIEnv<'_>,
    _object: JObject<'_>,
    password: JString<'_>,
    user_inputs: JObjectArray<'_>,
    out: JLongArray<'_>,
) -> jlong {
    unwrap(contained(|| {
        // The output array is validated first: an estimate computed for a
        // caller that cannot receive it is wasted work on a hot path.
        java_result_array(&environment, &out)?;
        let user_inputs = java_string_array(&mut environment, &user_inputs)?;
        let password = java_string_within(&environment, &password, MAX_PASSWORD_BYTES)?;
        // Sizes are judged only after every argument was read, so an invalid
        // argument still wins, as it did when only the core checked sizes.
        let (Some(user_inputs), Some(password)) = (user_inputs, password) else {
            return Err(pack_bridge_input_too_long());
        };
        let borrowed: Vec<&str> = user_inputs.iter().map(|input| input.as_str()).collect();
        let wire = keyguard_zxcvbn_core::estimate(password.as_str(), &borrowed)
            .map_err(pack_bridge_error)?;
        environment
            .set_long_array_region(&out, 0, &wire.as_jni_fields())
            .map_err(|_| pack_bridge_invalid_argument())?;
        Ok(0)
    }))
}

#[cfg(test)]
mod tests {
    use keyguard_zxcvbn_core::{BridgeError, abi};

    use super::*;

    #[test]
    fn the_scalar_boundary_forwards_both_result_arms_unchanged() {
        assert_eq!(unwrap(Ok(Ok(0))), 0);
        assert_eq!(
            unwrap(Ok(Err(pack_bridge_invalid_argument()))),
            pack_bridge_invalid_argument()
        );
        assert_eq!(
            unwrap(Err(abi::pack_bridge_panic())),
            abi::pack_bridge_panic()
        );
    }

    #[test]
    fn core_errors_pack_into_the_documented_bridge_failures() {
        assert_eq!(
            pack_bridge_error(BridgeError::InputTooLong),
            pack_bridge_input_too_long()
        );
        assert_eq!(
            pack_bridge_error(BridgeError::InvalidArgument),
            pack_bridge_invalid_argument()
        );
    }

    #[test]
    fn the_output_array_length_matches_the_wire_field_count() {
        assert_eq!(ResultWire::JNI_FIELD_COUNT, 11);
    }
}
