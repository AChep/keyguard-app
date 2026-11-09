//! JNI adapter for `com.artemchep.keyguard.util.webrtc.NativeWebrtcJni`.
//!
//! Each export unmarshals its Java arguments, delegates to
//! [`keyguard_webrtc_core`], and returns the ABI v1 scalar shared with the C
//! adapter: a handle, a length or zero on success, a packed failure
//! otherwise. Panics never cross the boundary.
//!
//! - Arguments and output arrays are checked before anything blocks, so a call
//!   with a bad output array never consumes an event or applies an SDP.
//! - No JNI borrow is held while a call blocks; output is copied in after.
//! - No Java exception is left pending: one raised by a JNI call is cleared and
//!   reported as a code, because a pending exception aborts the VM under
//!   `-Xcheck:jni` once the bridge builds its return value.

use std::time::Duration;

use jni::{
    Env, EnvUnowned,
    objects::{JByteArray, JObject, JString},
    sys::{jint, jlong},
};
use keyguard_ffi::{
    PanicHook, flatten as unwrap, java_byte_range, java_string_within, jbytes, jbytes_mut,
    with_java_env,
};
use keyguard_webrtc_core::{
    MAX_CANDIDATE_BYTES, MAX_CONFIG_BYTES, MAX_EVENT_BYTES, MAX_LABEL_BYTES, MAX_MESSAGE_BYTES,
    MAX_SDP_BYTES, WebrtcError, check_capacity, handle_from_abi, handle_to_abi, length_to_abi,
    unit_to_abi,
};
use zeroize::Zeroizing;

/// Unit tests keep Rust's default hook so a caught assertion still reports
/// its payload and source location in CI logs.
const PANIC_HOOK: PanicHook = if cfg!(test) {
    PanicHook::Keep
} else {
    PanicHook::Redact
};

/// Runs a scalar export behind the panic boundary.
fn scalar(body: impl FnOnce() -> i64) -> i64 {
    unwrap(keyguard_ffi::contained(PANIC_HOOK, || Ok(body())))
}

/// Runs an export that reads or writes Java objects. A Java exception that is
/// still pending when `body` returns is cleared.
fn with_environment(
    environment: &mut EnvUnowned<'_>,
    body: impl FnOnce(&mut Env<'_>) -> Result<i64, WebrtcError>,
) -> i64 {
    scalar(|| {
        with_java_env(environment, |environment| {
            let result = body(environment);
            if environment.exception_check() {
                environment.exception_clear();
            }
            result.unwrap_or_else(WebrtcError::pack)
        })
    })
}

/// Copies a string of at most `max_bytes` UTF-8 bytes, wiped once dropped: a
/// configuration carries TURN credentials.
fn string_within(
    environment: &Env<'_>,
    value: &JString<'_>,
    max_bytes: usize,
) -> Result<Zeroizing<String>, WebrtcError> {
    java_string_within(environment, value, max_bytes)
        .map_err(|_| WebrtcError::InvalidArgument)?
        .ok_or(WebrtcError::InputTooLong)
}

/// Returns the length of a non-null output array.
fn output_capacity(environment: &Env<'_>, out: &JByteArray<'_>) -> Result<usize, WebrtcError> {
    out.len(environment)
        .map_err(|_| WebrtcError::InvalidArgument)
}

/// Copies `bytes` to the start of `out`, which the caller sized.
fn write_output(
    environment: &Env<'_>,
    out: &JByteArray<'_>,
    offset: usize,
    bytes: &[u8],
) -> Result<(), WebrtcError> {
    let offset = jint::try_from(offset).map_err(|_| WebrtcError::Internal)?;
    out.set_region(environment, offset, jbytes(bytes))
        .map_err(|_| WebrtcError::Internal)
}

/// Returns the native function ABI version.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_webrtc_NativeWebrtcJni_abiVersion(
    _environment: EnvUnowned<'_>,
    _object: JObject<'_>,
) -> jint {
    keyguard_ffi::contained(PANIC_HOOK, || keyguard_webrtc_core::ABI_VERSION as jint).unwrap_or(0)
}

