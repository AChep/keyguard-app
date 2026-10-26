package com.artemchep.keyguard.feature.home.settings.shortcuts

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.exception.GlobalShortcutException
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.GlobalShortcutAction
import com.artemchep.keyguard.common.model.GlobalShortcutBinding
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.shortcut.GlobalShortcutController
import com.artemchep.keyguard.common.service.shortcut.GlobalShortcutError
import com.artemchep.keyguard.common.usecase.PutGlobalShortcut
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.koin.compose.currentKoinScope

@Composable
fun produceShortcutRecorderState(action: GlobalShortcutAction): Loadable<ShortcutRecorderState> =
    with(currentKoinScope()) {
        val controller = get<GlobalShortcutController>()
        val putShortcut = get<PutGlobalShortcut>()
        produceScreenState(
            key = "shortcut_recorder",
            initial = Loadable.Loading,
            args = arrayOf(action, controller, putShortcut),
        ) {
            shortcutRecorderStateProducer(controller, putShortcut, action)
                .map { Loadable.Ok(it) }
        }
    }

suspend fun RememberStateFlowScope.shortcutRecorderStateProducer(
    controller: GlobalShortcutController,
    putShortcut: PutGlobalShortcut,
    action: GlobalShortcutAction,
): Flow<ShortcutRecorderState> {
    val executor = screenExecutor()
    val errorSink = MutableStateFlow<GlobalShortcutError?>(null)

    fun onDismiss() {
        if (!executor.isExecutingFlow.value) navigate(NavigationIntent.Pop)
    }

    fun onSave(binding: GlobalShortcutBinding) {
        val io =
            ioEffect {
                val error =
                    try {
                        putShortcut(action) { it.copy(binding = binding) }.bind()
                        null
                    } catch (e: GlobalShortcutException) {
                        e.error
                    }
                errorSink.value = error
                if (error == null) navigate(NavigationIntent.Pop)
            }
        executor.execute(io)
    }

    return combine(
        executor.isExecutingFlow,
        errorSink,
    ) { busy, error ->
        ShortcutRecorderState(
            action = action,
            busy = busy,
            error = error,
            startRecording = controller::suspendRecording,
            onSave = ::onSave,
            onClearError = { errorSink.value = null },
            onDismiss = ::onDismiss,
        )
    }
}
