package com.artemchep.keyguard.util.dns

import com.artemchep.keyguard.util.dns.internal.RR_TYPE_TXT
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.invoke
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import platform.darwin.DNSServiceErrorType
import platform.darwin.DNSServiceFlags
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.kDNSServiceErr_NoError
import platform.darwin.kDNSServiceErr_NoSuchName
import platform.darwin.kDNSServiceErr_NoSuchRecord
import platform.darwin.kDNSServiceErr_Timeout
import platform.darwin.kDNSServiceFlagsAdd
import platform.darwin.kDNSServiceFlagsMoreComing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DnsSdTxtResultTest {
    @Test
    fun `malformed rdata fails the awaiter without escaping the native callback`() = runTest {
        val result = DnsSdTxtResult()

        dispatchReplies(
            result,
            Reply(data = byteArrayOf(9, 97)),
            Reply(),
        )

        assertFailsWith<DnsFormatException> { result.await() }
    }

    @Test
    fun `missing rdata fails the awaiter`() = runTest {
        val result = DnsSdTxtResult()

        dispatchReplies(result, Reply(data = null, rdLength = 2u))

        assertFailsWith<DnsFormatException> { result.await() }
    }

    @Test
    fun `negative answers ignore undefined flags and payload`() = runTest {
        listOf(kDNSServiceErr_NoSuchName, kDNSServiceErr_NoSuchRecord).forEach { errorCode ->
            val result = DnsSdTxtResult()

            dispatchReplies(
                result,
                Reply(
                    errorCode = errorCode,
                    flags = kDNSServiceFlagsMoreComing.convert(),
                    data = null,
                    rdLength = 2u,
                ),
            )

            assertEquals(emptyList(), result.await())
        }
    }

    @Test
    fun `resolver errors ignore undefined flags and payload`() = runTest {
        val result = DnsSdTxtResult()

        dispatchReplies(
            result,
            Reply(
                errorCode = kDNSServiceErr_Timeout,
                flags = kDNSServiceFlagsMoreComing.convert(),
                data = null,
                rdLength = 2u,
            ),
        )

        val error = assertFailsWith<DnsLookupException> { result.await() }
        assertEquals("DNS lookup failed, error $kDNSServiceErr_Timeout.", error.message)
    }

    @Test
    fun `collects txt records and their chunks after an intermediate cname`() = runTest {
        val result = DnsSdTxtResult()

        dispatchReplies(
            result,
            Reply(rrType = 5u, data = byteArrayOf(0)),
            Reply(
                flags = (kDNSServiceFlagsAdd or kDNSServiceFlagsMoreComing).convert(),
                data = byteArrayOf(1, 97, 1, 98),
            ),
            Reply(),
        )

        assertEquals(listOf("ab", "x"), result.await())
    }

    @Test
    fun `completed results ignore subsequent malformed replies`() = runTest {
        val result = DnsSdTxtResult()

        dispatchReplies(result, Reply(), Reply(data = byteArrayOf(9, 97)))

        assertEquals(listOf("x"), result.await())
    }

    @Test
    fun `canceled results ignore subsequent malformed replies`() = runTest {
        val result = DnsSdTxtResult()
        result.cancel()

        dispatchReplies(result, Reply(data = byteArrayOf(9, 97)), Reply())

        assertFailsWith<CancellationException> { result.await() }
    }

    @Test
    fun `empty rdata accepts a null pointer`() = runTest {
        val result = DnsSdTxtResult()

        dispatchReplies(result, Reply(data = null))

        assertEquals(listOf(""), result.await())
    }
}

private class Reply(
    val errorCode: DNSServiceErrorType = kDNSServiceErr_NoError,
    val flags: DNSServiceFlags = kDNSServiceFlagsAdd.convert(),
    val rrType: UShort = RR_TYPE_TXT.convert(),
    val data: ByteArray? = byteArrayOf(1, 120),
    val rdLength: UShort = (data?.size ?: 0).convert(),
) {
    fun deliver(context: COpaquePointer) {
        val callback = staticCFunction(::onDnsSdReply)
        fun invoke(data: COpaquePointer?) {
            callback(null, flags, 0u, errorCode, null, rrType, 1u, rdLength, data, 0u, context)
        }
        if (data == null || data.isEmpty()) {
            invoke(null)
        } else {
            data.usePinned { invoke(it.addressOf(0)) }
        }
    }
}

private suspend fun dispatchReplies(result: DnsSdTxtResult, vararg replies: Reply) {
    val finished = CompletableDeferred<Unit>()
    val stableRef = StableRef.create(result)
    val queue = dispatch_queue_create("com.artemchep.keyguard.util.dns.test", null)
    dispatch_async(queue) {
        try {
            // Exercise the production C callback on its native queue. Catching here
            // would hide the process-terminating regression this test must detect.
            replies.forEach { it.deliver(stableRef.asCPointer()) }
        } finally {
            stableRef.dispose()
            finished.complete(Unit)
        }
    }
    finished.await()
}
