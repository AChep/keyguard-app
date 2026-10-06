package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.S3AccessKey
import com.artemchep.keyguard.common.model.S3Bucket
import kotlin.time.Instant

/** Lists the direct children of a prefix, rolling deeper keys up into folders. */
interface ListS3Directory {
    operator fun invoke(
        request: Request,
    ): IO<List<Child>>

    data class Request(
        val bucket: S3Bucket,
        val accessKey: S3AccessKey,
        /** Empty for the bucket root, otherwise ends with `/`. */
        val prefix: String,
    )

    data class Child(
        /** The full key; a folder's key ends with `/`. */
        val key: String,
        val name: String,
        val isFolder: Boolean,
        val size: Long?,
        val lastModified: Instant?,
    )
}
