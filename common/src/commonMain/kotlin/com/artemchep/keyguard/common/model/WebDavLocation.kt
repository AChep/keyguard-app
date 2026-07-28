package com.artemchep.keyguard.common.model

sealed interface WebDavLocation {
    val url: String
    val credentials: WebDavCredentials?

    data class Collection(
        override val url: String,
        override val credentials: WebDavCredentials? = null,
    ) : WebDavLocation {
        init {
            require(url.isNotBlank()) {
                "WebDAV collection URL must not be blank."
            }
        }
    }

    data class File(
        override val url: String,
        override val credentials: WebDavCredentials? = null,
    ) : WebDavLocation {
        init {
            require(url.isNotBlank()) {
                "WebDAV file URL must not be blank."
            }
            require(!url.substringBefore('#').substringBefore('?').endsWith('/')) {
                "WebDAV file URL must point to a file."
            }
        }
    }
}
