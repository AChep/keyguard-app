package com.artemchep.keyguard.common.service.relays.api

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.GeneratorContext
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf

interface EmailRelay {
    val type: String

    /**
     * Human-readable name of the email relay, will
     * be shown directly to a user.
     */
    val name: String

    val docUrl: String?
        get() = null

    val schema: ImmutableMap<String, EmailRelaySchema>
        get() = persistentMapOf()

    fun generate(
        context: GeneratorContext,
        config: ImmutableMap<String, String>,
    ): IO<String>
}
