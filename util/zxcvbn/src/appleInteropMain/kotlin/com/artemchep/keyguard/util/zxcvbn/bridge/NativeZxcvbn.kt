@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.artemchep.keyguard.util.zxcvbn.bridge

import com.artemchep.keyguard.util.zxcvbn.ZxcvbnException
import com.artemchep.keyguard.util.zxcvbn.ffi.keyguard_zxcvbn_abi_version
import com.artemchep.keyguard.util.zxcvbn.ffi.keyguard_zxcvbn_estimate
import com.artemchep.keyguard.util.zxcvbn.ffi.keyguard_zxcvbn_result_v1
import com.artemchep.keyguard.util.zxcvbn.ffi.keyguard_zxcvbn_str_v1
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlin.concurrent.Volatile
import kotlin.text.CharacterCodingException

internal actual object NativeZxcvbn {
    @Volatile
    private var abiVerified = false

    actual fun estimate(
        password: String,
        userInputs: List<String>,
        out: LongArray,
    ): Long {
        ensureCompatibleAbi()
        val arguments = encodeArguments(password, userInputs, out)
            // The C ABI would reject these too, but rejecting them here keeps
            // the scalar identical to the JNI bridge's.
            ?: return NATIVE_ZXCVBN_BRIDGE_INVALID_ARGUMENT
        return memScoped {
            val result = alloc<keyguard_zxcvbn_result_v1>()
            result.size = sizeOf<keyguard_zxcvbn_result_v1>().convert()
            val inputs = allocUserInputs(arguments.userInputs)
            val status = arguments.password.withNativePointer { pointer, size ->
                keyguard_zxcvbn_estimate(
                    pointer,
                    size,
                    inputs,
                    arguments.userInputs.size.convert(),
                    result.ptr,
                )
            }
            if (status == NATIVE_ZXCVBN_STATUS_SUCCESS) {
                result.copyInto(out)
            }
            status
        }
    }

    /**
     * Validates and encodes the estimate arguments, or reports `null` when
     * the bridge must refuse them.
     */
    private fun encodeArguments(
        password: String,
        userInputs: List<String>,
        out: LongArray,
    ): EncodedArguments? {
        if (
            out.size != NATIVE_ZXCVBN_JNI_FIELD_COUNT ||
            userInputs.size > NATIVE_ZXCVBN_MAX_USER_INPUTS
        ) {
            return null
        }
        val encodedPassword = password.strictUtf8OrNull()
        val encodedUserInputs = userInputs.mapNotNull { userInput ->
            userInput.strictUtf8OrNull()
        }
        return if (
            encodedPassword != null &&
            encodedUserInputs.size == userInputs.size
        ) {
            EncodedArguments(
                password = encodedPassword,
                userInputs = encodedUserInputs,
            )
        } else {
            null
        }
    }

    private fun ensureCompatibleAbi() {
        if (abiVerified) return
        val actual = keyguard_zxcvbn_abi_version().toInt()
        if (actual != NATIVE_ZXCVBN_ABI_VERSION) {
            throw ZxcvbnException(
                "Unsupported native zxcvbn ABI $actual; expected $NATIVE_ZXCVBN_ABI_VERSION",
            )
        }
        abiVerified = true
    }

    /**
     * Copies the user inputs into scope-owned native memory.
     *
     * The bytes are copied rather than pinned because pinning an arbitrary
     * number of arrays would need an equally deep nesting of `usePinned`
     * blocks; the inputs are short and bounded by
     * [NATIVE_ZXCVBN_MAX_USER_INPUTS].
     */
    private fun MemScope.allocUserInputs(
        userInputs: List<ByteArray>,
    ): CPointer<keyguard_zxcvbn_str_v1>? {
        if (userInputs.isEmpty()) return null
        val array = allocArray<keyguard_zxcvbn_str_v1>(userInputs.size)
        userInputs.forEachIndexed { index, bytes ->
            val entry = array[index]
            if (bytes.isEmpty()) {
                entry.ptr = null
                entry.len = 0uL.convert()
            } else {
                val buffer = allocArray<UByteVar>(bytes.size)
                bytes.forEachIndexed { byteIndex, byte ->
                    buffer[byteIndex] = byte.toUByte()
                }
                entry.ptr = buffer
                entry.len = bytes.size.convert()
            }
        }
        return array
    }

    /**
     * Encodes a string for the C ABI, or reports `null` when it is not
     * representable as UTF-8.
     *
     * A string containing an unpaired surrogate cannot be encoded, and the C
     * ABI validates its input with `str::from_utf8`. The caller turns `null`
     * into [NATIVE_ZXCVBN_BRIDGE_INVALID_ARGUMENT] — the very scalar the JNI
     * bridge returns for the same input — instead of throwing, so both
     * bridges fail identically and the caller's decode path decides the
     * exception type.
     */
    private fun String.strictUtf8OrNull(): ByteArray? = try {
        encodeToByteArray(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }

    private inline fun <T> ByteArray.withNativePointer(
        block: (CPointer<UByteVar>?, ULong) -> T,
    ): T = if (isEmpty()) {
        // The ABI allows a null pointer when the length is zero, and an empty
        // password is a valid estimate.
        block(null, 0uL)
    } else {
        usePinned { pinned ->
            block(
                pinned.addressOf(0).reinterpret(),
                size.convert(),
            )
        }
    }

    private fun keyguard_zxcvbn_result_v1.copyInto(out: LongArray) {
        out[NATIVE_ZXCVBN_FIELD_SIZE] = size.toLong()
        out[NATIVE_ZXCVBN_FIELD_VERSION] = version.toLong()
        out[NATIVE_ZXCVBN_FIELD_SCORE] = score.toLong()
        out[NATIVE_ZXCVBN_FIELD_WARNING] = warning.toLong()
        out[NATIVE_ZXCVBN_FIELD_SUGGESTIONS] = suggestions.toLong()
        // The count is unsigned on the wire and can exceed Long.MAX_VALUE;
        // saturate exactly as the JNI bridge does so both report the same
        // ceiling instead of a wrapped negative count.
        out[NATIVE_ZXCVBN_FIELD_GUESSES] = if (guesses > Long.MAX_VALUE.toULong()) {
            Long.MAX_VALUE
        } else {
            guesses.toLong()
        }
        out[NATIVE_ZXCVBN_FIELD_GUESSES_LOG10] = guesses_log10.toRawBits()
        out[NATIVE_ZXCVBN_FIELD_ONLINE_THROTTLING_100_PER_HOUR] =
            online_throttling_100_per_hour.toRawBits()
        out[NATIVE_ZXCVBN_FIELD_ONLINE_NO_THROTTLING_10_PER_SECOND] =
            online_no_throttling_10_per_second.toRawBits()
        out[NATIVE_ZXCVBN_FIELD_OFFLINE_SLOW_HASHING_1E4_PER_SECOND] =
            offline_slow_hashing_1e4_per_second.toRawBits()
        out[NATIVE_ZXCVBN_FIELD_OFFLINE_FAST_HASHING_1E10_PER_SECOND] =
            offline_fast_hashing_1e10_per_second.toRawBits()
    }
}

private const val NATIVE_ZXCVBN_STATUS_SUCCESS: Long = 0L

/** UTF-8 encoded, length-validated arguments of one estimate. */
private class EncodedArguments(
    val password: ByteArray,
    val userInputs: List<ByteArray>,
)
