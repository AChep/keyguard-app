package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.exception.S3ConditionalWritesUnsupportedException
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.model.S3AccessKey
import com.artemchep.keyguard.common.model.S3Bucket
import com.artemchep.keyguard.common.model.S3Location
import com.artemchep.keyguard.common.service.s3.InMemoryS3Client
import com.artemchep.keyguard.common.service.s3.InMemoryS3ClientFactory
import com.artemchep.keyguard.util.s3.S3Exception
import com.artemchep.keyguard.util.s3.S3WritePrecondition
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CheckS3ConnectionImplTest {
    private val bucket = S3Bucket(endpoint = null, region = null, name = "bucket")
    private val accessKey = S3AccessKey("AKID", Password("secret"))

    @Test
    fun `prefix check writes reads and deletes a probe under the prefix`() = runTest {
        val factory = InMemoryS3ClientFactory()

        CheckS3ConnectionImpl(factory)(
            S3Location.Prefix(bucket, accessKey, prefix = "backups/"),
        ).bind()

        val (key, precondition) = factory.client.preconditions.first()
        assertTrue(key.startsWith("backups/health-check/"))
        assertEquals(S3WritePrecondition.IfNoneMatch, precondition)
        // The server must enforce both kinds of conditional writes.
        val probes = factory.client.preconditions.drop(1)
        assertTrue(probes.all { (probeKey, _) -> probeKey == key })
        assertEquals(S3WritePrecondition.IfNoneMatch, probes[0].second)
        assertIs<S3WritePrecondition.IfMatch>(probes[1].second)
        assertEquals(2, probes.size)
        assertEquals(listOf(key), factory.client.deleted)
        assertTrue(factory.client.objects.isEmpty())
        assertEquals(1, factory.client.closeCalls)
    }

    @Test
    fun `prefix check rejects a server that ignores conditional writes`() = runTest {
        val factory = InMemoryS3ClientFactory(InMemoryS3Client(ignoresPreconditions = true))

        assertFailsWith<S3ConditionalWritesUnsupportedException> {
            CheckS3ConnectionImpl(factory)(
                S3Location.Prefix(bucket, accessKey, prefix = "backups/"),
            ).bind()
        }
        assertTrue(factory.client.objects.isEmpty())
        assertEquals(1, factory.client.closeCalls)
    }

    @Test
    fun `object check reads the object`() = runTest {
        val factory = InMemoryS3ClientFactory()
        factory.client.objects["vault.kdbx"] = byteArrayOf(1, 2)
        factory.client.objects["empty.kdbx"] = byteArrayOf()
        val check = CheckS3ConnectionImpl(factory)

        check(S3Location.Object(bucket, accessKey, key = "vault.kdbx")).bind()
        check(S3Location.Object(bucket, accessKey, key = "empty.kdbx")).bind()
        assertFailsWith<S3Exception.NotFound> {
            check(S3Location.Object(bucket, accessKey, key = "missing.kdbx")).bind()
        }
        assertTrue(factory.client.preconditions.isEmpty())
    }
}
