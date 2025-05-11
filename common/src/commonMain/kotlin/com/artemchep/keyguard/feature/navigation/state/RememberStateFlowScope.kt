package com.artemchep.keyguard.feature.navigation.state

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.input.key.KeyEvent
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.feature.loading.LoadingTask
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.LeContext
import org.jetbrains.compose.resources.StringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import org.jetbrains.compose.resources.PluralStringResource

interface TranslatorScope {
    companion object {
        fun of(context: LeContext) = TranslatorScopeContext(context)
    }

    suspend fun translate(
        res: StringResource,
    ): String

    suspend fun translate(
        res: StringResource,
        vararg args: Any,
    ): String

    suspend fun translate(
        res: PluralStringResource,
        quantity: Int,
        vararg args: Any,
    ): String
}

class TranslatorScopeContext(
    private val context: LeContext,
) : TranslatorScope {
    override suspend fun translate(res: StringResource): String =
        textResource(res, context)

    override suspend fun translate(res: StringResource, vararg args: Any): String =
        textResource(res, context, *args)

    override suspend fun translate(
        res: PluralStringResource,
        quantity: Int,
        vararg args: Any,
    ): String = textResource(res, context, quantity, *args)
}

suspend fun TranslatorScope.translate(text: TextHolder) = when (text) {
    is TextHolder.Res -> translate(text.data)
    is TextHolder.Value -> text.data
}

interface RememberStateFlowScope : RememberStateFlowScopeSub, CoroutineScope, TranslatorScope {
    val appScope: CoroutineScope

    val screenScope: CoroutineScope

    val screenId: String

    val screenName: String

    val context: LeContext

    val colorScheme: ColorScheme

    val isStartedFlow: Flow<Boolean>

    /**
     * Commands a closest router to execute the
     * given intent.
     */
    fun navigate(
        intent: NavigationIntent,
    )

    /** Sends a message from this screen */
    fun message(
        message: ToastMessage,
    )

    /** Sends a message from this screen */
    fun message(
        exception: Throwable,
    )

    fun screenExecutor(): LoadingTask

    /**
     * Register a listener to exit request. Once registered, you must
     * call the callback with `true` if you can go back, `false` if that
     * should be ignored.
     */
    fun interceptExit(
        isEnabledFlow: Flow<Boolean>,
        callback: ((Boolean) -> Unit) -> Unit,
    )

    /**
     * Register a back press interceptor. Once registered, the view model subscribes to
     * the flow and if the given lambda is not empty -> invokes it on back press event.
     */
    fun interceptBackPress(
        interceptorFlow: Flow<(() -> Unit)?>,
    ): () -> Unit

    fun interceptKeyEvent(
        interceptorFlow: Flow<((KeyEvent) -> Boolean)?>,
    ): () -> Unit

    fun launchUi(
        block: CoroutineScope.() -> Unit,
    ): () -> Unit

    fun action(block: suspend () -> Unit)

    //
    // Helpers
    //

    fun <T> Flow<T>.shareInScreenScope(
        started: SharingStarted = SharingStarted.WhileSubscribed(1000),
        replay: Int = 1,
    ) = this
        .shareIn(
            scope = screenScope,
            started = started,
            replay = replay,
        )
}

interface RememberStateFlowScopeZygote : RememberStateFlowScope {
    val keepAliveFlow: Flow<Unit>
}

fun RememberStateFlowScope.onClick(block: suspend () -> Unit): () -> Unit = {
    action(block)
}

fun RememberStateFlowScope.navigatePopSelf() {
    val intent = NavigationIntent.PopById(screenId, exclusive = false)
    navigate(intent)
}
