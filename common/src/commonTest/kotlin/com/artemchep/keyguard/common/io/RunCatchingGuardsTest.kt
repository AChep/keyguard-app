package com.artemchep.keyguard.common.io

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The contract the guard idioms document.
 *
 * Deliberately in the helpers' own package: `runCatchingUntrustedInput` is
 * behind a `ForbiddenImport` allow-list, and a test in this package needs no
 * import at all — so the allow-list stays exactly one production path.
 *
 * `StackOverflowError`, `UnknownError` and `LinkageError` are JVM-only types,
 * which is fine here: this module attaches `src/commonTest/kotlin` to the
 * `jvmTest` source set (see `common/build.gradle.kts`), so these tests only
 * ever compile for desktop and the Android host.
 */
class RunCatchingGuardsTest {
    @Test
    fun `the default guard turns an ordinary failure into a value`() {
        val result = runCatchingNonFatal { error("gone") }
        assertIs<IllegalStateException>(result.exceptionOrNull())
    }

    @Test
    fun `the default guard never swallows a cancellation`() {
        assertFailsWith<CancellationException> {
            runCatchingNonFatal { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun `the default guard never swallows a blown heap`() {
        assertFailsWith<OutOfMemoryError> {
            runCatchingNonFatal { throw OutOfMemoryError("heap") }
        }
    }

    @Test
    fun `the default guard never swallows a blown stack`() {
        assertFailsWith<StackOverflowError> {
            runCatchingNonFatal { throw StackOverflowError("stack") }
        }
    }

    @Test
    fun `the default guard never swallows any other error`() {
        assertFailsWith<UnknownError> {
            runCatchingNonFatal { throw UnknownError("broken") }
        }
    }

    @Test
    fun `the untrusted input guard absorbs a blown heap`() {
        assertIs<OutOfMemoryError>(
            runCatchingUntrustedInput { throw OutOfMemoryError("heap") }.exceptionOrNull(),
        )
    }

    @Test
    fun `the untrusted input guard absorbs a blown stack`() {
        assertIs<StackOverflowError>(
            runCatchingUntrustedInput { throw StackOverflowError("stack") }.exceptionOrNull(),
        )
    }

    @Test
    fun `the untrusted input guard still refuses an unknown error`() {
        assertFailsWith<UnknownError> {
            runCatchingUntrustedInput { throw UnknownError("broken") }
        }
    }

    @Test
    fun `the untrusted input guard still refuses a linkage error`() {
        assertFailsWith<LinkageError> {
            runCatchingUntrustedInput { throw LinkageError("no such class") }
        }
    }

    @Test
    fun `the untrusted input guard still refuses a cancellation`() {
        assertFailsWith<CancellationException> {
            runCatchingUntrustedInput { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun `the cancellation-only guard still rethrows a cancellation`() {
        // This guard deliberately allows an `Error` to reach the recovery path;
        // most recovery paths should use runCatchingNonFatal instead.
        assertFailsWith<CancellationException> {
            runCatching { throw CancellationException("cancelled") }
                .getOrElse { e ->
                    e.throwIfCancellation()
                    "recovered"
                }
        }
    }

    @Test
    fun `the cancellation-only guard lets an error reach the recovery path`() {
        val recovered = runCatching { throw LinkageError("no such class") }
            .getOrElse { e ->
                e.throwIfCancellation()
                "recovered"
            }
        assertEquals("recovered", recovered)
    }

    @Test
    fun `the cancellation-only guard lets an ordinary failure reach the recovery path`() {
        val recovered = runCatching { error("gone") }
            .getOrElse { e ->
                e.throwIfCancellation()
                "recovered"
            }
        assertEquals("recovered", recovered)
    }

    @Test
    fun `both guards pass a success straight through`() {
        assertTrue(runCatchingNonFatal { 1 }.isSuccess)
        assertTrue(runCatchingUntrustedInput { 1 }.isSuccess)
    }
}
