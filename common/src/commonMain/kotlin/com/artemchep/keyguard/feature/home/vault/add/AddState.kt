package com.artemchep.keyguard.feature.home.vault.add

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.model.UsernameVariation2
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.feature.add.AddStateItem
import com.artemchep.keyguard.feature.add.AddStateOwnership
import com.artemchep.keyguard.feature.auth.common.SwitchFieldModel
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.confirmation.organization.FolderInfo
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.icons.AccentColors
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * @author Artem Chepurnyi
 */
data class AddState(
    val title: String = "",
    val favourite: SwitchFieldModel,
    val ownership: Ownership,
    val merge: Merge? = null,
    val sideEffects: SideEffects,
    val actions: List<FlatItemAction> = emptyList(),
    val items: List<AddStateItem> = emptyList(),
    val onSave: (() -> Unit)? = null,
) {
    @Immutable
    data class SideEffects(
        val filePickerIntentFlow: Flow<FilePickerIntent<*>>,
    ) {
        companion object
    }

    data class Ownership(
        val data: Data,
        val ui: AddStateOwnership,
    ) {
        data class Data(
            val accountId: String?,
            val folderId: FolderInfo,
            val organizationId: String?,
            val collectionIds: Set<String>,
        )
    }

    data class Merge(
        val ciphers: List<DSecret>,
        val note: SimpleNote?,
        val removeOrigin: SwitchFieldModel,
    )
}
