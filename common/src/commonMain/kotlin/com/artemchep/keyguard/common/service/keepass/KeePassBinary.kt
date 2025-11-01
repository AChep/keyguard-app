package com.artemchep.keyguard.common.service.keepass

import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base32Service
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SCHEME = "hashref://"

suspend fun KeePassUtil.generateAttachmentUrl(
    data: ByteArray,
    cryptoGenerator: CryptoGenerator,
    base32Service: Base32Service,
): String = withContext(Dispatchers.Default) {
    val hash = kotlin.run {
        val hashData = cryptoGenerator
            .hashSha256(data)
        base32Service.encodeToString(hashData)
            .replace("=", "") // no padding
    }
    "$SCHEME$hash"
}

suspend fun KeePassUtil.parseAttachmentUrl(
    url: String,
    base32Service: Base32Service,
): ByteArray = withContext(Dispatchers.Default) {
    when {
        url.startsWith(SCHEME, ignoreCase = true) -> {
            val hashDataStr = url.substring(SCHEME.length)
            val hashData = base32Service
                .decode(hashDataStr)
            return@withContext hashData
        }
    }

    val msg = "Could not obtain attachment info from the url! Expected " +
            "'$SCHEME' scheme, got '$url' instead!"
    throw UnsupportedOperationException(msg)
}
