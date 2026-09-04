#ifndef KEYGUARD_ZXCVBN_H
#define KEYGUARD_ZXCVBN_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Native password strength ABI v1. keyguard_zxcvbn_estimate returns an
 * int64_t scalar:
 *
 *   - Zero means the caller-owned result record was written in full.
 *
 *   - A negative value (bit 63 set) is a packed bridge failure and leaves the
 *     result record unchanged:
 *       bits 0..7   operation, always KEYGUARD_ZXCVBN_OP_BRIDGE (0)
 *       bits 8..15  failure kind (keyguard_zxcvbn_failure_kind)
 *       bits 16..23 error domain, always KEYGUARD_ZXCVBN_ERROR_DOMAIN_BRIDGE
 *       bits 24..55 bridge error code (keyguard_zxcvbn_bridge_error)
 *       bits 56..62 reserved as zero
 *     The reserved bits keep -1 unrepresentable as a failure, matching the
 *     scalar contract shared with keyguard_io.
 *
 *   - No positive value is defined.
 *
 * The enum values are stable, project-owned ABI codes rather than Rust enum
 * discriminants.
 */

enum keyguard_zxcvbn_operation {
    KEYGUARD_ZXCVBN_OP_BRIDGE = 0,
};

enum keyguard_zxcvbn_failure_kind {
    KEYGUARD_ZXCVBN_FAILURE_INVALID_INPUT = 8,
    KEYGUARD_ZXCVBN_FAILURE_INTERNAL = 12,
};

enum keyguard_zxcvbn_error_domain {
    KEYGUARD_ZXCVBN_ERROR_DOMAIN_BRIDGE = 3,
};

enum keyguard_zxcvbn_bridge_error {
    KEYGUARD_ZXCVBN_BRIDGE_INVALID_ARGUMENT = 1,
    KEYGUARD_ZXCVBN_BRIDGE_PANIC = 2,
    KEYGUARD_ZXCVBN_BRIDGE_INTERNAL = 3,
    KEYGUARD_ZXCVBN_BRIDGE_INPUT_TOO_LONG = 4,
};

/*
 * Explains what is wrong with the password. Reported in the result's
 * `warning` field, which is KEYGUARD_ZXCVBN_WARNING_NONE when the estimator
 * has no specific complaint.
 */
enum keyguard_zxcvbn_warning {
    KEYGUARD_ZXCVBN_WARNING_NONE = -1,
    KEYGUARD_ZXCVBN_WARNING_STRAIGHT_ROWS_OF_KEYS_ARE_EASY_TO_GUESS = 0,
    KEYGUARD_ZXCVBN_WARNING_SHORT_KEYBOARD_PATTERNS_ARE_EASY_TO_GUESS = 1,
    KEYGUARD_ZXCVBN_WARNING_REPEATS_LIKE_AAA_ARE_EASY_TO_GUESS = 2,
    KEYGUARD_ZXCVBN_WARNING_REPEATS_LIKE_ABCABC_ARE_ONLY_SLIGHTLY_HARDER_TO_GUESS = 3,
    KEYGUARD_ZXCVBN_WARNING_THIS_IS_A_TOP_10_PASSWORD = 4,
    KEYGUARD_ZXCVBN_WARNING_THIS_IS_A_TOP_100_PASSWORD = 5,
    KEYGUARD_ZXCVBN_WARNING_THIS_IS_A_COMMON_PASSWORD = 6,
    KEYGUARD_ZXCVBN_WARNING_THIS_IS_SIMILAR_TO_A_COMMONLY_USED_PASSWORD = 7,
    KEYGUARD_ZXCVBN_WARNING_SEQUENCES_LIKE_ABC_ARE_EASY_TO_GUESS = 8,
    KEYGUARD_ZXCVBN_WARNING_RECENT_YEARS_ARE_EASY_TO_GUESS = 9,
    KEYGUARD_ZXCVBN_WARNING_A_WORD_BY_ITSELF_IS_EASY_TO_GUESS = 10,
    KEYGUARD_ZXCVBN_WARNING_DATES_ARE_OFTEN_EASY_TO_GUESS = 11,
    KEYGUARD_ZXCVBN_WARNING_NAMES_AND_SURNAMES_BY_THEMSELVES_ARE_EASY_TO_GUESS = 12,
    KEYGUARD_ZXCVBN_WARNING_COMMON_NAMES_AND_SURNAMES_ARE_EASY_TO_GUESS = 13,
};

/*
 * Advice for choosing a better password. The result's `suggestions` field is
 * the bitwise OR of zero or more of these bits; no other bit is ever set.
 */
