package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.webdav.KtorWebDavClientFactory
import com.artemchep.keyguard.common.service.webdav.WebDavClientFactory
import com.artemchep.keyguard.common.service.webdav.toWebDavAuthorization
import com.artemchep.keyguard.common.usecase.ListWebDavDirectory
import com.artemchep.keyguard.util.webdav.WebDavClientConfig
import io.ktor.client.HttpClient
import org.kodein.di.DirectDI
import org.kodein.di.instance

class ListWebDavDirectoryImpl internal constructor(
    private val clientFactory: WebDavClientFactory,
) : ListWebDavDirectory {
    constructor(
        httpClient: HttpClient,
    ) : this(
        clientFactory = KtorWebDavClientFactory(httpClient),
    )

    constructor(
        directDI: DirectDI,
    ) : this(
        httpClient = directDI.instance(),
    )

    override fun invoke(
        request: ListWebDavDirectory.Request,
    ): IO<List<ListWebDavDirectory.Child>> = ioEffect {
        val client = clientFactory.create(
            WebDavClientConfig(
                baseUrl = request.rootUrl,
                authorization = request.credentials?.toWebDavAuthorization(),
                noCache = true,
            ),
        )
        try {
            client.listChildren(request.path)
                .map { resource ->
                    ListWebDavDirectory.Child(
                        path = resource.path,
                        name = resource.name,
                        isCollection = resource.isCollection,
                        size = resource.size,
                        lastModified = resource.lastModified,
                    )
                }
        } finally {
            client.close()
        }
    }
}
