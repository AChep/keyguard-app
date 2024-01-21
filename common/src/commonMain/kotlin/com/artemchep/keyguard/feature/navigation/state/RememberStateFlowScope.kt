package com.artemchep.keyguard.feature.navigation.state

import androidx.compose.material3.ColorScheme
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.feature.loading.LoadingTask
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.LeContext
import dev.icerock.moko.resources.PluralsResource
import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn

interface TranslatorScope {
    fun translate(
        res: StringResource,
    ): String

    fun translate(
        res: StringResource,
        vararg args: Any,
    ): String

    fun translate(
        res: PluralsResource,
        quantity: Int,
        vararg args: Any,
    ): String
}

fun TranslatorScope.translate(text: TextHolder) = when (text) {
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

fun RememberStateFlowScope.navigatePopSelf() {
    val intent = NavigationIntent.PopById(screenId, exclusive = false)
    navigate(intent)
}
