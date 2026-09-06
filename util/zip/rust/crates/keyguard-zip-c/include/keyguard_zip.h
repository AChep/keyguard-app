#ifndef KEYGUARD_ZIP_H
#define KEYGUARD_ZIP_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Native ZIP archive reading and writing ABI v1. Every function except
 * keyguard_zip_abi_version returns an int64_t:
 *
 *   - 0: success without a payload.
 *   - > 0: a payload — an archive handle (>= 1), an entry name length, or a
 *     byte count.
 *   - -1: only from keyguard_zip_reader_next_entry; the archive has no
 *     further entries.
 *   - < 0 (bit 63 set): a packed failure:
 *       bits 0..7   operation (keyguard_zip_operation)
 *       bits 8..15  failure kind (keyguard_zip_failure_kind)
 *       bits 16..23 error domain (keyguard_zip_error_domain)
 *       bits 24..55 raw code: a keyguard_zip_bridge_error for the BRIDGE
 *                   domain, an errno for POSIX_ERRNO, otherwise zero
 *       bits 56..62 reserved as zero
 *     The reserved bits keep -1 unrepresentable as a failure. The layout is
 *     shared with keyguard_io and keyguard_zxcvbn.
 *
 * Writing: open, then begin_entry / write... / end_entry per file, then
 * finish (central directory + fsync) or abort (removes the file). Reading:
 * open, then next_entry / read... until next_entry returns -1, then close.
 * next_entry discards whatever is left of the previous entry. finish, abort,
 * and close consume the handle whatever they return; close never removes the
 * file. Handles come from one counter, are never reused, and a handle of the
 * wrong kind reports KEYGUARD_ZIP_BRIDGE_INVALID_HANDLE.
 *
 * A pointer may be NULL only when its length is zero, and must stay valid for
 * the duration of the call. Paths, entry names, and passwords never travel
 * back across the ABI. Calls are serialized inside the bridge; a single
 * archive is meant to be driven by one caller.
 */

enum keyguard_zip_operation {
    KEYGUARD_ZIP_OP_BRIDGE = 0,
    KEYGUARD_ZIP_OP_OPEN = 1,
    KEYGUARD_ZIP_OP_BEGIN_ENTRY = 2,
    KEYGUARD_ZIP_OP_WRITE = 3,
    KEYGUARD_ZIP_OP_END_ENTRY = 4,
    KEYGUARD_ZIP_OP_FINISH = 5,
    KEYGUARD_ZIP_OP_ABORT = 6,
    KEYGUARD_ZIP_OP_READER_OPEN = 7,
    KEYGUARD_ZIP_OP_NEXT_ENTRY = 8,
    KEYGUARD_ZIP_OP_READ = 9,
    KEYGUARD_ZIP_OP_CLOSE = 10,
};

enum keyguard_zip_failure_kind {
    KEYGUARD_ZIP_FAILURE_NONE = 0,
    KEYGUARD_ZIP_FAILURE_PERMISSION_DENIED = 1,
    KEYGUARD_ZIP_FAILURE_READ_ONLY_FILESYSTEM = 2,
    KEYGUARD_ZIP_FAILURE_NOT_FOUND = 3,
    KEYGUARD_ZIP_FAILURE_ALREADY_EXISTS = 4,
    KEYGUARD_ZIP_FAILURE_STORAGE_FULL = 5,
    KEYGUARD_ZIP_FAILURE_QUOTA_EXCEEDED = 6,
    KEYGUARD_ZIP_FAILURE_RESOURCE_BUSY = 7,
    KEYGUARD_ZIP_FAILURE_INVALID_INPUT = 8,
    KEYGUARD_ZIP_FAILURE_INTERRUPTED = 9,
    KEYGUARD_ZIP_FAILURE_UNSUPPORTED = 10,
    KEYGUARD_ZIP_FAILURE_OTHER = 11,
    KEYGUARD_ZIP_FAILURE_INTERNAL = 12,
};

enum keyguard_zip_error_domain {
    KEYGUARD_ZIP_ERROR_DOMAIN_NONE = 0,
    KEYGUARD_ZIP_ERROR_DOMAIN_POSIX_ERRNO = 1,
    KEYGUARD_ZIP_ERROR_DOMAIN_BRIDGE = 3,
};

/*
 * Raw code of a BRIDGE domain failure. All report operation
 * KEYGUARD_ZIP_OP_BRIDGE except ARCHIVE from the reader, which keeps
 * KEYGUARD_ZIP_OP_NEXT_ENTRY (kind OTHER) for a structural problem while
 * listing and KEYGUARD_ZIP_OP_READ (kind INVALID_INPUT) for a CRC or AES
 * authentication mismatch while reading.
 */
