#ifndef KEYGUARD_WEBRTC_H
#define KEYGUARD_WEBRTC_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * WebRTC data channel ABI v1.
 *
 * A runtime handle owns one tokio runtime; a peer handle owns one peer
 * connection with at most one data channel and belongs to one runtime.
 * Handles are positive int64_t values. The library never calls back: events
 * queue per peer and keyguard_webrtc_peer_next_event takes them, blocking the
 * calling thread. Closing a peer, or shutting its runtime down, from another
 * thread wakes every call blocked on it.
 *
 * Every function except keyguard_webrtc_abi_version returns an int64_t:
 *
 *   - Zero or a positive value is success: a handle, a length, an amount,
 *     or zero.
 *
 *   - A negative value (bit 63 set) is a packed failure:
 *       bits 0..7   operation, always KEYGUARD_WEBRTC_OP_BRIDGE (0)
 *       bits 8..15  failure kind (keyguard_webrtc_failure_kind)
 *       bits 16..23 error domain, always KEYGUARD_WEBRTC_ERROR_DOMAIN_BRIDGE
 *       bits 24..55 bridge error code (keyguard_webrtc_bridge_error)
 *       bits 56..62 reserved as zero
 *
 * The enum values are stable, project-owned ABI codes.
 *
 * An output buffer out_ptr/out_len must be out_len initialized bytes, such as
 * a zeroed array: the library writes to it as Rust bytes, which may not be
 * uninitialized memory.
 */

enum keyguard_webrtc_operation {
    KEYGUARD_WEBRTC_OP_BRIDGE = 0,
};

enum keyguard_webrtc_failure_kind {
    KEYGUARD_WEBRTC_FAILURE_RESOURCE_BUSY = 7,
    KEYGUARD_WEBRTC_FAILURE_INVALID_INPUT = 8,
    KEYGUARD_WEBRTC_FAILURE_INTERRUPTED = 9,
    KEYGUARD_WEBRTC_FAILURE_OTHER = 11,
    KEYGUARD_WEBRTC_FAILURE_INTERNAL = 12,
};

enum keyguard_webrtc_error_domain {
    KEYGUARD_WEBRTC_ERROR_DOMAIN_BRIDGE = 3,
};

enum keyguard_webrtc_bridge_error {
    /* An argument violated the ABI contract (kind INVALID_INPUT). */
    KEYGUARD_WEBRTC_BRIDGE_INVALID_ARGUMENT = 1,
    /* A panic was contained (kind INTERNAL). */
    KEYGUARD_WEBRTC_BRIDGE_PANIC = 2,
    /* The bridge failed internally (kind INTERNAL). */
    KEYGUARD_WEBRTC_BRIDGE_INTERNAL = 3,
    /* An input exceeded its byte limit (kind INVALID_INPUT). */
    KEYGUARD_WEBRTC_BRIDGE_INPUT_TOO_LONG = 4,
    /* The value was never issued as a handle of this kind (kind INVALID_INPUT). */
    KEYGUARD_WEBRTC_BRIDGE_INVALID_HANDLE = 5,
    /* The peer or runtime is closed (kind INTERRUPTED). */
    KEYGUARD_WEBRTC_BRIDGE_CLOSED = 6,
    /*
     * More than 1 MiB is buffered, retry after a BUFFERED_LOW event; or 64
     * runtimes or 1024 peers are live (kind RESOURCE_BUSY).
     */
    KEYGUARD_WEBRTC_BRIDGE_BUSY = 7,
    /* The stack did not answer within 10 seconds (kind OTHER). */
    KEYGUARD_WEBRTC_BRIDGE_TIMEOUT = 8,
    /* Not valid now, such as a send with no open channel (kind OTHER). */
    KEYGUARD_WEBRTC_BRIDGE_INVALID_STATE = 9,
    /* The stack rejected the request (kind OTHER). */
    KEYGUARD_WEBRTC_BRIDGE_STACK = 10,
};

/*
 * An event is one kind byte followed by its payload; the call returns the
 * total length.
 */
enum keyguard_webrtc_event_kind {
    /* UTF-8 JSON {"candidate","sdpMid","sdpMLineIndex"} for the remote peer. */
    KEYGUARD_WEBRTC_EVENT_ICE_CANDIDATE = 1,
    /* No payload. */
    KEYGUARD_WEBRTC_EVENT_ICE_GATHERING_COMPLETE = 2,
    /* One keyguard_webrtc_connection_state byte. */
    KEYGUARD_WEBRTC_EVENT_CONNECTION_STATE = 3,
    /* No payload. */
    KEYGUARD_WEBRTC_EVENT_CHANNEL_OPEN = 4,
    /* The message bytes. */
    KEYGUARD_WEBRTC_EVENT_CHANNEL_MESSAGE = 5,
    /* No payload. */
    KEYGUARD_WEBRTC_EVENT_CHANNEL_CLOSE = 6,
    /* No payload: the buffered amount fell to 256 KiB. */
    KEYGUARD_WEBRTC_EVENT_BUFFERED_LOW = 7,
    /* UTF-8 JSON {"localType","remoteType","protocol"}. */
    KEYGUARD_WEBRTC_EVENT_SELECTED_PAIR = 8,
    /* keyguard_webrtc_event_error, uint32_t little-endian. */
    KEYGUARD_WEBRTC_EVENT_ERROR = 9,
};

