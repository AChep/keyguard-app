package com.artemchep.keyguard.common.service.directorywatcher

import com.artemchep.keyguard.common.service.file.FileAccessToken
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.util.io.toFileUriString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FileWatcherServiceTest {
    @Test
    fun `access token overload preserves existing platform uri overrides`() = runTest {
        val uri = "content://documents/account.kdbx"
        val event = FileWatchEvent(LocalPath("/account.kdbx"), FileWatchEvent.Kind.MODIFIED, null)
        val service = object : FileWatcherService {
            override fun fileChangedFlow(file: LocalPath): Flow<FileWatchEvent> =
                error("A platform URI override must not be replaced by local path conversion.")

            override fun uriChangedFlow(uri: String): Flow<FileWatchEvent> {
                assertEquals("content://documents/account.kdbx", uri)
                return flowOf(event)
            }
        }

        assertEquals(listOf(event), service.uriChangedFlow(uri, FileAccessToken("grant")).toList())
    }

    @Test
    fun `access token overload still watches ordinary local file uris`() = runTest {
        val path = LocalPath(Path(SystemTemporaryDirectory, "account.kdbx").toString())
        val event = FileWatchEvent(path, FileWatchEvent.Kind.CREATED, null)
        val service = object : FileWatcherService {
            override fun fileChangedFlow(file: LocalPath): Flow<FileWatchEvent> {
                assertEquals(path, file)
                return flowOf(event)
            }
        }

        assertEquals(listOf(event), service.uriChangedFlow(path.toFileUriString(), null).toList())
    }

    @Test
    fun `unsupported uri suspends until cancelled instead of retrying a completed flow`() = runTest {
        val service = object : FileWatcherService {
            override fun fileChangedFlow(file: LocalPath): Flow<FileWatchEvent> =
                error("Remote URIs must not be watched as local files.")
        }
        val collection = backgroundScope.launch {
            service.uriChangedFlow("https://example.com/account.kdbx", null).collect()
            error("An unsupported URI must remain suspended until cancellation.")
        }
        runCurrent()
        assertTrue(collection.isActive)

        collection.cancelAndJoin()
        assertTrue(collection.isCancelled)
    }
}
