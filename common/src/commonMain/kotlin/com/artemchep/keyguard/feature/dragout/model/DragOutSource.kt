package com.artemchep.keyguard.feature.dragout.model

import com.artemchep.keyguard.common.exception.DragOutException
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.drag_out_error
import kotlin.coroutines.coroutineContext
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Something a presentation lets the user drag out. Nothing is prepared until a gesture
 * starts; a payload that is ready at once never shows progress.
 */
class DragOutSource(
    private val scope: CoroutineScope,
    val label: String,
    private val prepare: suspend ((DownloadProgress.Loading) -> Unit) -> DragPayload?,
    private val onError: (Throwable) -> Unit,
    private val errorTitle: TextHolder = TextHolder.Res(Res.string.drag_out_error),
    private val registerCancellation: ((() -> Unit) -> (() -> Unit))? = null,
    /**
     * The one file [prepare] returns, named before preparing it. Set only when preparation
     * never asks the user anything: a transport may then start the drag first.
     */
    val promisedFileName: String? = null,
    /** The link a source built by [Companion.url] offers; null for any other source. */
    val url: String? = null,
) {
    private val mutablePreparing = MutableStateFlow(false)

    /** Whether a started gesture is still waiting for slow preparation. */
    val preparing = mutablePreparing.asStateFlow()

    // The loader boundary reports arbitrary failures while preserving cancellation.
    @Suppress("TooGenericExceptionCaught")
    fun start(onResult: (DragPayload?) -> Unit): Job {
        val delivered = atomic(false)
        fun complete(payload: DragPayload?) {
            if (delivered.compareAndSet(expect = false, update = true)) onResult(payload)
            else payload?.close()
        }
        val job = scope.launch(Dispatchers.Main.immediate) {
            var payload: DragPayload? = null
            try {
                payload = prepare { mutablePreparing.value = true }
                coroutineContext.ensureActive()
                complete(payload)
                payload = null
            } catch (e: Throwable) {
                e.throwIfFatalOrCancellation()
                reportError(e)
            } finally {
                payload?.close()
                mutablePreparing.value = false
                complete(null)
            }
        }
        job.invokeOnCompletion { complete(null) }
        return job
    }

    internal fun observeCancellation(cancel: () -> Unit): () -> Unit = registerCancellation?.invoke(cancel) ?: {}

    internal fun reportError(error: Throwable) = onError(DragOutException(error, errorTitle))

    companion object {
        /** A link is ready at once; preparation cannot fail. */
        fun url(
            scope: CoroutineScope,
            url: String,
            onError: (Throwable) -> Unit,
        ) = DragOutSource(
            scope = scope,
            label = url,
            prepare = { DragPayload(url, listOf(DragItem(DragRepresentation.Url(url)))) },
            onError = onError,
            url = url,
        )
    }
}
