#ifndef KEYGUARD_CRYPTO_H
#define KEYGUARD_CRYPTO_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct KeyguardCryptoBuffer {
    uint8_t *ptr;
    size_t len;
    size_t capacity;
} KeyguardCryptoBuffer;

enum KeyguardCryptoTransportStatus {
    KEYGUARD_CRYPTO_TRANSPORT_OK = 0,
    KEYGUARD_CRYPTO_TRANSPORT_INVALID_POINTER = -1,
    KEYGUARD_CRYPTO_TRANSPORT_PANIC = -2,
    KEYGUARD_CRYPTO_TRANSPORT_INTERNAL = -3,
};

/* Stable operation codes shared with NativeCryptoErrorCode. */
enum KeyguardCryptoErrorCode {
    KEYGUARD_CRYPTO_OK = 0,
    KEYGUARD_CRYPTO_INVALID_REQUEST = 1,
    KEYGUARD_CRYPTO_UNSUPPORTED_PROTOCOL = 2,
    KEYGUARD_CRYPTO_INVALID_ARGUMENT = 3,
    KEYGUARD_CRYPTO_RESOURCE_LIMIT = 4,
    KEYGUARD_CRYPTO_CRYPTO_FAILURE = 5,
    KEYGUARD_CRYPTO_AUTHENTICATION_FAILED = 6,
    KEYGUARD_CRYPTO_INVALID_SESSION = 7,
    KEYGUARD_CRYPTO_PANIC = 8,
    KEYGUARD_CRYPTO_INTERNAL = 9,
    KEYGUARD_CRYPTO_UNSUPPORTED_KEY_VERSION = 10,
    KEYGUARD_CRYPTO_NO_USABLE_KEY = 11,
};

uint32_t keyguard_crypto_abi_version(void);
uint64_t keyguard_crypto_capabilities(void);

/*
 * Generates a secure random integer without a protobuf control envelope.
 * An `exclusive_upper_bound` of 0 requests an unbounded signed integer;
 * positive values return a value in [0, exclusive_upper_bound). On failure,
 * `output` is cleared to 0 and a stable KeyguardCryptoErrorCode is returned.
 */
int32_t keyguard_crypto_random_int(
    uint32_t exclusive_upper_bound,
    int32_t *output
);

int32_t keyguard_crypto_call(
    const uint8_t *input_ptr,
    size_t input_len,
    KeyguardCryptoBuffer *output
);

int32_t keyguard_crypto_stream_open(
    const uint8_t *input_ptr,
    size_t input_len,
    KeyguardCryptoBuffer *output
);

int32_t keyguard_crypto_stream_update(
    uint64_t handle,
    const uint8_t *input_ptr,
    size_t input_len,
    KeyguardCryptoBuffer *output
);

int32_t keyguard_crypto_stream_finish(
    uint64_t handle,
    KeyguardCryptoBuffer *output
);

int32_t keyguard_crypto_stream_close(
    uint64_t handle,
    KeyguardCryptoBuffer *output
);

/*
 * Encrypts plaintext with AES-CBC-PKCS7 and authenticates IV || ciphertext
 * with HMAC-SHA256. All output storage is caller-owned. `ciphertext_capacity`
 * must equal the PKCS7 ciphertext length and `mac_capacity` must equal 32.
 * On success, returns KEYGUARD_CRYPTO_OK and writes the ciphertext length to
 * `ciphertext_len`. On failure, output buffers are cleared and the length is 0.
 * Every non-empty pointer range must be readable/writable for its declared
 * length and output ranges must not overlap any input or each other. The
 * `ciphertext_len` slot must not overlap any byte range.
 */
int32_t keyguard_crypto_aes_cbc_pkcs7_hmac_sha256_encrypt(
    const uint8_t *encryption_key_ptr,
    size_t encryption_key_len,
    const uint8_t *mac_key_ptr,
    size_t mac_key_len,
    const uint8_t *iv_ptr,
    size_t iv_len,
    const uint8_t *plaintext_ptr,
    size_t plaintext_len,
    uint8_t *ciphertext_ptr,
    size_t ciphertext_capacity,
    uint8_t *mac_ptr,
    size_t mac_capacity,
    size_t *ciphertext_len
);

/*
 * Authenticates IV || ciphertext with HMAC-SHA256 before AES-CBC-PKCS7
 * decryption. `plaintext_capacity` must equal `ciphertext_len`. Any expected
 * MAC length or value mismatch is reported as authentication failure. On
 * success, writes the unpadded plaintext length to `plaintext_len`; unused
 * output bytes are cleared. On failure, the complete plaintext buffer is
 * cleared and the reported length is 0. Pointer validity and non-overlap
 * requirements match the encrypt operation, and the `plaintext_len` slot must
 * not overlap any byte range.
 */
int32_t keyguard_crypto_aes_cbc_pkcs7_hmac_sha256_decrypt(
    const uint8_t *encryption_key_ptr,
    size_t encryption_key_len,
    const uint8_t *mac_key_ptr,
    size_t mac_key_len,
    const uint8_t *iv_ptr,
    size_t iv_len,
    const uint8_t *ciphertext_ptr,
    size_t ciphertext_len,
    const uint8_t *expected_mac_ptr,
    size_t expected_mac_len,
    uint8_t *plaintext_ptr,
    size_t plaintext_capacity,
    size_t *plaintext_len
);

void keyguard_crypto_buffer_free(KeyguardCryptoBuffer *buffer);

#ifdef __cplusplus
}
#endif

#endif