enum keyguard_webrtc_connection_state {
    KEYGUARD_WEBRTC_STATE_UNKNOWN = 0,
    KEYGUARD_WEBRTC_STATE_NEW = 1,
    KEYGUARD_WEBRTC_STATE_CONNECTING = 2,
    KEYGUARD_WEBRTC_STATE_CONNECTED = 3,
    KEYGUARD_WEBRTC_STATE_DISCONNECTED = 4,
    KEYGUARD_WEBRTC_STATE_FAILED = 5,
    KEYGUARD_WEBRTC_STATE_CLOSED = 6,
};

enum keyguard_webrtc_event_error {
    /* The stack rejected a remote ICE candidate. */
    KEYGUARD_WEBRTC_EVENT_ERROR_REMOTE_CANDIDATE = 1,
    /* A STUN or TURN server failed while gathering. */
    KEYGUARD_WEBRTC_EVENT_ERROR_ICE_SERVER = 2,
    /* The data channel reported an error. */
    KEYGUARD_WEBRTC_EVENT_ERROR_CHANNEL = 3,
};

enum {
    KEYGUARD_WEBRTC_SDP_OFFER = 0,
    KEYGUARD_WEBRTC_SDP_ANSWER = 1,
    /* Largest data channel message. */
    KEYGUARD_WEBRTC_MAX_MESSAGE_BYTES = 65536,
    /* Smallest output buffer of keyguard_webrtc_peer_next_event. */
    KEYGUARD_WEBRTC_MAX_EVENT_BYTES = 65537,
    /* Largest SDP; smallest output buffer of create_offer and create_answer. */
    KEYGUARD_WEBRTC_MAX_SDP_BYTES = 65536,
    /* Largest configuration JSON. */
    KEYGUARD_WEBRTC_MAX_CONFIG_BYTES = 16384,
    /* Largest remote candidate JSON. */
    KEYGUARD_WEBRTC_MAX_CANDIDATE_BYTES = 4096,
    /* Largest channel label. */
    KEYGUARD_WEBRTC_MAX_LABEL_BYTES = 255,
};

/* Returns the native function ABI version, 1. */
uint32_t keyguard_webrtc_abi_version(void);

/* Creates a runtime with two worker threads. Returns its handle. */
int64_t keyguard_webrtc_runtime_new(void);

/*
 * Closes every peer of the runtime and stops it, waiting a few seconds at
 * most. Idempotent.
 */
int64_t keyguard_webrtc_runtime_shutdown(int64_t runtime);

/*
 * Creates a peer from UTF-8 JSON:
 * {"iceServers":[{"urls":[...],"username":"...","credential":"..."}],
 *  "iceTransportPolicy":"all"|"relay","bindAddresses":["0.0.0.0:0"]}.
 * Every field is optional; ICE mDNS is disabled. Returns the peer handle.
 */
int64_t keyguard_webrtc_peer_new(int64_t runtime, const uint8_t *config_ptr, size_t config_len);

/*
 * Creates an offer (or answer), applies it as the local description and
 * writes its UTF-8 SDP to out, which holds at least
 * KEYGUARD_WEBRTC_MAX_SDP_BYTES. Returns the SDP length.
 */
int64_t keyguard_webrtc_peer_create_offer(int64_t peer, uint8_t *out_ptr, size_t out_len);
int64_t keyguard_webrtc_peer_create_answer(int64_t peer, uint8_t *out_ptr, size_t out_len);

/* Applies the remote description; kind is KEYGUARD_WEBRTC_SDP_OFFER or _ANSWER. */
int64_t keyguard_webrtc_peer_set_remote(
    int64_t peer,
    uint32_t kind,
    const uint8_t *sdp_ptr,
    size_t sdp_len
);

/*
 * Adds a remote candidate, as UTF-8 JSON {"candidate","sdpMid","sdpMLineIndex"}.
 * Returns once parsed; it is applied in the background, after the remote
 * description. An empty "candidate" is ignored.
 */
int64_t keyguard_webrtc_peer_add_ice_candidate(
    int64_t peer,
    const uint8_t *json_ptr,
    size_t json_len
);

/* Creates the peer's ordered, reliable data channel (offerer side). */
int64_t keyguard_webrtc_peer_create_channel(
    int64_t peer,
    const uint8_t *label_ptr,
    size_t label_len
);

/*
 * Queues one message of at most KEYGUARD_WEBRTC_MAX_MESSAGE_BYTES. Returns
 * zero, or the packed KEYGUARD_WEBRTC_BRIDGE_BUSY failure when it would take
 * the buffered amount over 1 MiB.
 */
int64_t keyguard_webrtc_channel_send(int64_t peer, const uint8_t *data_ptr, size_t data_len);

/* Returns the bytes queued on the channel and not yet acknowledged. */
int64_t keyguard_webrtc_channel_buffered_amount(int64_t peer);

/*
 * Waits up to timeout_ms for the next event and writes it to out, which holds
 * at least KEYGUARD_WEBRTC_MAX_EVENT_BYTES. Returns the event length, zero on
 * timeout, or the packed KEYGUARD_WEBRTC_BRIDGE_CLOSED failure once the peer
 * is closed.
 */
int64_t keyguard_webrtc_peer_next_event(
    int64_t peer,
    uint8_t *out_ptr,
    size_t out_len,
    uint32_t timeout_ms
);

/* Closes the peer and wakes every call blocked on it. Idempotent. */
int64_t keyguard_webrtc_peer_close(int64_t peer);

#ifdef __cplusplus
}
#endif

#endif /* KEYGUARD_WEBRTC_H */
