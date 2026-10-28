package com.artemchep.keyguard.feature.agent.help

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Memory-only wizard, with actions confined to the UI dispatcher. */
@Suppress("TooManyFunctions") // Keep the wizard's small UI actions and their lifecycle in one owner.
internal class AgentAutomaticSetupEditor(
    initial: AgentSetupData,
    private val client: AgentSetupClient?,
    private val scope: CoroutineScope,
    private val onClose: () -> Unit,
) {
    private val sink = MutableStateFlow(initial)
    val state = sink.asStateFlow()
    private var closed = false
    private var revision = 0
    private var job: Job? = null
    private val active get() = !closed && scope.isActive
    private val canRead get() = active && sink.value.setupAllowed && !sink.value.busy && !sink.value.external

    init {
        if (initial.mode == AgentSetupMode.UNDO && !initial.external && initial.available) refresh()
    }

    fun setEnabled(enabled: Boolean) {
        if (!active || sink.value.enabled == enabled) return
        sink.value = sink.value.copy(enabled = enabled)
        if (!enabled && !sink.value.setupAllowed && !sink.value.mutating) {
            cancelRead()
            sink.value = sink.value.copy(plan = null)
        }
    }

    private fun edit(transform: (AgentSetupData) -> AgentSetupData) {
        if (!active || !sink.value.editable) return
        val next = transform(sink.value)
        if (next == sink.value) return
        sink.value = next.copy(hasEdits = true, plan = null, error = null, diagnostic = null)
    }

    fun toggleShell(shell: SetupShell) = edit { current ->
        if (shell !in current.shells) return@edit current
        val selected = current.selectedShells.toMutableSet()
        if (!selected.add(shell)) selected.remove(shell)
        current.copy(selectedShells = selected.toImmutableSet())
    }

    fun setOneHost(value: Boolean) = edit { it.copy(oneHost = value) }
    fun setHost(value: String) = edit { it.copy(host = value) }

    fun next() {
        if (!active || !sink.value.canContinue) return
        val current = sink.value
        when {
            !current.available || current.step == AgentSetupStep.RESULT -> close()
            current.step == AgentSetupStep.SETTINGS -> {
                sink.value = current.copy(step = AgentSetupStep.REVIEW)
                if (!current.external) refresh()
            }
            current.external -> sink.value = current.copy(step = AgentSetupStep.RESULT)
            else -> applyReviewedPlan()
        }
    }

    fun refresh() {
        if (!active || !sink.value.canRefresh || client == null) return
        sink.value = sink.value.copy(plan = null)
        perform(AgentSetupOperation.REVIEWING) { current ->
            val plan = if (current.mode == AgentSetupMode.UNDO) client.undo() else {
                client.preview(current.selectedShells, current.selectedHost)
            }
            current.copy(plan = plan)
        }
    }

    private fun applyReviewedPlan() {
        val current = sink.value
        val plan = current.plan ?: return
        if (client == null || plan.conflicts.isNotEmpty()) return
        val changes = plan.edits.isNotEmpty() || plan.undo
        val operation = if (changes) AgentSetupOperation.APPLYING else AgentSetupOperation.REVIEWING
        perform(operation, afterSuccess = {
            if (sink.value.mode == AgentSetupMode.CONFIGURE) verify()
        }) {
            if (!sink.value.setupAllowed) return@perform sink.value.copy(plan = null)
            // The helper revalidates read dependencies without writing for a no-edit plan.
            client.apply(plan)
            it.copy(
                step = AgentSetupStep.RESULT,
                applied = changes,
                hasEdits = false,
                plan = null,
            )
        }
    }

    fun verify() {
        if (!canRead || client == null) return
        if (sink.value.step != AgentSetupStep.RESULT || sink.value.mode == AgentSetupMode.UNDO) return
        sink.value = sink.value.copy(verified = null, diagnostic = null)
        perform(AgentSetupOperation.VERIFYING) { current ->
            current.copy(verified = true, diagnostic = client.verify())
        }
    }

    private fun perform(
        operation: AgentSetupOperation,
        afterSuccess: () -> Unit = {},
        block: suspend (AgentSetupData) -> AgentSetupData,
    ) {
        val token = ++revision
        val current = sink.value.copy(operation = operation, error = null)
        sink.value = current
        job = scope.launch(start = CoroutineStart.LAZY) {
            val result = runCatchingNonFatal { block(current) }
            if (!active || token != revision) return@launch
            result.onSuccess {
                sink.value = it.copy(operation = AgentSetupOperation.IDLE, enabled = sink.value.enabled)
            }
                .onFailure { error ->
                    sink.value = current.copy(
                        operation = AgentSetupOperation.IDLE,
                        enabled = sink.value.enabled,
                        plan = null,
                        verified = if (operation == AgentSetupOperation.VERIFYING) false else current.verified,
                        error = error.toAgentSetupError(),
                    )
                }
            if (result.isSuccess) afterSuccess()
        }
        job?.start()
    }

    fun back() {
        if (!active || sink.value.mutating) return
        cancelRead()
        val current = sink.value
        when {
            current.step == AgentSetupStep.RESULT || !current.available -> close()
            current.step == AgentSetupStep.REVIEW && current.mode == AgentSetupMode.CONFIGURE ->
                sink.value = current.copy(step = AgentSetupStep.SETTINGS, plan = null, error = null)
            current.hasEdits -> sink.value = current.copy(confirmingDiscard = true)
            else -> close()
        }
    }

    fun confirmDiscard(confirmed: Boolean) {
        if (!active || !sink.value.confirmingDiscard) return
        if (confirmed) close() else sink.value = sink.value.copy(confirmingDiscard = false)
    }

    private fun cancelRead() {
        revision += 1
        job?.cancel()
        sink.value = sink.value.copy(operation = AgentSetupOperation.IDLE)
    }

    private fun close() {
        closed = true
        cancelRead()
        onClose()
    }
}
