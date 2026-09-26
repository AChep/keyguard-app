package com.artemchep.keyguard.feature.navigation.state

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.State
import arrow.core.Some
import com.artemchep.keyguard.common.service.clipboard.ClipboardEventBus
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
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
import com.artemchep.keyguard.platform.WindowId
import com.artemchep.keyguard.platform.get
import com.artemchep.keyguard.platform.leBundleOf
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus
import kotlinx.serialization.json.Json

class FlowHolderViewModel(
    private val navigationEntry: NavigationEntry,
) {
    var bundle: LeBundle = leBundleOf()

    private val lock = SynchronizedObject()

    private val store = mutableMapOf<String, Some<Entry>>()
    private val retiredState = mutableMapOf<String, LeBundle>()

    private class Entry(
        val sessionId: String?,
        val scope: RememberStateFlowScope,
        val job: Job,
        val value: Any?,
    )

    private val scope get() = navigationEntry.scope

    fun getScopeOrNull(
        key: String,
        sessionId: String?,
    ): RememberStateFlowScope? = synchronized(lock) {
        store[key]?.value
            ?.takeIf { it.sessionId == sessionId && it.job.isActive }
            ?.scope
    }

    fun <T> getOrPut(
        key: String,
        sessionId: String?,
        c: NavigationController,
        showMessage: ShowMessage,
        clipboardService: ClipboardService,
        clipboardEventBus: ClipboardEventBus,
        getScreenState: GetScreenState,
        putScreenState: PutScreenState,
        windowCoroutineScope: WindowCoroutineScope,
        json: Json,
        screen: String,
        screenName: String,
        context: LeContext,
        colorSchemeState: State<ColorScheme>,
        windowIdState: State<WindowId>,
        init: RememberStateFlowScopeZygote.() -> T,
    ): T = synchronized(lock) {
        val previous = store[key]?.value
        if (previous != null && previous.sessionId == sessionId && previous.job.isActive) {
            return@synchronized previous.value as T
        }
        if (previous != null) {
            retiredState[key] = previous.scope.persistedState()
            store.remove(key)
            previous.job.cancel()
        }
        val entry = createEntry(
            key = key,
            sessionId = sessionId,
            c = c,
            showMessage = showMessage,
            clipboardService = clipboardService,
            clipboardEventBus = clipboardEventBus,
            getScreenState = getScreenState,
            putScreenState = putScreenState,
            windowCoroutineScope = windowCoroutineScope,
            json = json,
            screen = screen,
            screenName = screenName,
            context = context,
            colorSchemeState = colorSchemeState,
            windowIdState = windowIdState,
            init = init,
        )
        store[key] = Some(entry)
        entry.job.invokeOnCompletion {
            synchronized(lock) {
                if (store[key]?.value === entry) {
                    // Keep restorable fields, but release producers and their retired services.
                    retiredState[key] = entry.scope.persistedState()
                    store.remove(key)
                }
            }
        }
        entry.value as T
    }

    /**
     * Creates a new entry for the given key; the caller must
     * hold the [lock].
     */
    private fun <T> createEntry(
        key: String,
        sessionId: String?,
        c: NavigationController,
        showMessage: ShowMessage,
        clipboardService: ClipboardService,
        clipboardEventBus: ClipboardEventBus,
        getScreenState: GetScreenState,
        putScreenState: PutScreenState,
        windowCoroutineScope: WindowCoroutineScope,
        json: Json,
        screen: String,
        screenName: String,
        context: LeContext,
        colorSchemeState: State<ColorScheme>,
        windowIdState: State<WindowId>,
        init: RememberStateFlowScopeZygote.() -> T,
    ): Entry {
        // Vault work must stop on retirement even while its navigation entry survives.
        val parentJob = if (sessionId != null) windowCoroutineScope.job ?: scope.job else scope.job
        val vmCoroutineScopeJob = SupervisorJob(parent = parentJob)
        val navigationLifetime = scope.job
            ?.takeIf { it !== parentJob }
            ?.let { Job(parent = it) }
        // This child has no work of its own, so cancellation propagates immediately
        // without waiting for unrelated navigation children to finish stopping.
        navigationLifetime?.invokeOnCompletion { vmCoroutineScopeJob.cancel() }
        vmCoroutineScopeJob.invokeOnCompletion { navigationLifetime?.cancel() }
        val vmCoroutineScope = WindowCoroutineScopeImpl(
            scope = scope + vmCoroutineScopeJob + Dispatchers.Default,
            showMessage = showMessage,
        )
        val vmRestoredState = retiredState.remove(key)
            ?: (bundle[key] as? LeBundle) // see: this.persistedState(...) for implementation
            // Create an empty state
            ?: leBundleOf()
        val vmScope = RememberStateFlowScopeImpl(
            key = key,
            scope = vmCoroutineScope,
            navigationController = c,
            backPressInterceptorHost = navigationEntry,
            keyEventInterceptorHost = navigationEntry,
            showMessage = showMessage,
            clipboardService = clipboardService,
            clipboardEventBus = clipboardEventBus,
            getScreenState = getScreenState,
            putScreenState = putScreenState,
            windowCoroutineScope = windowCoroutineScope,
            json = json,
            bundle = vmRestoredState,
            screen = screen,
            screenName = screenName,
            colorSchemeState = colorSchemeState,
            windowIdState = windowIdState,
            context = context,
        )
        var initialized = false
        val value = try {
            init(vmScope)
                .also { initialized = true }
        } finally {
            if (!initialized) {
                vmCoroutineScopeJob.cancel()
            }
        }
        return Entry(
            sessionId = sessionId,
            scope = vmScope,
            job = vmCoroutineScopeJob,
            value = value,
        )
    }

    fun clear(key: String) {
        synchronized(lock) {
            retiredState.remove(key)
            store.remove(key)
        }?.map { it.job.cancel() }
    }

    fun destroy() {
        // Do nothing. We do not want to clear all of the screens
        // because there still might be a screen exit animation running.
    }

    fun persistedState(): LeBundle {
        val state = synchronized(lock) {
            (retiredState + store.mapValues { (_, sink) -> sink.value.scope.persistedState() })
                .map { (key, state) -> key to state }
                .toTypedArray()
        }
        return leBundleOf(*state)
    }
}
