package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.model.S3AccessKey
import com.artemchep.keyguard.common.model.S3Bucket
import com.artemchep.keyguard.common.service.s3.InMemoryS3Client
import com.artemchep.keyguard.common.service.s3.InMemoryS3ClientFactory
import com.artemchep.keyguard.common.usecase.ListS3Directory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ListS3DirectoryImplTest {
    @Test
    fun `lists folders and files of a prefix across pages`() = runTest {
        val client = InMemoryS3Client(pageSize = 2)
        listOf("a/x.kdbx", "a/y.txt", "a/sub/z.kdbx", "a/sub2/w", "a/", "b/q").forEach { key ->
            client.objects[key] = byteArrayOf(1)
        }
        val factory = InMemoryS3ClientFactory(client)

        val children = ListS3DirectoryImpl(factory)(
            ListS3Directory.Request(
                bucket = S3Bucket(endpoint = null, region = null, name = "bucket"),
                accessKey = S3AccessKey("AKID", Password("secret")),
                prefix = "a/",
            ),
        ).bind()

        assertEquals(
            listOf(
                Triple("a/sub/", "sub", true),
                Triple("a/sub2/", "sub2", true),
                Triple("a/x.kdbx", "x.kdbx", false),
                Triple("a/y.txt", "y.txt", false),
            ),
            children
                .map { Triple(it.key, it.name, it.isFolder) }
                .sortedBy { it.first },
        )
        assertEquals(1, client.closeCalls)
    }
}
