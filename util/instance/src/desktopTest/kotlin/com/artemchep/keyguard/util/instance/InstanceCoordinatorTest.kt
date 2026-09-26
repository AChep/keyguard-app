package com.artemchep.keyguard.util.instance

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InstanceCoordinatorTest {
    @Test
    fun unreachablePrimaryPreservesNativeDiagnosticsInJvmException() = withFixture { root, config ->
        acquire(config).use { primary ->
            primary.stop()
            val error = assertFailsWith<InstanceException> {
                InstanceCoordinator.acquireOrActivate(config.copy(timeoutMillis = 50))
            }
            assertEquals(InstanceFailureKind.TIMEOUT, error.kind)
            val diagnostic = assertNotNull(error.diagnostic)
            assertContains(diagnostic, "operation=")
            assertContains(diagnostic, "os_code=")
            assertFalse(diagnostic.contains(root.toString()))
        }
    }

    @Test
    fun activationIsRetainedBeforeReceiverStarts() = withFixture { _, config ->
        val primary = acquire(config)
        primary.use {
            assertEquals(InstanceResult.Activated, InstanceCoordinator.acquireOrActivate(config))
            assertTrue(primary.awaitActivation())
        }
        primary.close()
        primary.stop()
        assertFalse(primary.awaitActivation())
        acquire(config).close()
    }

    @Test
    fun stopAndCloseWakeBlockedReceiver() = withFixture { _, config ->
        for (close in listOf(false, true)) {
            val primary = acquire(config)
            val executor = Executors.newSingleThreadExecutor { task ->
                Thread(task, "instance-test-receiver").apply { isDaemon = true }
            }
            try {
                val started = CountDownLatch(1)
                val received = executor.submit<Boolean> {
                    started.countDown()
                    primary.awaitActivation()
                }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                if (close) primary.close() else primary.stop()
                assertFalse(received.get(2, TimeUnit.SECONDS))
                primary.close()
            } finally {
                primary.close()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun packagedLibraryLoadsAndActivatesAcrossProcesses() = withFixture { root, config ->
        val primary = acquire(config)
        primary.use {
            val resources = Files.createDirectory(root.resolve("resources"))
            val library = Paths.get(System.getProperty("keyguard.nativeInstance.libraryPath"))
            Files.copy(library, resources.resolve(library.fileName))
            val process = process(
                config,
                "secondary",
                listOf(
                    "-Dcompose.application.resources.dir=$resources",
                ),
            )
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Secondary did not exit")
                assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
                assertTrue(primary.awaitActivation())
            } finally {
                process.destroyForcibly()
            }
        }
    }

    @Test
    fun separateJvmOwnsInstanceAndReceivesActivation() = withFixture { root, config ->
        val ready = root.resolve("ready")
        val process = process(
            config,
            "primary",
            listOf(
                "-Dkeyguard.nativeInstance.libraryPath=${System.getProperty("keyguard.nativeInstance.libraryPath")}",
            ),
            ready.toString(),
        )
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!Files.exists(ready) && process.isAlive && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertTrue(Files.exists(ready), "Primary did not publish readiness")
            assertEquals(InstanceResult.Activated, InstanceCoordinator.acquireOrActivate(config))
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Primary did not receive activation")
            assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
            acquire(config).close()
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun jvmAndNativeProcessesActivateEachOther() = withFixture { _, config ->
        acquire(config).use { primary ->
            val secondary = nativeProcess(config)
            try {
                assertTrue(secondary.waitFor(10, TimeUnit.SECONDS))
                assertEquals(0, secondary.exitValue())
                assertEquals("ACTIVATED", secondary.inputStream.bufferedReader().readLine())
                assertTrue(primary.awaitActivation())
            } finally {
                secondary.destroyForcibly()
            }
        }

        val primary = nativeProcess(config)
        val reader = primary.inputStream.bufferedReader()
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "native-instance-fixture-output").apply { isDaemon = true }
        }
        try {
            assertEquals("PRIMARY", executor.submit<String> { reader.readLine() }.get(10, TimeUnit.SECONDS))
            assertEquals(InstanceResult.Activated, InstanceCoordinator.acquireOrActivate(config))
            assertEquals("ACTIVATION", executor.submit<String> { reader.readLine() }.get(10, TimeUnit.SECONDS))
            primary.outputStream.write("close\n".toByteArray())
            primary.outputStream.flush()
            assertTrue(primary.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, primary.exitValue())
        } finally {
            primary.destroyForcibly()
            executor.shutdownNow()
        }
    }

    private fun nativeProcess(config: InstanceConfig): Process = ProcessBuilder(
        System.getProperty("keyguard.nativeInstance.fixturePath"),
        config.coordinationDirectory,
        config.runtimeDirectory,
        config.identity,
        config.timeoutMillis.toString(),
    ).redirectErrorStream(true).start()

    private fun acquire(config: InstanceConfig): PrimaryInstance =
        assertIs<InstanceResult.Primary>(InstanceCoordinator.acquireOrActivate(config)).instance

    private fun process(
        config: InstanceConfig,
        mode: String,
        properties: List<String>,
        ready: String = "unused",
    ): Process {
        val classpath = listOf(
            InstanceProcessFixture::class.java,
            InstanceCoordinator::class.java,
            Unit::class.java,
        ).map { Paths.get(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct()
            .joinToString(java.io.File.pathSeparator)
        val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        return ProcessBuilder(
            listOf(java, "-Xcheck:jni") + properties + listOf(
                "-cp", classpath, InstanceProcessFixture::class.java.name,
                mode, config.coordinationDirectory, config.runtimeDirectory, config.identity, ready,
            ),
        ).redirectErrorStream(true).start()
    }

    private inline fun withFixture(block: (Path, InstanceConfig) -> Unit) {
        // The short runtime path also exercises macOS's small Unix socket path limit.
        val temp = if (System.getProperty("os.name").startsWith("Windows")) {
            Paths.get(System.getProperty("java.io.tmpdir"))
        } else {
            Paths.get("/tmp")
        }
        val root = Files.createTempDirectory(temp, "ki-").toRealPath()
        Files.createDirectory(root.resolve("ipc"))
        val config = InstanceConfig(root.resolve("state").toString(), root.resolve("ipc").toString(), "test")
        try {
            block(root, config)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

/** Separate JVM fixture deliberately loads through the public API without test-framework dependencies. */
object InstanceProcessFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = InstanceConfig(args[1], args[2], args[3])
        when (args[0]) {
            "primary" -> {
                val primary = (InstanceCoordinator.acquireOrActivate(config) as InstanceResult.Primary).instance
                primary.use {
                    Files.writeString(Paths.get(args[4]), "ready")
                    check(primary.awaitActivation())
                }
            }
            "secondary" -> check(InstanceCoordinator.acquireOrActivate(config) == InstanceResult.Activated)
            else -> error("Unknown fixture mode")
        }
    }
}
