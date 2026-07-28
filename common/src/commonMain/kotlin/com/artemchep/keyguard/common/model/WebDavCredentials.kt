package com.artemchep.keyguard.common.model

data class WebDavCredentials(
    val username: String,
    val password: Password? = null,
) {
    init {
        require(username.isNotBlank()) {
            "WebDAV username must not be blank."
        }
    }

    companion object {
        fun of(
            username: String?,
            password: String?,
        ) = of(
            username = username,
            password = password
                ?.takeIf { it.isNotEmpty() }
                ?.let(::Password),
        )

        fun of(
            username: String?,
            password: Password?,
        ): WebDavCredentials? {
            val trimmedUsername = username
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            return WebDavCredentials(
                username = trimmedUsername,
                password = password
                    ?.takeIf { it.value.isNotEmpty() },
            )
        }
    }
}