#ifndef KEYGUARD_IO_H
#define KEYGUARD_IO_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Native I/O ABI v1. Every function returns an int64_t scalar:
 *
 *   - A negative value (bit 63 set) is a packed protocol failure:
 *       bits 0..7   protocol operation (keyguard_io_operation)
 *       bits 8..15  failure kind (keyguard_io_failure_kind, never NONE)
 *       bits 16..23 error domain (keyguard_io_error_domain)
 *       bits 24..55 raw native error bit pattern
 *       bit  56     cleanup after the primary failure was incomplete
 *       bits 57..62 reserved as zero
 *     The reserved bits keep -1 unrepresentable as a failure; -1 is the
 *     end-of-file marker of read operations.
 *
 *   - A non-negative value is a function-specific success payload: a handle,
 *     a byte count, zero, or the packed commit report documented below.
 *
 * The enum values are stable, project-owned ABI codes rather than Rust enum
 * discriminants or platform error numbers.
 */

enum keyguard_io_operation {
    KEYGUARD_IO_OP_BRIDGE = 0,
    KEYGUARD_IO_OP_BEGIN = 1,
    KEYGUARD_IO_OP_CREATE_STAGED = 2,
    KEYGUARD_IO_OP_WRITE = 3,
    KEYGUARD_IO_OP_FLUSH_FILE = 4,
    KEYGUARD_IO_OP_METADATA = 5,
    KEYGUARD_IO_OP_RENAME = 6,
    KEYGUARD_IO_OP_FLUSH_DIR = 7,
    KEYGUARD_IO_OP_CLEANUP = 8,
    KEYGUARD_IO_OP_READ = 9,
    KEYGUARD_IO_OP_CLOSE = 10,
    KEYGUARD_IO_OP_SWEEP = 11,
    KEYGUARD_IO_OP_PREPARE_PARENT = 12,
    KEYGUARD_IO_OP_FLUSH_PARENT = 13,
    KEYGUARD_IO_OP_HARD_LINK = 14,
};

enum keyguard_io_failure_kind {
    KEYGUARD_IO_FAILURE_NONE = 0,
    KEYGUARD_IO_FAILURE_PERMISSION_DENIED = 1,
    KEYGUARD_IO_FAILURE_READ_ONLY_FILESYSTEM = 2,
    KEYGUARD_IO_FAILURE_NOT_FOUND = 3,
    KEYGUARD_IO_FAILURE_ALREADY_EXISTS = 4,
    KEYGUARD_IO_FAILURE_STORAGE_FULL = 5,
    KEYGUARD_IO_FAILURE_QUOTA_EXCEEDED = 6,
    KEYGUARD_IO_FAILURE_RESOURCE_BUSY = 7,
    KEYGUARD_IO_FAILURE_INVALID_INPUT = 8,
    KEYGUARD_IO_FAILURE_INTERRUPTED = 9,
    KEYGUARD_IO_FAILURE_UNSUPPORTED = 10,
    KEYGUARD_IO_FAILURE_OTHER = 11,
    KEYGUARD_IO_FAILURE_INTERNAL = 12,
    KEYGUARD_IO_FAILURE_DURABILITY_UNAVAILABLE = 13,
};

enum keyguard_io_error_domain {
    KEYGUARD_IO_ERROR_DOMAIN_NONE = 0,
    KEYGUARD_IO_ERROR_DOMAIN_POSIX_ERRNO = 1,
    KEYGUARD_IO_ERROR_DOMAIN_WIN32_LAST_ERROR = 2,
    KEYGUARD_IO_ERROR_DOMAIN_BRIDGE = 3,
};

