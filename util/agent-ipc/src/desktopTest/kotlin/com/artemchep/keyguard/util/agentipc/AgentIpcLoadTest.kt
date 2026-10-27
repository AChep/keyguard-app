package com.artemchep.keyguard.util.agentipc

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Loading the JNI library, each in a fresh JVM: a library that is missing or broken
 * is an [AgentIpcException] with [AgentIpcFailureKind.UNAVAILABLE], never a linkage
 * error, and the packaged library loads from the Compose resources directory.
 */
class AgentIpcLoadTest {
    @Test
    fun aMissingLibraryIsUnavailable() = withDirectory { directory ->
        val missing = directory.resolve(System.mapLibraryName("keyguard_agent_ipc_jni"))
        assertEquals(
            listOf("UNAVAILABLE", "UNAVAILABLE"),
            load("-Dkeyguard.nativeAgentIpc.libraryPath=$missing"),
        )
    }

    @Test
    fun aFileThatIsNotALibraryIsUnavailable() = withDirectory { directory ->
        val broken = Files.writeString(
            directory.resolve(System.mapLibraryName("keyguard_agent_ipc_jni")),
            "not a library",
        )
        assertEquals(
            listOf("UNAVAILABLE", "UNAVAILABLE"),
            load("-Dkeyguard.nativeAgentIpc.libraryPath=$broken"),
        )
    }

    @Test
    fun noLibraryAtAllIsUnavailable() = withDirectory { directory ->
        assertEquals(
            listOf("UNAVAILABLE", "UNAVAILABLE"),
            load("-Dcompose.application.resources.dir=$directory"),
        )
    }

    @Test
    fun thePackagedLibraryLoads() = withDirectory { directory ->
        val library = Paths.get(System.getProperty(LIBRARY_PATH_PROPERTY))
        Files.copy(library, directory.resolve(System.mapLibraryName("keyguard_agent_ipc_jni")))
        assertEquals(listOf("OK", "OK"), load("-Dcompose.application.resources.dir=$directory"))
    }

    /** `-Xcheck:jni` aborts on a fatal misuse and only prints a warning; neither may appear. */
    @Test
    fun everyExportRunsCleanUnderCheckJni() {
        val property = "-D$LIBRARY_PATH_PROPERTY=${System.getProperty(LIBRARY_PATH_PROPERTY)}"
        assertEquals(listOf("OK"), run("exports", property))
    }

    private fun load(property: String): List<String> = run("load", property)

    private fun run(mode: String, property: String): List<String> {
        val process = AgentIpcProcessFixture.start(mode, properties = listOf(property))
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "The fixture did not exit")
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.exitValue(), output)
            assertFalse(output.contains("in native method"), output)
            return output.lines().filter { it in OUTCOMES || it.startsWith("FAILED") }
        } finally {
            process.destroyForcibly()
        }
    }

    private inline fun withDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("kg-ipc-load-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

private const val LIBRARY_PATH_PROPERTY = "keyguard.nativeAgentIpc.libraryPath"

private val OUTCOMES = setOf("OK") + AgentIpcFailureKind.entries.map { it.name }
