package com.artemchep.keyguard.feature.export

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.permission.PermissionState
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.home.vault.model.FilterItem
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.flow.StateFlow

data class ExportState(
    /** The step of the wizard; only Compose follows it. */
    val wizardFlow: StateFlow<Wizard>,
    val itemsFlow: StateFlow<Items>,
    val attachmentsFlow: StateFlow<Attachments>,
    val filterFlow: StateFlow<Filter>,
    val formatFlow: StateFlow<Format>,
    val passwordFlow: StateFlow<Password>,
    val confirmationPasswordFlow: StateFlow<Password>,
    val contentFlow: StateFlow<Content>,
) {
    @Immutable
    data class Wizard(
        val step: ExportStep,
        /** `null` on the first step, back leaves the screen. */
        val onBack: (() -> Unit)?,
        val onContinue: (() -> Unit)?,
    )

    data class Content(
        val writePermission: PermissionState,
        val onExportClick: (() -> Unit)? = null,
    )

    @Immutable
    data class Format(
        val title: String,
        val note: String,
        val attachmentsNote: String,
        val passwordLabel: String,
        val confirmPasswordLabel: String,
        val options: List<FlatItemAction>,
    )

    data class Password(
        val model: TextFieldModel,
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
