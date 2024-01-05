package com.artemchep.keyguard.common.service.placeholder.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.placeholder.Placeholder
import com.artemchep.keyguard.common.usecase.GetTotpCode

class CipherPlaceholder(
//    private val getTotpCode: GetTotpCode,
    private val cipher: DSecret,
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
//        key.equals("otp", ignoreCase = true) -> run {
//            val token = cipher.login?.totp?.token
//                ?: return@run null.let(::io)
//            getTotpCode(token)
//                .toIO()
//                .map { code -> code.code }
//        }
        key.equals("notes", ignoreCase = true) ->
            cipher.notes.let(::io)
        // extras
        key.equals("favorite", ignoreCase = true) ->
            cipher.favorite.toString().let(::io)
        // unknown
        else -> null
    }

    class Factory {

    }
}
