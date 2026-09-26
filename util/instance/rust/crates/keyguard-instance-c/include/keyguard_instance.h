#ifndef KEYGUARD_INSTANCE_H
#define KEYGUARD_INSTANCE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ABI v2. Errors: -1 argument, -2 I/O, -3 timeout, -4 protocol, -5 handle,
 * -6 unavailable, -7 permission, -8 internal. Strings are strict UTF-8 and
 * contain at most 65536 bytes. Pointers must remain readable throughout calls.
 * A zero-length string may use a null pointer. No function takes ownership of
 * caller memory. All exported functions contain Rust panics.
 */
uint32_t keyguard_instance_abi_version(void);

/* Safe UTF-8 diagnostic from the last operation on this thread, without NUL.
 * Read immediately after an error, before another operation. Success clears it.
 * Returns required bytes, copying only when capacity is sufficient. Querying
 * does not clear it. A null buffer with zero capacity queries the required size.
 * Nonzero capacity requires that many writable bytes. No paths/tokens are returned.
 */
size_t keyguard_instance_last_error(uint8_t *buffer, size_t capacity);

/* Clear stale diagnostics before validating arguments outside the native bridge. */
void keyguard_instance_clear_error(void);

/* Positive result: owned primary handle and ready listener. Zero: another
 * instance acknowledged and queued activation. Negative: error. Both paths
 * must be local and absolute. Coordination storage is private, with a permanent
 * ownership file. Runtime storage is an existing root for private IPC directories.
 */
int64_t keyguard_instance_acquire_or_activate(
    const uint8_t *coordination_ptr, size_t coordination_len,
    const uint8_t *runtime_ptr, size_t runtime_len,
    const uint8_t *identity_ptr, size_t identity_len,
    uint64_t timeout_ms);

/* One receiver per handle. Blocks until activation (1), shutdown (0), or error.
 * This call must not run on the UI thread. */
int64_t keyguard_instance_wait_event(uint64_t handle);

/* Stops listening and wakes the receiver while retaining instance ownership. */
int64_t keyguard_instance_stop(uint64_t handle);

/* Stops transport work and releases ownership last. Consumes the handle.
 * Calling this again returns -5. Safe concurrently with wait_event/stop. */
int64_t keyguard_instance_close(uint64_t handle);

#ifdef __cplusplus
}
#endif
#endif
