package com.artemchep.keyguard.feature.auth.keepass

import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.ui.tabs.TabItem

data class KeePassLoginType(
    override val key: String,
    override val title: TextHolder,
    val checked: Boolean,
    val onClick: (() -> Unit)? = null,
) : TabItem
