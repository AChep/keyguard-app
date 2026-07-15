package com.artemchep.keyguard.buildplugins.androidssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AndroidCargoEnvironmentTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `resolves only the requested NDK version`() {
        val sdkRoot = temporaryFolder.newFolder("sdk")
        File(sdkRoot, "ndk/25.2.9519653").mkdirs()
        val requested = File(sdkRoot, "ndk/27.0.12077973").apply { mkdirs() }
        File(sdkRoot, "ndk/28.0.13004108").mkdirs()

        assertEquals(
            requested,
            AndroidCargoEnvironment.resolveNdkDirectory(
                sdkRoot = sdkRoot,
                ndkVersion = "27.0.12077973",
            ),
        )
    }

    @Test
    fun `does not fall back to another installed NDK`() {
        val sdkRoot = temporaryFolder.newFolder("sdk")
        File(sdkRoot, "ndk/28.0.13004108").mkdirs()
        File(sdkRoot, "ndk-bundle").mkdirs()

        assertNull(
            AndroidCargoEnvironment.resolveNdkDirectory(
                sdkRoot = sdkRoot,
                ndkVersion = "27.0.12077973",
            ),
        )
    }
}
