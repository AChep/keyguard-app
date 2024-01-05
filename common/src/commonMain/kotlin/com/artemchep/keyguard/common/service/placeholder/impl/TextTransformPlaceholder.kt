package com.artemchep.keyguard.common.service.placeholder.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.service.placeholder.Placeholder
import com.artemchep.keyguard.common.service.placeholder.util.Parser
import io.ktor.util.*
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

class TextTransformPlaceholder(
) : Placeholder {
    private val parser = Parser(
        name = "t-conv",
        count = 2,
    )

    override fun get(
        key: String,
    ): IO<String?>? {
        val params = parser.parse(key)
            ?: return null
        val command = params.params.firstOrNull()
        val value = when {
            // Lower-case.
            command.equals("l", ignoreCase = true) ||
                    command.equals("lower", ignoreCase = true)
            -> transformLowercase(params.value)

            // Upper-case.
            command.equals("u", ignoreCase = true) ||
                    command.equals("upper", ignoreCase = true)
            -> transformUppercase(params.value)

            // The Base64 encoding of the UTF-8 representation of the text.
            command.equals("base64", ignoreCase = true) -> transformBase64(params.value)

            // The Hex encoding of the UTF-8 representation of the text.
            command.equals("hex", ignoreCase = true) -> transformHex(params.value)

            // The URI-escaped representation of the text.
            command.equals("uri", ignoreCase = true) -> transformUriEncode(params.value)

            // The URI-unescaped representation of the text.
            command.equals("uri-dec", ignoreCase = true) -> transformUriDecode(params.value)

            else -> null
        }
        return value.let(::io)
    }

    private fun transformLowercase(
        value: String,
    ): String = value.lowercase(Locale.ENGLISH)

    private fun transformUppercase(
        value: String,
    ): String = value.uppercase(Locale.ENGLISH)

    private fun transformBase64(
        value: String,
    ): String {
        val bytes = value.toByteArray()
        return java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)
    }

    private fun transformHex(
        value: String,
    ): String {
        val bytes = value.toByteArray()
        return hex(bytes)
    }

    private fun transformUriEncode(
        value: String,
    ): String {
        return URLEncoder.encode(value, "UTF-8")
    }

    private fun transformUriDecode(
        value: String,
    ): String {
        return URLDecoder.decode(value, "UTF-8")
    }
}
