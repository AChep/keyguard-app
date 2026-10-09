package com.artemchep.keyguard.copy

import com.artemchep.keyguard.util.io.atomic.AtomicDirectoryPermissions
import com.artemchep.keyguard.util.io.atomic.AtomicFilePermissions
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.AtomicPublicationPolicy
import com.artemchep.keyguard.util.io.atomic.AtomicWriteOptions
import com.artemchep.keyguard.util.io.atomic.ExistingParentLinkPolicy
import com.artemchep.keyguard.util.io.atomic.ParentDirectoryPolicy
import com.artemchep.keyguard.util.io.atomic.SyncLevel
import com.artemchep.keyguard.util.io.atomic.SynchronizationPolicy
import com.artemchep.keyguard.util.io.atomic.writeFileAtomically
import kotlinx.io.writeString
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DataDirectoryAtomicTest {
    private val tempDirs = mutableListOf<Path>()

    @AfterTest
    fun cleanUpTempDirs() {
        tempDirs.forEach { dir -> dir.toFile().deleteRecursively() }
        tempDirs.clear()
    }

    private fun tempDir(prefix: String): Path =
        createTempDirectory(prefix).also(tempDirs::add)

    @Test
    fun firstWriteCreatesTheCompleteAppOwnedSuffixBelowExistingRoot() {
        val root = tempDir("atomic-app-root")
        val platformDirectory = root.resolve("missing-platform-base")
        val appDirectory = platformDirectory.resolve("keyguard-dev")
        val directory = atomicAppDirectory(
            platformDirectory = platformDirectory,
            appDirectory = appDirectory,
            creatableRoot = root,
        )
        val destination = directory.resolve(
            AtomicPathComponent.parse("preferences.json"),
        )

        writeFileAtomically(
            destination = destination,
            options = AtomicWriteOptions(
                publication = AtomicPublicationPolicy.Create(
                    permissions = AtomicFilePermissions.OwnerOnly,
                ),
                parentDirectories = ParentDirectoryPolicy.CreateMissing(
                    permissions = AtomicDirectoryPermissions.OwnerOnly,
                ),
                existingParentLinks = ExistingParentLinkPolicy.Reject,
                synchronization = SynchronizationPolicy.Required(
                    SyncLevel.FileSynchronized,
                ),
            ),
        ) { sink ->
            sink.writeString("first write")
        }

        assertEquals(
            "first write",
            appDirectory.resolve("preferences.json").readText(),
        )
    }

    @Test
    fun missingPlatformDirectoryFallsBackToNearestExistingAncestor() {
        val home = tempDir("atomic-app-home")
        val platformDirectory = home.resolve(".local/share")
        val directory = atomicAppDirectory(
            platformDirectory = platformDirectory,
            appDirectory = platformDirectory.resolve("keyguard-dev"),
            creatableRoot = home,
        )
        assertEquals(home.toString(), directory.root.value)
        assertEquals(".local/share/keyguard-dev", directory.relativePath.value)
    }

    @Test
    fun existingPlatformDirectoryStaysTheTrustRoot() {
        val home = tempDir("atomic-app-home")
        val downloads = home.resolve("Downloads").createDirectory()
        val directory = atomicAppDirectory(
            platformDirectory = downloads,
            appDirectory = downloads.resolve("keyguard-dev"),
            creatableRoot = null,
        )
        assertEquals(downloads.toString(), directory.root.value)
        assertEquals("keyguard-dev", directory.relativePath.value)
    }

    @Test
    fun missingPlatformDirectoryOutsideCreatableRootIsRejected() {
        val root = tempDir("atomic-app-root")
        val home = root.resolve("home/user").createDirectories()
        // The empty mount point of a drive that is not mounted.
        val platformDirectory = root.resolve("mnt/data").createDirectories()
            .resolve("Downloads")
        listOf(home, null).forEach { creatableRoot ->
            assertFailsWith<IllegalArgumentException>(message = creatableRoot.toString()) {
                atomicAppDirectory(
                    platformDirectory = platformDirectory,
                    appDirectory = platformDirectory.resolve("keyguard-dev"),
                    creatableRoot = creatableRoot,
                )
            }
        }
    }

    @Test
    fun appDirectoryOutsidePlatformDirectoryIsRejected() {
        val home = tempDir("atomic-app-home")
        val existingDownloads = home.resolve("Downloads").createDirectory()
        val missingDownloads = home.resolve("Missing/Downloads")
        listOf(existingDownloads, missingDownloads).forEach { downloads ->
            listOf(
                downloads,
                home.resolve("Documents/keyguard-dev"),
            ).forEach { appDirectory ->
                assertFailsWith<IllegalArgumentException>(message = appDirectory.toString()) {
                    atomicAppDirectory(
                        platformDirectory = downloads,
                        appDirectory = appDirectory,
                        creatableRoot = home,
                    )
                }
            }
        }
    }
}
