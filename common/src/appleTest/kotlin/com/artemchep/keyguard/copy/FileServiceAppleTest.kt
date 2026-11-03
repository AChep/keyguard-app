package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.file.FileAccessToken
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.file.FileServiceImpl
import com.artemchep.keyguard.nativecrypto.NativeCryptoPkcs12
import com.artemchep.keyguard.platform.appleBookmarkCreationOptions
import com.artemchep.keyguard.platform.toSecurityScopedBookmarkToken
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class FileServiceAppleTest {
    @Test
    fun boundedReadDoesNotBufferTheWholeBookmarkedFile() {
        val limit = NativeCryptoPkcs12.MAX_BUNDLE_BYTES.toLong()
        withBookmarkedSource(size = limit * 4) { files, token, upstream ->
            val oversized = files.readFromFile(STALE_URI, token) { source ->
                assertEquals(0, upstream.closes)
                source.request(limit + 1)
            }

            assertTrue(oversized)
            // A buffered source may read one extra segment, but not the whole file.
            assertTrue(upstream.bytesRead in (limit + 1)..(limit + 8192))
            assertEquals(1, upstream.closes)
        }
    }

    @Test
    fun bookmarkedReadReturnsContentAndClosesSource() {
        withBookmarkedSource(size = 16) { files, token, upstream ->
            val bytes = files.readFromFile(STALE_URI, token) { it.readByteArray() }

            assertContentEquals(ByteArray(16) { 1 }, bytes)
            assertEquals(1, upstream.closes)
        }
    }

    @Test
    fun failedBookmarkedReadClosesSource() {
        withBookmarkedSource(size = 16) { files, token, upstream ->
            val failure = IllegalArgumentException("Rejected file")
            val actual = assertFailsWith<IllegalArgumentException> {
                files.readFromFile(STALE_URI, token) { source ->
                    assertTrue(source.request(1))
                    throw failure
                }
            }

            assertSame(failure, actual)
            assertEquals(1, upstream.closes)
        }
    }

    private fun withBookmarkedSource(
        size: Long,
        block: (FileServiceApple, FileAccessToken, ProbeSource) -> Unit,
    ) {
        val name = "keyguard-file-service-test-${NSUUID().UUIDString}.p12"
        val path = Path(NSTemporaryDirectory(), name)
        SystemFileSystem.sink(path).close()
        try {
            val url = NSURL.fileURLWithPath(path.toString())
            val bookmark = assertNotNull(
                url.bookmarkDataWithOptions(appleBookmarkCreationOptions, null, null, null),
            )
            val token = FileAccessToken(bookmark.toSecurityScopedBookmarkToken())
            val upstream = ProbeSource(size)
            val delegate = object : FileService by FileServiceImpl() {
                override fun readFromFile(uri: String): Source {
                    // The bookmark must resolve the actual file instead of the stale URI.
                    assertEquals(name, uri.substringAfterLast('/'))
                    return upstream.buffered()
                }
            }
            block(FileServiceApple(delegate), token, upstream)
        } finally {
            SystemFileSystem.delete(path)
        }
    }

    private class ProbeSource(private val size: Long) : RawSource {
        var bytesRead = 0L
        var closes = 0

        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            if (bytesRead == size) return -1
            val count = minOf(byteCount, size - bytesRead, 8192).toInt()
            // Fail safely if a regression tries to eagerly buffer a huge input.
            check(bytesRead + count <= NativeCryptoPkcs12.MAX_BUNDLE_BYTES.toLong() + 8192)
            sink.write(ByteArray(count) { 1 })
            bytesRead += count
            return count.toLong()
        }

        override fun close() {
            closes++
        }
    }

    private companion object {
        const val STALE_URI = "file:///stale-certificate.p12"
    }
}
