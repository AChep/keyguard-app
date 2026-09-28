package com.artemchep.keyguard.apple.vault

import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.download.awaitCompleteResult
import com.artemchep.keyguard.common.service.export.ExportManager
import com.artemchep.keyguard.common.service.export.model.ExportRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

internal data class ExportExecutionState(
    val running: Boolean = false,
    val exportId: String? = null,
    val downloaded: Long? = null,
    val total: Long? = null,
)

/** One transient export run, owned by the navigation entry's scope. */
internal class ExportExecution(
    private val scope: CoroutineScope,
    private val manager: ExportManager,
    private val onSaved: suspend () -> Unit,
    private val onFailure: suspend (Throwable) -> Unit,
) {
    val state = MutableStateFlow(ExportExecutionState())

    fun start(request: ExportRequest) {
        val idle = ExportExecutionState()
        if (!state.compareAndSet(idle, ExportExecutionState(running = true))) return
        scope.launch {
            var exportId: String? = null
            try {
                val queued = manager.queue(request)
                exportId = queued.exportId
                state.value = ExportExecutionState(running = true, exportId = exportId)
                val result = queued.flow.onEach { progress ->
                    if (progress is DownloadProgress.Loading) {
                        state.value = ExportExecutionState(
                            running = true,
                            exportId = exportId,
                            downloaded = progress.downloaded,
                            total = progress.total,
                        )
                    }
                }.awaitCompleteResult()
                result.fold(
                    ifLeft = { error ->
                        if (error !is CancellationException) onFailure(error)
                    },
                    ifRight = { uri ->
                        if (uri != null) onSaved()
                    },
                )
            } catch (e: Exception) {
                e.throwIfFatalOrCancellation()
                onFailure(e)
            } finally {
                exportId?.let(manager::cancel)
                state.value = idle
            }
        }
    }

    fun cancel() {
        state.value.exportId?.let(manager::cancel)
    }
}
