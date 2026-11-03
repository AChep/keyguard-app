package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.icon.IconResolver
import com.artemchep.keyguard.common.service.icon.readIconResponse
import com.artemchep.keyguard.common.usecase.GetIconImage
import com.artemchep.keyguard.feature.favicon.IconRequest
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers

class GetIconImageImpl(
    private val iconResolver: IconResolver,
) : GetIconImage {
    override fun invoke(
        request: IconRequest,
    ): IO<ByteArray?> = ioEffect(Dispatchers.Default) {
        val icon = iconResolver.resolve(request)
            ?: return@ioEffect null
        icon
            .request { httpClient, url ->
                httpClient.readIconResponse(
                    url = Url(url),
                    maxBytes = MAX_ICON_BYTES,
                )
            }
            .bytes
    }

    companion object {
        const val MAX_ICON_BYTES = 1024L * 1024L
    }
}
