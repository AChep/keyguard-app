package com.artemchep.keyguard.feature.export

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.permission.PermissionState
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.home.vault.model.FilterItem
import kotlinx.coroutines.flow.StateFlow

data class ExportState(
    val itemsFlow: StateFlow<Items>,
    val filterFlow: StateFlow<Filter>,
    val passwordFlow: StateFlow<Password>,
    val contentFlow: StateFlow<Content>,
) {
    data class Content(
        val writePermission: PermissionState,
        val onExportClick: (() -> Unit)? = null,
    )

    data class Password(
        val model: TextFieldModel2,
    )

    @Immutable
    data class Filter(
        val items: List<FilterItem>,
        val onClear: (() -> Unit)? = null,
    )

    @Immutable
    data class Items(
        val revision: Int,
        val list: List<DSecret>,
        val count: Int,
        val onView: (() -> Unit)? = null,
    )
}
