//! Stable C ABI for Keyguard WebRTC data channels.
//!
//! Each function unmarshals its raw arguments, delegates to
//! [`keyguard_webrtc_core`], and returns the ABI v1 scalar shared with the JNI
//! adapter: a handle, a length or zero on success, a packed failure
//! otherwise. Panics never cross the boundary. `include/keyguard_webrtc.h`
//! documents the same contract for C callers.

#![deny(unsafe_op_in_unsafe_fn)]

use std::time::Duration;

use keyguard_ffi::{PanicHook, bytes_from_raw, bytes_from_raw_mut, string_from_raw};
use keyguard_webrtc_core::{
    MAX_CANDIDATE_BYTES, MAX_CONFIG_BYTES, MAX_EVENT_BYTES, MAX_LABEL_BYTES, MAX_MESSAGE_BYTES,
    MAX_SDP_BYTES, WebrtcError, check_capacity, handle_from_abi, handle_to_abi, length_to_abi,
};

/// Unit tests keep Rust's default hook so a caught assertion still reports
/// its payload and source location in CI logs.
const PANIC_HOOK: PanicHook = if cfg!(test) {
    PanicHook::Keep
} else {
    PanicHook::Redact
};

/// Runs an export behind the panic boundary.
fn scalar(body: impl FnOnce() -> Result<i64, WebrtcError>) -> i64 {
    keyguard_ffi::contained(PANIC_HOOK, || body().unwrap_or_else(WebrtcError::pack))
        .unwrap_or_else(|panic| panic)
}

/// Borrows a UTF-8 string of at most `max_bytes`.
///
/// # Safety
///
/// As [`string_from_raw`] for the returned `'a`.
unsafe fn string_within<'a>(
    pointer: *const u8,
    length: usize,
    max_bytes: usize,
) -> Result<&'a str, WebrtcError> {
    // Checked first, so an oversized buffer is never scanned.
    if length > max_bytes {
        return Err(WebrtcError::InputTooLong);
    }
    // SAFETY: The caller contract is forwarded from this function's own.
    unsafe { string_from_raw(pointer, length) }.map_err(|_| WebrtcError::InvalidArgument)
}

/// Borrows an output buffer of at least `minimum` bytes.
///
/// # Safety
///
/// As [`bytes_from_raw_mut`] for the returned `'a`.
unsafe fn output<'a>(
    pointer: *mut u8,
    length: usize,
    minimum: usize,
) -> Result<&'a mut [u8], WebrtcError> {
    check_capacity(length, minimum)?;
    // SAFETY: The caller contract is forwarded from this function's own.
    unsafe { bytes_from_raw_mut(pointer, length) }.map_err(|_| WebrtcError::InvalidArgument)
}

/// Copies `bytes` to the start of `out`.
fn write_output(out: &mut [u8], bytes: &[u8]) -> Result<i64, WebrtcError> {
    out.get_mut(..bytes.len())
        .ok_or(WebrtcError::Internal)?
        .copy_from_slice(bytes);
    Ok(length_to_abi(Ok(bytes.len())))
}

/// Returns the native function ABI version.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_webrtc_abi_version() -> u32 {
    keyguard_ffi::contained(PANIC_HOOK, || keyguard_webrtc_core::ABI_VERSION).unwrap_or(0)
}

/// Creates a runtime: its handle, or a packed failure.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_webrtc_runtime_new() -> i64 {
    scalar(|| Ok(handle_to_abi(keyguard_webrtc_core::runtime_new())))
}

/// Closes every peer of the runtime and stops it. Idempotent.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_webrtc_runtime_shutdown(runtime: i64) -> i64 {
    scalar(|| {
        keyguard_webrtc_core::runtime_shutdown(handle_from_abi(runtime)?)?;
        Ok(0)
    })
}

/// Creates a peer from the UTF-8 JSON configuration.
///
/// # Safety
///
/// `config_ptr`/`config_len` must describe readable bytes for the duration of
/// the call; the pointer may be null when the length is zero.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_webrtc_peer_new(
    runtime: i64,
    config_ptr: *const u8,
    config_len: usize,
) -> i64 {
    scalar(|| {
        let runtime = handle_from_abi(runtime)?;
        // SAFETY: The caller contract is forwarded; the view does not outlive this call.
        let config = unsafe { string_within(config_ptr, config_len, MAX_CONFIG_BYTES) }?;
        Ok(handle_to_abi(keyguard_webrtc_core::peer_new(
            runtime, config,
        )))
    })
}

/// # Safety
///
/// `out_ptr`/`out_len` must describe initialized, writable bytes for the
/// duration of the call.
unsafe fn create_description(
    peer: i64,
    out_ptr: *mut u8,
    out_len: usize,
    create: fn(u64) -> Result<String, WebrtcError>,
) -> i64 {
    scalar(|| {
        let peer = handle_from_abi(peer)?;
        // SAFETY: The caller contract is forwarded; the view does not outlive this call.
        let out = unsafe { output(out_ptr, out_len, MAX_SDP_BYTES) }?;
        let sdp = create(peer)?;
        write_output(out, sdp.as_bytes())
    })
}

