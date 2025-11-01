package com.artemchep.keyguard.feature.auth.bitwarden

import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.ui.tabs.TabItem

data class BitwardenLoginRegionItem(
    override val key: String,
    override val title: TextHolder,
    val checked: Boolean,
    val onClick: (() -> Unit)? = null,
) : TabItem