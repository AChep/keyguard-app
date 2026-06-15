package com.artemchep.keyguard.common.io

import arrow.core.Either
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class IOFlowTest {
    @Test
    fun `attempt rethrows CancellationException`() = runTest {
        assertFailsWith<CancellationException> {
            flow<Int> {
                throw CancellationException("cancel")
            }.attempt()
                .first()
        }
    }

    @Test
    fun `attempt wraps non-fatal exception as Left`() = runTest {
        val result = flow<Int> {
            throw IllegalStateException("boom")
        }.attempt()
            .first()

        val left = assertIs<Either.Left<Throwable>>(result)
        assertIs<IllegalStateException>(left.value)
        assertEquals("boom", left.value.message)
    }
}
