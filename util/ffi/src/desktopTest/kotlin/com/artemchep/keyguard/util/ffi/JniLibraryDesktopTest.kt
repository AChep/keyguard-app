package com.artemchep.keyguard.util.ffi

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JniLibraryDesktopTest {
    private val pathProperty = "keyguard.nativeFfiTest.libraryPath"
    private var resourcesDirectory: String? = null
    private lateinit var directory: File

    @BeforeTest
    fun setUp() {
        resourcesDirectory = System.getProperty(COMPOSE_RESOURCES_DIRECTORY_PROPERTY)
        directory = Files.createTempDirectory("keyguard-ffi").toFile()
    }

    @AfterTest
    fun tearDown() {
        restore(COMPOSE_RESOURCES_DIRECTORY_PROPERTY, resourcesDirectory)
        System.clearProperty(pathProperty)
        directory.deleteRecursively()
    }

    @Test
    fun missingResourcesDirectoryFindsNothing() {
        System.clearProperty(COMPOSE_RESOURCES_DIRECTORY_PROPERTY)

        assertFalse(loadJniLibrary(LIBRARY_NAME, pathProperty))
    }

    @Test
    fun emptyResourcesDirectoryFindsNothing() {
        System.setProperty(COMPOSE_RESOURCES_DIRECTORY_PROPERTY, directory.path)

        assertFalse(loadJniLibrary(LIBRARY_NAME, pathProperty))
    }

    @Test
    fun bundledLibraryUsesThePlatformFileName() {
        File(directory, System.mapLibraryName(LIBRARY_NAME)).writeText("not a library")
        System.setProperty(COMPOSE_RESOURCES_DIRECTORY_PROPERTY, directory.path)

        // Reaching System.load proves the file was found.
        assertFailsWith<UnsatisfiedLinkError> { loadJniLibrary(LIBRARY_NAME, pathProperty) }
    }

    @Test
    fun configuredPathWinsOverTheBundledLibrary() {
        File(directory, System.mapLibraryName(LIBRARY_NAME)).writeText("not a library")
        System.setProperty(COMPOSE_RESOURCES_DIRECTORY_PROPERTY, directory.path)
        val configured = File(directory, "configured").path
        System.setProperty(pathProperty, configured)

        val error = assertFailsWith<UnsatisfiedLinkError> { loadJniLibrary(LIBRARY_NAME, pathProperty) }
        assertTrue(error.message.orEmpty().contains("configured"))
    }

    @Test
    fun invalidConfiguredPathIsUnavailable() {
        System.setProperty(pathProperty, "bad\u0000path")
        val library = JniLibrary(
            name = LIBRARY_NAME,
            pathProperty = pathProperty,
            unavailable = { cause -> IllegalStateException("unavailable", cause) },
        )

        val error = assertFailsWith<IllegalStateException> { library.ensureLoaded() }
        assertIs<IOException>(error.cause)
    }

    private fun restore(key: String, value: String?) {
        if (value == null) System.clearProperty(key) else System.setProperty(key, value)
    }
}

private const val LIBRARY_NAME = "keyguard_ffi_test_jni"
