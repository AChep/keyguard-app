package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.timeout
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.export.impl.ExportManagerBase
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import org.kodein.di.DirectDI
import org.kodein.di.instance

class ExportManagerImpl(
    private val directDI: DirectDI,
    private val showMessage: ShowMessage,
    private val context: LeContext,
) : ExportManagerBase(
    directDI = directDI,
    onLaunch = { exportId ->
        GlobalScope.launch {
            val exportStatusFlow = statusByExportId(exportId = exportId)
            kotlin.run {
                // ...check if the status is other then None.
                val result = exportStatusFlow
                    .filter { it !is DownloadProgress.None }
                    .toIO()
                    .timeout(500L)
                    .attempt()
                    .bind()
                if (result.isLeft()) {
                    return@launch
                }
            }

            val result = exportStatusFlow
                // complete once we finish the download
                .transformWhile { progress ->
                    emit(progress) // always emit progress
                    progress !is DownloadProgress.Complete
                }
                .last()
            require(result is DownloadProgress.Complete)
            result.result.fold(
                ifLeft = {
                    val message = ToastMessage(
                        title = textResource(Res.string.exportaccount_export_failure, context),
                        type = ToastMessage.Type.ERROR,
                    )
                    showMessage.copy(message)
                },
                ifRight = {
                    val message = ToastMessage(
                        title = textResource(Res.string.exportaccount_export_success, context),
                        type = ToastMessage.Type.SUCCESS,
                    )
                    showMessage.copy(message)
                },
            )
        }
    },
) {
    constructor(
        directDI: DirectDI,
    ) : this(
        directDI = directDI,
        showMessage = directDI.instance(),
        context = directDI.instance(),
    )
}
