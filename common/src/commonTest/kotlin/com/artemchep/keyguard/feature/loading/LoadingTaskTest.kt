package com.artemchep.keyguard.feature.loading

import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LoadingTaskTest {
    @Test
    fun `the first call claims synchronously and a queued second call is skipped`() = runTest {
        val task = loadingTask(scope = this)
        val release = CompletableDeferred<Unit>()
        var firstCalls = 0
        var secondCalls = 0

        assertTrue(
            task.execute(
                io = {
                    firstCalls += 1
                    release.await()
                },
            ),
        )
        assertTrue(task.isExecutingFlow.value)
        assertFalse(
            task.execute(
                io = {
                    secondCalls += 1
                },
            ),
        )

        runCurrent()
        assertEquals(1, firstCalls)
        assertEquals(0, secondCalls)

        release.complete(Unit)
        advanceUntilIdle()
        assertFalse(task.isExecutingFlow.value)
    }

    @Test
    fun `success keeps the claim through the debounce and then accepts another task`() = runTest {
        val task = loadingTask(scope = this)
        var calls = 0

        assertTrue(task.execute(io = { calls += 1 }))
        runCurrent()
        assertEquals(1, calls)
        assertTrue(task.isExecutingFlow.value)

        advanceTimeBy(59L)
        runCurrent()
        assertTrue(task.isExecutingFlow.value)

        advanceTimeBy(1L)
        runCurrent()
        assertFalse(task.isExecutingFlow.value)

        assertTrue(task.execute(io = { calls += 1 }))
        advanceUntilIdle()
        assertEquals(2, calls)
        assertFalse(task.isExecutingFlow.value)
    }

    @Test
    fun `failure emits its tag and releases the claim`() = runTest {
        val task = loadingTask(scope = this)
        val error = async {
            task.errorFlow.first()
        }
        runCurrent()

        assertTrue(
            task.execute(
                io = ioRaise<Unit>(IllegalStateException("broken")),
                tag = "save",
            ),
        )
        runCurrent()

        assertEquals("save", error.await().tag)
        assertFalse(task.isExecutingFlow.value)
        assertTrue(task.execute(io = { Unit }))
        advanceUntilIdle()
    }

    @Test
    fun `cancelling before the launched body starts still releases the claim`() = runTest {
        val owner = Job()
        val taskScope = CoroutineScope(
            StandardTestDispatcher(testScheduler) + owner,
        )
        val task = loadingTask(scope = taskScope)
        var calls = 0

        assertTrue(task.execute(io = { calls += 1 }))
        assertTrue(task.isExecutingFlow.value)
        owner.cancel()
        runCurrent()

        assertEquals(0, calls)
        assertFalse(task.isExecutingFlow.value)
    }

    @Test
    fun `cancelling a running task releases the claim`() = runTest {
        val owner = Job()
        val taskScope = CoroutineScope(
            StandardTestDispatcher(testScheduler) + owner,
        )
        val task = loadingTask(scope = taskScope)
        var started = false

        assertTrue(
            task.execute(
                io = {
                    started = true
                    awaitCancellation()
                },
            ),
        )
        runCurrent()
        assertTrue(started)
        assertTrue(task.isExecutingFlow.value)

        taskScope.cancel()
        runCurrent()
        assertFalse(task.isExecutingFlow.value)
    }

    private fun loadingTask(
        scope: CoroutineScope,
    ) = LoadingTask(
        translator = TestTranslator,
        scope = scope,
        exceptionHandler = { e ->
            ReadableExceptionMessage(
                title = e.message.orEmpty(),
            )
        },
    )
}

private object TestTranslator : TranslatorScope {
    override suspend fun translate(res: StringResource): String = res.toString()

    override suspend fun translate(
        res: StringResource,
        vararg args: Any,
    ): String = res.toString()

    override suspend fun translate(
        res: PluralStringResource,
        quantity: Int,
        vararg args: Any,
    ): String = res.toString()
}
