package com.artemchep.keyguard.feature.navigation.state

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import arrow.core.partially1
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.GetScreenState
import com.artemchep.keyguard.common.usecase.PutScreenState
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.loading.LoadingTask
import com.artemchep.keyguard.feature.loading.getErrorReadableMessage
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.NavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.backpress.BackPressInterceptorHost
import com.artemchep.keyguard.feature.navigation.backpress.interceptBackPress
import com.artemchep.keyguard.platform.LeBundle
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.contains
import com.artemchep.keyguard.platform.get
import com.artemchep.keyguard.platform.leBundleOf
import dev.icerock.moko.resources.PluralsResource
import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.plus
import kotlinx.serialization.json.Json

class RememberStateFlowScopeImpl(
    private val key: String,
    private val bundle: LeBundle,
    private val showMessage: ShowMessage,
    private val getScreenState: GetScreenState,
    private val putScreenState: PutScreenState,
    private val windowCoroutineScope: WindowCoroutineScope,
    private val navigationController: NavigationController,
    private val backPressInterceptorHost: BackPressInterceptorHost,
    private val json: Json,
    private val scope: CoroutineScope,
    private val screen: String,
    private val colorSchemeState: State<ColorScheme>,
    override val screenName: String,
    override val context: LeContext,
) : RememberStateFlowScopeZygote, CoroutineScope by scope {
    private val registry = mutableMapOf<String, Entry<Any?, Any?>>()

    override val colorScheme get() = colorSchemeState.value

    private data class Entry<T, S>(
        val sink: MutableStateFlow<T>,
        val serialize: (T) -> S,
        val deserialize: (S) -> T,
        val composeState: ComposeState<T>,
    ) {
        class ComposeState<T>(
            scope: CoroutineScope,
            sink: MutableStateFlow<T>,
        ) {
            private val collectScope by lazy {
                scope + Dispatchers.Main + Job()
            }

            val mutableState by lazy {
                val state = mutableStateOf(sink.value)
                // Send back the data from a source of truth to the
                // property's sink.
                snapshotFlow { state.value }
                    .onEach { text ->
                        sink.value = text
                    }
                    .launchIn(collectScope)
                return@lazy state
            }

            fun dispose() {
                collectScope.cancel()
            }
        }
    }

    override val appScope: CoroutineScope
        get() = windowCoroutineScope

    override val screenScope: CoroutineScope
        get() = scope

    override val screenId: String
        get() = screen

    /**
     * A flow that is getting observed while the user interface is
     * visible on a screen. Used to provide lifecycle events for the
     * screen.
     */
    private val keepAliveSharedFlow = MutableSharedFlow<Unit>()

    private val isStartedFlow = keepAliveSharedFlow
        .subscriptionCount
        .map { it > 0 }
        .distinctUntilChanged()

    override val keepAliveFlow get() = keepAliveSharedFlow

    private fun getBundleKey(key: String) = "${this.key}:$key"

    override fun navigate(
        intent: NavigationIntent,
    ) {
        navigationController.queue(intent)
    }

    override fun message(
        message: ToastMessage,
    ) {
        showMessage.copy(
            value = message,
            target = key,
        )
    }

    override fun message(
        exception: Throwable,
    ) {
        val parsedMessage = getErrorReadableMessage(exception, this)
        val message = ToastMessage(
            type = ToastMessage.Type.ERROR,
            title = parsedMessage.title,
            text = parsedMessage.text,
        )
        message(message)
    }

    override fun translate(res: StringResource): String =
        textResource(res, context)

    override fun translate(res: StringResource, vararg args: Any): String =
        textResource(res, context, *args)

    override fun translate(
        res: PluralsResource,
        quantity: Int,
        vararg args: Any,
    ): String = textResource(res, context, quantity, *args)

    override fun screenExecutor(): LoadingTask {
        val executor = LoadingTask(this, appScope)
        executor
            .errorFlow
            .onEach { message ->
                val model = ToastMessage(
                    title = message.title,
                    text = message.text,
                    type = ToastMessage.Type.ERROR,
                )
                message(model)
            }
            .launchIn(screenScope)
        return executor
    }

    override fun interceptExit(
        isEnabledFlow: Flow<Boolean>,
        callback: ((Boolean) -> Unit) -> Unit,
    ) {
    }

    override fun interceptBackPress(
        interceptorFlow: Flow<(() -> Unit)?>,
    ): () -> Unit {
        // We want to launch back interceptor only when the
        // screen is currently added to the composable. Otherwise
        // it would intercept the back press event if visually invisible
        // to a user.
        return launchUi {
            backPressInterceptorHost.interceptBackPress(
                scope = this,
                interceptorFlow = interceptorFlow,
            )
        }
    }

    override fun launchUi(block: CoroutineScope.() -> Unit): () -> Unit {
        val job = isStartedFlow
            .mapLatest { active ->
                if (!active) {
                    return@mapLatest
                }

                coroutineScope {
                    block()
                }
            }
            .launchIn(scope)
        return {
            job.cancel()
        }
    }

    override suspend fun loadDiskHandle(
        key: String,
        global: Boolean,
    ): DiskHandle = kotlin.run {
        val diskHandleKey = if (global) {
            "$key@*"
        } else {
            "$key@$screenName"
        }
        DiskHandleImpl
            .read(
                scope = screenScope,
                getScreenState = getScreenState,
                putScreenState = putScreenState,
                key = diskHandleKey,
            )
    }

    override fun <T> mutablePersistedFlow(
        key: String,
        storage: PersistedStorage,
        initialValue: () -> T,
    ): MutableStateFlow<T> = mutablePersistedFlow(
        key = key,
        storage = storage,
        serialize = { _, value -> value },
        deserialize = { _, value -> value },
        initialValue = initialValue,
    )

    override fun <T, S> mutablePersistedFlow(
        key: String,
        storage: PersistedStorage,
        serialize: (Json, T) -> S,
        deserialize: (Json, S) -> T,
        initialValue: () -> T,
    ): MutableStateFlow<T> = synchronized(this) {
        registry.getOrPut(key) {
            val value = kotlin.run {
                val bundleKey = getBundleKey(key)
                if (bundle.contains(bundleKey)) {
                    // Obtain the persisted value from the bundle. We can not guarantee
                    // the type, so we yolo it.
                    val serializedValue = bundle[bundleKey]
                    try {
                        val v = serializedValue as S
                        return@run deserialize(json, v)
                    } catch (e: Exception) {
                        // Fall down.
                        e.printStackTrace()
                    }
                }

                if (
                    storage is PersistedStorage.InDisk &&
                    storage.disk.restoredState.containsKey(key)
                ) {
                    try {
                        val v = storage.disk.restoredState[key] as S
                        return@run deserialize(json, v)
                    } catch (e: Exception) {
                        // Fall down.
                        e.printStackTrace()
                    }
                }

                initialValue()
            }

            val sink = MutableStateFlow(value)
            val entry = Entry(
                sink = sink,
                serialize = serialize.partially1(json),
                deserialize = deserialize.partially1(json),
                composeState = Entry.ComposeState(
                    scope = screenScope,
                    sink = sink,
                ),
            ) as Entry<Any?, Any?>
            // Remember all the updates of the flow in the
            // persisted storage.
            if (storage is PersistedStorage.InDisk) {
                val flow = entry
                    .sink
                    .map {
                        serialize(json, it as T)
                    }
                storage.disk.link(key, flow)
            }
            entry
        }.sink as MutableStateFlow<T>
    }

    override fun <T> asComposeState(key: String) = synchronized(this) {
        registry[key]!!.composeState.mutableState as MutableState<T>
    }

    override fun clearPersistedFlow(key: String) {
        val entry = synchronized(this) {
            registry.remove(key)
        }
        @Suppress("IfThenToSafeAccess")
        if (entry != null) {
            entry.composeState.dispose()
        }
    }

    override fun <T> mutableComposeState(sink: MutableStateFlow<T>): MutableState<T> {
        val entry = synchronized(this) {
            registry.values.firstOrNull { it.sink === sink }
        }
        requireNotNull(entry) {
            "Provided sink must be created using mutablePersistedFlow(...)!"
        }
        return entry.composeState.mutableState as MutableState<T>
    }

    override fun persistedState(): LeBundle {
        val state = synchronized(this) {
            registry
                .map { (key, entry) ->
                    val fullKey = getBundleKey(key)
                    val serializableValue = kotlin.run {
                        val rawValue = entry.sink.value
                        entry.serialize(rawValue)
                    }
                    fullKey to serializableValue
                }
                .toTypedArray()
        }
        return leBundleOf(*state)
    }
}
