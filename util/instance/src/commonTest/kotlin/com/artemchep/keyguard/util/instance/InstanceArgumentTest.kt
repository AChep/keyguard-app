package com.artemchep.keyguard.util.instance

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class InstanceArgumentTest {
    @Test
    fun invalidConfigurationProducesTypedFailure() {
        val error = assertFailsWith<InstanceException> {
            InstanceCoordinator.acquireOrActivate(InstanceConfig("", "", "test"))
        }
        assertEquals(InstanceFailureKind.INVALID_ARGUMENT, error.kind)
        assertContains(assertNotNull(error.diagnostic), "operation=validate_config")
    }

    @Test
    fun nativeDiagnosticsDoNotExposeInputPathsOrReuseAnEarlierFailure() {
        val error = assertFailsWith<InstanceException> {
            InstanceCoordinator.acquireOrActivate(InstanceConfig("private-secret-path", "", "test"))
        }
        assertFalse(assertNotNull(error.diagnostic).contains("private-secret-path"))
        // Local Kotlin/Native validation and JNI validation must not return the
        // previous native operation's diagnostic when rejecting malformed UTF-16.
        assertEquals(-1L, NativeInstance.acquireOrActivate("/tmp", "/tmp", "\uD800", 5_000))
        assertFalse(NativeInstance.lastError().orEmpty().contains("validate_config"))
    }

    @Test
    fun malformedUtf16IsRejectedConsistentlyAcrossBindings() {
        assertEquals(-1L, NativeInstance.acquireOrActivate("/tmp", "/tmp", "\uD800", 5_000))
        assertEquals(-1L, NativeInstance.acquireOrActivate("/tmp", "/tmp", "test", -1))
    }

    @Test
    fun invalidHandlesAreRejectedByRawBridge() {
        assertEquals(-5L, NativeInstance.waitEvent(0))
        assertEquals(-5L, NativeInstance.stop(0))
        assertEquals(-5L, NativeInstance.close(0))
        assertEquals(-1L, NativeInstance.waitEvent(-1))
        assertEquals(-1L, NativeInstance.stop(-1))
        assertEquals(-1L, NativeInstance.close(-1))
    }
}
