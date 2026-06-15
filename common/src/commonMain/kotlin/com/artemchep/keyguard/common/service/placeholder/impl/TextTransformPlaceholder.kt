package com.artemchep.keyguard.common.service.placeholder.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.service.placeholder.Placeholder
import com.artemchep.keyguard.common.service.placeholder.PlaceholderScope
import com.artemchep.keyguard.common.service.placeholder.util.Parser
import com.artemchep.keyguard.common.util.toHex
import io.ktor.http.URLDecodeException
import io.ktor.http.decodeURLQueryComponent
import org.kodein.di.DirectDI
import kotlin.io.encoding.Base64

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
    ): String = value.lowercase()

    private fun transformUppercase(
        value: String,
    ): String = value.uppercase()

    private fun transformBase64(
        value: String,
    ): String = Base64.UrlSafe
        .withPadding(Base64.PaddingOption.ABSENT)
        .encode(value.encodeToByteArray())

    private fun transformHex(
        value: String,
    ): String {
        val bytes = value.encodeToByteArray()
        return bytes.toHex()
    }

    private fun transformUriEncode(
        value: String,
    ): String = urlEncodeUtf8(value)

    private fun transformUriDecode(
        value: String,
    ): String = try {
        value.decodeURLQueryComponent(plusIsSpace = true)
    } catch (e: URLDecodeException) {
        throw IllegalArgumentException(e.message, e)
    }

    class Factory(
    ) : Placeholder.Factory {
        constructor(
            directDI: DirectDI,
        ) : this(
        )

        override fun createOrNull(
            scope: PlaceholderScope,
        ) = TextTransformPlaceholder()
    }
}

private fun urlEncodeUtf8(value: String): String = buildString {
    // Match java.net.URLEncoder UTF-8 form semantics.
    // Ktor's public encoders differ for '*' and '~'.
    value.encodeToByteArray().forEach { byte ->
        val int = byte.toInt() and 0xff
        val char = int.toChar()
        when {
            int in 'A'.code..'Z'.code ||
                    int in 'a'.code..'z'.code ||
                    int in '0'.code..'9'.code ||
                    char == '-' ||
                    char == '_' ||
                    char == '.' ||
                    char == '*' -> append(char)
            char == ' ' -> append('+')
            else -> {
                append('%')
                append(int.toString(radix = 16).padStart(2, '0').uppercase())
            }
        }
    }
}
