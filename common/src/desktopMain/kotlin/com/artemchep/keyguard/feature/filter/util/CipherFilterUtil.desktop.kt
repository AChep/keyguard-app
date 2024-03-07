package com.artemchep.keyguard.feature.filter.util

import com.artemchep.keyguard.common.model.DCipherFilter
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.ui.FlatItemAction

context(RememberStateFlowScope)
actual fun CipherFilterUtil.addShortcutActionOrNull(
    filter: DCipherFilter,
): FlatItemAction? = null
