package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.StringResource

enum class NavAnimation(
    val key: String,
    val title: StringResource,
) {
    DISABLED(
        key = "disabled",
        title = Res.strings.nav_animation_disabled,
    ),
    CROSSFADE(
        key = "crossfade",
        title = Res.strings.nav_animation_crossfade,
    ),
    DYNAMIC(
        key = "dynamic",
        title = Res.strings.nav_animation_dynamic,
    ),
    ;

    companion object {
        val default get() = DYNAMIC
    }
}
