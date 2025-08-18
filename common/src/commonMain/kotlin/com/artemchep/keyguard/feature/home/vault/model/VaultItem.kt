package com.artemchep.keyguard.feature.home.vault.model

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import arrow.optics.optics
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GroupableShapeItem
import com.artemchep.keyguard.common.model.PasswordStrength
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.AccentColors
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Immutable
@optics
sealed interface VaultItem2 {
    companion object

    val id: String

    @Immutable
    data class QuickFilters(
        override val id: String,
        val items: ImmutableList<Item>,
    ) : VaultItem2 {
        companion object;

        data class Item(
            val key: String = Uuid.random().toString(),
            val leading: (@Composable () -> Unit)? = null,
            val imageVector: ImageVector? = null,
            val title: String,
            val selected: Boolean = false,
            val primary: Boolean = false,
            val onClick: (() -> Unit)? = null,
        )
    }

    @Immutable
    data class Button(
        override val id: String,
        val title: String,
        val shapeState: Int = ShapeState.ALL,
        val leading: (@Composable () -> Unit)? = null,
        val onClick: (() -> Unit)? = null,
    ) : VaultItem2, GroupableShapeItem<Button> {
        companion object;

        override fun withShape(shape: Int) = copy(shapeState = shape)
    }

    @Immutable
    data object NoSuggestions : VaultItem2 {
        override val id: String get() = "vault_item:no_suggestions"
    }

    @Immutable
    data object NoItems : VaultItem2 {
        override val id: String get() = "vault_item:no_items"
    }

    @Immutable
    data class Section(
        override val id: String = Uuid.random().toString(),
        val text: TextHolder? = null,
        val caps: Boolean = true,
    ) : VaultItem2 {
        companion object
    }

    @Immutable
    data class Item(
        override val id: String,
        val source: DSecret,
        val accentLight: Color,
        val accentDark: Color,
        val tag: String? = source.accountId,
        val accountId: String,
        val groupId: String?,
        val revisionDate: Instant,
        val createdDate: Instant?,
        val password: String?,
        val passwordRevisionDate: Instant?,
        val score: PasswordStrength?,
        val type: String,
        val folderId: String?,
        val icon: VaultItemIcon,
        val feature: Feature,
        val copyText: CopyText,
        val token: TotpToken?,
        val passkeys: ImmutableList<Passkey>,
        val attachments2: ImmutableList<Attachment>,
        /**
         * The name of the item.
         */
        val title: AnnotatedString,
        val text: String?,
        val favourite: Boolean,
        val attachments: Boolean,
        val shapeState: Int = ShapeState.ALL,
        //
        val action: Action,
        val localStateFlow: StateFlow<LocalState>,
    ) : VaultItem2, GroupableShapeItem<Item> {
        companion object;

        override fun withShape(shape: Int) = copy(shapeState = shape)

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
            data class Dropdown(
                val actions: List<ContextItem>,
            ) : Action

            @Immutable
            data class Go(
                val onClick: () -> Unit,
            ) : Action
        }

        @Immutable
        data class Passkey(
            val source: DSecret.Login.Fido2Credentials,
            val onClick: () -> Unit,
        )

        @Immutable
        data class Attachment(
            val source: DSecret.Attachment,
            val onClick: () -> Unit,
        )

        @Immutable
        sealed interface Feature {
            @Immutable
            data object None : Feature

            @Immutable
            data class Totp(
                val token: TotpToken,
            ) : Feature

            @Immutable
            data class Organization(
                val name: String,
                val accentColors: AccentColors,
            ) : Feature
        }
    }
}
