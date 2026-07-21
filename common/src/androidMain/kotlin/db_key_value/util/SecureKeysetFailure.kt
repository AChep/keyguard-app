package db_key_value.encrypted

import java.io.CharConversionException
import java.io.IOException

/**
 * Tink is a runtime-only dependency of AndroidX Security, so its protobuf exception
 * is intentionally recognized without adding it to this module's compile API. On
 * Android the exception is `tink-android`'s shaded copy
 * (`com.google.crypto.tink.shaded.protobuf.InvalidProtocolBufferException`), so the
 * match is by suffix and covers the plain `com.google.protobuf` variant as well.
 */
internal fun Throwable.isMalformedTinkKeyset(): Boolean =
    this is CharConversionException ||
        this is IOException &&
        generateSequence<Class<*>>(javaClass) { it.superclass }
            .any { it.name.endsWith(INVALID_PROTOCOL_BUFFER_EXCEPTION_SUFFIX) }

private const val INVALID_PROTOCOL_BUFFER_EXCEPTION_SUFFIX =
    ".protobuf.InvalidProtocolBufferException"