enum keyguard_io_commit_outcome {
    KEYGUARD_IO_COMMIT_PUBLISHED = 0,
    KEYGUARD_IO_COMMIT_DESTINATION_EXISTS = 1,
    /*
     * Publication is established, but staged-artifact cleanup is incomplete.
     * The failure fields may all be zero when cleanup was deliberately
     * skipped to avoid deleting a possibly published destination.
    */
    KEYGUARD_IO_COMMIT_PUBLISHED_CLEANUP_INCOMPLETE = 2,
    KEYGUARD_IO_COMMIT_PUBLISHED_DURABILITY_UNKNOWN = 3,
    /*
     * Synchronization is unknown and cleanup/finalization is also incomplete.
     * The packed secondary failure describes synchronization; this outcome
     * code retains the independent cleanup state.
     */
    KEYGUARD_IO_COMMIT_PUBLISHED_DURABILITY_UNKNOWN_CLEANUP_INCOMPLETE = 4,
    /*
     * Create found an existing destination, and removing/finalizing the
     * unpublished staged artifact was incomplete. The packed secondary
     * failure describes cleanup.
     */
    KEYGUARD_IO_COMMIT_DESTINATION_EXISTS_CLEANUP_INCOMPLETE = 5,
    /*
     * A rename or hard-link request was issued, but publication could not be
     * positively established. The achieved synchronization nibble is 0xf;
     * the packed failure is the primary publication failure; bits 56..62
     * identify the publication operation.
     */
    KEYGUARD_IO_COMMIT_PUBLICATION_UNKNOWN = 6,
    /*
     * Publication is unknown and exact staged-artifact cleanup was incomplete
     * or deliberately skipped because it could have deleted the destination.
     * The packed failure remains the primary publication failure; the scalar
     * does not invent or overwrite it with a cleanup failure.
     */
    KEYGUARD_IO_COMMIT_PUBLICATION_UNKNOWN_CLEANUP_INCOMPLETE = 7,
};

enum keyguard_io_sweep_status {
    KEYGUARD_IO_SWEEP_COMPLETE = 0,
    KEYGUARD_IO_SWEEP_BUSY = 1,
    KEYGUARD_IO_SWEEP_INCOMPLETE = 2,
};

enum {
    KEYGUARD_IO_SWEEP_REPORT_VERSION = 1,
};

/*
 * Size- and version-tagged sweep output. Before calling
 * keyguard_io_sweep_orphans, initialize `size` to sizeof(struct
 * keyguard_io_sweep_report_v1). The remaining fields may be uninitialized.
 *
 * first_failure_kind/domain/raw_code are all zero when no enumeration or
 * candidate failure was observed. Paths never cross the ABI.
 */
struct keyguard_io_sweep_report_v1 {
    uint32_t size;
    uint32_t version;
    uint32_t status;
    uint32_t first_failure_kind;
    uint32_t first_failure_domain;
    uint32_t first_failure_raw_code;
    uint64_t entries_seen;
    uint64_t candidate_names;
    uint64_t removed;
    uint64_t skipped_young;
    uint64_t skipped_busy;
    uint64_t skipped_unsafe;
    uint64_t skipped_changed;
    uint64_t inspection_failed;
    uint64_t removal_failed;
};

enum keyguard_io_publication {
    /*
     * Publish only when the destination does not exist. The new file uses
     * the requested keyguard_io_file_permissions policy.
     */
    KEYGUARD_IO_PUBLICATION_CREATE = 0,

    /*
     * Replace an existing destination, or create a missing destination,
     * using the requested keyguard_io_file_permissions policy.
     */
    KEYGUARD_IO_PUBLICATION_REPLACE_USE_REQUESTED_PERMISSIONS = 1,

    /*
     * Replace an existing regular file while preserving its basic
     * permissions: ordinary POSIX rwx bits (0777), or the Windows DACL and
     * protected/unprotected inheritance state. This does not preserve
     * ownership, POSIX special bits or ACLs, Windows SACLs, timestamps,
     * extended attributes, capabilities, security labels, or any other
     * metadata. A symlink/reparse point or non-regular destination is
     * rejected. A missing destination uses the requested
     * keyguard_io_file_permissions policy. Platforms without an exact
     * implementation fail before publication; ABI v1 does not silently
     * approximate it. The permission snapshot comes from the destination
     * observed when the transaction opens; a later concurrent replacement
     * does not update it.
     */
    KEYGUARD_IO_PUBLICATION_REPLACE_PRESERVE_EXISTING_BASIC_PERMISSIONS = 2,
};

