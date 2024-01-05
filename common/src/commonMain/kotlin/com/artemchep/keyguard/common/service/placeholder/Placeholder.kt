package com.artemchep.keyguard.common.service.placeholder

import arrow.core.Option
import com.artemchep.keyguard.common.io.IO

// See:
// https://keepass.info/help/base/placeholders.html
interface Placeholder {
    operator fun get(key: String): IO<String?>?

    interface Factory {

    }
}
