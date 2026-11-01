package com.artemchep.keyguard.feature.dragout.compose

import com.artemchep.desktop.MacFilePromiseCallbacks
import com.artemchep.desktop.NativeDragSession
import com.artemchep.keyguard.feature.dragout.model.DragItem
import com.artemchep.keyguard.feature.dragout.model.DragOutSource
import com.artemchep.keyguard.feature.dragout.model.DragPayload
import com.artemchep.keyguard.feature.dragout.model.DragRepresentation
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions")
class MacFilePromiseDragOutTest {
    @Test
    fun acceptedDropKeepsEveryFileReadableUntilTheStoreRetiresThem() {
        val first = DragOutTestContent("report.txt", byteArrayOf(1, 2, 3))
        val second = DragOutTestContent("Report.txt", byteArrayOf(4, 5))
        var callbacks: MacFilePromiseCallbacks? = null
        var names: List<String> = emptyList()
        var revokes = 0
        var ended = 0
        val offer = offer(first, second) { window, value, native ->
            assertEquals(WINDOW, window)
            names = value
            callbacks = native
            NativeDragSession { revokes++ }
        }
        assertTrue(offer.start { ended++ })
        assertEquals(listOf("report.txt", "Report (2).txt"), names)
        assertEquals(listOf(1, 1), listOf(first.handoffs, second.handoffs))
        offer.close()
        callbacks!!.ended(true)
        assertEquals(1, ended)
        assertEquals(0, first.closes + second.closes)

        val data = ByteArray(8)
        callbacks!!.openReader(1).use { reader ->
            assertEquals(2L, reader.size)
            assertEquals(2, reader.readAt(0, data, data.size))
            assertContentEquals(byteArrayOf(4, 5), data.copyOf(2))
        }
        val early = callbacks!!.openReader(0)

        // Revocation waits for every file: the store retires each on its own deadline.
        first.retire()
        assertEquals(0, revokes)
        second.retire()
        assertEquals(1, revokes)
        assertFailsWith<IllegalStateException> { callbacks!!.openReader(0) }
        // A reader opened before expiry still owns its immutable bytes.
        assertEquals(3, early.readAt(0, data, data.size))
        early.close()

        callbacks!!.retired()
        callbacks!!.retired()
        assertEquals(1, first.closes)
        assertEquals(1, second.closes)
    }

    @Test
    fun rejectedDropRetiresEveryFile() {
        val first = DragOutTestContent("a.bin")
        val second = DragOutTestContent("b.bin")
        var callbacks: MacFilePromiseCallbacks? = null
        val offer = offer(first, second) { _, _, value ->
            callbacks = value
            NativeDragSession {}
        }
        var ended = 0
        assertTrue(offer.start { ended++ })
        callbacks!!.ended(false)
        callbacks!!.ended(false)
        assertEquals(1, ended)
        assertEquals(listOf(1, 1), listOf(first.closes, second.closes))
        assertFailsWith<IllegalStateException> { callbacks!!.openReader(0) }
    }

    @Test
    fun acceptedDropRetiresOnceEveryFileIsWrittenInEitherOrder() {
        listOf(true, false).forEach { acceptedFirst ->
            val files = listOf(DragOutTestContent("a.bin"), DragOutTestContent("b.bin"))
            val drag = StartedOffer(files)
            if (acceptedFirst) drag.callbacks.ended(true)
            drag.callbacks.written(1)
            drag.callbacks.written(0)
            if (!acceptedFirst) {
                assertEquals(0, drag.revokes)
                drag.callbacks.ended(true)
            }
            assertEquals(1, drag.revokes)
            assertEquals(1, drag.ended)
            // In-flight writes finish before native reports retirement.
            assertEquals(listOf(0, 0), files.map { it.closes })
            drag.callbacks.openReader(0).close()

            drag.callbacks.retired()
            assertEquals(listOf(1, 1), files.map { it.closes })
            assertFailsWith<IllegalStateException> { drag.callbacks.openReader(0) }
        }
    }

    @Test
    fun acceptedDropWithAnUnwrittenFileWaitsForTheStore() {
        val files = listOf(DragOutTestContent("a.bin"), DragOutTestContent("b.bin"))
        val drag = StartedOffer(files)
        drag.callbacks.ended(true)
        drag.callbacks.written(0)
        assertEquals(0, drag.revokes)
        assertEquals(listOf(0, 0), files.map { it.closes })
        drag.callbacks.openReader(1).close()

        files.forEach(DragOutTestContent::retire)
        assertEquals(1, drag.revokes)
    }

    @Test
    fun writesWithoutAcceptanceKeepTheOfferAndRejectionStillRetiresIt() {
        val files = listOf(DragOutTestContent("a.bin"), DragOutTestContent("b.bin"))
        val drag = StartedOffer(files)
        drag.callbacks.written(0)
        drag.callbacks.written(1)
        assertEquals(0, drag.revokes)
        assertEquals(listOf(0, 0), files.map { it.closes })
        drag.callbacks.openReader(0).close()

        drag.callbacks.ended(false)
        assertEquals(1, drag.ended)
        assertEquals(listOf(1, 1), files.map { it.closes })
        assertFailsWith<IllegalStateException> { drag.callbacks.openReader(0) }
    }

    @Test
    fun repeatedAndUnknownWritesCountOnce() {
        val drag = StartedOffer(listOf(DragOutTestContent("a.bin"), DragOutTestContent("b.bin")))
        drag.callbacks.ended(true)
        listOf(0, 0, -1, 2).forEach(drag.callbacks::written)
        assertEquals(0, drag.revokes)
        listOf(1, 1, 0).forEach(drag.callbacks::written)
        assertEquals(1, drag.revokes)
    }

