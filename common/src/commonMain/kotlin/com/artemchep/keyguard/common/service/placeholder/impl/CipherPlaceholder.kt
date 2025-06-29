package com.artemchep.keyguard.common.service.placeholder.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.placeholder.Placeholder
import com.artemchep.keyguard.common.service.placeholder.PlaceholderScope
import com.artemchep.keyguard.common.service.totp.TotpService
import kotlinx.coroutines.Dispatchers
import kotlin.time.Instant
import org.kodein.di.DirectDI
import org.kodein.di.instance

class CipherPlaceholder(
    private val totpService: TotpService,
    private val cipher: DSecret,
    private val now: Instant,
) : Placeholder {
    override fun get(
        key: String,
    ): IO<String?>? = when {
        key.equals("uuid", ignoreCase = true) ->
            cipher.service.remote?.id.let(::io)

        key.equals("title", ignoreCase = true) ->
            cipher.name.let(::io)

        key.equals("username", ignoreCase = true) ->
            cipher.login?.username.let(::io)

        key.equals("password", ignoreCase = true) ->
            cipher.login?.password.let(::io)

        key.equals("otp", ignoreCase = true) -> run {
            val token = cipher.login?.totp?.token
                ?: return@run null.let(::io)
            ioEffect(Dispatchers.Default) {
                totpService
                    .generate(
                        token = token,
                        timestamp = now,
                    )
                    .code
            }
        }

        key.equals("notes", ignoreCase = true) ->
            cipher.notes.let(::io)
        // extras
        key.equals("favorite", ignoreCase = true) ->
            cipher.favorite.toString().let(::io)
        // unknown
        else -> null
    }

    class Factory(
        private val totpService: TotpService,
    ) : Placeholder.Factory {
        constructor(
            directDI: DirectDI,
        ) : this(
            totpService = directDI.instance(),
        )

        override fun createOrNull(
            scope: PlaceholderScope,
        ) = CipherPlaceholder(
            totpService = totpService,
            cipher = scope.cipher,
            now = scope.now,
        )
    }
}
