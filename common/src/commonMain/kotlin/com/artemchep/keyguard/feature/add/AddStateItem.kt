package com.artemchep.keyguard.feature.add

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.text.input.VisualTransformation
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.model.UsernameVariation2
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.feature.auth.common.SwitchFieldModel
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.home.vault.add.KeyPairDecor2Brr
import com.artemchep.keyguard.ui.ContextItem
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDateTime

sealed interface AddStateItem {
    val id: String

    interface HasOptions<T> {
        val options: ImmutableList<ContextItem>

        /**
         * Copies the data class replacing the old options with a
         * provided ones.
         */
        fun withOptions(
            options: ImmutableList<ContextItem>,
        ): T
    }

    interface HasState<T, Request> {
        val state: LocalStateItem<T, Request>
    }

    @Stable
    data class Title<Request>(
        override val id: String,
        override val state: LocalStateItem<TextFieldModel2, Request>,
    ) : AddStateItem, HasState<TextFieldModel2, Request>

    @Stable
    data class Username<Request>(
        override val id: String,
        override val state: LocalStateItem<State, Request>,
    ) : AddStateItem, HasState<Username.State, Request> {
        data class State(
            val value: TextFieldModel2,
            val type: UsernameVariation2,
        )
    }

    @Stable
    data class Password<Request>(
        override val id: String,
        val label: String? = null,
        override val state: LocalStateItem<TextFieldModel2, Request>,
    ) : AddStateItem, HasState<TextFieldModel2, Request>

    @Stable
    data class Text<Request>(
        override val id: String,
        val leading: (@Composable RowScope.() -> Unit)? = null,
        val note: String? = null,
        override val state: LocalStateItem<State, Request>,
    ) : AddStateItem, HasState<Text.State, Request> {
        data class State(
            val value: TextFieldModel2,
            val label: String? = null,
            val singleLine: Boolean = false,
            val keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
            val visualTransformation: VisualTransformation = VisualTransformation.None,
        )
    }

