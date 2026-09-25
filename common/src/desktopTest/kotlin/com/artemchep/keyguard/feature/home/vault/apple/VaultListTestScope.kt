package com.artemchep.keyguard.feature.home.vault.apple

import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.KeyEvent
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.clipboard.ClipboardEventBus
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.GetScreenState
import com.artemchep.keyguard.common.usecase.PutScreenState
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.navigation.NavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.backpress.BackPressInterceptorHost
import com.artemchep.keyguard.feature.navigation.keyboard.KeyEventInterceptorHost
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScopeImpl
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScopeZygote
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.WindowId
import com.artemchep.keyguard.platform.leBundleOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import java.util.concurrent.CopyOnWriteArrayList

internal class InMemoryScreenStateStore {
    private val sink = MutableStateFlow<Map<String, Map<String, Any?>>>(emptyMap())

    val flow: StateFlow<Map<String, Map<String, Any?>>> get() = sink

    val getScreenState: GetScreenState = object : GetScreenState {
        override fun invoke(key: String) = sink
            .map { it[key].orEmpty() }
    }

    val putScreenState: PutScreenState = object : PutScreenState {
        override fun invoke(
            key: String,
            state: Map<String, Any?>,
        ): IO<Unit> = {
            sink.update { it + (key to state) }
        }
    }

    fun snapshot(): Map<String, Map<String, Any?>> = sink.value

    /** Awaits an entry written by the (debounced) disk handle. */
    suspend fun await(
        diskKey: String,
        predicate: (Map<String, Any?>) -> Boolean,
    ): Map<String, Any?> = sink
        .map { it[diskKey].orEmpty() }
        .first(predicate)
}

internal class RecordingNavigationController(
    override val scope: CoroutineScope,
) : NavigationController {
    val intents = CopyOnWriteArrayList<NavigationIntent>()

    override fun queue(intent: NavigationIntent) {
        intents += intent
    }

    override fun canPop() = flowOf(false)
}

internal class RecordingShowMessage : ShowMessage {
    val messages = CopyOnWriteArrayList<ToastMessage>()

    override fun copy(value: ToastMessage, target: String?) {
        messages += value
    }
}

internal class RecordingClipboardService : ClipboardService {
    data class Clip(
        val value: String,
        val concealed: Boolean,
    )

    val clips = CopyOnWriteArrayList<Clip>()

    override fun setPrimaryClip(value: String, concealed: Boolean) {
        clips += Clip(value, concealed)
    }

    override fun clearPrimaryClip() = Unit

    override fun hasCopyNotification(): Boolean = true
}

private object NoOpBackPressInterceptorHost : BackPressInterceptorHost {
    override fun interceptBackPress(block: () -> Unit): () -> Unit = {}
}

private object NoOpKeyEventInterceptorHost : KeyEventInterceptorHost {
    override fun interceptKeyEvent(block: (KeyEvent) -> Boolean): () -> Unit = {}
}

internal object JvmTestTranslator : TranslatorScope {
    override suspend fun translate(res: StringResource): String =
        runCatching { getString(res) }.getOrElse { res.key }

    override suspend fun translate(res: StringResource, vararg args: Any): String =
        runCatching { getString(res, *args) }
            .getOrElse { "${res.key}(${args.joinToString()})" }

    override suspend fun translate(
        res: PluralStringResource,
        quantity: Int,
        vararg args: Any,
    ): String = runCatching { getPluralString(res, quantity, *args) }
        .getOrElse { "${res.key}($quantity)" }
}

private class TranslatorOverrideScope(
    private val impl: RememberStateFlowScopeZygote,
    private val translator: TranslatorScope,
) : RememberStateFlowScopeZygote by impl {
    override suspend fun translate(res: StringResource): String =
        translator.translate(res)

    override suspend fun translate(res: StringResource, vararg args: Any): String =
        translator.translate(res, *args)

    override suspend fun translate(
        res: PluralStringResource,
        quantity: Int,
        vararg args: Any,
    ): String = translator.translate(res, quantity, *args)
}

internal fun newVaultListTestScope(
    screenName: String,
    screenScope: CoroutineScope,
    appScope: CoroutineScope,
    screenStateStore: InMemoryScreenStateStore,
    navigationController: NavigationController,
    showMessage: ShowMessage,
    clipboardService: ClipboardService,
    json: Json = Json,
    translator: TranslatorScope = JvmTestTranslator,
): RememberStateFlowScopeZygote {
    val impl = RememberStateFlowScopeImpl(
        key = screenName,
        bundle = leBundleOf(),
        showMessage = showMessage,
        clipboardService = clipboardService,
        clipboardEventBus = ClipboardEventBus(),
        getScreenState = screenStateStore.getScreenState,
        putScreenState = screenStateStore.putScreenState,
        windowCoroutineScope = object : WindowCoroutineScope, CoroutineScope by appScope {},
        navigationController = navigationController,
        backPressInterceptorHost = NoOpBackPressInterceptorHost,
        keyEventInterceptorHost = NoOpKeyEventInterceptorHost,
        json = json,
        scope = screenScope,
        screen = screenName,
        colorSchemeState = mutableStateOf(lightColorScheme()),
        windowIdState = mutableStateOf(WindowId(0L)),
        screenName = screenName,
        context = LeContext(),
    )
    return TranslatorOverrideScope(
        impl = impl,
        translator = translator,
    )
}
