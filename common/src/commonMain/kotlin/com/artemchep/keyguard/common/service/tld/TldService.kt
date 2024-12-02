package com.artemchep.keyguard.common.service.tld

import com.artemchep.keyguard.common.io.IO

interface TldService {
    val version: String

    fun getDomainName(host: String): IO<String>
}
