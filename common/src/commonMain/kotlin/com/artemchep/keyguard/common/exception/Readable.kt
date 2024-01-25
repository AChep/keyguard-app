package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.feature.localization.TextHolder

interface Readable {
    val title: TextHolder
    val text: TextHolder? get() = null
}
