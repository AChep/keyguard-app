package com.artemchep.keyguard

import com.artemchep.keyguard.nativebundle.NativeBundleProbe
import com.artemchep.keyguard.nativebundle.nativeProbe
import com.artemchep.keyguard.util.io.LocalPath
import com.artemchep.keyguard.util.zip.ZipConfig
import com.artemchep.keyguard.util.zip.ZipEntry
import com.artemchep.keyguard.util.zip.ZipReader
import com.artemchep.keyguard.util.zip.createZipService
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
class NativeBundleSmokeTest {
    @Test
    fun linksAndExecutesAllAppleNativeLibraries() = runTest {
        val directory = Path(SystemTemporaryDirectory, "keyguard-native-smoke-${Uuid.random()}")
        SystemFileSystem.createDirectories(directory)
        try {
            NativeBundleProbe.run(LocalPath(directory.toString()))
            nativeProbe("zip") {
                val payload = "native zip probe".encodeToByteArray()
                val archive = Buffer()
                createZipService().zip(
                    outputStream = archive,
                    config = ZipConfig(),
                    entries = listOf(ZipEntry("probe.txt", ZipEntry.Data.Out { it.write(payload) })),
                )
                ZipReader(archive).use { reader ->
                    val entry = assertNotNull(reader.nextEntry())
                    assertEquals("probe.txt", entry.name)
                    assertContentEquals(payload, entry.source.readByteArray())
                    assertNull(reader.nextEntry())
                }
            }
        } finally {
            SystemFileSystem.delete(directory)
        }
    }
}
