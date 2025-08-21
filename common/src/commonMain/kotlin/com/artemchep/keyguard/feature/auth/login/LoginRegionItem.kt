package com.artemchep.keyguard.feature.auth.login

import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.ui.tabs.TabItem

data class LoginRegionItem(
    override val key: String,
    override val title: TextHolder,
    val checked: Boolean,
    val onClick: (() -> Unit)? = null,
) : TabItem