enum keyguard_io_file_permissions {
    KEYGUARD_IO_FILE_PERMISSIONS_OWNER_ONLY = 0,
    KEYGUARD_IO_FILE_PERMISSIONS_PROCESS_DEFAULT = 1,
};

enum keyguard_io_parent_creation {
    KEYGUARD_IO_PARENT_REQUIRE_EXISTING = 0,
    KEYGUARD_IO_PARENT_CREATE_MISSING = 1,
};

enum keyguard_io_directory_permissions {
    KEYGUARD_IO_DIRECTORY_PERMISSIONS_OWNER_ONLY = 0,
    KEYGUARD_IO_DIRECTORY_PERMISSIONS_PROCESS_DEFAULT = 1,
};

enum keyguard_io_existing_parent_links {
    KEYGUARD_IO_EXISTING_PARENT_LINKS_REJECT = 0,
    KEYGUARD_IO_EXISTING_PARENT_LINKS_FOLLOW_AND_PIN = 1,
};

enum keyguard_io_sync_level {
    KEYGUARD_IO_SYNC_PROCESS_ATOMIC = 0,
    KEYGUARD_IO_SYNC_FILE = 1,
    KEYGUARD_IO_SYNC_FILE_AND_NAMESPACE = 2,
};

enum keyguard_io_sync_policy_mode {
    KEYGUARD_IO_SYNC_REQUIRED = 0,
    KEYGUARD_IO_SYNC_PREFER = 1,
};

enum {
    KEYGUARD_IO_TXN_OPTIONS_VERSION = 1,
};

/*
 * Size- and version-tagged transaction options. Every field must be
 * initialized. Unknown flags and all reserved words must be zero. The bridge
 * validates this complete record before any filesystem operation.
 */
struct keyguard_io_txn_options_v1 {
    uint32_t size;
    uint32_t version;
    int32_t publication;
    int32_t file_permissions;
    int32_t parent_creation;
    int32_t directory_permissions;
    int32_t existing_parent_links;
    int32_t preferred_sync_level;
    int32_t minimum_sync_level;
    int32_t sync_policy_mode;
    uint32_t flags;
    uint32_t reserved[5];
};

/**
 * Version of this direct function ABI.
 */
uint32_t keyguard_io_abi_version(void);

/**
 * Resolves an existing absolute directory once and retains its complete
 * native handle chain. Links in this path select the caller-trusted root and
 * are followed exactly once. Returns a positive directory handle or a packed
 * failure.
 */
int64_t keyguard_io_directory_open(
    const uint8_t *directory_ptr,
    size_t directory_len
);

/**
 * Closes one logical directory handle. Transactions already opened from the
 * capability remain valid. Returns zero or a packed failure.
 */
int64_t keyguard_io_directory_close(uint64_t handle);

/**
 * Opens an atomic-write transaction targeting the destination path.
 *
 * Paths are non-null UTF-8 byte slices when their length is non-zero.
 * `options` must point to a fully initialized keyguard_io_txn_options_v1
 * whose size and version match ABI v1. Required synchronization that exceeds
 * the platform maximum fails before parent creation or staging. Prefer may
 * select a lower level only for this known capability shortfall and never for
 * an ordinary I/O failure. Windows advertises at most KEYGUARD_IO_SYNC_FILE.
 *
 * Returns a positive transaction handle or a packed failure.
 */
int64_t keyguard_io_txn_begin(
    const uint8_t *destination_ptr,
    size_t destination_len,
    const struct keyguard_io_txn_options_v1 *options
);

/**
 * Opens an atomic-write transaction beneath a retained directory capability.
 *
 * The relative path uses '/' separators and must identify one descendant
 * file. Absolute, empty, trailing/doubled-separator, dot, dot-dot, backslash,
 * drive/UNC, colon, and NUL spellings are rejected before handle lookup or
 * filesystem access. `existing_parent_links` must be REJECT; all descendant
 * links and mount crossings are rejected while the retained root itself stays
 * pinned across lexical rename or retarget.
 */