/// Creates a runtime: its handle, or a packed failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_webrtc_NativeWebrtcJni_runtimeNew(
    _environment: EnvUnowned<'_>,
    _object: JObject<'_>,
) -> jlong {
    new_runtime()
}

fn new_runtime() -> jlong {
    scalar(|| handle_to_abi(keyguard_webrtc_core::runtime_new()))
}

/// Closes every peer of the runtime and stops it. Idempotent.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_webrtc_NativeWebrtcJni_runtimeShutdown(
    _environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    runtime: jlong,
) -> jlong {
    shutdown_runtime(runtime)
}

fn shutdown_runtime(runtime: jlong) -> jlong {
    scalar(|| {
        unit_to_abi(handle_from_abi(runtime).and_then(keyguard_webrtc_core::runtime_shutdown))
    })
}

/// Creates a peer from the JSON configuration: its handle, or a packed failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_webrtc_NativeWebrtcJni_peerNew(
    mut environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    runtime: jlong,
    config_json: JString<'_>,
) -> jlong {
    with_environment(&mut environment, |environment| {
        let runtime = handle_from_abi(runtime)?;
        let config = string_within(environment, &config_json, MAX_CONFIG_BYTES)?;
        Ok(handle_to_abi(keyguard_webrtc_core::peer_new(
            runtime, &config,
        )))
    })
}

fn create_description(
    environment: &mut EnvUnowned<'_>,
    peer: jlong,
    out: &JByteArray<'_>,
    create: fn(u64) -> Result<String, WebrtcError>,
) -> jlong {
    with_environment(environment, |environment| {
        let peer = handle_from_abi(peer)?;
        check_capacity(output_capacity(environment, out)?, MAX_SDP_BYTES)?;
        let sdp = create(peer)?;
        write_output(environment, out, 0, sdp.as_bytes())?;
        Ok(length_to_abi(Ok(sdp.len())))
    })
}

/// Creates and applies an offer, writing its UTF-8 SDP to `out`, which holds
/// at least 64 KiB. Returns the SDP length.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_webrtc_NativeWebrtcJni_peerCreateOffer(
    mut environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    peer: jlong,
    out: JByteArray<'_>,
) -> jlong {
    create_description(
        &mut environment,
        peer,
        &out,
        keyguard_webrtc_core::peer_create_offer,
    )
}

/// Creates and applies an answer; as `peerCreateOffer`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_webrtc_NativeWebrtcJni_peerCreateAnswer(
    mut environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    peer: jlong,
    out: JByteArray<'_>,
) -> jlong {
    create_description(
        &mut environment,
        peer,
        &out,
        keyguard_webrtc_core::peer_create_answer,
    )
}

/// Applies the remote description; `kind` is 0 for an offer, 1 for an answer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_webrtc_NativeWebrtcJni_peerSetRemote(
    mut environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    peer: jlong,
    kind: jint,
    sdp: JString<'_>,
) -> jlong {
    with_environment(&mut environment, |environment| {
        let peer = handle_from_abi(peer)?;
        let kind = u32::try_from(kind).map_err(|_| WebrtcError::InvalidArgument)?;
        let sdp = string_within(environment, &sdp, MAX_SDP_BYTES)?;
        Ok(unit_to_abi(keyguard_webrtc_core::peer_set_remote(
            peer, kind, &sdp,
        )))
    })
}

/// Adds a remote ICE candidate given as JSON.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_webrtc_NativeWebrtcJni_peerAddIceCandidate(
    mut environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    peer: jlong,
    candidate_json: JString<'_>,
) -> jlong {
    with_environment(&mut environment, |environment| {
        let peer = handle_from_abi(peer)?;
        let candidate = string_within(environment, &candidate_json, MAX_CANDIDATE_BYTES)?;
        Ok(unit_to_abi(keyguard_webrtc_core::peer_add_ice_candidate(
            peer, &candidate,
        )))
    })
}

/// Creates the peer's ordered, reliable data channel.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_webrtc_NativeWebrtcJni_peerCreateChannel(
    mut environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    peer: jlong,
    label: JString<'_>,
) -> jlong {
    with_environment(&mut environment, |environment| {
        let peer = handle_from_abi(peer)?;
        let label = string_within(environment, &label, MAX_LABEL_BYTES)?;
        Ok(unit_to_abi(keyguard_webrtc_core::peer_create_channel(
            peer, &label,
        )))
    })
}

