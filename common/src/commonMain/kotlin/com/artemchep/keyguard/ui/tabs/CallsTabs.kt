package com.artemchep.keyguard.ui.tabs

import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.StringResource

enum class CallsTabs(
    override val title: StringResource,
) : TabItem {
    RECENTS(
        title = Res.strings.ciphers_recently_opened,
    ),
    FAVORITES(
        title = Res.strings.ciphers_often_opened,
    );

    companion object {
        val default get() = RECENTS
    }
}
