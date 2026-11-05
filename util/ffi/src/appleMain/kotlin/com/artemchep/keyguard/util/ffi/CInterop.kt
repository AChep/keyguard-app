@file:OptIn(ExperimentalForeignApi::class)

package com.artemchep.keyguard.util.ffi

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlin.text.CharacterCodingException

/**
 * Encodes this string as strict UTF-8, or returns `null` for an unpaired surrogate. The C
 * ABIs read strings with `str::from_utf8` and the JNI bridges reject such a string, so a
 * bridge rejects it before the native call, with its own invalid-argument result.
 */
fun String.encodeToStrictUtf8OrNull(): ByteArray? = try {
    encodeToByteArray(throwOnInvalidSequence = true)
} catch (_: CharacterCodingException) {
    null
}

/** Whether `[offset, offset + length)` lies within this array. Never overflows. */
fun ByteArray.hasRange(offset: Int, length: Int): Boolean =
    offset >= 0 && length >= 0 && offset <= size - length

/**
 * Passes `[offset, offset + length)` of this array to [block], pinned for the call only, or
 * a null pointer when [length] is 0, which the C ABIs take for an empty buffer.
 *
 * @throws IllegalArgumentException when the range is outside the array; a bridge that
 * answers a scalar instead checks [hasRange] first.
 */
inline fun <T> ByteArray.withNativePointer(
    offset: Int = 0,
    length: Int = size - offset,
    block: (CPointer<UByteVar>?, ULong) -> T,
): T {
    require(hasRange(offset, length)) { "The range is outside the array" }
    return if (length == 0) {
        block(null, 0uL)
    } else {
        usePinned { pinned -> block(pinned.addressOf(offset).reinterpret(), length.toULong()) }
    }
}

/**
 * Passes this string as strict UTF-8 to [block], as [withNativePointer] does, or returns
 * [invalid] without calling it when the string has an unpaired surrogate.
 */
inline fun String.withStrictUtf8(
    invalid: Long,
    block: (CPointer<UByteVar>?, ULong) -> Long,
): Long = encodeToStrictUtf8OrNull()?.withNativePointer(block = block) ?: invalid
