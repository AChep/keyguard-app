package com.artemchep.keyguard.feature.gpgagent.tools.keypicker

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.auth.common.TextFieldHandle
import com.artemchep.keyguard.feature.auth.common.textFieldHandle
import com.artemchep.keyguard.feature.auth.common.toModel
import com.artemchep.keyguard.feature.gpgagent.tools.GpgToolsState
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

@Composable
internal fun produceGpgToolsKeyPickerState(
    args: GpgToolsKeyPickerRoute.Args,
    transmitter: RouteResultTransmitter<Set<String>>,
): GpgToolsKeyPickerState = produceScreenState(
    key = "gpg_tools_key_picker",
    initial = GpgToolsKeyPickerState(),
    args = arrayOf(args),
) {
    gpgToolsKeyPickerStateProducer(
        args = args,
        transmitter = transmitter,
    )
}

fun RememberStateFlowScope.gpgToolsKeyPickerStateProducer(
    args: GpgToolsKeyPickerRoute.Args,
    transmitter: RouteResultTransmitter<Set<String>>,
): Flow<GpgToolsKeyPickerState> {
    val selectedIdsSink = mutablePersistedFlow<Set<String>, List<String>>(
        key = "selected_ids",
        serialize = { _, ids -> ids.toList() },
        deserialize = { _, ids -> ids.toSet() },
    ) { args.selectedIds }
    return gpgToolsKeyPickerStateFlow(
        args = args,
        selectedIdsSink = selectedIdsSink,
        queryHandle = textFieldHandle("query"),
        onConfirm = transmitter,
        onDismiss = ::navigatePopSelf,
    )
}

internal fun gpgToolsKeyPickerStateFlow(
    args: GpgToolsKeyPickerRoute.Args,
    selectedIdsSink: MutableStateFlow<Set<String>>,
    queryHandle: TextFieldHandle,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
): Flow<GpgToolsKeyPickerState> {
    val availableIds = args.keys.mapTo(HashSet()) { it.id }
    var finished = false
    fun finish(selection: Set<String>?) {
        if (finished) return
        finished = true
        if (selection != null) onConfirm(selection)
        onDismiss()
    }
    return combine(selectedIdsSink, queryHandle.sink) { selectedIds, query ->
        val selection = confirmedGpgToolsKeySelection(args.mode, availableIds, selectedIds)
        GpgToolsKeyPickerState(
            query = queryHandle.toModel(),
            items = filterGpgToolsKeys(args.keys, query.text).map { key ->
                GpgToolsKeyPickerState.Item(
                    key = key,
                    selected = key.id in selectedIds,
                    onSelect = {
                        selectedIdsSink.update { current ->
                            if (args.mode != GpgToolsKeyPickerRoute.Mode.Recipients) {
                                setOf(key.id)
                            } else if (key.id in current) {
                                current - key.id
                            } else {
                                current + key.id
                            }
                        }
                    },
                )
            },
            noneSelected = selection.isNullOrEmpty(),
            onSelectNone = if (args.mode == GpgToolsKeyPickerRoute.Mode.OptionalSigning) {
                { selectedIdsSink.value = emptySet() }
            } else {
                null
            },
            onDeny = { finish(null) },
            onConfirm = if (selection != null) {
                {
                    confirmedGpgToolsKeySelection(args.mode, availableIds, selectedIdsSink.value)
                        ?.let(::finish)
                }
            } else {
                null
            },
        )
    }
}

internal fun confirmedGpgToolsKeySelection(
    mode: GpgToolsKeyPickerRoute.Mode,
    availableIds: Set<String>,
    selectedIds: Set<String>,
): Set<String>? {
    val selection = selectedIds.intersect(availableIds)
    return selection.takeIf {
        when (mode) {
            GpgToolsKeyPickerRoute.Mode.Recipients -> true
            GpgToolsKeyPickerRoute.Mode.Signing -> it.size == 1
            GpgToolsKeyPickerRoute.Mode.OptionalSigning -> it.size <= 1
        }
    }
}

internal fun filterGpgToolsKeys(
    keys: List<GpgToolsState.KeyItem>,
    query: String,
): List<GpgToolsState.KeyItem> {
    val nameQuery = query.trim()
    val fingerprintQuery = nameQuery.filterNot(Char::isWhitespace)
    return keys.filter { key ->
        nameQuery.isEmpty() ||
            key.title.contains(nameQuery, ignoreCase = true) ||
            key.description?.filterNot(Char::isWhitespace)
                ?.contains(fingerprintQuery, ignoreCase = true) == true
    }
}