int64_t keyguard_io_txn_begin_at_directory(
    uint64_t directory_handle,
    const uint8_t *relative_destination_ptr,
    size_t relative_destination_len,
    const struct keyguard_io_txn_options_v1 *options
);

/**
 * Appends bytes to a transaction. Returns the byte count or a packed failure.
 *
 * The first failed append permanently prevents publication. The handle stays
 * valid for abort, and later writes return the original failure without
 * replaying I/O. Commit consumes a poisoned handle, attempts cleanup only,
 * and reports the original write failure.
 */
int64_t keyguard_io_txn_write(
    uint64_t handle,
    const uint8_t *input_ptr,
    size_t input_len
);

/**
 * Commits a transaction, consuming its handle on every result.
 *
 * A negative result is a failure proven before namespace mutation dispatch.
 * The staged temporary was removed or remains recognizable to the orphan
 * sweeper. A non-negative result packs the commit report:
 *   bits 0..3   keyguard_io_commit_outcome
 *   bits 4..7   achieved synchronization level, or 0xf only for outcomes 6/7
 *   bits 8..15  reported failure kind (NONE when absent)
 *   bits 16..23 reported error domain
 *   bits 24..55 reported raw native error bit pattern
 *   bits 56..62 publication operation only for outcomes 6/7
 *
 * Outcomes 6/7 carry no achieved synchronization claim or success receipt.
 * They require a primary publication failure and RENAME or HARD_LINK in the
 * publication-operation field. Every other outcome requires a normal achieved
 * level and zero publication-operation bits. Outcome 2 may omit failure
 * metadata when cleanup was deliberately skipped; outcomes 3/4/5 require a
 * reported failure.
 */
int64_t keyguard_io_txn_commit(uint64_t handle);

/**
 * Aborts a transaction, consuming its handle and removing the staged
 * temporary. Returns a packed cleanup failure when removal fails and may
 * leave a recognizable, sweepable
 * temporary behind. Returns zero or a packed failure.
 */
int64_t keyguard_io_txn_abort(uint64_t handle);

/**
 * Opens pathless private scratch storage inside the directory. Returns a
 * positive scratch handle or a packed failure.
 */
int64_t keyguard_io_scratch_open(
    const uint8_t *directory_ptr,
    size_t directory_len
);

/**
 * Appends bytes to scratch storage. Returns the byte count or a packed
 * failure.
 */
int64_t keyguard_io_scratch_write(
    uint64_t handle,
    const uint8_t *input_ptr,
    size_t input_len
);

/**
 * Seals scratch storage for reading. Returns zero or a packed failure.
 */
int64_t keyguard_io_scratch_seal(uint64_t handle);

/**
 * Returns the scratch length in bytes or a packed failure.
 */
int64_t keyguard_io_scratch_length(uint64_t handle);

/**
 * Reads scratch bytes at a fixed position. Returns the byte count, -1 at
 * end-of-file, or a packed failure.
 */
int64_t keyguard_io_scratch_read_at(
    uint64_t handle,
    uint64_t position,
    uint8_t *output_ptr,
    size_t output_len
);

/**
 * Closes scratch storage, consuming its handle. Returns zero or a packed
 * failure.
 */
int64_t keyguard_io_scratch_close(uint64_t handle);

/**
 * Sweeps a directory (non-recursively) for orphaned Keyguard temporary
 * artifacts older than the given age. `role_mask` selects artifact roles
 * (bit 0 new, bit 1 previous, bit 2 scratch).
 *
 * Returns zero after writing `report` for complete, busy, and incomplete
 * sweeps. Root/open failures return a packed negative failure and leave
 * `report` unchanged. A busy report is a reserved platform-wide normal no-op.
 * An incomplete report contains partial counts and the first enumeration or
 * candidate failure.
 */
int64_t keyguard_io_sweep_orphans(
    const uint8_t *directory_ptr,
    size_t directory_len,
    uint64_t older_than_ms,
    uint32_t role_mask,
    struct keyguard_io_sweep_report_v1 *report
);

#ifdef __cplusplus
}
#endif

#endif /* KEYGUARD_IO_H */