enum keyguard_zip_bridge_error {
    /* NULL pointer with a non-zero length, non-UTF-8 bytes, oversized path. */
    KEYGUARD_ZIP_BRIDGE_INVALID_ARGUMENT = 1,
    KEYGUARD_ZIP_BRIDGE_PANIC = 2,
    KEYGUARD_ZIP_BRIDGE_INTERNAL = 3,
    /* Unknown, consumed, or wrong-kind handle. */
    KEYGUARD_ZIP_BRIDGE_INVALID_HANDLE = 4,
    /* A call outside the begin_entry / end_entry state machine. */
    KEYGUARD_ZIP_BRIDGE_INVALID_STATE = 5,
    KEYGUARD_ZIP_BRIDGE_NAME_TOO_LONG = 6,
    /* A structural archive error that is not an I/O error. */
    KEYGUARD_ZIP_BRIDGE_ARCHIVE = 7,
    /* An encrypted entry did not decrypt, or no password was given. */
    KEYGUARD_ZIP_BRIDGE_WRONG_PASSWORD = 8,
    /* A compression or encryption method the reader cannot decode. */
    KEYGUARD_ZIP_BRIDGE_UNSUPPORTED_ENTRY = 9,
    /* The entry name does not fit the caller's buffer. */
    KEYGUARD_ZIP_BRIDGE_BUFFER_TOO_SMALL = 10,
};

enum {
    KEYGUARD_ZIP_ABI_VERSION = 1,
    KEYGUARD_ZIP_MAX_ENTRY_NAME_BYTES = 4096,
    KEYGUARD_ZIP_MAX_PATH_BYTES = 4096,
};

uint32_t keyguard_zip_abi_version(void);

/*
 * Creates or truncates an archive at a UTF-8 path and returns its handle. A
 * zero password_len writes a plain archive; otherwise every entry is encrypted
 * with AES-256 (WinZip AE-2). The handle must reach finish or abort.
 */
int64_t keyguard_zip_writer_open(
    const uint8_t *path_ptr,
    size_t path_len,
    const uint8_t *password_ptr,
    size_t password_len
);

/*
 * Starts a deflated, zip64-capable entry named by UTF-8 bytes with "/" as
 * the separator.
 */
int64_t keyguard_zip_writer_begin_entry(
    uint64_t handle,
    const uint8_t *name_ptr,
    size_t name_len
);

/* Appends bytes to the current entry. */
int64_t keyguard_zip_writer_write(
    uint64_t handle,
    const uint8_t *data_ptr,
    size_t data_len
);

/* Closes the current entry. */
int64_t keyguard_zip_writer_end_entry(uint64_t handle);

/*
 * Writes the central directory, fsyncs, and closes the file. A failed finish
 * leaves the incomplete file for the caller to remove.
 */
int64_t keyguard_zip_writer_finish(uint64_t handle);

/* Discards the archive and removes the file; a missing file is not a failure. */
int64_t keyguard_zip_writer_abort(uint64_t handle);

/*
 * Opens an existing archive at a UTF-8 path for sequential reading. A zero
 * password_len opens it without a password; otherwise the password decrypts
 * every encrypted entry. Plain entries read normally either way. The handle
 * must reach close.
 */
int64_t keyguard_zip_reader_open(
    const uint8_t *path_ptr,
    size_t path_len,
    const uint8_t *password_ptr,
    size_t password_len
);

/*
 * Advances to the next entry and writes its UTF-8 name, without a NUL, into
 * the buffer. Returns the name length, or -1 at the end. No failure advances
 * the reader, so a BUFFER_TOO_SMALL call can be retried with a bigger buffer.
 */
int64_t keyguard_zip_reader_next_entry(
    uint64_t handle,
    uint8_t *name_ptr,
    size_t name_cap
);

/*
 * Reads up to buf_cap decompressed, decrypted bytes of the current entry.
 * Returns the byte count, or zero at the end of the entry; a short read is
 * not the end.
 */
int64_t keyguard_zip_reader_read(
    uint64_t handle,
    uint8_t *buf_ptr,
    size_t buf_cap
);

/* Closes the reader, leaving the file in place. */
int64_t keyguard_zip_reader_close(uint64_t handle);

#ifdef __cplusplus
}
#endif

#endif /* KEYGUARD_ZIP_H */