/// Creates and applies an offer, writing its UTF-8 SDP to `out`. Returns the
/// SDP length.
///
/// # Safety
///
/// `out_ptr`/`out_len` must describe initialized, writable bytes for the
/// duration of the call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_webrtc_peer_create_offer(
    peer: i64,
    out_ptr: *mut u8,
    out_len: usize,
) -> i64 {
    // SAFETY: The caller contract is forwarded from this function's own.
    unsafe {
        create_description(
            peer,
            out_ptr,
            out_len,
            keyguard_webrtc_core::peer_create_offer,
        )
    }
}

/// Creates and applies an answer; as [`keyguard_webrtc_peer_create_offer`].
///
/// # Safety
///
/// `out_ptr`/`out_len` must describe initialized, writable bytes for the
/// duration of the call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_webrtc_peer_create_answer(
    peer: i64,
    out_ptr: *mut u8,
    out_len: usize,
) -> i64 {
    // SAFETY: The caller contract is forwarded from this function's own.
    unsafe {
        create_description(
            peer,
            out_ptr,
            out_len,
            keyguard_webrtc_core::peer_create_answer,
        )
    }
}

/// Applies the remote description; `kind` is 0 for an offer, 1 for an answer.
///
/// # Safety
///
/// `sdp_ptr`/`sdp_len` must describe readable bytes for the duration of the
/// call; the pointer may be null when the length is zero.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_webrtc_peer_set_remote(
    peer: i64,
    kind: u32,
    sdp_ptr: *const u8,
    sdp_len: usize,
) -> i64 {
    scalar(|| {
        let peer = handle_from_abi(peer)?;
        // SAFETY: The caller contract is forwarded; the view does not outlive this call.
        let sdp = unsafe { string_within(sdp_ptr, sdp_len, MAX_SDP_BYTES) }?;
        keyguard_webrtc_core::peer_set_remote(peer, kind, sdp)?;
        Ok(0)
    })
}

/// Adds a remote ICE candidate given as UTF-8 JSON.
///
/// # Safety
///
/// `json_ptr`/`json_len` must describe readable bytes for the duration of the
/// call; the pointer may be null when the length is zero.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_webrtc_peer_add_ice_candidate(
    peer: i64,
    json_ptr: *const u8,
    json_len: usize,
) -> i64 {
    scalar(|| {
        let peer = handle_from_abi(peer)?;
        // SAFETY: The caller contract is forwarded; the view does not outlive this call.
        let json = unsafe { string_within(json_ptr, json_len, MAX_CANDIDATE_BYTES) }?;
        keyguard_webrtc_core::peer_add_ice_candidate(peer, json)?;
        Ok(0)
    })
}

/// Creates the peer's ordered, reliable data channel.
///
/// # Safety
///
/// `label_ptr`/`label_len` must describe readable bytes for the duration of
/// the call; the pointer may be null when the length is zero.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_webrtc_peer_create_channel(
    peer: i64,
    label_ptr: *const u8,
    label_len: usize,
) -> i64 {
    scalar(|| {
        let peer = handle_from_abi(peer)?;
        // SAFETY: The caller contract is forwarded; the view does not outlive this call.
        let label = unsafe { string_within(label_ptr, label_len, MAX_LABEL_BYTES) }?;
        keyguard_webrtc_core::peer_create_channel(peer, label)?;
        Ok(0)
    })
}

/// Queues one message on the channel: zero, or the packed busy failure while
/// more than 1 MiB is buffered.
///
/// # Safety
///
/// `data_ptr`/`data_len` must describe readable bytes for the duration of the
/// call; the pointer may be null when the length is zero.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_webrtc_channel_send(
    peer: i64,
    data_ptr: *const u8,
    data_len: usize,
) -> i64 {
    scalar(|| {
        let peer = handle_from_abi(peer)?;
        if data_len > MAX_MESSAGE_BYTES {
            return Err(WebrtcError::InputTooLong);
        }
        // SAFETY: The caller contract is forwarded; the view does not outlive this call.
        let data = unsafe { bytes_from_raw(data_ptr, data_len) }
            .map_err(|_| WebrtcError::InvalidArgument)?;
        keyguard_webrtc_core::channel_send(peer, data)?;
        Ok(0)
    })
}

/// Returns the bytes buffered on the channel.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_webrtc_channel_buffered_amount(peer: i64) -> i64 {
    scalar(|| {
        let amount = keyguard_webrtc_core::channel_buffered_amount(handle_from_abi(peer)?)?;
        Ok(length_to_abi(Ok(amount)))
    })
}

/// Waits up to `timeout_ms` for the next event and writes it to `out`.
/// Returns the event length, zero on timeout, or the packed closed failure
/// once the peer is closed.
///
/// # Safety
///
/// `out_ptr`/`out_len` must describe initialized, writable bytes for the
/// duration of the call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn keyguard_webrtc_peer_next_event(
    peer: i64,
    out_ptr: *mut u8,
    out_len: usize,
    timeout_ms: u32,
) -> i64 {
    scalar(|| {
        let peer = handle_from_abi(peer)?;
        // SAFETY: The caller contract is forwarded; the view does not outlive this call.
        let out = unsafe { output(out_ptr, out_len, MAX_EVENT_BYTES) }?;
        let timeout = Duration::from_millis(u64::from(timeout_ms));
        let Some(event) = keyguard_webrtc_core::peer_next_event(peer, timeout)? else {
            return Ok(0);
        };
        let length = event.encode_into(out).ok_or(WebrtcError::Internal)?;
        Ok(length_to_abi(Ok(length)))
    })
}

