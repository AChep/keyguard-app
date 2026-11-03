#ifndef KEYGUARD_AGENT_IPC_H
#define KEYGUARD_AGENT_IPC_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ABI v1 of the private endpoint between Keyguard and its agent helpers.
 *
 * Errors are negative: -1 invalid argument, -2 I/O (including the handle
 * limit), -3 closed, -4 peer closed, -5 unknown or consumed handle, -6 busy,
 * -7 permission, -8 internal (including a contained panic).
 *
 * Strings are strict UTF-8, given as pointer and length. A null pointer is
 * allowed only with length 0. No pointer is retained after a call. Every
 * function contains Rust panics.
 *
 * Calls block the calling thread. Closing the object a call blocks on is the
 * only way to cancel it: the call then returns -3. A connection's inbound lane
 * (read, await_input) and outbound lane (write) run at the same time; a second
 * call on a busy lane, or a second accept, returns -6.
 */
uint32_t keyguard_agent_ipc_abi_version(void);

/* This thread's diagnostic from the last failed call:
 * "<operation>: <reason>[ (os N)]", ASCII, at most 128 bytes, never a path, a
 * name or a payload. Returns the required size and copies only if it fits; no
 * NUL. 0: no diagnostic. A null buffer with capacity 0 queries the size.
 * Reading does not clear it; every other call except abi_version clears it on
 * entry. */
size_t keyguard_agent_ipc_last_error(uint8_t *buffer, size_t capacity);

/* Clears this thread's diagnostic. */
void keyguard_agent_ipc_clear_error(void);

/* Creates and secures an endpoint; it is connectable on return. >0: listener.
 * Unix: the socket <parent>/<prefix><12 hex>/i in a new 0700 directory.
 * Windows: the pipe \\.\pipe\<prefix><32 hex>. An exact name replaces
 * <prefix><hex>; it is for tests. Names use [A-Za-z0-9._-] and are never "."
 * or "..". -7: the exact name is taken. */
int64_t  keyguard_agent_ipc_listen(const uint8_t *parent, size_t parent_length,     /* Unix: required; Windows: 0 */
                                   const uint8_t *prefix, size_t prefix_length,     /* 0..32 bytes */
                                   const uint8_t *exact_name, size_t exact_length); /* 0 = random; else 1..64 */

/* The socket path or pipe name, UTF-8 without NUL; the value of a helper's
 * --ipc-socket. Returns the required size and copies only if it fits. */
int64_t  keyguard_agent_ipc_endpoint(uint64_t listener, uint8_t *buffer, size_t capacity); /* >=0 required size */

/* Blocks until a client connects. Per-connection failures never surface here.
 * -3 after close_listener. */
int64_t  keyguard_agent_ipc_accept(uint64_t listener);                  /* blocks; >0 connection */

/* Stops listening and wakes a blocked accept. Accepted connections stay open.
 * Unix removes the socket and its directory if they are still its own. */
int64_t  keyguard_agent_ipc_close_listener(uint64_t listener);          /* 0; consumes */

/* The peer's process ID from the kernel snapshot taken at accept, before any
 * I/O. -7 when the peer is not a verifiable process of this user. */
int64_t  keyguard_agent_ipc_peer_pid(uint64_t connection);              /* >0 pid; -7 not verifiable */

/* Inbound lane. Reads up to min(capacity, 1 MiB) bytes. */
int64_t  keyguard_agent_ipc_read(uint64_t connection, uint8_t *buffer, size_t capacity);    /* capacity>=1; >0 n; 0 EOF */

/* Outbound lane. Writes all bytes, at most 32 MiB. -4: the peer closed. */
int64_t  keyguard_agent_ipc_write(uint64_t connection, const uint8_t *buffer, size_t length); /* 0; length 0 is a no-op */

/* Inbound lane. Waits for input or end of stream without consuming input. */
int64_t  keyguard_agent_ipc_await_input(uint64_t connection);           /* 1 input; 0 EOF */

/* Wakes every blocked call on the connection. Data already written stays
 * readable by the peer. */
int64_t  keyguard_agent_ipc_close(uint64_t connection);                 /* 0; consumes; wakes every op */

#ifdef __cplusplus
}
#endif
#endif
