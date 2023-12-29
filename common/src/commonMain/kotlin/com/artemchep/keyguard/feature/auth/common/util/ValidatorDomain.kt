package com.artemchep.keyguard.feature.auth.common.util

import arrow.core.Either
import io.ktor.http.Url

val REGEX_DOMAIN =
    "^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9][a-z0-9-]{0,61}[a-z0-9]\$".toRegex()

val REGEX_IPV4 = "^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\\.?\\b){4}\$".toRegex()

private val privateIpAddressList = listOf(
    IpRange(
        from = intArrayOf(10, 0, 0, 0),
        to = intArrayOf(10, 255, 255, 255),
    ),
    IpRange(
        from = intArrayOf(172, 16, 0, 0),
        to = intArrayOf(172, 31, 255, 255),
    ),
    IpRange(
        from = intArrayOf(192, 168, 0, 0),
        to = intArrayOf(192, 168, 255, 255),
    ),
)

private data class IpRange(
    val from: IntArray,
    val to: IntArray,
) {
    init {
        require(from.size == 4)
        require(to.size == 4)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IpRange

        if (!from.contentEquals(other.from)) return false
        if (!to.contentEquals(other.to)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = from.contentHashCode()
        result = 31 * result + to.contentHashCode()
        return result
    }

    fun matches(ip: IntArray): Boolean {
        if (ip.size != 4) return false
        ip.forEachIndexed { index, block ->
            val a = from[index]
            val b = to[index]
            if (block < a || block > b) return false
        }
        return true
    }
}

fun verifyIsLocalUrl(url: String) = Either.catch {
    verifyIsLocalUrl(Url(url))
}

fun verifyIsLocalUrl(url: Url) = run {
    val host = url.host
    when {
        host == "localhost" -> true
        REGEX_IPV4.matches(host) -> {
            val ip = host.split(".")
                .map { it.toInt() }
                .toIntArray()
            privateIpAddressList.any { it.matches(ip) }
        }

        else -> false
    }
}
