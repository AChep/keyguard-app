package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.file.AtomicFileWriteOutcome
import com.artemchep.keyguard.common.service.file.FileAccessToken
import com.artemchep.keyguard.common.service.file.FileMetadata
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.file.FileServiceImpl
import com.artemchep.keyguard.platform.appleBookmarkResolutionOptions
import com.artemchep.keyguard.platform.toSecurityScopedBookmarkDataOrNull
import com.artemchep.keyguard.platform.withSecurityScopedAccess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import platform.Foundation.NSURL

class FileServiceApple(
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
        // The file pickers create a 0-byte placeholder before returning a URI.
        delegate.metadata(scopedUri)?.size
            ?.let { size -> size > 0L }
            ?: delegate.exists(scopedUri)
    }

    override fun metadata(
        uri: String,
        accessToken: FileAccessToken?,
    ): FileMetadata? = withSecurityScopedUrl(
        uri = uri,
        accessToken = accessToken,
    ) { scopedUri ->
        delegate.metadata(scopedUri)
    }

    override fun readFromFile(uri: String): Source = delegate.readFromFile(uri)

    override fun readFromFile(
        uri: String,
        accessToken: FileAccessToken?,
    ): Source {
        accessToken ?: return readFromFile(uri)
        return withSecurityScopedUrl(
            uri = uri,
            accessToken = accessToken,
        ) { scopedUri ->
            Buffer().apply {
                delegate.readFromFile(scopedUri)
                    .use { source -> transferFrom(source) }
            }
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
        ).buffered()
    }

    override fun atomicWriteToFile(
        uri: String,
        accessToken: FileAccessToken?,
        write: (Sink) -> Unit,
    ): AtomicFileWriteOutcome {
        accessToken ?: return delegate.atomicWriteToFile(
            uri = uri,
            accessToken = null,
            write = write,
        )
        // A security-scoped bookmark grants access to the resolved resource. It
        // does not guarantee that we can create a sibling temp file and rename it
        // over the picked document, so scoped destinations use the caller's
        // direct-write fallback instead of promising atomic replacement. Note
        // that the scoped fallback sink buffers the whole payload in memory
        // before writing it out, so scoped saves get neither the streaming nor
        // the atomic-replacement benefits of this API.
        return AtomicFileWriteOutcome.Unsupported
    }

    override fun delete(uri: String): Boolean = delegate.delete(uri)

    private fun <T> withSecurityScopedUrl(
        uri: String,
        accessToken: FileAccessToken?,
        block: (String) -> T,
    ): T {
        val url = accessToken
            ?.let(::resolveSecurityScopedUrl)
            ?: return block(uri)
        return url.withSecurityScopedAccess {
            block(url.absoluteString ?: uri)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun resolveSecurityScopedUrl(
        accessToken: FileAccessToken,
    ): NSURL? {
        val data = accessToken.value.toSecurityScopedBookmarkDataOrNull()
            ?: return null
        return NSURL.URLByResolvingBookmarkData(
            bookmarkData = data,
            options = appleBookmarkResolutionOptions,
            relativeToURL = null,
            bookmarkDataIsStale = null,
            error = null,
        )
    }

    private inner class SecurityScopedBufferedRawSink(
        private val uri: String,
        private val accessToken: FileAccessToken,
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
            withSecurityScopedUrl(
                uri = uri,
                accessToken = accessToken,
            ) { scopedUri ->
                delegate.writeToFile(scopedUri)
                    .use { sink -> sink.transferFrom(buffer) }
            }
        }
    }
}