/// Queues `data[offset..offset + length]` on the channel: zero, or the
/// packed busy failure while more than 1 MiB is buffered.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_webrtc_NativeWebrtcJni_channelSend(
    mut environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    peer: jlong,
    data: JByteArray<'_>,
    offset: jint,
    length: jint,
) -> jlong {
    with_environment(&mut environment, |environment| {
        let peer = handle_from_abi(peer)?;
        let length = java_byte_range(environment, &data, offset, length)
            .map_err(|_| WebrtcError::InvalidArgument)?;
        // Rejected before copying, so an oversized array is never duplicated.
        if length > MAX_MESSAGE_BYTES {
            return Err(WebrtcError::InputTooLong);
        }
        let mut bytes = vec![0_u8; length];
        if length != 0 {
            data.get_region(environment, offset, jbytes_mut(&mut bytes))
                .map_err(|_| WebrtcError::InvalidArgument)?;
        }
        Ok(unit_to_abi(keyguard_webrtc_core::channel_send(
            peer, &bytes,
        )))
    })
}

/// Returns the bytes buffered on the channel.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_webrtc_NativeWebrtcJni_channelBufferedAmount(
    _environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    peer: jlong,
) -> jlong {
    buffered_amount(peer)
}

fn buffered_amount(peer: jlong) -> jlong {
    scalar(|| {
        length_to_abi(handle_from_abi(peer).and_then(keyguard_webrtc_core::channel_buffered_amount))
    })
}

/// Waits up to `timeout_ms` for the next event and writes it to `out`, which
/// holds at least 65537 bytes. Returns the event length, zero on timeout, or
/// the packed closed failure once the peer is closed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_webrtc_NativeWebrtcJni_peerNextEvent(
    mut environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    peer: jlong,
    out: JByteArray<'_>,
    timeout_ms: jint,
) -> jlong {
    with_environment(&mut environment, |environment| {
        let peer = handle_from_abi(peer)?;
        let timeout = u64::try_from(timeout_ms).map_err(|_| WebrtcError::InvalidArgument)?;
        check_capacity(output_capacity(environment, &out)?, MAX_EVENT_BYTES)?;
        let Some(event) =
            keyguard_webrtc_core::peer_next_event(peer, Duration::from_millis(timeout))?
        else {
            return Ok(0);
        };
        let length =
            event.write_parts(|offset, bytes| write_output(environment, &out, offset, bytes))?;
        Ok(length_to_abi(Ok(length)))
    })
}

/// Closes the peer and wakes every blocked call. Idempotent.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_artemchep_keyguard_util_webrtc_NativeWebrtcJni_peerClose(
    _environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    peer: jlong,
) -> jlong {
    close_peer(peer)
}

fn close_peer(peer: jlong) -> jlong {
    scalar(|| unit_to_abi(handle_from_abi(peer).and_then(keyguard_webrtc_core::peer_close)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_scalar_boundary_contains_panics() {
        assert_eq!(scalar(|| 7), 7);
        assert_eq!(
            scalar(|| panic!("boom")),
            WebrtcError::Panic.pack(),
            "a panic must become the bridge panic failure"
        );
    }

    #[test]
    fn handle_exports_reject_values_that_are_not_handles() {
        for handle in [-1, 0, 1, jlong::MAX] {
            assert_eq!(close_peer(handle), WebrtcError::InvalidHandle.pack());
            assert_eq!(shutdown_runtime(handle), WebrtcError::InvalidHandle.pack());
            assert_eq!(buffered_amount(handle), WebrtcError::InvalidHandle.pack());
        }
    }

    #[test]
    fn a_runtime_round_trips_through_the_scalar_exports() {
        let runtime = new_runtime();
        assert!(runtime > 0, "{runtime:#x} must be a handle");
        assert_eq!(shutdown_runtime(runtime), 0);
        assert_eq!(shutdown_runtime(runtime), 0, "shutdown must be idempotent");
        // A runtime handle never names a peer.
        assert_eq!(close_peer(runtime), WebrtcError::InvalidHandle.pack());
    }
}
