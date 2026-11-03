package com.artemchep.keyguard.feature.home.settings.shortcuts

import com.artemchep.keyguard.common.model.GlobalShortcutBinding
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.key.toText
import com.artemchep.keyguard.ui.shortcut.shortcutText

internal fun GlobalShortcutBinding.displayText(platform: Platform = CurrentPlatform): String =
    shortcutText(
        keyLabel = shortcutKeys.getValue(key).toText(),
        ctrl = ctrl,
        shift = shift,
        alt = alt,
        meta = meta,
        platform = platform,
    ).text
