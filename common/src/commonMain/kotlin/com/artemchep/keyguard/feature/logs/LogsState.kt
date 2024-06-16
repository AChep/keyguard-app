@file:JvmName("GeneratorStateUtils")

package com.artemchep.keyguard.feature.logs

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.service.permission.PermissionState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.StateFlow

@Immutable
data class LogsState(
    val contentFlow: StateFlow<Content>,
    val exportFlow: StateFlow<Export>,
    val switchFlow: StateFlow<Switch>,
) {
    @Immutable
    data class Content(
        val items: ImmutableList<LogsItem>,
    )

    @Immutable
    data class Export(
        val writePermission: PermissionState,
        val onExportClick: (() -> Unit)? = null,
    )

    @Immutable
    data class Switch(
        val checked: Boolean,
        val onToggle: (() -> Unit)? = null,
    )
}
