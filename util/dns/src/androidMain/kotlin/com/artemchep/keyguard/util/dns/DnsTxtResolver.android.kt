package com.artemchep.keyguard.util.dns

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import com.artemchep.keyguard.util.dns.internal.DnsMessageParser
import com.artemchep.keyguard.util.dns.internal.RR_TYPE_TXT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException

private const val RCODE_NO_ERROR = 0
private const val RCODE_NAME_ERROR = 3

actual fun createDnsTxtResolver(): DnsTxtResolver = AndroidDnsTxtResolver()

/**
 * Queries through [DnsResolver] on the default network, so Private DNS and
 * the VPN's resolver are honoured. The API exists from Android 10; older
 * releases have no public API for TXT records.
 */
internal class AndroidDnsTxtResolver : DnsTxtResolver {
    override suspend fun lookupTxt(name: String): List<String> {
        val normalized = requireValidDnsName(name)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw UnsupportedDnsLookupException()
        }
        return queryTxt(normalized)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun queryTxt(name: String): List<String> = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        val callback = object : DnsResolver.Callback<ByteArray> {
            override fun onAnswer(answer: ByteArray, rcode: Int) {
                if (continuation.isActive) {
                    continuation.resumeWith(runCatching { parseAnswer(answer, rcode) })
                }
            }

            override fun onError(error: DnsResolver.DnsException) {
                if (continuation.isActive) {
                    val exception = DnsLookupException("DNS lookup failed with code ${error.code}.", error)
                    continuation.resumeWithException(exception)
                }
            }
        }
        DnsResolver.getInstance().rawQuery(
            null,
            name,
            DnsResolver.CLASS_IN,
            RR_TYPE_TXT,
            DnsResolver.FLAG_EMPTY,
            Dispatchers.IO.asExecutor(),
            signal,
            callback,
        )
    }
}

private fun parseAnswer(
    answer: ByteArray,
    rcode: Int,
): List<String> = when (rcode) {
    RCODE_NO_ERROR -> DnsMessageParser.parseTxtMessage(answer)
    RCODE_NAME_ERROR -> emptyList()
    else -> throw DnsResponseCodeException(rcode)
}
