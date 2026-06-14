package com.artemchep.keyguard.common.service.hibp

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachGroup
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachResponse

interface HibpRepository {
    fun getBreaches(): IO<HibpBreachGroup>

    fun getBreachedAccount(
        username: String,
        apiToken: String,
    ): IO<List<HibpBreachResponse>>

    fun getSubscriptionStatus(
        apiToken: String,
    ): IO<Unit>

    fun getPwnedPasswordOccurrences(
        passwordSha1Hash: String,
    ): IO<Int>
}
