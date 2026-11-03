package com.artemchep.keyguard.feature.dragout

import com.artemchep.keyguard.feature.dragout.compose.DragOutTestContent
import com.artemchep.keyguard.feature.dragout.model.DragItem
import com.artemchep.keyguard.feature.dragout.model.DragOutSource
import com.artemchep.keyguard.feature.dragout.model.DragPayload
import com.artemchep.keyguard.feature.dragout.model.DragRepresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class DragOutSourceTest {
    @Test
    fun `cancelled preparation completes the native provider exactly once`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val result = mutableListOf<DragPayload?>()
            val gate = CompletableDeferred<Unit>()
            val drag = DragOutSource(backgroundScope, "label", { gate.await(); null }, { throw it })
            val job = drag.start(result::add)
            runCurrent()
            job.cancel()
            runCurrent()
            assertEquals(listOf<DragPayload?>(null), result)
            assertFalse(drag.preparing.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `cancelled drag closes late prepared bytes without opening a reader`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val file = DragOutTestContent("attachment.txt")
            val content = file.content
            val gate = CompletableDeferred<Unit>()
            val result = mutableListOf<DragPayload?>()
            val drag = DragOutSource(
                scope = backgroundScope,
                label = content.name,
                prepare = {
                    withContext(NonCancellable) {
                        gate.await()
                        DragPayload(content.name, listOf(DragItem(DragRepresentation.File(content))))
                    }
                },
                onError = { throw it },
            )
            val job = drag.start(result::add)
            runCurrent()
            job.cancel()
            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf<DragPayload?>(null), result)
            assertEquals(1, file.closes)
            assertEquals(0, file.handoffs)
            assertEquals(0, file.readersOpened)
            assertFalse(drag.preparing.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a ready link is delivered without progress`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val result = mutableListOf<DragPayload?>()
            val drag = DragOutSource.url(backgroundScope, "https://example.com/", onError = { throw it })
            drag.start(result::add)
            runCurrent()

            val payload = assertNotNull(result.single())
            assertEquals("https://example.com/", payload.label)
            val url = assertIs<DragRepresentation.Url>(payload.items.single().representations.single())
            assertEquals("https://example.com/", url.url)
            assertFalse(drag.preparing.value)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
