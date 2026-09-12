package com.artemchep.keyguard.common.service.browseragent

import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.util.toHex
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Derives the WebSocket-path shared secret from a pairing code and stores it
 * in a hex file that the browser agent reads via `--secret-path`.
 *
 * The derivation must stay in sync with `pairing.rs` in the agent binary:
 * HKDF-SHA256 with salt `keyguard-pairing-v1` and info `shared-secret`.
 */
class AgentPairingSecretFileWriter(
    private val logRepository: LogRepository,
) {
    companion object {
        private const val TAG = "AgentPairingSecretFileWriter"

        private val SECRET_PATH: Path = Path.of(
            System.getProperty("user.home"),
            ".config", "keyguard", "agent-secret.hex",
        )

        /**
         * Path of the hex-encoded shared secret file passed to the agent
         * binary via `--secret-path`.
         */
        val secretPath: Path get() = SECRET_PATH

        private val HKDF_SALT = "keyguard-pairing-v1".toByteArray()
        private val HKDF_INFO = "shared-secret".toByteArray()
        private const val SECRET_LENGTH_BYTES = 32

        /**
         * Characters used in the pairing code (unambiguous, no lookalikes).
         * Must match `CODE_CHARS` in `pairing.rs`.
         */
        val CODE_CHARS: String = "abcdefghjkmnpqrstuvwxyz23456789"

        const val PAIRING_CODE_LENGTH = 24

        fun generatePairingCode(random: java.security.SecureRandom = java.security.SecureRandom()): String {
            val sb = StringBuilder(PAIRING_CODE_LENGTH)
            repeat(PAIRING_CODE_LENGTH) {
                sb.append(CODE_CHARS[random.nextInt(CODE_CHARS.length)])
            }
            return sb.toString()
        }

        /**
         * HKDF-SHA256 extract-then-expand, producing [SECRET_LENGTH_BYTES] bytes.
         */
        fun deriveSharedSecret(pairingCode: String): ByteArray {
            val ikm = pairingCode.trim().toByteArray()

            // Extract: PRK = HMAC-SHA256(salt, IKM)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(HKDF_SALT, "HmacSHA256"))
            val prk = mac.doFinal(ikm)

            // Expand: T(1) = HMAC(PRK, info || 0x01), ...
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val okm = ByteArray(SECRET_LENGTH_BYTES)
            var t = ByteArray(0)
            var pos = 0
            var counter = 1
            while (pos < SECRET_LENGTH_BYTES) {
                mac.update(t)
                mac.update(HKDF_INFO)
                mac.update(counter.toByte())
                t = mac.doFinal()
                val n = minOf(t.size, SECRET_LENGTH_BYTES - pos)
                System.arraycopy(t, 0, okm, pos, n)
                pos += n
                counter++
            }
            return okm
        }
    }

    /**
     * Writes the raw shared secret as hex with mode 0600.
     */
    fun write(sharedSecret: ByteArray) {
        try {
            Files.createDirectories(SECRET_PATH.parent)
            Files.writeString(SECRET_PATH, sharedSecret.toHex())
            try {
                Files.setPosixFilePermissions(
                    SECRET_PATH,
                    PosixFilePermissions.fromString("rw-------"),
                )
            } catch (_: UnsupportedOperationException) {
                // Windows doesn't support POSIX permissions; skip.
            }
            logRepository.post(TAG, "Pairing secret file written: $SECRET_PATH", LogLevel.INFO)
        } catch (e: Exception) {
            logRepository.post(TAG, "Failed to write pairing secret file: ${e.message}", LogLevel.ERROR)
        }
    }

    /**
     * Deletes the secret file. Called when the WS server stops.
     */
    fun delete() {
        try {
            Files.deleteIfExists(SECRET_PATH)
            logRepository.post(TAG, "Pairing secret file deleted", LogLevel.INFO)
        } catch (e: Exception) {
            logRepository.post(TAG, "Failed to delete pairing secret file: ${e.message}", LogLevel.WARNING)
        }
    }
}
