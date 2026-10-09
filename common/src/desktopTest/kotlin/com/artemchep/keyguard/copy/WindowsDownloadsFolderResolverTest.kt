package com.artemchep.keyguard.copy

import net.harawata.appdirs.AppDirsException
import net.harawata.appdirs.impl.WindowsAppDirs.FolderId
import net.harawata.appdirs.impl.WindowsFolderResolver
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class WindowsDownloadsFolderResolverTest {
    private val missingFolders = WindowsFolderResolver { folderId ->
        throw AppDirsException("$folderId is missing")
    }

    @Test
    fun otherFoldersAreResolvedByTheDelegate() {
        val resolver = WindowsDownloadsFolderResolver(
            delegate = { folderId -> "C:\\Known\\$folderId" },
            resolveDownloads = { error("Only Downloads should use this resolver") },
        )
        FolderId.entries.filter { it != FolderId.DOWNLOADS }.forEach { folderId ->
            assertEquals("C:\\Known\\$folderId", resolver.resolveFolder(folderId))
        }
    }

    @Test
    fun redirectedDownloadsDoesNotUseTheVerifyingDelegate() {
        val configuredPath = "D:\\Offline\\Downloads"
        val resolver = WindowsDownloadsFolderResolver(
            delegate = missingFolders,
            resolveDownloads = { configuredPath },
        )
        assertEquals(configuredPath, resolver.resolveFolder(FolderId.DOWNLOADS))
    }

    @Test
    fun downloadsResolutionFailureIsPropagated() {
        val failure = AppDirsException("Downloads could not be resolved")
        val resolver = WindowsDownloadsFolderResolver(
            delegate = { "home/Downloads" },
            resolveDownloads = { throw failure },
        )
        val actual = assertFailsWith<AppDirsException> {
            resolver.resolveFolder(FolderId.DOWNLOADS)
        }
        assertSame(failure, actual)
    }

    @Test
    fun otherMissingFoldersStillFail() {
        val resolver = WindowsDownloadsFolderResolver(
            delegate = missingFolders,
            resolveDownloads = { error("Only Downloads should use this resolver") },
        )
        assertFailsWith<AppDirsException> {
            resolver.resolveFolder(FolderId.LOCAL_APPDATA)
        }
    }

    @Test
    fun missingRedirectedDownloadsOutsideHomeIsRejected() {
        val root = createTempDirectory("windows-redirected-downloads")
        try {
            val home = root.resolve("home").createDirectory()
            val drive = root.resolve("unmounted-drive").createDirectory()
            val configuredPath = drive.resolve("Downloads")
            val resolver = WindowsDownloadsFolderResolver(
                delegate = { home.resolve("Downloads").toString() },
                resolveDownloads = { configuredPath.toString() },
            )
            val platformDirectory = Path.of(resolver.resolveFolder(FolderId.DOWNLOADS))

            assertFailsWith<IllegalArgumentException> {
                atomicAppDirectory(
                    platformDirectory = platformDirectory,
                    appDirectory = platformDirectory.resolve("keyguard-dev"),
                    creatableRoot = home,
                )
            }
            assertFalse(home.resolve("Downloads").exists())
            assertFalse(configuredPath.exists())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
