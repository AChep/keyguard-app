package com.artemchep.keyguard.common.service.s3

import com.artemchep.keyguard.common.exception.S3ConditionalWritesUnsupportedException
import com.artemchep.keyguard.util.s3.S3Client
import com.artemchep.keyguard.util.s3.S3Exception
import com.artemchep.keyguard.util.s3.S3WritePrecondition

/**
 * Checks that the server enforces conditional writes, against the existing
 * object at [key]. Some servers, such as Garage, accept conditional writes
 * but ignore the condition, so a write could overwrite a change of another
 * device. Such a server gets the object rewritten with [payload].
 */
internal suspend fun S3Client.requireConditionalWrites(
    key: String,
    payload: ByteArray,
) {
    val preconditions = listOf(
        S3WritePrecondition.IfNoneMatch,
        S3WritePrecondition.IfMatch(MISMATCHED_ETAG),
    )
    for (precondition in preconditions) {
        if (!enforces(key, payload, precondition)) {
            throw S3ConditionalWritesUnsupportedException()
        }
    }
}

private suspend fun S3Client.enforces(
    key: String,
    payload: ByteArray,
    precondition: S3WritePrecondition,
): Boolean = try {
    putObject(
        key = key,
        bytes = payload,
        precondition = precondition,
    )
    false
} catch (_: S3Exception.AlreadyExists) {
    true
} catch (_: S3Exception.PreconditionFailed) {
    true
}

/** An ETag that no object has. */
private const val MISMATCHED_ETAG = "\"00000000000000000000000000000000\""
