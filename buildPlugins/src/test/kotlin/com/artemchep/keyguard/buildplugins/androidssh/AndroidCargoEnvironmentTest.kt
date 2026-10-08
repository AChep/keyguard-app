package com.artemchep.keyguard.buildplugins.androidssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties

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
    fun `sdk dir in local properties wins over the environment like in AGP`() {
        val rootDir = temporaryFolder.newFolder("root")
        val localSdk = temporaryFolder.newFolder("local-sdk")
        val environmentSdk = temporaryFolder.newFolder("environment-sdk")
        writeLocalProperties(rootDir, sdkDir = localSdk.absolutePath)

        assertEquals(
            localSdk,
            resolveSdk(
                rootDir = rootDir,
                environment = mapOf(
                    "ANDROID_HOME" to environmentSdk,
                    "ANDROID_SDK_ROOT" to environmentSdk,
                ),
            ),
        )
    }

    @Test
    fun `falls back to ANDROID_HOME and then ANDROID_SDK_ROOT`() {
        val rootDir = temporaryFolder.newFolder("root")
        val androidHome = temporaryFolder.newFolder("android-home")
        val androidSdkRoot = temporaryFolder.newFolder("android-sdk-root")
        // AGP skips an sdk.dir that does not exist, so it must not stop the lookup here either.
        writeLocalProperties(rootDir, sdkDir = File(rootDir, "missing-sdk").absolutePath)

        assertEquals(
            androidHome,
            resolveSdk(
                rootDir = rootDir,
                environment = mapOf(
                    "ANDROID_HOME" to androidHome,
                    "ANDROID_SDK_ROOT" to androidSdkRoot,
                ),
            ),
        )
        assertEquals(
            androidSdkRoot,
            resolveSdk(
                rootDir = rootDir,
                environment = mapOf("ANDROID_SDK_ROOT" to androidSdkRoot),
            ),
        )
    }

    @Test
    fun `resolves a relative sdk dir against the root directory`() {
        val rootDir = temporaryFolder.newFolder("root")
        val sdk = temporaryFolder.newFolder("sdk")
        writeLocalProperties(rootDir, sdkDir = "../sdk")

        assertEquals(
            sdk.canonicalFile,
            resolveSdk(rootDir = rootDir)?.canonicalFile,
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

    private fun writeLocalProperties(
        rootDir: File,
        sdkDir: String,
    ) {
        val properties = Properties().apply { setProperty("sdk.dir", sdkDir) }
        File(rootDir, "local.properties").outputStream().use { properties.store(it, null) }
    }

    private fun resolveSdk(
        rootDir: File,
        environment: Map<String, File> = emptyMap(),
    ): File? = AndroidCargoEnvironment.resolveAndroidSdkRoot(
        rootDir = rootDir,
        localPropertiesFile = null,
        environment = { name -> environment[name]?.path },
    ).sdkRoot
}
