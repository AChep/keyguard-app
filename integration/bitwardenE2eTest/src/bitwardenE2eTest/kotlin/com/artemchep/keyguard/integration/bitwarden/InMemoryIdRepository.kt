package com.artemchep.keyguard.integration.bitwarden

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.id.IdRepository

internal class InMemoryIdRepository : IdRepository {
    @Volatile
    private var id = ""

    override fun put(id: String): IO<Unit> = ioEffect {
        this.id = id
    }

    override fun get(): IO<String> = ioEffect {
        id
    }
}