    data class Suggestion<Request>(
        override val id: String,
        override val state: LocalStateItem<State, Request>,
    ) : AddStateItem, HasState<Suggestion.State, Request> {
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

    data class Totp<Request>(
        override val id: String,
        override val state: LocalStateItem<State, Request>,
    ) : AddStateItem, HasState<Totp.State, Request> {
        data class State(
            val copyText: CopyText,
            val value: TextFieldModel2,
            val onScanned: ((String) -> Unit)? = null,
            val totpToken: TotpToken? = null,
        )
    }

    data class Passkey<Request>(
        override val id: String,
        override val options: ImmutableList<ContextItem> = persistentListOf(),
        override val state: LocalStateItem<State, Request>,
    ) : AddStateItem, HasOptions<Passkey<*>>, HasState<Passkey.State, Request> {
        override fun withOptions(
            options: ImmutableList<ContextItem>,
        ) = copy(
            options = options,
        )

        data class State(
            val passkey: DSecret.Login.Fido2Credentials?,
        )
    }

    data class Attachment<Request>(
        override val id: String,
        override val options: ImmutableList<ContextItem> = persistentListOf(),
        override val state: LocalStateItem<State, Request>,
    ) : AddStateItem, HasOptions<Attachment<*>>, HasState<Attachment.State, Request> {
        override fun withOptions(
            options: ImmutableList<ContextItem>,
        ) = copy(
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

    data class Url<Request>(
        override val id: String,
        override val options: ImmutableList<ContextItem> = persistentListOf(),
        override val state: LocalStateItem<State, Request>,
    ) : AddStateItem, HasOptions<Url<*>>, HasState<Url.State, Request> {
        override fun withOptions(
            options: ImmutableList<ContextItem>,
        ) = copy(
            options = options,
        )

        data class State(
            override val options: ImmutableList<ContextItem> = persistentListOf(),
            val text: TextFieldModel2,
            val matchType: DSecret.Uri.MatchType? = null,
            val matchTypeTitle: String? = null,
        ) : HasOptions<State> {
            override fun withOptions(
                options: ImmutableList<ContextItem>,
            ) = copy(
                options = options,
            )
        }
    }

    data class Field<Request>(
        override val id: String,
        override val options: ImmutableList<ContextItem> = persistentListOf(),
        override val state: LocalStateItem<State, Request>,
    ) : AddStateItem, HasOptions<Field<*>>, HasState<Field.State, Request> {
        override fun withOptions(
            options: ImmutableList<ContextItem>,
        ) = copy(
            options = options,
        )

        sealed interface State : HasOptions<State> {
            data class Text(
                override val options: ImmutableList<ContextItem> = persistentListOf(),
                val label: TextFieldModel2,
                val text: TextFieldModel2,
                val hidden: Boolean = false,
            ) : State {
                override fun withOptions(
                    options: ImmutableList<ContextItem>,
                ) = copy(
                    options = options,
                )
            }

            data class Switch(
                override val options: ImmutableList<ContextItem> = persistentListOf(),
                val checked: Boolean = false,
                val onCheckedChange: ((Boolean) -> Unit)? = null,
                val label: TextFieldModel2,
            ) : State {
                override fun withOptions(
                    options: ImmutableList<ContextItem>,
                ) = copy(
                    options = options,
                )
            }

            data class LinkedId(
                override val options: ImmutableList<ContextItem> = persistentListOf(),
                val value: DSecret.Field.LinkedId?,
                val actions: ImmutableList<ContextItem>,
                val label: TextFieldModel2,
            ) : State {
                override fun withOptions(
                    options: ImmutableList<ContextItem>,
                ) = copy(
                    options = options,
                )
            }
        }
    }

    data class Tag<Request>(
        override val id: String,
        override val options: ImmutableList<ContextItem> = persistentListOf(),
        override val state: LocalStateItem<State, Request>,
    ) : AddStateItem, HasOptions<Tag<*>>, HasState<Tag.State, Request> {
        override fun withOptions(
            options: ImmutableList<ContextItem>,
        ) = copy(
            options = options,
        )

        sealed interface State : HasOptions<State> {
            data class Text(
                override val options: ImmutableList<ContextItem> = persistentListOf(),
                val text: TextFieldModel2,
            ) : State {
                override fun withOptions(
                    options: ImmutableList<ContextItem>,
                ) = copy(
                    options = options,
                )
            }
        }
    }

    data class Note<Request>(
        override val id: String,
        override val state: LocalStateItem<TextFieldModel2, Request>,
        val markdown: Boolean,
    ) : AddStateItem, HasState<TextFieldModel2, Request>

    data class SshKey<Request>(
        override val id: String,
        override val state: LocalStateItem<KeyPairDecor2Brr, Request>,
    ) : AddStateItem, HasState<KeyPairDecor2Brr, Request>

    data class Enum<Request>(
        override val id: String,
        val leading: (@Composable RowScope.() -> Unit)? = null,
        val label: String,
        override val state: LocalStateItem<State, Request>,
    ) : AddStateItem, HasState<Enum.State, Request> {
        data class State(
            val data: Any? = null,
            val value: String = "",
            val dropdown: ImmutableList<ContextItem> = persistentListOf(),
        )
    }

    data class Switch<Request>(
        override val id: String,
        val title: String,
        val text: String? = null,
        override val state: LocalStateItem<SwitchFieldModel, Request>,
    ) : AddStateItem, HasState<SwitchFieldModel, Request> {
        companion object
    }

    data class Section(
        override val id: String,
        val text: String? = null,
    ) : AddStateItem

    //
    // CUSTOM
    //

    data class DateMonthYear<Request>(
        override val id: String,
        override val state: LocalStateItem<State, Request>,
        val label: String,
    ) : AddStateItem, HasState<DateMonthYear.State, Request> {
        data class State(
            val month: TextFieldModel2,
            val year: TextFieldModel2,
            val onClick: () -> Unit,
        )
    }

    data class DateTime<Request>(
        override val id: String,
        override val state: LocalStateItem<State, Request>,
    ) : AddStateItem, HasState<DateTime.State, Request> {
        companion object;

        data class State(
            val value: LocalDateTime,
            val date: String,
            val time: String,
            val badge: TextFieldModel2.Vl? = null,
            val onSelectDate: () -> Unit,
            val onSelectTime: () -> Unit,
        )
    }

    //
    // Items that may modify the amount of items in the
    // list.
    //

    data class Add(
        override val id: String,
        val text: String,
        val actions: ImmutableList<ContextItem>,
    ) : AddStateItem
}

@Stable
data class LocalStateItem<T, Request>(
    val flow: StateFlow<T>,
    val populator: Request.(T) -> Request = { this },
)
