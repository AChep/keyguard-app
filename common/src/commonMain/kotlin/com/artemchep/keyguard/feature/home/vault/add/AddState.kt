package com.artemchep.keyguard.feature.home.vault.add

import androidx.compose.foundation.text.KeyboardOptions
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
        val account: SaveToElement? = null,
        val organization: SaveToElement? = null,
        val collection: SaveToElement? = null,
        val folder: SaveToElement? = null,
        val onClick: (() -> Unit)? = null,
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

    data class SaveToElement(
        val readOnly: Boolean,
        val items: List<Item> = emptyList(),
    ) {
        data class Item(
            val key: String,
            val title: String,
            val text: String? = null,
            val accentColors: AccentColors? = null,
        )
    }
}

sealed interface AddStateItem {
    val id: String

    interface HasOptions<T> {
        val options: List<FlatItemAction>

        /**
         * Copies the data class replacing the old options with a
         * provided ones.
         */
        fun withOptions(
            options: List<FlatItemAction>,
        ): T
    }

    interface HasDecor {
        val decor: Decor
    }

    interface HasState<T> {
        val state: LocalStateItem<T>
    }

    data class Decor(
        val shape: Shape = RectangleShape,
        val elevation: Dp = 0.dp,
    )

    data class Title(
        override val id: String,
        override val state: LocalStateItem<TextFieldModel2>,
    ) : AddStateItem, HasState<TextFieldModel2>

    data class Username(
        override val id: String,
        override val state: LocalStateItem<State>,
    ) : AddStateItem, HasState<Username.State> {
        data class State(
            val value: TextFieldModel2,
            val type: UsernameVariation2,
        )
    }

    data class Password(
        override val id: String,
        override val state: LocalStateItem<TextFieldModel2>,
    ) : AddStateItem, HasState<TextFieldModel2>

    data class Text(
        override val id: String,
        override val decor: Decor = Decor(),
        override val state: LocalStateItem<State>,
    ) : AddStateItem, HasDecor, HasState<Text.State> {
        data class State(
            val value: TextFieldModel2,
            val label: String? = null,
            val singleLine: Boolean = false,
            val keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
            val visualTransformation: VisualTransformation = VisualTransformation.None,
        )
    }

    data class Suggestion(
        override val id: String,
        override val decor: Decor = Decor(),
        override val state: LocalStateItem<State>,
    ) : AddStateItem, HasDecor, HasState<Suggestion.State> {
        data class State(
            val items: ImmutableList<Item>,
        )

        data class Item(
            val key: String,
            val text: String,
            val value: String,
            val source: String,
            val selected: Boolean,
            val onClick: (() -> Unit)? = null,
        )
    }

    data class Totp(
        override val id: String,
        override val state: LocalStateItem<State>,
    ) : AddStateItem, HasState<Totp.State> {
        data class State(
            val copyText: CopyText,
            val value: TextFieldModel2,
            val totpToken: TotpToken? = null,
        )
    }

    data class Passkey(
        override val id: String,
        override val options: List<FlatItemAction> = emptyList(),
        override val state: LocalStateItem<State>,
    ) : AddStateItem, HasOptions<Passkey>, HasState<Passkey.State> {
        override fun withOptions(
            options: List<FlatItemAction>,
        ): Passkey = copy(
            options = options,
        )

        data class State(
            val passkey: DSecret.Login.Fido2Credentials?,
        )
    }

    data class Attachment(
        override val id: String,
        override val options: List<FlatItemAction> = emptyList(),
        override val state: LocalStateItem<State>,
    ) : AddStateItem, HasOptions<Attachment>, HasState<Attachment.State> {
        override fun withOptions(options: List<FlatItemAction>): Attachment =
            copy(
                options = options,
            )

        data class State(
            val id: String,
            val name: TextFieldModel2,
            val size: String? = null,
            /**
             * `true` if the attachment is already uploaded to the server,
             * `false` otherwise.
             */
            val synced: Boolean,
        )
    }

    data class Url(
        override val id: String,
        override val options: List<FlatItemAction> = emptyList(),
        override val state: LocalStateItem<State>,
    ) : AddStateItem, HasOptions<Url>, HasState<Url.State> {
        override fun withOptions(options: List<FlatItemAction>): Url =
            copy(
                options = options,
            )

        data class State(
            override val options: List<FlatItemAction> = emptyList(),
            val text: TextFieldModel2,
            val matchType: DSecret.Uri.MatchType? = null,
            val matchTypeTitle: String? = null,
        ) : HasOptions<State> {
            override fun withOptions(options: List<FlatItemAction>): State =
                copy(
                    options = options,
                )
        }
    }

    data class Field(
        override val id: String,
        override val options: List<FlatItemAction> = emptyList(),
        override val state: LocalStateItem<State>,
    ) : AddStateItem, HasOptions<Field>, HasState<Field.State> {
        override fun withOptions(options: List<FlatItemAction>): Field =
            copy(
                options = options,
            )

        sealed interface State : HasOptions<State> {
            data class Text(
                override val options: List<FlatItemAction> = emptyList(),
                val label: TextFieldModel2,
                val text: TextFieldModel2,
                val hidden: Boolean = false,
            ) : State {
                override fun withOptions(options: List<FlatItemAction>): Text =
                    copy(
                        options = options,
                    )
            }

            data class Switch(
                override val options: List<FlatItemAction> = emptyList(),
                val checked: Boolean = false,
                val onCheckedChange: ((Boolean) -> Unit)? = null,
                val label: TextFieldModel2,
            ) : State {
                override fun withOptions(options: List<FlatItemAction>): Switch =
                    copy(
                        options = options,
                    )
            }

            data class LinkedId(
                override val options: List<FlatItemAction> = emptyList(),
                val value: DSecret.Field.LinkedId?,
                val actions: List<FlatItemAction>,
                val label: TextFieldModel2,
            ) : State {
                override fun withOptions(options: List<FlatItemAction>): LinkedId =
                    copy(
                        options = options,
                    )
            }
        }
    }

    data class Note(
        override val id: String,
        override val state: LocalStateItem<TextFieldModel2>,
        val markdown: Boolean,
    ) : AddStateItem, HasState<TextFieldModel2>

    data class Enum(
        override val id: String,
        val icon: ImageVector,
        val label: String,
        override val state: LocalStateItem<State>,
    ) : AddStateItem, HasState<Enum.State> {
        data class State(
            val value: String = "",
            val dropdown: List<FlatItemAction> = emptyList(),
        )
    }

    data class Switch(
        override val id: String,
        val title: String,
        val text: String? = null,
        override val state: LocalStateItem<SwitchFieldModel>,
    ) : AddStateItem, HasState<SwitchFieldModel>

    data class Section(
        override val id: String,
        val text: String? = null,
    ) : AddStateItem

    //
    // CUSTOM
    //

    data class DateMonthYear(
        override val id: String,
        override val state: LocalStateItem<State>,
        val label: String,
    ) : AddStateItem, HasState<DateMonthYear.State> {
        data class State(
            val month: TextFieldModel2,
            val year: TextFieldModel2,
            val onClick: () -> Unit,
        )
    }

    //
    // Items that may modify the amount of items in the
    // list.
    //

    data class Add(
        override val id: String,
        val text: String,
        val actions: List<FlatItemAction>,
    ) : AddStateItem
}

data class LocalStateItem<T>(
    val flow: StateFlow<T>,
    val populator: CreateRequest.(T) -> CreateRequest = { this },
)
