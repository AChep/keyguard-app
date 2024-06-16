package com.artemchep.keyguard.feature.logs

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import arrow.core.partially1
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.permission.Permission
import com.artemchep.keyguard.common.service.permission.PermissionService
import com.artemchep.keyguard.common.service.permission.PermissionState
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.ExportLogs
import com.artemchep.keyguard.common.usecase.GetGeneratorHistory
import com.artemchep.keyguard.common.usecase.GetInMemoryLogs
import com.artemchep.keyguard.common.usecase.GetInMemoryLogsEnabled
import com.artemchep.keyguard.common.usecase.PutInMemoryLogsEnabled
import com.artemchep.keyguard.common.usecase.RemoveGeneratorHistory
import com.artemchep.keyguard.common.usecase.RemoveGeneratorHistoryById
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

private const val MESSAGE_LENGTH_LIMIT = 300

@Composable
fun produceLogsState() = with(localDI().direct) {
    produceLogsState(
        getGeneratorHistory = instance(),
        removeGeneratorHistory = instance(),
        removeGeneratorHistoryById = instance(),
        dateFormatter = instance(),
        clipboardService = instance(),
        getInMemoryLogs = instance(),
        getInMemoryLogsEnabled = instance(),
        putInMemoryLogsEnabled = instance(),
        permissionService = instance(),
        exportLogs = instance(),
    )
}

@Composable
fun produceLogsState(
    getGeneratorHistory: GetGeneratorHistory,
    removeGeneratorHistory: RemoveGeneratorHistory,
    removeGeneratorHistoryById: RemoveGeneratorHistoryById,
    dateFormatter: DateFormatter,
    clipboardService: ClipboardService,
    getInMemoryLogs: GetInMemoryLogs,
    getInMemoryLogsEnabled: GetInMemoryLogsEnabled,
    putInMemoryLogsEnabled: PutInMemoryLogsEnabled,
    permissionService: PermissionService,
    exportLogs: ExportLogs,
): Loadable<LogsState> = produceScreenState(
    initial = Loadable.Loading,
    key = "generator_history",
    args = arrayOf(
        getGeneratorHistory,
        removeGeneratorHistory,
        removeGeneratorHistoryById,
        dateFormatter,
        clipboardService,
    ),
) {
    fun onExport(
    ) {
        exportLogs()
            .effectTap {
                val msg = ToastMessage(
                    title = translate(Res.string.logs_export_success),
                    type = ToastMessage.Type.SUCCESS,
                )
                message(msg)
            }
            .launchIn(appScope)
    }

    val writeDownloadsPermissionFlow = permissionService
        .getState(Permission.WRITE_EXTERNAL_STORAGE)

    val itemsRawFlow = getInMemoryLogs()
        .shareInScreenScope()
    val itemsFlow = itemsRawFlow
        .map { logs ->
            val items = logs
                .mapIndexed { index, log ->
                    val text = buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                fontWeight = FontWeight.Bold,
                            ),
                        ) {
                            append(log.tag)
                        }
                        append(" ")

                        // Append only a part of a message if that is
                        // too long. This is needed because rendering
                        // huge text will freeze the UI.
                        if (log.message.length > MESSAGE_LENGTH_LIMIT) {
                            val part = log.message.take(MESSAGE_LENGTH_LIMIT)
                            append(part)
                            append("... [truncated]")
                        } else {
                            append(log.message)
                        }
                    }
                    val time = dateFormatter.formatDateTime(log.createdAt)
                    LogsItem.Value(
                        id = index.toString(),
                        text = text,
                        level = log.level,
                        time = time,
                    )
                }
                .toPersistentList()
            LogsState.Content(
                items = items,
            )
        }
        .stateIn(screenScope)

    val switchFlow = getInMemoryLogsEnabled()
        .map { enabled ->
            LogsState.Switch(
                checked = enabled,
                onToggle = {
                    putInMemoryLogsEnabled(!enabled)
                        .launchIn(appScope)
                },
            )
        }
        .stateIn(screenScope)
    val exportFlow = writeDownloadsPermissionFlow
        .map { writeDownloadsPermission ->
            val onExportClick = when (writeDownloadsPermission) {
                is PermissionState.Granted -> ::onExport
                is PermissionState.Declined -> {
                    // lambda
                    writeDownloadsPermission.ask
                        .partially1(context)
                }
            }
            LogsState.Export(
                writePermission = writeDownloadsPermission,
                onExportClick = onExportClick,
            )
        }
        .stateIn(screenScope)

    val state = LogsState(
        contentFlow = itemsFlow,
        switchFlow = switchFlow,
        exportFlow = exportFlow,
    )
    flowOf(Loadable.Ok(state))
}
