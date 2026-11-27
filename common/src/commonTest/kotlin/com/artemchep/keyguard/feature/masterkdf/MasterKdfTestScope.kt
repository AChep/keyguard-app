package com.artemchep.keyguard.feature.masterkdf

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.input.key.KeyEvent
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.feature.loading.LoadingTask
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.DiskHandle
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.platform.LeBundle
import com.artemchep.keyguard.platform.LeContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource

internal const val TRANSLATED = "translated"

/**
 * TEST ONLY: the persistence, translations, messages and
 * navigation of a screen.
 */
internal class MasterKdfTestScope(
    override val appScope: CoroutineScope,
) : RememberStateFlowScope,
    CoroutineScope by appScope {
    val errors = mutableListOf<Throwable>()

    val messages = mutableListOf<ToastMessage>()

    val navigations = mutableListOf<NavigationIntent>()

    /** The format arguments of each translation, in order. */
    val translatedArgs = mutableListOf<List<Any?>>()

    private val persisted = mutableMapOf<String, MutableStateFlow<*>>()

    override val screenScope: CoroutineScope = appScope
    override val screenId: String = "masterkdf_test"
    override val screenName: String = "masterkdf_test"
    override val context: LeContext get() = error("Unused")
    override val colorScheme: ColorScheme get() = error("Unused")
    override val isStartedFlow: Flow<Boolean> = MutableStateFlow(true)

    override fun screenExecutor(): LoadingTask = error("Unused")

    override suspend fun translate(res: StringResource): String {
        translatedArgs += emptyList<Any?>()
        return TRANSLATED
    }

    override suspend fun translate(
        res: StringResource,
        vararg args: Any,
    ): String {
        translatedArgs += args.toList()
        return TRANSLATED
    }

    override suspend fun translate(
        res: PluralStringResource,
        quantity: Int,
        vararg args: Any,
    ): String {
        translatedArgs += args.toList()
        return TRANSLATED
    }

    override fun navigate(intent: NavigationIntent) {
        navigations += intent
    }

    override fun copier(): CopyText = error("Unused")

    override fun message(message: ToastMessage) {
        messages += message
    }

    override fun message(exception: Throwable) {
        errors += exception
    }

    override fun interceptExit(
        isEnabledFlow: Flow<Boolean>,
        callback: ((Boolean) -> Unit) -> Unit,
    ): Unit = error("Unused")

    override fun interceptBackPress(interceptorFlow: Flow<(() -> Unit)?>): () -> Unit = error("Unused")

    override fun interceptKeyEvent(interceptorFlow: Flow<((KeyEvent) -> Boolean)?>): () -> Unit = error("Unused")

    override fun launchUi(block: suspend CoroutineScope.() -> Unit): () -> Unit = error("Unused")

    override fun action(block: suspend () -> Unit): Unit = error("Unused")

    override suspend fun loadDiskHandle(
        key: String,
        global: Boolean,
    ): DiskHandle = error("Unused")

    override fun <T> mutablePersistedFlow(
        key: String,
        storage: PersistedStorage,
        initialValue: () -> T,
    ): MutableStateFlow<T> = persistedFlow(key, initialValue)

    override fun <T, S> mutablePersistedFlow(
        key: String,
        storage: PersistedStorage,
        serialize: (Json, T) -> S,
        deserialize: (Json, S) -> T,
        initialValue: () -> T,
    ): MutableStateFlow<T> = persistedFlow(key, initialValue)

    @Suppress("UNCHECKED_CAST")
    private fun <T> persistedFlow(
        key: String,
        initialValue: () -> T,
    ): MutableStateFlow<T> = persisted.getOrPut(key) {
        MutableStateFlow(initialValue())
    } as MutableStateFlow<T>

    override fun clearPersistedFlow(key: String): Unit = error("Unused")

    override fun persistedState(): LeBundle = error("Unused")
}
