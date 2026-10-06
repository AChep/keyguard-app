package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.S3Location
import com.artemchep.keyguard.common.service.s3.S3ClientFactory
import com.artemchep.keyguard.common.service.s3.requireConditionalWrites
import com.artemchep.keyguard.common.service.s3.toS3ClientConfig
import com.artemchep.keyguard.common.usecase.CheckS3Connection
import com.artemchep.keyguard.util.io.readByteArrayAndClose
import com.artemchep.keyguard.util.s3.S3ByteRange
import com.artemchep.keyguard.util.s3.S3Client
import com.artemchep.keyguard.util.s3.S3Exception
import com.artemchep.keyguard.util.s3.S3WritePrecondition
import kotlin.random.Random
import kotlin.time.Clock

class CheckS3ConnectionImpl(
    private val clientFactory: S3ClientFactory,
) : CheckS3Connection {
    override fun invoke(
        location: S3Location,
    ): IO<Unit> = ioEffect {
        val client = clientFactory.create(location.toS3ClientConfig())
        try {
            when (location) {
                is S3Location.Prefix -> testReadWrite(client, location.prefix)
                is S3Location.Object -> testRead(client, location.key)
            }
        } finally {
            client.close()
        }
    }

    private suspend fun testReadWrite(
        client: S3Client,
        prefix: String,
    ) {
        client.listObjects(
            prefix = prefix,
            maxKeys = 1,
        )
        val probeKey = prefix + createS3ConnectionProbeKey()
        val payload = S3_CONNECTION_CHECK_PAYLOAD
        try {
            client.putObject(
                key = probeKey,
                bytes = payload,
                precondition = S3WritePrecondition.IfNoneMatch,
            )
            val read = client.getObject(probeKey).readByteArrayAndClose()
            check(payload.contentEquals(read)) {
                "S3 probe read returned different bytes."
            }
            client.requireConditionalWrites(probeKey, payload)
        } finally {
            try {
                client.deleteObject(probeKey)
            } catch (_: Exception) {
                // Best-effort cleanup must not hide the primary probe failure.
            }
        }
    }

    private suspend fun testRead(
        client: S3Client,
        key: String,
    ) {
        // A ranged GET needs only read access and, unlike HEAD, returns an
        // error body that tells a bad signature apart from a denied read.
        try {
            client.getObject(key, S3ByteRange(offset = 0L, length = 1L)).readByteArrayAndClose()
        } catch (_: S3Exception.InvalidRange) {
            // An empty object has no first byte, but it does exist.
        }
    }
}

private fun createS3ConnectionProbeKey(): String {
    val timestamp = Clock.System.now().toEpochMilliseconds()
    val nonce = Random.nextLong().toString().replace("-", "n")
    return "health-check/$timestamp-$nonce.probe"
}

private val S3_CONNECTION_CHECK_PAYLOAD =
    "keyguard-s3-test\n".encodeToByteArray()
