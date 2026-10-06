package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.s3.S3ClientFactory
import com.artemchep.keyguard.common.service.s3.s3ClientConfigOf
import com.artemchep.keyguard.common.usecase.ListS3Directory

class ListS3DirectoryImpl(
    private val clientFactory: S3ClientFactory,
) : ListS3Directory {
    override fun invoke(
        request: ListS3Directory.Request,
    ): IO<List<ListS3Directory.Child>> = ioEffect {
        val client = clientFactory.create(
            s3ClientConfigOf(
                bucket = request.bucket,
                accessKey = request.accessKey,
            ),
        )
        try {
            val children = mutableListOf<ListS3Directory.Child>()
            var token: String? = null
            var pages = 0
            do {
                val page = client.listObjects(
                    prefix = request.prefix,
                    delimiter = DELIMITER,
                    continuationToken = token,
                )
                page.commonPrefixes.forEach { key ->
                    val name = key.removePrefix(request.prefix).removeSuffix(DELIMITER)
                    if (name.isNotEmpty()) {
                        children += ListS3Directory.Child(
                            key = key,
                            name = name,
                            isFolder = true,
                            size = null,
                            lastModified = null,
                        )
                    }
                }
                page.objects.forEach { entry ->
                    // Skip the folder marker that consoles create for the prefix itself.
                    val name = entry.key.removePrefix(request.prefix)
                    if (name.isNotEmpty() && !name.endsWith(DELIMITER)) {
                        children += ListS3Directory.Child(
                            key = entry.key,
                            name = name,
                            isFolder = false,
                            size = entry.size,
                            lastModified = entry.lastModified,
                        )
                    }
                }
                token = page.nextContinuationToken
                pages += 1
            } while (token != null && pages < MAX_PAGES)
            children
        } finally {
            client.close()
        }
    }
}

private const val DELIMITER = "/"

/** Caps a listing at about 20,000 entries; a picker cannot show more anyway. */
private const val MAX_PAGES = 20