    @Test
    fun dropAcceptedAndWrittenDuringStartupRetiresOnceInstalled() {
        val file = DragOutTestContent("a.bin")
        var revokes = 0
        val offer = offer(file) { _, _, native ->
            native.written(0)
            native.ended(true)
            NativeDragSession { revokes++ }
        }
        assertTrue(offer.start {})
        assertEquals(1, revokes)
        assertEquals(0, file.closes)
    }

    @Test
    fun closingBeforeStartPreventsNativeOwnership() {
        val file = DragOutTestContent("a.bin")
        val offer = offer(file) { _, _, _ ->
            error("Native drag must not start after cancellation")
        }
        offer.close()
        assertFalse(offer.start { error("No native session exists") })
        assertEquals(1, file.closes)
        assertEquals(0, file.handoffs)
    }

    @Test
    fun rejectedOrThrowingNativeStartupClosesOffer() {
        listOf(false, true).forEach { throws ->
            val file = DragOutTestContent("a.bin")
            var ended = 0
            val offer = offer(file) { _, _, _ ->
                if (throws) throw IOException("Native startup failed")
                null
            }
            if (throws) assertFailsWith<IOException> { offer.start { ended++ } }
            else assertFalse(offer.start { ended++ })
            offer.close()
            assertEquals(1, file.closes)
            assertEquals(1, ended)
        }
    }

    @Test
    fun promisedWriteWaitsForPreparationAndReadsItsSize() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val file = DragOutTestContent("report.txt", byteArrayOf(1, 2, 3))
            val gate = CompletableDeferred<Unit>()
            val promised = promised("report.txt") { gate.await(); file.payload() }
            assertTrue(promised.start())
            assertEquals(listOf("report.txt"), promised.names)
            runCurrent()

            val reader = async(Dispatchers.IO) { promised.callbacks!!.openReader(0) }
            gate.complete(Unit)
            runCurrent()
            reader.await().use {
                assertEquals(3L, it.size)
                val data = ByteArray(8)
                assertEquals(3, it.readAt(0, data, data.size))
                assertContentEquals(byteArrayOf(1, 2, 3), data.copyOf(3))
            }
            assertEquals(1, file.handoffs)
            file.retire()
            assertEquals(1, promised.revokes)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedPreparationWakesWaitingWritersAndRevokes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val gate = CompletableDeferred<Unit>()
            val promised = promised("report.txt") { gate.await(); null }
            assertTrue(promised.start())
            runCurrent()
            val reader = async(Dispatchers.IO) {
                assertFailsWith<IllegalStateException> { promised.callbacks!!.openReader(0) }
            }
            gate.complete(Unit)
            runCurrent()
            reader.await()
            assertEquals(1, promised.revokes)
            assertEquals(1, promised.ended)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun rejectedPromisedDropCancelsPreparationAndClosesLateContent() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val file = DragOutTestContent("report.txt")
            val gate = CompletableDeferred<Unit>()
            val promised = promised("report.txt") {
                withContext(NonCancellable) { gate.await() }
                file.payload()
            }
            assertTrue(promised.start())
            runCurrent()
            promised.callbacks!!.ended(false)
            gate.complete(Unit)
            runCurrent()
            assertEquals(1, file.closes)
            assertEquals(0, file.handoffs)
            assertFailsWith<IllegalStateException> { promised.callbacks!!.openReader(0) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun promisedDropRetiresOnceAcceptedAndWritten() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val file = DragOutTestContent("report.txt")
            val promised = promised("report.txt") { file.payload() }
            assertTrue(promised.start())
            runCurrent()
            val callbacks = promised.callbacks!!
            callbacks.openReader(0).close()
            callbacks.written(0)
            assertEquals(0, promised.revokes)
            callbacks.ended(true)
            assertEquals(1, promised.revokes)
            assertEquals(0, file.closes)

            callbacks.retired()
            assertEquals(1, file.closes)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.promised(name: String, prepare: suspend () -> DragPayload?) = Promised(
        DragOutSource(
            scope = backgroundScope,
            label = name,
            prepare = { prepare() },
            onError = {},
            promisedFileName = name,
        ),
    )

    private class Promised(source: DragOutSource) {
        var names: List<String> = emptyList()
        var callbacks: MacFilePromiseCallbacks? = null
        var revokes = 0
        var ended = 0
        private val offer = MacFilePromiseDragOut(NativeDragFiles.Promised(source), WINDOW) { _, value, native ->
            names = value
            callbacks = native
            NativeDragSession { revokes++ }
        }

        fun start() = offer.start { ended++ }
    }

    /** A started offer of prepared [files] whose session counts revocations. */
    private class StartedOffer(files: List<DragOutTestContent>) {
        lateinit var callbacks: MacFilePromiseCallbacks
        var revokes = 0
        var ended = 0

        init {
            val offer = offer(*files.toTypedArray()) { _, _, native ->
                callbacks = native
                NativeDragSession { revokes++ }
            }
            assertTrue(offer.start { ended++ })
        }
    }
}

private fun offer(
    vararg files: DragOutTestContent,
    start: (Long, List<String>, MacFilePromiseCallbacks) -> NativeDragSession?,
) = MacFilePromiseDragOut(NativeDragFiles.Prepared(files.map { it.content }), WINDOW, start)

private fun DragOutTestContent.payload() =
    DragPayload(content.name, listOf(DragItem(DragRepresentation.File(content))))

private const val WINDOW = 1234L
