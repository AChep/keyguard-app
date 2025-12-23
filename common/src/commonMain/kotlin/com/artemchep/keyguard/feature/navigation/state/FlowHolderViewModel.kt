package com.artemchep.keyguard.feature.navigation.state

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.State
import arrow.core.Some
import com.artemchep.keyguard.common.usecase.GetScreenState
import com.artemchep.keyguard.common.usecase.PutScreenState
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.usecase.impl.WindowCoroutineScopeImpl
import com.artemchep.keyguard.common.util.job
import com.artemchep.keyguard.feature.navigation.NavigationController
import com.artemchep.keyguard.feature.navigation.NavigationEntry
import com.artemchep.keyguard.platform.LeBundle
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.get
import com.artemchep.keyguard.platform.leBundleOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus
import kotlinx.serialization.json.Json

class FlowHolderViewModel(
    private val navigationEntry: NavigationEntry,
) {
    var bundle: LeBundle = leBundleOf()

    private val store = mutableMapOf<String, Some<Entry>>()

    private class Entry(
        val scope: RememberStateFlowScope,
        val job: Job,
        val value: Any?,
    )

    private val scope get() = navigationEntry.scope

    fun getScopeOrNull(
        key: String,
    ): RememberStateFlowScope? = synchronized(this) {
        store[key]
    }?.value?.scope

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
        init: RememberStateFlowScopeZygote.() -> T,
    ): T = synchronized(this) {
        store.getOrPut(key) {
            val vmCoroutineScopeJob = SupervisorJob(parent = scope.job)
            val vmCoroutineScope = WindowCoroutineScopeImpl(
                scope = scope + vmCoroutineScopeJob + Dispatchers.Default,
                showMessage = showMessage,
            )
            val vmRestoredState = bundle[key]
                ?.let { it as? LeBundle } // see: this.persistedState(...) for implementation
                // Create an empty state
                ?: leBundleOf()
            val vmScope = RememberStateFlowScopeImpl(
                key = key,
                scope = vmCoroutineScope,
                navigationController = c,
                backPressInterceptorHost = navigationEntry,
                keyEventInterceptorHost = navigationEntry,
                showMessage = showMessage,
                getScreenState = getScreenState,
                putScreenState = putScreenState,
                windowCoroutineScope = windowCoroutineScope,
                json = json,
                bundle = vmRestoredState,
                screen = screen,
                screenName = screenName,
                colorSchemeState = colorSchemeState,
                context = context,
            )
            val value = init(vmScope)
            Some(
                Entry(
                    scope = vmScope,
                    job = vmCoroutineScopeJob,
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

    fun destroy() {
        // Do nothing. We do not want to clear all of the screens
        // because there still might be a screen exit animation running.
    }

    fun persistedState(): LeBundle {
        val state = store
            .map { (key, sink) -> key to sink.value.scope.persistedState() }
            .toTypedArray()
        return leBundleOf(*state)
    }
}
