package com.artemchep.keyguard.feature.send

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import arrow.optics.optics
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Instant
import kotlin.uuid.Uuid

@Immutable
@optics
sealed interface SendItem {
    companion object

    val id: String

    @Immutable
    data class Section(
        override val id: String = Uuid.random().toString(),
        val text: String? = null,
        val caps: Boolean = true,
    ) : SendItem {
        companion object
    }

    @Immutable
    data class Item(
        override val id: String,
        val source: DSend,
        val accentLight: Color,
        val accentDark: Color,
        val tag: String? = source.accountId,
        val accountId: String,
        val groupId: String?,
        val revisionDate: Instant,
        val createdDate: Instant?,
        val hasPassword: Boolean,
        val hasFile: Boolean,
        val type: String,
        val icon: VaultItemIcon,
        /**
         * The name of the item.
         */
        val title: AnnotatedString,
        val text: String?,
        val notes: String?,
        val deletion: String?,
        //
        val action: Action,
        val localStateFlow: StateFlow<LocalState>,
    ) : SendItem {
        companion object;

        @Immutable
        data class LocalState(
            val openedState: OpenedState,
            val selectableItemState: SelectableItemState,
        )

        @Immutable
        data class OpenedState(
            val isOpened: Boolean,
        )

        @Immutable
        sealed interface Action {
            @Immutable
            data class Go(
                val onClick: () -> Unit,
            ) : Action
        }
    }
}
