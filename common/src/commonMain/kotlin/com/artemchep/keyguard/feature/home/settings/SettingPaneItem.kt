package com.artemchep.keyguard.feature.home.settings

import com.artemchep.keyguard.feature.home.settings.component.SettingSectionArgs

sealed interface SettingPaneItem {
    val key: String

    data class Group(
        override val key: String,
        val title: String? = null,
        val list: List<Item>,
    ) : SettingPaneItem {
        fun toSectionArgs() = SettingSectionArgs(
            title = title,
        )
    }

    data class Item(
        override val key: String,
        val suffix: String = "",
    ) : SettingPaneItem
}
