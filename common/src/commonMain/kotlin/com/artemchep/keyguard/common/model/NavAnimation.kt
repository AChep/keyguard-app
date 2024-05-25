package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.StringResource

enum class NavAnimation(
    val key: String,
    val title: StringResource,
) {
    DISABLED(
        key = "disabled",
        title = Res.string.nav_animation_disabled,
    ),
    CROSSFADE(
        key = "crossfade",
        title = Res.string.nav_animation_crossfade,
    ),
    DYNAMIC(
        key = "dynamic",
        title = Res.string.nav_animation_dynamic,
    ),
    ;

    companion object {
        val default get() = DYNAMIC
    }
}
