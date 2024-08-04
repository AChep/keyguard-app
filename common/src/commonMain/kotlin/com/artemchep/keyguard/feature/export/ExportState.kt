package com.artemchep.keyguard.feature.export

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.permission.PermissionState
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.home.vault.model.FilterItem
import kotlinx.coroutines.flow.StateFlow

data class ExportState(
    val itemsFlow: StateFlow<Items>,
    val attachmentsFlow: StateFlow<Attachments>,
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
        val onSave: (() -> Unit)? = null,
    )

    @Immutable
    data class Items(
        val revision: Int,
        val list: List<DSecret>,
        val count: Int,
        val onView: (() -> Unit)? = null,
    )

    @Immutable
    data class Attachments(
        val revision: Int,
        val list: List<DSecret.Attachment>,
        val size: String? = null,
        val count: Int,
        val onView: (() -> Unit)? = null,
        val enabled: Boolean,
        val onToggle: (() -> Unit)? = null,
    )
}
