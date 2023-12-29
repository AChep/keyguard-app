package com.artemchep.keyguard.feature.auth.login

import androidx.compose.runtime.Immutable
import arrow.optics.optics
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.provider.bitwarden.ServerHeader
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

@Immutable
@optics
data class LoginState(
    val email: TextFieldModel2,
    val password: TextFieldModel2,
    val clientSecret: TextFieldModel2? = null,
    val effects: SideEffect = SideEffect(),
    val showCustomEnv: Boolean = false,
    val regionItems: List<LoginStateItem> = emptyList(),
    val items: List<LoginStateItem> = emptyList(),
    val isLoading: Boolean = false,
    val onLoginClick: (() -> Unit)? = null,
) {
    companion object;

    @Immutable
    @optics
    data class SideEffect(
        val onSuccessFlow: Flow<Unit> = emptyFlow(),
        val onErrorFlow: Flow<LoginEvent.Error> = emptyFlow(),
    ) {
        companion object
    }
}

sealed interface LoginStateItem {
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

    interface HasState<T> {
        val state: LocalStateItem<T>
    }

    data class Dropdown(
        override val id: String,
        override val state: LocalStateItem<State>,
    ) : LoginStateItem, HasState<Dropdown.State> {
        data class State(
            val value: LoginRegion,
            val title: String,
            val text: String?,
            val options: List<FlatItemAction> = emptyList(),
        )
    }

    data class Url(
        override val id: String,
        override val options: List<FlatItemAction> = emptyList(),
        override val state: LocalStateItem<State>,
    ) : LoginStateItem, HasOptions<Url>, HasState<Url.State> {
        override fun withOptions(options: List<FlatItemAction>): Url =
            copy(
                options = options,
            )

        data class State(
            override val options: List<FlatItemAction> = emptyList(),
            val label: String,
            val text: TextFieldModel2,
        ) : HasOptions<State> {
            override fun withOptions(options: List<FlatItemAction>): State =
                copy(
                    options = options,
                )
        }
    }

    data class HttpHeader(
        override val id: String,
        override val options: List<FlatItemAction> = emptyList(),
        override val state: LocalStateItem<State>,
    ) : LoginStateItem, HasOptions<HttpHeader>, HasState<HttpHeader.State> {
        override fun withOptions(options: List<FlatItemAction>): HttpHeader =
            copy(
                options = options,
            )

        data class State(
            override val options: List<FlatItemAction> = emptyList(),
            val label: TextFieldModel2,
            val text: TextFieldModel2,
        ) : HasOptions<State> {
            override fun withOptions(options: List<FlatItemAction>) =
                copy(
                    options = options,
                )
        }
    }

    //
    // Items that may modify the amount of items in the
    // list.
    //

    data class Section(
        override val id: String,
        val text: String? = null,
    ) : LoginStateItem

    data class Label(
        override val id: String,
        val text: String,
    ) : LoginStateItem

    data class Add(
        override val id: String,
        val text: String,
        val actions: List<FlatItemAction>,
    ) : LoginStateItem
}

data class LocalStateItem<T>(
    val flow: StateFlow<T>,
    val populator: CreateRequest.(T) -> CreateRequest = { this },
)

data class CreateRequest(
    val error: Boolean = false,
    // general
    val username: String? = null,
    val password: String? = null,
    val clientSecret: String? = null,
    val region: LoginRegion? = null,
    // base environment
    val baseUrl: String? = null,
    // custom environment
    val webVaultUrl: String? = null,
    val apiUrl: String? = null,
    val identityUrl: String? = null,
    val iconsUrl: String? = null,
    // headers
    val headers: PersistentList<ServerHeader> = persistentListOf(),
)
