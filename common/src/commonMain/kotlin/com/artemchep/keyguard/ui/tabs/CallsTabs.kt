package com.artemchep.keyguard.ui.tabs

import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.StringResource

enum class CallsTabs(
    override val title: StringResource,
) : TabItem {
    RECENTS(
        title = Res.string.ciphers_recently_opened,
    ),
    FAVORITES(
        title = Res.string.ciphers_often_opened,
    );

    companion object {
        val default get() = RECENTS
    }
}
