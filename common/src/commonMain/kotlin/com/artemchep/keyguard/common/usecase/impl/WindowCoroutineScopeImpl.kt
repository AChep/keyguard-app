package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.NoAnalytics
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.platform.recordException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

class WindowCoroutineScopeImpl(
    private val scope: CoroutineScope,
    private val showMessage: ShowMessage,
) : WindowCoroutineScope {
    constructor(directDI: DirectDI) : this(
        scope = GlobalScope,
        showMessage = directDI.instance(),
    )

    private val handler = CoroutineExceptionHandler { _, exception ->
        if (exception is CancellationException) {
            return@CoroutineExceptionHandler
        }
        if (
            exception !is IOException &&
            exception !is NoAnalytics
        ) {
            recordException(exception)
        }

        exception.printStackTrace()

        val title = exception.localizedMessage
            ?: exception.message
            ?: exception.javaClass.simpleName
        val msg = ToastMessage(
            title = title,
            type = ToastMessage.Type.ERROR,
        )
        showMessage.copy(msg)
    }

    private val internalScope = scope + SupervisorJob() + handler

    override val coroutineContext: CoroutineContext
        get() = internalScope.coroutineContext
}
