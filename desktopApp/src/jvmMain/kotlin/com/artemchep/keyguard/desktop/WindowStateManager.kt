package com.artemchep.keyguard.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AnyMap
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.getObject
import com.artemchep.keyguard.common.service.state.impl.toJson
import com.artemchep.keyguard.common.service.state.impl.toMap
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.platform.recordLogDebug
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.kodein.di.DirectDI
import org.kodein.di.instance

// TODO: Add a support for window positioning via
//  the alignment and not absolute values.
class WindowStateManager(
    private val store: KeyValueStore,
    private val json: Json,
) {
    companion object {
        private const val KEY_WINDOW_STATE = "main"

        /**
         * The debounce period between saving the
         * window state.
         */
        private const val SAVE_DEBOUNCE_MS = 100L
    }

    private val windowStatePref by lazy {
        store.getObject(
            key = KEY_WINDOW_STATE,
            defaultValue = null,
            serialize = { entity ->
                val obj = entity?.value.toJson()
                obj.let(json::encodeToString)
            },
            deserialize = { text ->
                kotlin.runCatching {
                    val obj = json.decodeFromString<JsonObject>(text)
                    val map = obj.toMap()
                    AnyMap(map)
                }.getOrNull()
            },
        )
    }

    private var windowStateLatest: SaveableWindowState? = null

    private val requestForegroundSink = EventFlow<Unit>()

    constructor(directDI: DirectDI) : this(
        store = directDI.instance<Files, KeyValueStore>(
            arg = Files.WINDOW_STATE,
        ),
        json = directDI.instance(),
    )

    fun requestForeground() {
        requestForegroundSink.emit(Unit)
    }

    private data class SaveableWindowState(
        val placement: WindowPlacement,
        val position: WindowPosition,
        val size: DpSize,
    ) {
        companion object {
            private const val KEY_ARG_WIDTH = "width"
            private const val KEY_ARG_HEIGHT = "height"

            private const val KEY_ARG_PLACEMENT = "placement"
            private const val KEY_ARG_PLACEMENT_FLOATING = "floating"
            private const val KEY_ARG_PLACEMENT_MAXIMIZED = "maximized"
            private const val KEY_ARG_PLACEMENT_FULLSCREEN = "fullscreen"

            private const val KEY_ARG_POSITION_X = "position_x"
            private const val KEY_ARG_POSITION_Y = "position_y"
            private const val KEY_ARG_POSITION_SPECIFIED = "position_specified"

            private val defaultSize get() = DpSize(800.dp, 600.dp)

            private val defaultPlacement get() = WindowPlacement.Floating

            private val defaultPosition get() = WindowPosition.PlatformDefault

            fun of(state: Map<String, Any?>): SaveableWindowState {
                val placement = when (state[KEY_ARG_PLACEMENT]) {
                    KEY_ARG_PLACEMENT_FLOATING -> WindowPlacement.Floating
                    KEY_ARG_PLACEMENT_MAXIMIZED -> WindowPlacement.Maximized
                    KEY_ARG_PLACEMENT_FULLSCREEN -> WindowPlacement.Fullscreen
                    else -> defaultPlacement
                }
                val size = kotlin.run {
                    val width = state[KEY_ARG_WIDTH] as? Number
                        ?: return@run null
                    val height = state[KEY_ARG_HEIGHT] as? Number
                        ?: return@run null
                    DpSize(
                        width = width.toDouble().dp,
                        height = height.toDouble().dp,
                    )
                } ?: defaultSize
                val position = kotlin.run {
                    fun getPositionAxisAsDpOrNull(key: String): Dp {
                        val v = state[key] as? Number
                            ?: return Dp.Unspecified
                        return v.toDouble().dp
                    }

                    val specified = kotlin.run {
                        val v = state[KEY_ARG_POSITION_SPECIFIED] as? Boolean
                        v == true
                    }
                    if (specified) {
                        val x = getPositionAxisAsDpOrNull(key = KEY_ARG_POSITION_X)
                        val y = getPositionAxisAsDpOrNull(key = KEY_ARG_POSITION_Y)
                        return@run WindowPosition.Absolute(
                            x = x,
                            y = y,
                        )
                    }

                    null
                } ?: defaultPosition
                return SaveableWindowState(
                    placement = placement,
                    position = position,
                    size = size,
                )
            }

            fun of(state: WindowState): SaveableWindowState {
                return SaveableWindowState(
                    placement = state.placement,
                    position = state.position,
                    size = state.size,
                )
            }
        }

        fun toMap(): Map<String, Any?> {
            val placement = when (placement) {
                WindowPlacement.Floating -> KEY_ARG_PLACEMENT_FLOATING
                WindowPlacement.Maximized -> KEY_ARG_PLACEMENT_MAXIMIZED
                WindowPlacement.Fullscreen -> KEY_ARG_PLACEMENT_FULLSCREEN
            }
            val width = size.width.value.toDouble()
            val height = size.height.value.toDouble()

            fun convertPositionAxisToDoubleOrNull(v: Dp): Double? {
                return v.takeIf { it.isSpecified }?.value?.toDouble()
            }

            val x = convertPositionAxisToDoubleOrNull(position.x)
            val y = convertPositionAxisToDoubleOrNull(position.y)
            return mapOf(
                KEY_ARG_PLACEMENT to placement,
                KEY_ARG_WIDTH to width,
                KEY_ARG_HEIGHT to height,
                KEY_ARG_POSITION_X to x,
                KEY_ARG_POSITION_Y to y,
                KEY_ARG_POSITION_SPECIFIED to position.isSpecified,
            )
        }
    }

    private suspend fun get(): SaveableWindowState {
        val state = kotlin.runCatching {
            windowStatePref
                .first()
                ?.value
        }.getOrNull().orEmpty()
        return SaveableWindowState.of(state)
    }

    @Composable
    fun rememberWindowState(): WindowState {
        // Load the previous window configuration from the
        // disk. This is bad, but I do not see any other
        // solution on how to do it without blocking the
        // user.
        val restoredState = remember {
            windowStateLatest
                ?: runBlocking { get() }
                    .also { state ->
                        recordLogDebug {
                            "Restoring window state: $state"
                        }
                    }
        }
        val state = rememberWindowState(
            placement = restoredState.placement,
            position = restoredState.position,
            size = restoredState.size,
        )

        LaunchedEffect(state) {
            requestForegroundSink
                .onEach {
                    state.isMinimized = false
                }
                .collect()
        }
        LaunchSaveEffect(state)
        return state
    }

    @OptIn(FlowPreview::class)
    @Composable
    private fun LaunchSaveEffect(windowState: WindowState) {
        val stateFlow = remember(windowState) {
            val stateFlow = snapshotFlow {
                SaveableWindowState.of(windowState)
            }
            stateFlow
        }

        LaunchedEffect(stateFlow) {
            stateFlow
                .onEach { state ->
                    windowStateLatest = state
                }
                .debounce(SAVE_DEBOUNCE_MS)
                .onEach { state ->
                    recordLogDebug {
                        "Saving window state: $state"
                    }

                    val model = state.toMap()
                    val anyMap = AnyMap(
                        value = model,
                    )
                    windowStatePref.setAndCommit(anyMap)
                        .attempt()
                        .bind()
                }
                .collect()
        }
    }
}
