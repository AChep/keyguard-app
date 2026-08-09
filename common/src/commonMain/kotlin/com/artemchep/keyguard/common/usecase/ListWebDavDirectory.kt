package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.WebDavCredentials
import kotlin.time.Instant

interface ListWebDavDirectory {
    operator fun invoke(
        request: Request,
    ): IO<List<Child>>

    data class Request(
        val rootUrl: String,
        val path: String,
        val credentials: WebDavCredentials?,
    )

    data class Child(
        val path: String,
        val name: String,
        val isCollection: Boolean,
        val size: Long?,
        val lastModified: Instant?,
    )
}
