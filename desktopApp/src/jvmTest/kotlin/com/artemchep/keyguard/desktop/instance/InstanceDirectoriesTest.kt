package com.artemchep.keyguard.desktop.instance

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InstanceDirectoriesTest {
    @Test
    fun windowsUsesJvmTemporaryDirectoryInsteadOfUnixOrXdgPaths() = temporaryDirectory { root ->
        val runtime = instanceRuntimeDirectory(
            osName = "Windows 11",
            temporaryDirectory = root,
            xdgRuntimeDirectory = root.resolve("xdg").toString(),
            flatpakId = "com.artemchep.keyguard",
        )
        assertEquals(root.toAbsolutePath(), runtime)
        assertTrue(runtime.isAbsolute)
    }

    @Test
    fun windowsResolvesARootedTemporaryDirectoryToAnAbsolutePath() {
        val temporaryDirectory = Path.of("/tmp")
        val runtime = instanceRuntimeDirectory(
            osName = "Windows 11",
            temporaryDirectory = temporaryDirectory,
            xdgRuntimeDirectory = null,
            flatpakId = null,
        )
        assertTrue(runtime.isAbsolute)
        assertEquals(temporaryDirectory.toAbsolutePath(), runtime)
    }

    @Test
    fun linuxUsesTheSharedFlatpakRuntimeDirectory() = temporaryDirectory { root ->
        for (flatpakId in listOf(null, "", "com.artemchep.keyguard")) {
            val runtime = instanceRuntimeDirectory(
                osName = "Linux",
                temporaryDirectory = root.resolve("tmp"),
                xdgRuntimeDirectory = root.toString(),
                flatpakId = flatpakId,
            )
            val expected = if (flatpakId.isNullOrBlank()) root else root.resolve("app/$flatpakId")
            assertEquals(expected, runtime)
        }
    }

    @Test
    fun erasingApplicationDirectoriesPreservesHeldOwnershipFile() = temporaryDirectory { root ->
        val dataDirectory = root.resolve("data/keyguard")
        val configDirectory = root.resolve("config/keyguard")
        val cacheDirectory = root.resolve("cache/keyguard")
        val applicationDirectories = listOf(dataDirectory, configDirectory, cacheDirectory)
        applicationDirectories.forEach { directory ->
            Files.createDirectories(directory)
            Files.writeString(directory.resolve("preferences.json"), "application data")
        }
        val coordination = instanceCoordinationDirectory(dataDirectory)
        Files.createDirectories(coordination)
        val lock = coordination.resolve("keyguard.lock")
        val endpoint = coordination.resolve("keyguard.endpoint")
        Files.writeString(endpoint, "endpoint metadata")
        FileChannel.open(lock, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { lease ->
                val originalFileKey = Files.readAttributes(lock, BasicFileAttributes::class.java)
                    .fileKey()

                // ClearDataAndroid erases these trees before process shutdown.
                applicationDirectories.forEach { directory ->
                    assertTrue(directory.toFile().deleteRecursively())
                    assertFalse(Files.exists(directory))
                }

                assertTrue(lease.isValid)
                val retainedFileKey = Files.readAttributes(lock, BasicFileAttributes::class.java)
                    .fileKey()
                assertEquals(originalFileKey, retainedFileKey)
                assertEquals("endpoint metadata", Files.readString(endpoint))
                Files.createDirectories(dataDirectory)
                assertEquals(coordination, instanceCoordinationDirectory(dataDirectory))
            }
        }
    }

    @Test
    fun coordinationDirectoriesStaySeparateForIndependentDataDomains() = temporaryDirectory { root ->
        val release = instanceCoordinationDirectory(root.resolve("data/keyguard"))
        val development = instanceCoordinationDirectory(root.resolve("data/keyguard-dev"))
        val otherDataRoot = instanceCoordinationDirectory(root.resolve("other/keyguard"))
        assertNotEquals(release, development)
        assertNotEquals(release, otherDataRoot)
    }

    private fun temporaryDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("keyguard-instance-path-test-").toAbsolutePath()
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
