package com.artemchep.keyguard.desktop.instance

import com.artemchep.keyguard.util.instance.InstanceConfig
import com.artemchep.keyguard.util.instance.InstanceCoordinator
import com.artemchep.keyguard.util.instance.InstanceResult
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val ACTIVATION_TIMEOUT_SECONDS = 5L

/** Exercises the packaged library and IPC without opening the user's vault or instance lock. */
internal fun verifyPackagedInstanceService() {
    val directory = Files.createTempDirectory("keyguard-instance-smoke-").toRealPath()
    try {
        val configuration = InstanceConfig(
            coordinationDirectory = directory.resolve("coordination").toString(),
            runtimeDirectory = instanceRuntimeDirectory().toString(),
            identity = "keyguard-package-smoke",
        )
        val first = InstanceCoordinator.acquireOrActivate(configuration)
        check(first is InstanceResult.Primary)
        first.instance.use { primary ->
            val receiver = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "keyguard-instance-smoke").apply { isDaemon = true }
            }
            try {
                val activation = receiver.submit<Boolean> { primary.awaitActivation() }
                check(InstanceCoordinator.acquireOrActivate(configuration) == InstanceResult.Activated)
                check(activation.get(ACTIVATION_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            } finally {
                // Interrupting the JVM thread cannot wake a native receiver; stop IPC first.
                primary.stop()
                receiver.shutdownNow()
            }
        }
    } finally {
        directory.toFile().deleteRecursively()
    }
}
