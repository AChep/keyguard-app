package com.artemchep.keyguard.feature.dragout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.artemchep.keyguard.common.service.dragout.PreparedContentLimits
import com.artemchep.keyguard.common.service.dragout.PreparedContentStore
import com.artemchep.keyguard.feature.dragout.compose.dragOutSource
import com.artemchep.keyguard.feature.dragout.model.DragItem
import com.artemchep.keyguard.feature.dragout.model.DragOutSource
import com.artemchep.keyguard.feature.dragout.model.DragPayload
import com.artemchep.keyguard.feature.dragout.model.DragRepresentation
import com.artemchep.keyguard.test.RecordingLogRepository
import com.artemchep.keyguard.ui.LocalComposeWindow
import com.artemchep.keyguard.util.io.spool.ByteSnapshotReader
import com.artemchep.keyguard.util.io.spool.RandomAccessByteSnapshot
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/** Opt-in, account-free UI using the real Compose modifier and native AppKit promise bridge. */
@OptIn(ExperimentalCoroutinesApi::class)
fun main() {
    System.setProperty("apple.awt.application.name", "Compose attachment drag probe")
    Dispatchers.setMain(Dispatchers.Swing)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    // A long offer, so late or repeated promise writes stay visible.
    val store = PreparedContentStore(
        scope = scope,
        logRepository = RecordingLogRepository(),
        limits = PreparedContentLimits(offerLifetimeMillis = PROBE_OFFER_MILLIS),
    )
    val drags = AtomicInteger()
    val status = MutableStateFlow("Synthetic bytes only; no vault, account, or network.")
    try {
        application {
            var size by remember { mutableStateOf(PROBE_MAXIMUM_BYTES) }
            var slow by remember { mutableStateOf(false) }
            var showSource by remember { mutableStateOf(true) }
            val name = "keyguard-drag-probe-$size.bin"
            val drag = remember(size, slow) {
                probeDragSource(scope, store, drags, name, size, slow) { status.value = it }
            }
            LaunchedEffect(Unit) {
                delay(PROBE_LIFETIME_MILLIS)
                exitApplication()
            }
            Window(onCloseRequest = ::exitApplication, title = "Compose attachment drag probe") {
                CompositionLocalProvider(LocalComposeWindow provides window) {
                    MaterialTheme {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(status.collectAsState().value)
                            ProbeSizeButtons { size = it; showSource = true }
                            Row {
                                Checkbox(checked = slow, onCheckedChange = { slow = it })
                                Text("Slow reads for source-detachment checks")
                            }
                            if (showSource) {
                                ProbeDragRow(name, drag)
                            }
                            Button(onClick = { showSource = false }) { Text("Detach source row") }
                            Button(onClick = ::exitApplication) { Text("Finish probe") }
                        }
                    }
                }
            }
        }
    } finally {
        store.close()
        scope.cancel()
        Dispatchers.resetMain()
    }
}

private fun probeDragSource(
    scope: CoroutineScope,
    store: PreparedContentStore,
    drags: AtomicInteger,
    name: String,
    size: Int,
    slow: Boolean,
    onStatus: (String) -> Unit,
) = DragOutSource(
    scope = scope,
    label = name,
    prepare = {
        val id = drags.incrementAndGet()
        store.reserve().use { reservation ->
            val content = reservation.publish(name, ProbeSnapshot(id, size, slow))
            probeLog("drag $id: prepared $size bytes")
            content.invokeOnRetirement { probeLog("drag $id: retired") }
            DragPayload(name, listOf(DragItem(DragRepresentation.File(content))))
        }
    },
    onError = { onStatus("Preparation failed: ${it::class.simpleName}") },
    // Like an attachment without re-prompt: the drag starts before preparing.
    promisedFileName = name,
)

@Composable
private fun ProbeSizeButtons(onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0, PROBE_SMALL_BYTES, PROBE_MAXIMUM_BYTES).forEach { bytes ->
            Button(onClick = { onSelect(bytes) }) { Text("$bytes bytes") }
        }
    }
}

@Composable
private fun ProbeDragRow(name: String, drag: DragOutSource) {
    Box(
        Modifier.size(320.dp, 80.dp).dragOutSource(
            drag, enabled = true, touch = false,
        ),
    ) { Text("Drag $name") }
}

private class ProbeSnapshot(
    private val id: Int,
    override val size: Long,
    private val slow: Boolean,
) : RandomAccessByteSnapshot {
    constructor(id: Int, size: Int, slow: Boolean) : this(id, size.toLong(), slow)
    private val closed = AtomicBoolean()
    private val writes = AtomicInteger()

    /** Each promised-file write opens one reader, lazily on its first read. */
    override fun openReader(): ByteSnapshotReader {
        val write = writes.incrementAndGet()
        probeLog("drag $id: write $write opened")
        return object : ByteSnapshotReader {
            @Volatile
            private var served = 0L

            override fun readAt(position: Long, data: ByteArray, startIndex: Int, endIndex: Int): Int {
                check(!closed.get())
                val count = minOf((endIndex - startIndex).toLong(), size - position).toInt().coerceAtLeast(0)
                if (slow && count > 0) Thread.sleep(PROBE_DELAY_MILLIS)
                for (i in 0 until count) data[startIndex + i] = (((position + i) * 31 + 7) and 255).toByte()
                served = maxOf(served, position + count)
                return count
            }

            override fun close() = probeLog("drag $id: write $write closed at $served/$size bytes")
        }
    }

    override fun close() {
        closed.set(true)
        probeLog("drag $id: storage released")
    }
}

private val probeStart = TimeSource.Monotonic.markNow()

private fun probeLog(event: String) {
    val seconds = probeStart.elapsedNow().inWholeMilliseconds / 1000.0
    println("[probe +%.3fs] %s".format(seconds, event))
}

private const val PROBE_SMALL_BYTES = 1024
private const val PROBE_MAXIMUM_BYTES = 2 * 1024 * 1024
private const val PROBE_DELAY_MILLIS = 50L
private const val PROBE_OFFER_MILLIS = 15 * 60 * 1000L
private const val PROBE_LIFETIME_MILLIS = 20 * 60 * 1000L
