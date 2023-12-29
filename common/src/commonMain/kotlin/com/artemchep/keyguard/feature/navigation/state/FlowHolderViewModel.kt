package com.artemchep.keyguard.feature.navigation.state

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.State
import arrow.core.Some
import com.artemchep.keyguard.common.usecase.GetScreenState
import com.artemchep.keyguard.common.usecase.PutScreenState
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.navigation.NavigationController
import com.artemchep.keyguard.platform.LeBundle
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.leBundleOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.plus
import kotlinx.serialization.json.Json

class FlowHolderViewModel(
    private val source: String,
    private val scope: CoroutineScope,
) {
    var bundle: LeBundle = leBundleOf()

    private val store = mutableMapOf<String, Some<Entry>>()

    private class Entry(
        val scope: RememberStateFlowScope,
        val job: Job,
        val value: Any?,
    )

    fun <T> getOrPut(
        key: String,
        c: NavigationController,
        showMessage: ShowMessage,
        getScreenState: GetScreenState,
        putScreenState: PutScreenState,
        windowCoroutineScope: WindowCoroutineScope,
        json: Json,
        screen: String,
        screenName: String,
        context: LeContext,
        colorSchemeState: State<ColorScheme>,
        init: RememberStateFlowScope.() -> T,
    ): T = synchronized(this) {
        store.getOrPut(key) {
            val job = Job()
            val scope = RememberStateFlowScopeImpl(
                key = key,
                scope = scope + job + Dispatchers.Default,
                navigationController = c,
                showMessage = showMessage,
                getScreenState = getScreenState,
                putScreenState = putScreenState,
                windowCoroutineScope = windowCoroutineScope,
                json = json,
                bundle = bundle,
                screen = screen,
                screenName = screenName,
                colorSchemeState = colorSchemeState,
                context = context,
            )
            val value = init(scope)
            Some(
                Entry(
                    scope = scope,
                    job = job,
                    value = value,
                ),
            )
        }.value.value as T
    }

    fun clear(key: String) {
        synchronized(this) {
            store.remove(key)?.map { it.job.cancel() }
        }
    }

    private var isDestroyed = false

    fun destroy() {
        synchronized(this) {
            if (!isDestroyed) {
                isDestroyed = true
                //
                store.keys.toSet().forEach {
                    clear(it)
                }
            }
        }
    }

    fun persistedState(): LeBundle {
        val state = store
            .map { (key, sink) -> key to sink.value.scope.persistedState() }
            .toTypedArray()
        return leBundleOf(*state)
    }
}
