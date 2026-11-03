package com.artemchep.keyguard.feature.dragout

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.dragout.compose.dragOutSource
import com.artemchep.keyguard.feature.dragout.model.DragOutSource
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DragOutGestureTest {
    @Test
    fun `touch handle prepares on long press without selecting its parent row`() = runBlocking {
        Dispatchers.setMain(Dispatchers.Swing)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val prepared = CompletableDeferred<Unit>()
        val selections = AtomicInteger()
        var scene: ImageComposeScene? = null
        try {
            val drag = DragOutSource(
                scope = scope,
                label = "attachment.txt",
                prepare = { prepared.complete(Unit); null },
                onError = { throw it },
            )
            SwingUtilities.invokeAndWait {
                scene = ImageComposeScene(width = 120, height = 120) {
                    val configuration = LocalViewConfiguration.current
                    CompositionLocalProvider(
                        LocalViewConfiguration provides object : ViewConfiguration by configuration {
                            override val longPressTimeoutMillis = 0L
                        },
                    ) {
                        MaterialTheme {
                            Box(
                                Modifier.size(120.dp).combinedClickable(
                                    onClick = {},
                                    onLongClick = { selections.incrementAndGet() },
                                ),
                            ) {
                                Box(
                                    Modifier.size(48.dp).dragOutSource(
                                        drag, enabled = true, touch = true,
                                    ),
                                )
                            }
                        }
                    }
                }
                scene!!.render(0L).close()
                scene!!.sendPointerEvent(PointerEventType.Press, Offset(24f, 24f), type = PointerType.Touch)
            }
            withTimeout(TIMEOUT_MILLIS) { prepared.await() }
            assertEquals(0, selections.get())
            SwingUtilities.invokeAndWait {
                scene!!.sendPointerEvent(PointerEventType.Release, Offset(24f, 24f), type = PointerType.Touch)
            }
        } finally {
            SwingUtilities.invokeAndWait { scene?.close() }
            scope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a shared touch handle lets taps reach its parent`() = runBlocking {
        Dispatchers.setMain(Dispatchers.Swing)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val clicked = CompletableDeferred<Unit>()
        var scene: ImageComposeScene? = null
        try {
            val drag = DragOutSource(
                scope = scope,
                label = "https://example.com/",
                prepare = { error("A tap must not prepare a drag") },
                onError = { throw it },
            )
            SwingUtilities.invokeAndWait {
                scene = ImageComposeScene(width = 120, height = 120) {
                    MaterialTheme {
                        Box(Modifier.size(120.dp).combinedClickable(onClick = { clicked.complete(Unit) })) {
                            Box(
                                Modifier.size(48.dp).dragOutSource(
                                    drag, enabled = true, touch = true, exclusiveTouch = false,
                                ),
                            )
                        }
                    }
                }
                scene!!.render(0L).close()
                scene!!.sendPointerEvent(PointerEventType.Press, Offset(24f, 24f), type = PointerType.Touch)
                scene!!.sendPointerEvent(PointerEventType.Release, Offset(24f, 24f), type = PointerType.Touch)
            }
            withTimeout(TIMEOUT_MILLIS) { clicked.await() }
        } finally {
            SwingUtilities.invokeAndWait { scene?.close() }
            scope.cancel()
            Dispatchers.resetMain()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}
