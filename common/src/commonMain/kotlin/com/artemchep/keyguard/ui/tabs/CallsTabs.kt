package com.artemchep.keyguard.ui.tabs

import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

enum class CallsTabs(
    override val key: String,
    override val title: TextHolder,
) : TabItem {
    RECENTS(
        key = "recents",
        title = TextHolder.Res(Res.string.ciphers_recently_opened),
    ),
    FAVORITES(
        key = "favorites",
        title = TextHolder.Res(Res.string.ciphers_often_opened),
    );

    companion object {
        val default get() = RECENTS
    }
}
