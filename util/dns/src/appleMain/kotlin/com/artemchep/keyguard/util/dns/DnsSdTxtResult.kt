@file:OptIn(ExperimentalForeignApi::class)

package com.artemchep.keyguard.util.dns

import com.artemchep.keyguard.util.dns.internal.DnsMessageParser
import com.artemchep.keyguard.util.dns.internal.RR_TYPE_TXT
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import platform.darwin.DNSServiceErrorType
import platform.darwin.DNSServiceFlags
import platform.darwin.DNSServiceRef
import platform.darwin.kDNSServiceErr_NoError
import platform.darwin.kDNSServiceErr_NoSuchName
import platform.darwin.kDNSServiceErr_NoSuchRecord
import platform.darwin.kDNSServiceFlagsAdd
import platform.darwin.kDNSServiceFlagsMoreComing

/** Accumulates replies on the query's serial dispatch queue. */
internal class DnsSdTxtResult {
    private val result = CompletableDeferred<List<String>>()
    private val records = mutableListOf<String>()

    suspend fun await(): List<String> = result.await()

    fun cancel() {
        result.cancel()
    }

    @Suppress("TooGenericExceptionCaught") // No Kotlin exception may escape the native callback.
    fun onReply(
        flags: DNSServiceFlags,
        errorCode: DNSServiceErrorType,
        rrType: Int,
        rdLength: Int,
        rdata: COpaquePointer?,
    ) {
        if (result.isCompleted) return
        try {
            // All reply fields other than the context are undefined on errors.
            when (errorCode) {
                kDNSServiceErr_NoError -> readRecord(flags, rrType, rdLength, rdata)
                kDNSServiceErr_NoSuchRecord,
                kDNSServiceErr_NoSuchName,
                -> result.complete(records.toList())

                else -> result.completeExceptionally(
                    DnsLookupException("DNS lookup failed, error $errorCode."),
                )
            }
        } catch (error: Throwable) {
            val failure = when (error) {
                is DnsLookupException, is CancellationException -> error
                else -> DnsLookupException("DNS reply could not be processed.", error)
            }
            result.completeExceptionally(failure)
        }
    }

    private fun readRecord(
        flags: DNSServiceFlags,
        rrType: Int,
        rdLength: Int,
        rdata: COpaquePointer?,
    ) {
        // Intermediate CNAME records may arrive in their own callback.
        if (rrType != RR_TYPE_TXT) return
        val added = (flags and kDNSServiceFlagsAdd.convert<DNSServiceFlags>()) != 0u
        if (added) {
            if (rdata == null && rdLength != 0) {
                throw DnsFormatException("DNS TXT record has no RDATA.")
            }
            val bytes = rdata?.readBytes(rdLength) ?: ByteArray(0)
            records += DnsMessageParser.parseTxtRdata(bytes)
        }
        val moreComing = (flags and kDNSServiceFlagsMoreComing.convert<DNSServiceFlags>()) != 0u
        if (!moreComing) {
            result.complete(records.toList())
        }
    }
}

// The parameter list is dictated by the DNSServiceQueryRecordReply C signature.
@Suppress("UnusedParameter", "LongParameterList")
internal fun onDnsSdReply(
    sdRef: DNSServiceRef?,
    flags: DNSServiceFlags,
    interfaceIndex: UInt,
    errorCode: DNSServiceErrorType,
    fullname: CPointer<ByteVar>?,
    rrType: UShort,
    rrClass: UShort,
    rdLength: UShort,
    rdata: COpaquePointer?,
    ttl: UInt,
    context: COpaquePointer?,
) {
    val result = context?.asStableRef<DnsSdTxtResult>()?.get() ?: return
    result.onReply(flags, errorCode, rrType.toInt(), rdLength.toInt(), rdata)
}
