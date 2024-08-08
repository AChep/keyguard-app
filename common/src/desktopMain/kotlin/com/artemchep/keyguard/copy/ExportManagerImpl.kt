package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.export.impl.ExportManagerBase
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.feature.loading.getErrorReadableMessage
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import org.kodein.di.DirectDI
import org.kodein.di.instance

@OptIn(ExperimentalCoroutinesApi::class)
class ExportManagerImpl(
    private val directDI: DirectDI,
    private val showMessage: ShowMessage,
    private val context: LeContext,
) : ExportManagerBase(
    directDI = directDI,
    onLaunch = { exportId ->
        GlobalScope.launch {
            val exportStatusFlow = getProgressFlowByExportId(exportId = exportId)
                // Return None if the progress flow doesn't
                // exist anymore. Notice how we use the concat here,
                // this is intended.
                .flatMapConcat { flow ->
                    flow ?: flowOf(DownloadProgress.None)
                }

            val result = exportStatusFlow
                // complete once we finish the download
                .transformWhile { progress ->
                    emit(progress) // always emit progress
                    progress !is DownloadProgress.Complete &&
                            progress !is DownloadProgress.None
                }
                .last()
            // None means that the progress flow doesn't exist
            // anymore. This is most likely to happen because
            // someone has cancelled the export.
            if (result is DownloadProgress.None) {
                return@launch
            }

            require(result is DownloadProgress.Complete)
            result.result.fold(
                ifLeft = { e ->
                    val translator = TranslatorScope.of(context)
                    val message = ToastMessage(
                        title = textResource(Res.string.exportaccount_export_failure, context),
                        text = getErrorReadableMessage(e, translator).run { text ?: title },
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
