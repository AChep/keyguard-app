package com.artemchep.keyguard.util.dns

import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import platform.darwin.DNSServiceQueryRecord
import platform.darwin.DNSServiceRef
import platform.darwin.DNSServiceRefDeallocate
import platform.darwin.DNSServiceRefVar
import platform.darwin.DNSServiceSetDispatchQueue
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.kDNSServiceClass_IN
import platform.darwin.kDNSServiceErr_NoError
import platform.darwin.kDNSServiceFlagsReturnIntermediates
import platform.darwin.kDNSServiceFlagsTimeout
import platform.darwin.kDNSServiceType_TXT

private const val DNS_SD_QUEUE_LABEL = "com.artemchep.keyguard.util.dns"

actual fun createDnsTxtResolver(): DnsTxtResolver = DnsSdTxtResolver()

/**
 * Queries through `dns_sd`, the system resolver. Unlike `libresolv` it does
 * not talk to the nameserver directly, so it needs no Local Network permission
 * and follows the VPN and encrypted-DNS configuration.
 */
internal class DnsSdTxtResolver : DnsTxtResolver {
    override suspend fun lookupTxt(name: String): List<String> {
        val fqdn = requireValidDnsName(name) + "."
        return DnsSdTxtQuery(fqdn).execute()
    }
}

private class DnsSdTxtQuery(
    private val fqdn: String,
) {
    private val queue = dispatch_queue_create(DNS_SD_QUEUE_LABEL, null)
    private val result = DnsSdTxtResult()

    suspend fun execute(): List<String> {
        val stableRef = StableRef.create(result)
        val serviceRef = start(stableRef)
        return try {
            result.await()
        } finally {
            result.cancel()
            // Replies run on the queue, so releasing there guarantees that no
            // reply can observe a released reference.
            dispatch_async(queue) {
                DNSServiceRefDeallocate(serviceRef)
                stableRef.dispose()
            }
        }
    }

    private fun start(
        stableRef: StableRef<DnsSdTxtResult>,
    ): DNSServiceRef = memScoped {
        val ref = alloc<DNSServiceRefVar>()
        // Without ReturnIntermediates a negative answer never calls back.
        val flags = kDNSServiceFlagsReturnIntermediates or kDNSServiceFlagsTimeout
        val startError = DNSServiceQueryRecord(
            ref.ptr,
            flags.convert(),
            0u,
            fqdn,
            kDNSServiceType_TXT.convert(),
            kDNSServiceClass_IN.convert(),
            staticCFunction(::onDnsSdReply),
            stableRef.asCPointer(),
        )
        val serviceRef = ref.value
        if (startError != kDNSServiceErr_NoError || serviceRef == null) {
            stableRef.dispose()
            throw DnsLookupException("DNS query could not be started, error $startError.")
        }
        val queueError = DNSServiceSetDispatchQueue(serviceRef, queue)
        if (queueError != kDNSServiceErr_NoError) {
            DNSServiceRefDeallocate(serviceRef)
            stableRef.dispose()
            throw DnsLookupException("DNS query could not be scheduled, error $queueError.")
        }
        serviceRef
    }
}