enum keyguard_zxcvbn_suggestion {
    KEYGUARD_ZXCVBN_SUGGESTION_USE_A_FEW_WORDS_AVOID_COMMON_PHRASES = 1 << 0,
    KEYGUARD_ZXCVBN_SUGGESTION_NO_NEED_FOR_SYMBOLS_DIGITS_OR_UPPERCASE_LETTERS = 1 << 1,
    KEYGUARD_ZXCVBN_SUGGESTION_ADD_ANOTHER_WORD_OR_TWO = 1 << 2,
    KEYGUARD_ZXCVBN_SUGGESTION_CAPITALIZATION_DOESNT_HELP_VERY_MUCH = 1 << 3,
    KEYGUARD_ZXCVBN_SUGGESTION_ALL_UPPERCASE_IS_ALMOST_AS_EASY_TO_GUESS_AS_ALL_LOWERCASE = 1 << 4,
    KEYGUARD_ZXCVBN_SUGGESTION_REVERSED_WORDS_ARENT_MUCH_HARDER_TO_GUESS = 1 << 5,
    KEYGUARD_ZXCVBN_SUGGESTION_PREDICTABLE_SUBSTITUTIONS_DONT_HELP_VERY_MUCH = 1 << 6,
    KEYGUARD_ZXCVBN_SUGGESTION_USE_A_LONGER_KEYBOARD_PATTERN_WITH_MORE_TURNS = 1 << 7,
    KEYGUARD_ZXCVBN_SUGGESTION_AVOID_REPEATED_WORDS_AND_CHARACTERS = 1 << 8,
    KEYGUARD_ZXCVBN_SUGGESTION_AVOID_SEQUENCES = 1 << 9,
    KEYGUARD_ZXCVBN_SUGGESTION_AVOID_RECENT_YEARS = 1 << 10,
    KEYGUARD_ZXCVBN_SUGGESTION_AVOID_YEARS_THAT_ARE_ASSOCIATED_WITH_YOU = 1 << 11,
    KEYGUARD_ZXCVBN_SUGGESTION_AVOID_DATES_AND_YEARS_THAT_ARE_ASSOCIATED_WITH_YOU = 1 << 12,
};

enum {
    KEYGUARD_ZXCVBN_RESULT_VERSION = 1,
    /* Largest accepted password, in UTF-8 bytes. */
    KEYGUARD_ZXCVBN_MAX_PASSWORD_BYTES = 256,
    /* Largest accepted number of user inputs. */
    KEYGUARD_ZXCVBN_MAX_USER_INPUTS = 64,
    /* Largest accepted user input, in UTF-8 bytes. */
    KEYGUARD_ZXCVBN_MAX_USER_INPUT_BYTES = 256,
};

/*
 * Borrowed UTF-8 string. `ptr` may be NULL only when `len` is zero. The bytes
 * must stay readable for the duration of the call.
 */
struct keyguard_zxcvbn_str_v1 {
    const uint8_t *ptr;
    size_t len;
};

/*
 * Size- and version-tagged estimation output. Before calling
 * keyguard_zxcvbn_estimate, initialize `size` to sizeof(struct
 * keyguard_zxcvbn_result_v1). The remaining fields may be uninitialized; on
 * success every field is written, and `size` is overwritten with the number of
 * bytes the bridge actually wrote.
 *
 * `warning` is a keyguard_zxcvbn_warning value. `suggestions` is a bitmask of
 * keyguard_zxcvbn_suggestion bits. `guesses_log10` is negative infinity for an
 * empty password, whose `guesses` is zero. The reserved fields are always
 * zero. Passwords and user inputs never travel back across the ABI.
 */
struct keyguard_zxcvbn_result_v1 {
    uint32_t size;
    uint32_t version;
    uint32_t score;
    int32_t warning;
    uint32_t suggestions;
    uint32_t reserved0;
    uint64_t guesses;
    double guesses_log10;
    double online_throttling_100_per_hour;
    double online_no_throttling_10_per_second;
    double offline_slow_hashing_1e4_per_second;
    double offline_fast_hashing_1e10_per_second;
    uint64_t reserved[2];
};

/**
 * Returns the native function ABI version.
 */
uint32_t keyguard_zxcvbn_abi_version(void);

/**
 * Estimates the strength of a password, biased by optional user inputs such
 * as a username or an item name.
 *
 * `password_ptr` may be NULL when `password_len` is zero, and `user_inputs`
 * may be NULL when `user_inputs_len` is zero. `result` must be non-NULL with
 * `size` initialized to at least sizeof(struct keyguard_zxcvbn_result_v1);
 * anything smaller, a non-UTF-8 input, or more than
 * KEYGUARD_ZXCVBN_MAX_USER_INPUTS inputs returns the packed
 * KEYGUARD_ZXCVBN_BRIDGE_INVALID_ARGUMENT failure. A password longer than
 * KEYGUARD_ZXCVBN_MAX_PASSWORD_BYTES, or a user input longer than
 * KEYGUARD_ZXCVBN_MAX_USER_INPUT_BYTES, returns the packed
 * KEYGUARD_ZXCVBN_BRIDGE_INPUT_TOO_LONG failure.
 *
 * Returns zero after writing `result`, or a packed negative failure that
 * leaves `result` unchanged. Estimation is pure and safe to call
 * concurrently; the first non-empty call initializes the dictionaries.
 */
int64_t keyguard_zxcvbn_estimate(
    const uint8_t *password_ptr,
    size_t password_len,
    const struct keyguard_zxcvbn_str_v1 *user_inputs,
    size_t user_inputs_len,
    struct keyguard_zxcvbn_result_v1 *result
);

#ifdef __cplusplus
}
#endif

#endif /* KEYGUARD_ZXCVBN_H */
