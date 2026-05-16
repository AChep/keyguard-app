package com.artemchep.keyguard.common.service.hibp.passwords.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.PasswordPwnage
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.hibp.passwords.PasswordPwnageDataSourceRemote
import com.artemchep.keyguard.common.util.toHex
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.userAgent
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class PasswordPwnageDataSourceRemoteImpl(
    private val cryptoGenerator: CryptoGenerator,
    private val httpClient: HttpClient,
) : PasswordPwnageDataSourceRemote {
    constructor(directDI: DirectDI) : this(
        cryptoGenerator = directDI.instance(),
        httpClient = directDI.instance(),
    )

    override fun check(
        password: String,
    ): IO<PasswordPwnage> = ioEffect(Dispatchers.Default) {
        val hash = cryptoGenerator.hashSha1(password.encodeToByteArray())
            .toHex()
            .uppercase()
        hash
    }
        .flatMap(::pwnedPasswordsRequestIo)
        .map { occurrences ->
            PasswordPwnage(
                occurrences = occurrences,
            )
        }

    private fun pwnedPasswordsRequestIo(
        passwordSha1Hash: String,
    ): IO<Int> = ioEffect(Dispatchers.IO) {
        val prefix = passwordSha1Hash.take(5)
        val suffix = passwordSha1Hash.drop(5)

        // https://haveibeenpwned.com/API/v3
        val url = "https://api.pwnedpasswords.com/range/$prefix"
        val response = httpClient
            .get(url) {
                // https://haveibeenpwned.com/API/v3#UserAgent
                userAgent("Keyguard")
            }
        val channel = response
            .bodyAsChannel()
        try {
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line()
                if (line != null && line.startsWith(suffix)) {
                    val count = line
                        .substringAfter(':')
                        .toInt()
                    return@ioEffect count
                }
            }
        } finally {
            if (!channel.isClosedForRead)
                channel.cancel()
        }

        return@ioEffect 0
    }
}
