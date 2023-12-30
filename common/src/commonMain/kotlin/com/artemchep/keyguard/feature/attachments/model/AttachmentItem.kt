package com.artemchep.keyguard.feature.attachments.model

import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.common.util.canRetry
import com.artemchep.keyguard.common.util.getHttpCode
import kotlinx.coroutines.flow.StateFlow

data class AttachmentItem(
    val key: String,
    val name: String,
    val extension: String?,
    val size: String?,
    val statusState: StateFlow<Status>,
    val actionsState: StateFlow<List<ContextItem>>,
    val selectableState: StateFlow<SelectableItemState>,
) {
    sealed interface Status {
        companion object {
            fun of(
                downloadStatus: DownloadProgress,
            ) = when (downloadStatus) {
                is DownloadProgress.None -> None

                is DownloadProgress.Loading -> Loading(
                    downloaded = downloadStatus.downloaded,
                    total = downloadStatus.total,
                )

                is DownloadProgress.Complete ->
                    downloadStatus
                        .result
                        .fold(
                            ifLeft = { e ->
                                val code = e.getHttpCode()
                                val autoResume = code.canRetry()
                                Failed(
                                    code = code,
                                    autoResume = autoResume,
                                )
                            },
                            ifRight = { file ->
                                val uri = file.toURI().toString()
                                Downloaded(
                                    localUrl = uri,//leParseUri(file).toString(),
                                )
                            },
                        )
            }
        }

        val previewUrl: String?

        data object None : Status {
            override val previewUrl get() = null
        }

        data class Loading(
            val downloaded: Long?,
            val total: Long?,
        ) : Status {
            override val previewUrl get() = null
        }

        data class Failed(
            val code: Int,
            val autoResume: Boolean,
        ) : Status {
            override val previewUrl get() = null
        }

        data class Downloaded(
            val localUrl: String,
        ) : Status {
            override val previewUrl get() = localUrl
        }
    }
}
