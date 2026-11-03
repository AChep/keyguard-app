package com.artemchep.keyguard.feature.gpgkey.userids

import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.ui.tabs.TabItem
import kotlinx.collections.immutable.ImmutableList

data class GpgUserIdsState(
    val title: String,
    val message: String,
    val error: String?,
    val busy: Boolean,
    val loading: Boolean,
    val page: Page,
    val identities: List<Identity>,
    val history: List<Identity>,
    val fields: List<Field>,
    val add: Action?,
    val formats: ImmutableList<Format>,
    val confirm: Action?,
    val back: Action,
) {
    enum class Page { List, Edit, Revoke }

    data class Format(
        override val key: String,
        val action: Action,
        val selected: Boolean,
    ) : TabItem {
        override val title: TextHolder get() = TextHolder.Value(action.title)
    }

    data class Identity(
        val id: String,
        val value: String,
        val status: String?,
        val actions: List<Action> = emptyList(),
    )

    data class Field(
        val kind: Kind,
        val label: String,
        val value: TextFieldModel,
    ) {
        enum class Kind { Name, Email, Raw }

        val id: String get() = kind.name.lowercase()
    }

    data class Action(
        val id: String,
        val title: String,
        val destructive: Boolean = false,
        val onClick: (() -> Unit)?,
    )
}
