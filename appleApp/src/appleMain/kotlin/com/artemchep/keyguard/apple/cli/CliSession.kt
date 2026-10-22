package com.artemchep.keyguard.apple.cli

import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.common.service.cli.CliService
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.feature.cli.ui.cliSettingsStateProducer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CliSnapshot(
    val enabled: Boolean,
    /** `null` if this build does not support the CLI. */
    val path: String?,
    /** How to get the CLI's own help. */
    val usage: String,
    val busy: Boolean,
    val failed: Boolean,
)

/** One native presentation of the CLI settings. */
class CliSession internal constructor(private val ctx: CoreContext) {
    private val service = ctx.koin.get<CliService>()
    private val clipboard = ctx.koin.get<ClipboardService>()
    private val scope = CoroutineScope(ctx.scope.coroutineContext + SupervisorJob(ctx.scope.coroutineContext[Job]))
    private val state = cliSettingsStateProducer(service)
        .stateIn(scope, SharingStarted.Eagerly, null)
    private var closed = false

    fun observe(onChange: (CliSnapshot) -> Unit): KeyguardCancellable {
        check(!closed)
        val job = scope.launch {
            state
                .filterNotNull()
                .map { state ->
                    CliSnapshot(
                        enabled = state.enabled,
                        path = state.path,
                        usage = state.usage,
                        busy = state.busy,
                        failed = state.failed,
                    )
                }
                .collect(onChange)
        }
        return KeyguardCancellable(job)
    }

    fun setEnabled(enabled: Boolean) {
        if (!closed) state.value?.onEnabledChange?.invoke(enabled)
    }

    fun copyPath() {
        if (closed) return
        service.state.value.path?.let { clipboard.setPrimaryClip(it, concealed = false) }
    }

    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
    }
}
