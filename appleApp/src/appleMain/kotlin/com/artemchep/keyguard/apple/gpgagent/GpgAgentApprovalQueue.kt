package com.artemchep.keyguard.apple.gpgagent

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentApprovalPrompt
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentOperation
import kotlin.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Main-confined approval state shared by the process-wide native panel. */
internal class GpgAgentApprovalQueue(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val timeoutMs: Long = 60_000L,
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val pending = mutableMapOf<String, CompletableDeferred<Boolean>>()
    private val requestsSink = MutableStateFlow<List<GpgAgentRequestSnapshot>>(emptyList())
    val requests = requestsSink.asStateFlow()
    private var counter = 0L

    suspend fun await(prompt: GpgAgentApprovalPrompt): Boolean = withContext(dispatcher) {
        val id = "gpg-${++counter}"
        val result = CompletableDeferred<Boolean>()
        pending[id] = result
        requestsSink.value += prompt.toSnapshot(id, timeoutMs, nowEpochMs() + timeoutMs)
        try {
            withTimeoutOrNull(timeoutMs) { result.await() } ?: false
        } finally {
            withContext(NonCancellable + dispatcher) {
                pending.remove(id)
                requestsSink.value = requestsSink.value.filterNot { it.id == id }
            }
        }
    }

    fun resolve(id: String, approved: Boolean) {
        pending[id]?.complete(approved)
    }

    fun denyAll() {
        pending.values.forEach { it.complete(false) }
        pending.clear()
        requestsSink.value = emptyList()
    }
}

internal fun GpgAgentApprovalPrompt.toSnapshot(
    id: String,
    timeoutMs: Long,
    expiresAtEpochMs: Long,
) = GpgAgentRequestSnapshot(
    id = id,
    keyName = keyName,
    keyFingerprint = keyFingerprint,
    callerName = caller?.appName?.takeIf { it.isNotBlank() } ?: caller?.processName.orEmpty(),
    callerPath = caller?.executablePath.orEmpty(),
    operation = when (operation) {
        GpgAgentOperation.SIGN -> GpgAgentOperationSnapshot.SIGN
        GpgAgentOperation.DECRYPT -> GpgAgentOperationSnapshot.DECRYPT
    },
    timeoutMs = timeoutMs,
    expiresAtEpochMs = expiresAtEpochMs,
)
