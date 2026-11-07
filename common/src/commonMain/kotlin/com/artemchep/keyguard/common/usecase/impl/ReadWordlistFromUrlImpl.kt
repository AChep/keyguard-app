package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.exception.WordlistWebPageException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.usecase.ReadWordlistFromUrl
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

class ReadWordlistFromUrlImpl(
    private val httpClient: HttpClient,
) : ReadWordlistFromUrl {
    override fun invoke(
        url: String,
    ): IO<List<String>> = ioEffect {
        val response = httpClient
            .get(url)
        if (!response.status.isSuccess()) {
            throw HttpException(
                statusCode = response.status,
                m = response.status.description,
                e = null,
            )
        }
        // A web page is never a wordlist; this usually means the user
        // pasted a link to a file's view page instead of the raw file.
        val isWebPage = response.contentType()
            ?.match(ContentType.Text.Html) == true
        if (isWebPage) {
            throw WordlistWebPageException()
        }
        val content = response
            .bodyAsText()
        with(content) {
            ReadWordlistUtil.parseAsWordlist()
        }
    }
}
