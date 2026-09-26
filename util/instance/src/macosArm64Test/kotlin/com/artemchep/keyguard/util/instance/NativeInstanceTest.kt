@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
    kotlin.native.concurrent.ObsoleteWorkersApi::class,
)

package com.artemchep.keyguard.util.instance

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.posix.mkdir
import platform.posix.mkdtemp
import platform.posix.usleep
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeInstanceTest {
    @Test
    fun cInteropDeliversQueuedActivationAndReleasesOwnership() = withFixture { config ->
        val primary = acquire(config)
        try {
            assertEquals(InstanceResult.Activated, InstanceCoordinator.acquireOrActivate(config))
            assertTrue(primary.awaitActivation())
        } finally {
            primary.close()
        }
        primary.close()
        assertFalse(primary.awaitActivation())
        acquire(config).close()
    }

    @Test
    fun stopWakesNativeReceiverWithoutReleasingOwnership() = withFixture { config ->
        val primary = acquire(config)
        val started = AtomicBoolean(false)
        val worker = Worker.start()
        try {
            val received = worker.execute(TransferMode.SAFE, { Pair(primary, started) }) { (instance, signal) ->
                signal.store(true)
                instance.awaitActivation()
            }
            var attempts = 0
            while (!started.load() && attempts++ < 2_000) usleep(1_000u)
            assertTrue(started.load())
            primary.stop()
            assertFalse(received.result)
            primary.stop()
            val error = assertFailsWith<InstanceException> {
                InstanceCoordinator.acquireOrActivate(config.copy(timeoutMillis = 50))
            }
            assertEquals(InstanceFailureKind.TIMEOUT, error.kind)
            val diagnostic = assertNotNull(error.diagnostic)
            assertContains(diagnostic, "operation=connect_activation_socket")
            assertContains(diagnostic, "os_code=")
            assertFalse(diagnostic.contains(config.coordinationDirectory))
        } finally {
            primary.close()
            worker.requestTermination().result
        }
    }

    private fun acquire(config: InstanceConfig): PrimaryInstance =
        assertIs<InstanceResult.Primary>(InstanceCoordinator.acquireOrActivate(config)).instance

    private inline fun withFixture(block: (InstanceConfig) -> Unit) {
        val template = "/private/tmp/ki-XXXXXX\u0000".encodeToByteArray()
        val root = template.usePinned { checkNotNull(mkdtemp(it.addressOf(0))).toKString() }
        try {
            check(mkdir("$root/ipc", 0x1c0u) == 0)
            block(InstanceConfig("$root/state", "$root/ipc", "native-test"))
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(root, error = null)
        }
    }
}
