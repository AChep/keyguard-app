package com.artemchep.keyguard.common.service.placeholder.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.service.placeholder.Placeholder
import com.artemchep.keyguard.common.service.placeholder.PlaceholderScope
import io.ktor.http.*
import org.kodein.di.DirectDI

class UrlPlaceholder(
    private val url: String,
) : Placeholder {
    private val uuu = Url(url)

    override fun get(
        key: String,
    ): IO<String?>? = when {
        key.equals("url", ignoreCase = true) ||
                key.equals("base", ignoreCase = true) ->
            url.let(::io)

        key.equals("url:rmvscm", ignoreCase = true) ||
                key.equals("base:rmvscm", ignoreCase = true) -> {
            // Cut out the scheme from the provided URL.
            val regex = "^.*://".toRegex()
            url.replace(regex, "").let(::io)
        }

        key.equals("url:scm", ignoreCase = true) ||
                key.equals("base:scm", ignoreCase = true) -> {
            uuu.protocol.name
                .let(::io)
        }

        key.equals("url:host", ignoreCase = true) ||
                key.equals("base:host", ignoreCase = true) -> {
            uuu.host
                .let(::io)
        }

        key.equals("url:port", ignoreCase = true) ||
                key.equals("base:port", ignoreCase = true) -> {
            uuu.port
                .toString()
                .let(::io)
        }

        key.equals("url:path", ignoreCase = true) ||
                key.equals("base:path", ignoreCase = true) -> {
            uuu.encodedPath
                .let(::io)
        }

        key.equals("url:query", ignoreCase = true) ||
                key.equals("base:query", ignoreCase = true) -> {
            uuu.encodedQuery
                .let(::io)
        }

        key.startsWith("url:parameter:", ignoreCase = true) -> {
            val name = key.substringAfter("url:parameter:")
            uuu.parameters[name]
                .let(::io)
        }

        key.startsWith("base:parameter:", ignoreCase = true) -> {
            val name = key.substringAfter("base:parameter:")
            uuu.parameters[name]
                .let(::io)
        }

        key.equals("url:userinfo", ignoreCase = true) ||
                key.equals("base:userinfo", ignoreCase = true) -> {
            val user = uuu.user.orEmpty()
            val password = uuu.password.orEmpty()
            "$user:$password"
                .let(::io)
        }

        key.equals("url:username", ignoreCase = true) ||
                key.equals("base:username", ignoreCase = true) -> {
            uuu.user
                .let(::io)
        }

        key.equals("url:password", ignoreCase = true) ||
                key.equals("base:password", ignoreCase = true) -> {
            uuu.password
                .let(::io)
        }
        // unknown
        else -> null
    }

    class Factory(
    ) : Placeholder.Factory {
        constructor(
            directDI: DirectDI,
        ) : this(
        )

        override fun createOrNull(
            scope: PlaceholderScope,
        ) = scope.url?.let(::UrlPlaceholder)
    }
}
