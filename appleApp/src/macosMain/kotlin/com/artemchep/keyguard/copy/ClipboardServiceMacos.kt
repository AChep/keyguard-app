package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.GetClipboardAutoClear
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import platform.AppKit.NSPasteboard
import platform.AppKit.NSPasteboardTypeString
import platform.Foundation.NSThread
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_sync
import kotlin.time.Duration

class ClipboardServiceMacos(
    private val getClipboardAutoClear: GetClipboardAutoClear,
    private val windowCoroutineScope: WindowCoroutineScope,
) : ClipboardService, CopyEventsSource {
    companion object {
        /** http://nspasteboard.org/ — opt-out marker for clipboard managers. */
        private const val CONCEALED_TYPE = "org.nspasteboard.ConcealedType"
    }

    private val copyEventsSink = MutableSharedFlow<Unit>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val copyEvents: Flow<Unit>
        get() = copyEventsSink

    private val autoClearRequestCounter = atomic(0L)

    private var autoClearJob: Job? = null

    override fun setPrimaryClip(
        value: String,
        concealed: Boolean,
    ) {
        val changeCount = internalSetPrimaryClip(value, concealed)
        scheduleAutoClear(changeCount)
        copyEventsSink.tryEmit(Unit)
    }

    override fun clearPrimaryClip() {
        cancelAutoClear()
        runOnMainThread {
            NSPasteboard.generalPasteboard.clearContents()
        }
    }

    override fun hasCopyNotification(): Boolean = false

    private fun internalSetPrimaryClip(
        value: String,
        concealed: Boolean,
    ): Long {
        var changeCount = 0L
        runOnMainThread {
            val pasteboard = NSPasteboard.generalPasteboard
            changeCount = pasteboard.clearContents()
            pasteboard.setString(value, NSPasteboardTypeString)
            if (concealed) {
                pasteboard.setString("", CONCEALED_TYPE)
            }
        }
        return changeCount
    }

    private fun cancelAutoClear() {
        autoClearRequestCounter.incrementAndGet()
        autoClearJob?.cancel()
        autoClearJob = null
    }

    private fun scheduleAutoClear(
        changeCount: Long,
    ) {
        val autoClearRequest = autoClearRequestCounter.incrementAndGet()
        autoClearJob?.cancel()
        autoClearJob = windowCoroutineScope.launch {
            val duration = getClipboardAutoClear()
                .first()
            internalScheduleAutoClear(
                autoClearRequest = autoClearRequest,
                changeCount = changeCount,
                duration = duration,
            )
        }
    }

    private suspend fun internalScheduleAutoClear(
        autoClearRequest: Long,
        changeCount: Long,
        duration: Duration,
    ) {
        if (duration == Duration.INFINITE) {
            cancelAutoClear()
            return
        }

        if (duration.isPositive()) {
            delay(duration)
        }
        clearPrimaryClipIfCurrent(
            autoClearRequest = autoClearRequest,
            changeCount = changeCount,
        )
    }

    private fun clearPrimaryClipIfCurrent(
        autoClearRequest: Long,
        changeCount: Long,
    ) {
        if (autoClearRequestCounter.value != autoClearRequest) {
            return
        }

        runOnMainThread {
            val pasteboard = NSPasteboard.generalPasteboard
            if (
                autoClearRequestCounter.value == autoClearRequest &&
                pasteboard.changeCount == changeCount
            ) {
                pasteboard.clearContents()
            }
        }
    }

    private fun runOnMainThread(
        block: () -> Unit,
    ) {
        if (NSThread.isMainThread) {
            block()
        } else {
            dispatch_sync(dispatch_get_main_queue()) {
                block()
            }
        }
    }
}
