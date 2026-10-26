package com.artemchep.keyguard.common.service.shortcut

import com.artemchep.autotype.GlobalHotKeyKey
import com.artemchep.autotype.GlobalHotKeyRegistration
import com.artemchep.autotype.GlobalHotKeyRegistrationFailureReason
import com.artemchep.autotype.GlobalHotKeyRegistrationResult
import com.artemchep.autotype.GlobalHotKeySpec
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.GlobalShortcutAction
import com.artemchep.keyguard.common.model.GlobalShortcutBinding
import com.artemchep.keyguard.common.model.GlobalShortcutConfig
import com.artemchep.keyguard.common.model.GlobalShortcutsConfig
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.recordException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns all registrations for the process, independently of windows and vault sessions.
 * Presses are published to [presses]; the controller never runs a handler itself.
 */
@Suppress("TooManyFunctions")
class DesktopGlobalShortcutController(
    private val configurations: Flow<GlobalShortcutsConfig>,
    private val persist: suspend (GlobalShortcutAction, GlobalShortcutConfig) -> Unit,
    private val registrar: GlobalHotKeyRegistrar,
    private val platform: Platform = CurrentPlatform,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GlobalShortcutController {
    private class Registration(
        val binding: GlobalShortcutBinding,
        val handle: GlobalHotKeyRegistration,
        val active: AtomicBoolean,
    )

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(GlobalShortcutRuntimeState())
    override val state = mutableState.asStateFlow()

    private val mutablePresses =
        MutableSharedFlow<GlobalShortcutAction>(
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val presses: SharedFlow<GlobalShortcutAction> = mutablePresses.asSharedFlow()

    private val registrations = mutableMapOf<GlobalShortcutAction, Registration>()
    private val failures = mutableMapOf<GlobalShortcutAction, GlobalShortcutError>()
    private var configuration: GlobalShortcutsConfig? = null
    private var runs = 0
    private var recorders = 0

    /** Registers the configured shortcuts until cancelled. Overlapping runs share them. */
    suspend fun run() {
        mutate { runs++ }
        try {
            // An emitted value can be older than an update that finished while the
            // collector waited for the mutex, so it only signals to read the latest one.
            configurations.flowOn(dispatcher).collect {
                mutate {
                    val config = configurations.first()
                    if (configuration == config) return@mutate
                    configuration = config
                    reconcile()
                }
            }
        } finally {
            mutate {
                if (--runs == 0) {
                    registrations.values.forEach(::close)
                    registrations.clear()
                    failures.clear()
                    configuration = null
                }
            }
        }
    }

    // Native calls and persistence finish even if the caller is cancelled, and every
    // completed transition is published. The outer NonCancellable context also prevents
    // a returned recording lease from being lost on the dispatcher switch back.
    private suspend fun <T> mutate(block: suspend () -> T): T =
        withContext(NonCancellable) {
            withContext(dispatcher) {
                mutex.withLock {
                    try {
                        block()
                    } finally {
                        publishState()
                    }
                }
            }
        }

    // Persistence failures keep the working binding for every non-fatal exception.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun update(
        action: GlobalShortcutAction,
        transform: (GlobalShortcutConfig) -> GlobalShortcutConfig,
    ): GlobalShortcutError? =
        mutate {
            if (configuration == null) return@mutate GlobalShortcutError.Unavailable
            // A settings restore may not be collected yet, so edit the saved value.
            val previous = configurations.first()
            val config = transform(previous[action])
            val next = previous.with(action, config)
            validate(action, next)?.let { return@mutate it }
            // Register a changed binding before saving it, so a failure keeps the old one.
            // While recording, the registration only probes that the binding is available.
            val binding = config.resolve(action, platform)
            val candidate =
                if (config.enabled && registrations[action]?.binding != binding) {
                    when (val result = register(action, binding)) {
                        is Attempt.Failed -> return@mutate result.error
                        is Attempt.Registered -> result.registration
                    }
                } else {
                    null
                }
            try {
                persist(action, config)
            } catch (e: Throwable) {
                e.throwIfFatalOrCancellation()
                candidate?.let(::close)
                return@mutate if (e is GlobalShortcutPersistenceRollbackException) {
                    GlobalShortcutError.PersistenceRestoration
                } else {
                    GlobalShortcutError.Persistence
                }
            }
            configuration = configurations.first()
            if (candidate != null) {
                if (recorders > 0) {
                    close(candidate)
                } else {
                    registrations.remove(action)?.let(::close)
                    activate(action, candidate)
                }
            }
            // Dependencies between actions can change even when only one is edited.
            reconcile()
            null
        }

    override suspend fun suspendRecording(): GlobalShortcutRecording =
        mutate {
            recorders++
            reconcile()
            object : GlobalShortcutRecording {
                private var released = false

                override suspend fun release() =
                    mutate {
                        if (released) return@mutate
                        released = true
                        recorders--
                        reconcile()
                    }
            }
        }

    private fun validate(
        action: GlobalShortcutAction,
        config: GlobalShortcutsConfig,
    ): GlobalShortcutError? {
        val value = config[action]
        val binding = value.resolve(action, platform)
        return when {
            !value.enabled -> {
                null
            }

            !binding.isValid(platform) -> {
                GlobalShortcutError.Invalid
            }

            else -> {
                GlobalShortcutAction.entries
                    .firstOrNull {
                        it != action && config[it].enabled && config[it].resolve(it, platform) == binding
                    }?.let(GlobalShortcutError::Duplicate)
            }
        }
    }

    private fun reconcile() {
        val config = configuration ?: return
        // Release every obsolete binding before registering replacements: a settings
        // restore can transfer a binding between actions or swap both bindings.
        releaseObsolete(config)
        if (recorders == 0) registerMissing(config)
    }

    private fun releaseObsolete(config: GlobalShortcutsConfig) {
        GlobalShortcutAction.entries.forEach { action ->
            val registration = registrations[action] ?: return@forEach
            val wanted = config[action].resolve(action, platform)
            val keep = recorders == 0 && config[action].enabled &&
                registration.binding == wanted && validate(action, config) == null
            if (!keep) {
                registrations.remove(action)
                close(registration)
            }
        }
    }

    private fun registerMissing(config: GlobalShortcutsConfig) {
        GlobalShortcutAction.entries.forEach { action ->
            val value = config[action]
            if (!value.enabled || validate(action, config) != null) {
                failures.remove(action)
                return@forEach
            }
            // The session or platform can't change while the process runs.
            if (action in registrations || failures[action]?.isPermanent == true) return@forEach
            when (val result = register(action, value.resolve(action, platform))) {
                is Attempt.Registered -> activate(action, result.registration)
                is Attempt.Failed -> failures[action] = result.error
            }
        }
    }

    private sealed interface Attempt {
        class Registered(
            val registration: Registration,
        ) : Attempt

        class Failed(
            val error: GlobalShortcutError,
        ) : Attempt
    }

    private fun register(
        action: GlobalShortcutAction,
        binding: GlobalShortcutBinding,
    ): Attempt {
        val active = AtomicBoolean(false)
        val result =
            registrar.register(binding.toNative()) {
                // Native callbacks may already be queued when a binding is replaced or recording starts.
                if (active.get()) mutablePresses.tryEmit(action)
            }
        return when (result) {
            is GlobalHotKeyRegistrationResult.Success -> {
                Attempt.Registered(Registration(binding, result.registration, active))
            }

            is GlobalHotKeyRegistrationResult.Failure -> {
                Attempt.Failed(result.reason.toError())
            }
        }
    }

    private fun activate(
        action: GlobalShortcutAction,
        registration: Registration,
    ) {
        registrations[action] = registration
        failures.remove(action)
        registration.active.set(true)
    }

    // Unregistering is best-effort: an inactive callback ignores late presses, and native
    // registration ids are never reused.
    @Suppress("TooGenericExceptionCaught")
    private fun close(registration: Registration) {
        registration.active.set(false)
        try {
            registration.handle.close()
        } catch (e: Throwable) {
            e.throwIfFatalOrCancellation()
            recordException(e)
        }
    }

    private fun status(
        action: GlobalShortcutAction,
        config: GlobalShortcutsConfig,
    ): GlobalShortcutStatus {
        val error = validate(action, config)
        return when {
            !config[action].enabled -> GlobalShortcutStatus.Disabled
            error != null -> GlobalShortcutStatus.Failed(error)
            recorders > 0 -> GlobalShortcutStatus.Recording
            action in registrations -> GlobalShortcutStatus.Active
            else -> GlobalShortcutStatus.Failed(failures[action] ?: GlobalShortcutError.Unavailable)
        }
    }

    private fun publishState() {
        val config = configuration
        mutableState.value =
            GlobalShortcutRuntimeState(
                config = config,
                statuses =
                    config
                        ?.let { GlobalShortcutAction.entries.associateWith { action -> status(action, it) } }
                        .orEmpty(),
            )
    }
}

internal fun GlobalShortcutBinding.toNative() =
    GlobalHotKeySpec(
        key = GlobalHotKeyKey.valueOf(key.name),
        isCtrlPressed = ctrl,
        isShiftPressed = shift,
        isAltPressed = alt,
        isMetaPressed = meta,
    )

private val GlobalShortcutError.isPermanent
    get() = this == GlobalShortcutError.UnsupportedPlatform || this == GlobalShortcutError.UnsupportedSession

private fun GlobalHotKeyRegistrationFailureReason.toError(): GlobalShortcutError =
    when (this) {
        GlobalHotKeyRegistrationFailureReason.UnsupportedPlatform -> GlobalShortcutError.UnsupportedPlatform
        GlobalHotKeyRegistrationFailureReason.UnsupportedSession -> GlobalShortcutError.UnsupportedSession
        GlobalHotKeyRegistrationFailureReason.InvalidShortcut -> GlobalShortcutError.Invalid
        GlobalHotKeyRegistrationFailureReason.Unavailable -> GlobalShortcutError.Unavailable
        GlobalHotKeyRegistrationFailureReason.InternalError -> GlobalShortcutError.Registration
    }