/// Closes the peer and wakes every blocked call. Idempotent.
#[unsafe(no_mangle)]
pub extern "C" fn keyguard_webrtc_peer_close(peer: i64) -> i64 {
    scalar(|| {
        keyguard_webrtc_core::peer_close(handle_from_abi(peer)?)?;
        Ok(0)
    })
}

#[cfg(test)]
mod tests {
    use std::ptr;

    use super::*;

    #[test]
    fn the_abi_version_is_pinned_to_one() {
        assert_eq!(keyguard_webrtc_abi_version(), 1);
    }

    #[test]
    fn a_contained_panic_uses_the_bridge_panic_failure() {
        assert_eq!(scalar(|| panic!("boom")), WebrtcError::Panic.pack());
    }

    #[test]
    fn values_that_are_not_handles_are_invalid_handles() {
        let mut out = vec![0_u8; MAX_EVENT_BYTES];
        for handle in [-1, 0, 1, i64::MAX] {
            assert_eq!(
                keyguard_webrtc_peer_close(handle),
                WebrtcError::InvalidHandle.pack()
            );
            assert_eq!(
                keyguard_webrtc_runtime_shutdown(handle),
                WebrtcError::InvalidHandle.pack()
            );
            // SAFETY: The buffer is live and writable for its whole length.
            let packed =
                unsafe { keyguard_webrtc_peer_next_event(handle, out.as_mut_ptr(), out.len(), 0) };
            assert_eq!(packed, WebrtcError::InvalidHandle.pack());
        }
    }

    #[test]
    fn a_peer_round_trips_through_the_c_abi() {
        let runtime = keyguard_webrtc_runtime_new();
        assert!(runtime > 0);
        let config = br#"{"bindAddresses":["127.0.0.1:0"]}"#;
        // SAFETY: The configuration is live for the call.
        let peer = unsafe { keyguard_webrtc_peer_new(runtime, config.as_ptr(), config.len()) };
        assert!(peer > 0, "{peer:#x} must be a handle");

        let label = b"keyguard";
        // SAFETY: The label is live for the call.
        let created =
            unsafe { keyguard_webrtc_peer_create_channel(peer, label.as_ptr(), label.len()) };
        assert_eq!(created, 0);

        let mut small = vec![0_u8; MAX_SDP_BYTES - 1];
        // SAFETY: The buffer is live and writable for its whole length.
        let rejected =
            unsafe { keyguard_webrtc_peer_create_offer(peer, small.as_mut_ptr(), small.len()) };
        assert_eq!(rejected, WebrtcError::InvalidArgument.pack());

        let mut sdp = vec![0_u8; MAX_SDP_BYTES];
        // SAFETY: The buffer is live and writable for its whole length.
        let length =
            unsafe { keyguard_webrtc_peer_create_offer(peer, sdp.as_mut_ptr(), sdp.len()) };
        assert!(length > 0);
        let sdp = std::str::from_utf8(&sdp[..length as usize]).expect("the SDP is UTF-8");
        assert!(sdp.starts_with("v=0"));
        assert!(sdp.contains("webrtc-datachannel"));

        // SAFETY: A null pointer with a non-zero length is rejected before use.
        let rejected = unsafe { keyguard_webrtc_channel_send(peer, ptr::null(), 1) };
        assert_eq!(rejected, WebrtcError::InvalidArgument.pack());
        let oversized = vec![0_u8; MAX_MESSAGE_BYTES + 1];
        // SAFETY: The message is live for the call.
        let rejected =
            unsafe { keyguard_webrtc_channel_send(peer, oversized.as_ptr(), oversized.len()) };
        assert_eq!(rejected, WebrtcError::InputTooLong.pack());

        let invalid_utf8 = [0xff_u8];
        // SAFETY: The candidate is live for the call.
        let rejected = unsafe {
            keyguard_webrtc_peer_add_ice_candidate(peer, invalid_utf8.as_ptr(), invalid_utf8.len())
        };
        assert_eq!(rejected, WebrtcError::InvalidArgument.pack());

        assert_eq!(keyguard_webrtc_peer_close(peer), 0);
        assert_eq!(
            keyguard_webrtc_peer_close(peer),
            0,
            "close must be idempotent"
        );
        let mut event = vec![0_u8; MAX_EVENT_BYTES];
        // SAFETY: The buffer is live and writable for its whole length.
        let closed =
            unsafe { keyguard_webrtc_peer_next_event(peer, event.as_mut_ptr(), event.len(), 0) };
        assert_eq!(closed, WebrtcError::Closed.pack());
        assert_eq!(keyguard_webrtc_runtime_shutdown(runtime), 0);
    }
}
