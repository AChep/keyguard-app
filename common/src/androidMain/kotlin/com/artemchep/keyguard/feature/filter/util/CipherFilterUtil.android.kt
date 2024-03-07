package com.artemchep.keyguard.feature.filter.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AddToHomeScreen
import androidx.core.content.pm.ShortcutManagerCompat
import com.artemchep.keyguard.android.util.ShortcutInfo
import com.artemchep.keyguard.common.model.DCipherFilter
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.iconSmall

context(RememberStateFlowScope)
actual fun CipherFilterUtil.addShortcutActionOrNull(
    filter: DCipherFilter,
): FlatItemAction? {
    val androidContext = context.context
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(androidContext)) {
        return null
    }

    val shortcut = ShortcutInfo.forFilter(
        context = androidContext,
        filter = filter,
    )
    return FlatItemAction(
        leading = iconSmall(Icons.AutoMirrored.Outlined.AddToHomeScreen),
        title = translate(Res.strings.add_to_home_screen),
        onClick = {
            ShortcutManagerCompat.requestPinShortcut(androidContext, shortcut, null)
        },
    )
}
