package com.artemchep.keyguard.feature.home.settings.shortcuts

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.exception.GlobalShortcutException
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.GlobalShortcutAction
import com.artemchep.keyguard.common.model.GlobalShortcutConfig
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
import kotlinx.coroutines.flow.update
import org.koin.compose.currentKoinScope

@Composable
fun produceKeyboardShortcutsSettingsState(): Loadable<KeyboardShortcutsSettingsState> =
    with(currentKoinScope()) {
        val controller = get<GlobalShortcutController>()
        val putShortcut = get<PutGlobalShortcut>()
        produceScreenState(
            key = "settings_keyboard_shortcuts",
            initial = Loadable.Loading,
            args = arrayOf(controller, putShortcut),
        ) {
            keyboardShortcutsSettingsStateProducer(controller, putShortcut)
        }
    }

suspend fun RememberStateFlowScope.keyboardShortcutsSettingsStateProducer(
    controller: GlobalShortcutController,
    putShortcut: PutGlobalShortcut,
): Flow<Loadable<KeyboardShortcutsSettingsState>> {
    val executor = screenExecutor()
    val errorsSink = MutableStateFlow<Map<GlobalShortcutAction, GlobalShortcutError>>(emptyMap())

    fun change(
        action: GlobalShortcutAction,
        transform: (GlobalShortcutConfig) -> GlobalShortcutConfig,
    ) {
        val io =
            ioEffect {
                val error =
                    try {
                        putShortcut(action, transform).bind()
                        null
                    } catch (e: GlobalShortcutException) {
                        e.error
                    }
                errorsSink.update { if (error == null) it - action else it + (action to error) }
            }
        executor.execute(io)
    }

    fun onEdit(action: GlobalShortcutAction) {
        if (executor.isExecutingFlow.value) return
        errorsSink.update { it - action }
        navigate(NavigationIntent.NavigateToRoute(ShortcutRecorderRoute(action)))
    }

    return combine(
        controller.state,
        executor.isExecutingFlow,
        errorsSink,
    ) { runtime, isBusy, actionErrors ->
        val config = runtime.config ?: return@combine Loadable.Loading
        val state =
            KeyboardShortcutsSettingsState(
                items =
                    GlobalShortcutAction.entries.map { action ->
                        KeyboardShortcutsSettingsState.Item(
                            action = action,
                            config = config[action],
                            status = runtime.statuses.getValue(action),
                            error = actionErrors[action],
                            onEdit = { onEdit(action) },
                            onEnabledChange = { enabled ->
                                change(action) { it.copy(enabled = enabled) }
                            },
                            onReset = {
                                change(action) { it.copy(binding = null) }
                            },
                        )
                    },
                busy = isBusy,
            )
        Loadable.Ok(state)
    }
}
