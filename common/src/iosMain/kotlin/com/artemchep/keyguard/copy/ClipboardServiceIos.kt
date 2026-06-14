package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.GetClipboardAutoClear
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.kodein.di.DirectDI
import org.kodein.di.instance
import platform.Foundation.NSDate
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.UIKit.UIPasteboard
import platform.UIKit.UIPasteboardOptionExpirationDate
import platform.UIKit.UIPasteboardOptionLocalOnly
import platform.UIKit.UIPasteboardTypeAutomatic
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_sync
import kotlin.time.Duration

class ClipboardServiceIos(
    private val getClipboardAutoClear: GetClipboardAutoClear,
    private val windowCoroutineScope: WindowCoroutineScope,
) : ClipboardService {
    constructor(directDI: DirectDI) : this(
        getClipboardAutoClear = directDI.instance(),
        windowCoroutineScope = directDI.instance(),
    )

    private val autoClearRequestCounter = atomic(0L)

    private val autoClearLock = SynchronizedObject()

    private var autoClearJob: Job? = null

    override fun setPrimaryClip(
        value: String,
        concealed: Boolean,
    ) {
        val changeCount = internalSetPrimaryClip(
            value = value,
            concealed = concealed,
            expirationDate = null,
        )
        scheduleAutoClear(
            value = value,
            concealed = concealed,
            changeCount = changeCount,
        )
    }

    override fun clearPrimaryClip() {
        cancelAutoClear()
        internalSetPrimaryClip(
            value = "",
            concealed = false,
            expirationDate = null,
        )
    }

    override fun hasCopyNotification(): Boolean = false

    private fun internalSetPrimaryClip(
        value: String,
        concealed: Boolean,
        expirationDate: NSDate?,
    ): Long {
        var changeCount = 0L
        runOnMainThread {
            val pasteboard = UIPasteboard.generalPasteboard
            changeCount = pasteboard.setPrimaryClip(
                value = value,
                concealed = concealed,
                expirationDate = expirationDate,
            )
        }
        return changeCount
    }

    private fun scheduleAutoClear(
        value: String,
        concealed: Boolean,
        changeCount: Long,
    ) {
        var job: Job? = null
        synchronized(autoClearLock) {
            val autoClearRequest = autoClearRequestCounter.incrementAndGet()
            autoClearJob?.cancel()
            job = windowCoroutineScope.launch(start = CoroutineStart.LAZY) {
                val duration = getClipboardAutoClear()
                    .first()
                internalScheduleAutoClear(
                    value = value,
                    concealed = concealed,
                    autoClearRequest = autoClearRequest,
                    changeCount = changeCount,
                    duration = duration,
                )
            }
            autoClearJob = job
        }
        job?.start()
    }

    private fun cancelAutoClear() {
        synchronized(autoClearLock) {
            autoClearRequestCounter.incrementAndGet()
            autoClearJob?.cancel()
            autoClearJob = null
        }
    }

    private suspend fun internalScheduleAutoClear(
        value: String,
        concealed: Boolean,
        autoClearRequest: Long,
        changeCount: Long,
        duration: Duration,
    ) {
        if (duration == Duration.INFINITE) {
            clearAutoClearJob(autoClearRequest)
            return
        }

        if (!duration.isPositive()) {
            clearPrimaryClipIfCurrent(
                autoClearRequest = autoClearRequest,
                changeCount = changeCount,
            )
            return
        }

        val expiringChangeCount = setPrimaryClipIfCurrent(
            value = value,
            concealed = concealed,
            autoClearRequest = autoClearRequest,
            changeCount = changeCount,
            expirationDate = NSDate.dateWithTimeIntervalSinceNow(
                duration.inWholeMilliseconds.toDouble() / 1_000.0,
            ),
        ) ?: return

        delay(duration)
        clearPrimaryClipIfCurrent(
            autoClearRequest = autoClearRequest,
            changeCount = expiringChangeCount,
        )
    }

    private fun setPrimaryClipIfCurrent(
        value: String,
        concealed: Boolean,
        autoClearRequest: Long,
        changeCount: Long,
        expirationDate: NSDate,
    ): Long? {
        if (autoClearRequestCounter.value != autoClearRequest) {
            return null
        }

        var expiringChangeCount: Long? = null
        runOnMainThread {
            val pasteboard = UIPasteboard.generalPasteboard
            if (
                autoClearRequestCounter.value == autoClearRequest &&
                pasteboard.changeCount.matchesExpected(changeCount)
            ) {
                expiringChangeCount = pasteboard.setPrimaryClip(
                    value = value,
                    concealed = concealed,
                    expirationDate = expirationDate,
                )
            }
        }

        if (expiringChangeCount == null) {
            clearAutoClearJob(autoClearRequest)
        }
        return expiringChangeCount
    }

    private fun clearPrimaryClipIfCurrent(
        autoClearRequest: Long,
        changeCount: Long,
    ) {
        if (autoClearRequestCounter.value != autoClearRequest) {
            return
        }

        runOnMainThread {
            val pasteboard = UIPasteboard.generalPasteboard
            if (
                autoClearRequestCounter.value == autoClearRequest &&
                pasteboard.changeCount.matchesExpected(changeCount)
            ) {
                pasteboard.setPrimaryClip(
                    value = "",
                    concealed = false,
                    expirationDate = null,
                )
            }
        }
        clearAutoClearJob(autoClearRequest)
    }

    private fun clearAutoClearJob(
        autoClearRequest: Long,
    ) {
        synchronized(autoClearLock) {
            if (autoClearRequestCounter.value == autoClearRequest) {
                autoClearJob = null
            }
        }
    }

    private fun UIPasteboard.setPrimaryClip(
        value: String,
        concealed: Boolean,
        expirationDate: NSDate?,
    ): Long {
        val beforeChangeCount = changeCount
        val item = mapOf(
            UIPasteboardTypeAutomatic to value,
        )
        val options = mutableMapOf<Any?, Any>()
        if (concealed) {
            UIPasteboardOptionLocalOnly?.let { key ->
                options[key] = true
            }
        }
        expirationDate?.let { date ->
            UIPasteboardOptionExpirationDate?.let { key ->
                options[key] = date
            }
        }

        setItems(
            items = listOf(item),
            options = options,
        )

        val afterChangeCount = changeCount
        return if (afterChangeCount > beforeChangeCount) {
            afterChangeCount
        } else {
            beforeChangeCount + 1L
        }
    }

    private fun Long.matchesExpected(
        expected: Long,
    ): Boolean = this == expected || this + 1L == expected

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
