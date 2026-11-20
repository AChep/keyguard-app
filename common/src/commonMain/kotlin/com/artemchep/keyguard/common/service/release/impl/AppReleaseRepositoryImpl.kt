package com.artemchep.keyguard.common.service.release.impl

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AppRelease
import com.artemchep.keyguard.common.service.release.AppReleaseRepository
import com.artemchep.keyguard.common.service.release.parseGitHubReleases
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.serialization.json.Json

/**
 * Lists the releases of the app on GitHub.
 */
class AppReleaseRepositoryImpl(
    private val httpClient: HttpClient,
    private val json: Json,
) : AppReleaseRepository {
    companion object {
        // The newest release may still be inside the delay, so
        // list several instead of asking for the latest one.
        private const val URL_RELEASES =
            "https://api.github.com/repos/AChep/keyguard-app/releases?per_page=30"
    }

    override fun getReleases(): IO<List<AppRelease>> = ioEffect(Dispatchers.IO) {
        val response = httpClient.get(URL_RELEASES) {
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        if (!response.status.isSuccess()) {
            throw HttpException(
                statusCode = response.status,
                response.status.description,
                null,
            )
        }

        val text = response.bodyAsText()
        parseGitHubReleases(
            json = json,
            text = text,
        )
    }
}
