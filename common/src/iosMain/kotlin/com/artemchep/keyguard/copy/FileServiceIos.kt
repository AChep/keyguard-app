package com.artemchep.keyguard.copy

import com.artemchep.keyguard.util.foundation.io.readByteArrayAndClose
import com.artemchep.keyguard.common.service.file.FileAccessToken
import com.artemchep.keyguard.common.service.file.FileMetadata
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.file.FileServiceImpl
import com.artemchep.keyguard.platform.toSecurityScopedBookmarkDataOrNull
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLBookmarkResolutionWithSecurityScope
import kotlin.time.Instant

private const val IOS_REFERENCE_DATE_UNIX_OFFSET_SECONDS = 978_307_200.0

class FileServiceIos(
    private val delegate: FileServiceImpl = FileServiceImpl(),
) : FileService {
    override fun exists(uri: String): Boolean = delegate.exists(uri)

    override fun exists(
        uri: String,
        accessToken: FileAccessToken?,
    ): Boolean = withSecurityScopedUrl(
        uri = uri,
        accessToken = accessToken,
    ) { scopedUri ->
        delegate.exists(scopedUri)
    }

    override fun metadata(
        uri: String,
        accessToken: FileAccessToken?,
    ): FileMetadata? = withSecurityScopedUrl(
        uri = uri,
        accessToken = accessToken,
    ) { scopedUri ->
        iosFileMetadata(scopedUri) ?: delegate.metadata(scopedUri)
    }

    override fun readFromFile(uri: String): Source = delegate.readFromFile(uri)

    override fun readFromFile(
        uri: String,
        accessToken: FileAccessToken?,
    ): Source {
        accessToken ?: return readFromFile(uri)
        val bytes = withSecurityScopedUrl(
            uri = uri,
            accessToken = accessToken,
        ) { scopedUri ->
            delegate.readFromFile(scopedUri)
                .readByteArrayAndClose()
        }
        return Buffer().apply {
            write(bytes)
        }
    }

    override fun writeToFile(uri: String): Sink = delegate.writeToFile(uri)

    override fun writeToFile(
        uri: String,
        accessToken: FileAccessToken?,
    ): Sink {
        accessToken ?: return writeToFile(uri)
        return SecurityScopedBufferedRawSink(
            uri = uri,
            accessToken = accessToken,
            fileService = this,
        ).buffered()
    }

    override fun atomicWriteToFile(
        uri: String,
        accessToken: FileAccessToken?,
        bytes: ByteArray,
    ): Boolean {
        accessToken ?: return delegate.atomicWriteToFile(
            uri = uri,
            accessToken = null,
            bytes = bytes,
        )
        // A security-scoped bookmark grants access to the resolved resource. It
        // does not guarantee that we can create a sibling temp file and rename it
        // over the picked document, so scoped destinations use the caller's
        // direct-write fallback instead of promising atomic replacement.
        return false
    }

    override fun delete(uri: String): Boolean = delegate.delete(uri)

    override fun atomicMove(
        sourceUri: String,
        destinationUri: String,
        accessToken: FileAccessToken?,
    ): Boolean {
        if (accessToken != null) {
            return false
        }
        return delegate.atomicMove(
            sourceUri = sourceUri,
            destinationUri = destinationUri,
        )
    }

    private fun writeScopedBytes(
        uri: String,
        accessToken: FileAccessToken,
        bytes: ByteArray,
    ) {
        withSecurityScopedUrl(
            uri = uri,
            accessToken = accessToken,
        ) { scopedUri ->
            delegate.writeToFile(scopedUri)
                .use { sink ->
                    sink.write(bytes)
                    sink.flush()
                }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun <T> withSecurityScopedUrl(
        uri: String,
        accessToken: FileAccessToken?,
        block: (String) -> T,
    ): T {
        accessToken ?: return block(uri)
        val url = resolveSecurityScopedUrl(
            uri = uri,
            accessToken = accessToken,
        ) ?: return block(uri)

        val didStartAccessing = url.startAccessingSecurityScopedResource()
        return try {
            block(url.absoluteString ?: uri)
        } finally {
            if (didStartAccessing) {
                url.stopAccessingSecurityScopedResource()
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun resolveSecurityScopedUrl(
        uri: String,
        accessToken: FileAccessToken,
    ): NSURL? = memScoped {
        val data = accessToken.value.toSecurityScopedBookmarkDataOrNull()
            ?: return@memScoped NSURL.URLWithString(uri)
        val stale = alloc<BooleanVar>()
        NSURL.URLByResolvingBookmarkData(
            bookmarkData = data,
            options = NSURLBookmarkResolutionWithSecurityScope,
            relativeToURL = null,
            bookmarkDataIsStale = stale.ptr,
            error = null,
        ) ?: NSURL.URLWithString(uri)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun iosFileMetadata(uri: String): FileMetadata? {
        val path = NSURL.URLWithString(uri)?.path
            ?: uri.removePrefix("file://")
                .takeIf { it != uri }
            ?: return null
        val attributes = NSFileManager.defaultManager
            .attributesOfItemAtPath(path, error = null)
            ?: return null
        return FileMetadata(
            lastModified = (attributes[NSFileModificationDate] as? NSDate)
                ?.timeIntervalSinceReferenceDate
                ?.let { referenceSeconds ->
                    val unixSeconds = referenceSeconds + IOS_REFERENCE_DATE_UNIX_OFFSET_SECONDS
                    Instant.fromEpochMilliseconds((unixSeconds * 1_000).toLong())
                },
            size = (attributes[NSFileSize] as? NSNumber)
                ?.longLongValue,
        )
    }

    private class SecurityScopedBufferedRawSink(
        private val uri: String,
        private val accessToken: FileAccessToken,
        private val fileService: FileServiceIos,
    ) : RawSink {
        private val buffer = Buffer()
        private var closed = false

        override fun write(
            source: Buffer,
            byteCount: Long,
        ) {
            check(!closed) {
                "Can not write to a closed sink."
            }
            buffer.write(source, byteCount)
        }

        override fun flush() = Unit

        override fun close() {
            if (closed) {
                return
            }
            closed = true
            val bytes = buffer.readByteArray()
            fileService.writeScopedBytes(
                uri = uri,
                accessToken = accessToken,
                bytes = bytes,
            )
        }
    }
}
