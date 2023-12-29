package com.artemchep.keyguard.common.service.id

import com.artemchep.keyguard.common.io.IO

interface IdRepository {
    fun put(id: String): IO<Unit>

    fun get(): IO<String>
}
