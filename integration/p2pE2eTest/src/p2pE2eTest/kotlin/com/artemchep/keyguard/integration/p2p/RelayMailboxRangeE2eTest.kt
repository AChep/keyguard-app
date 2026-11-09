package com.artemchep.keyguard.integration.p2p

import com.artemchep.keyguard.common.service.http.KeyguardHttpUserAgent
import com.artemchep.keyguard.common.service.http.configureKeyguardHttpClient
import com.artemchep.keyguard.common.service.serialization.createApplicationJson
import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxKind
import com.artemchep.keyguard.provider.keyguard.transport.KtorKeyguardRelayConnector
import com.artemchep.keyguard.provider.keyguard.transport.KtorKeyguardRelayMailboxes
import com.artemchep.keyguard.provider.keyguard.transport.RelayEndpoint
import com.artemchep.keyguard.provider.keyguard.transport.RelayMailboxClient
import com.artemchep.keyguard.provider.keyguard.transport.RelayMailboxDownload
import com.artemchep.keyguard.provider.keyguard.transport.RelayMailboxRef
import com.artemchep.keyguard.provider.keyguard.transport.RelayMailboxResult
import com.artemchep.keyguard.provider.keyguard.transport.RelayRoom
import com.artemchep.keyguard.provider.keyguard.transport.RelayState
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * The relay sends only the rest of an item to a GET that resumes a download with `Range`
 * and `If-Range`, see [RelayMailboxDownload]: the contract between the app and the relay
 * of `server/relay`.
 */
class RelayMailboxRangeE2eTest {
    @Test
    fun `a resumed get receives the rest of the item from the relay`() =
        p2pScenario(storage = RelayServer.Storage()) { testbed ->
            val relay = testbed.relay
            val endpoint = RelayEndpoint(relay.url, token = relay.token)
            val http = HttpClient(OkHttp) {
                configureKeyguardHttpClient(createApplicationJson(), KeyguardHttpUserAgent.Curl)
            }
            // The relay serves items only to a joined connection, by its ticket.
            val room = RelayRoom.forInvite(NativeCryptoGenerator().seed(length = 32), NativeCryptoGenerator())
            val connection = KtorKeyguardRelayConnector(http).open(endpoint, room)
            val joined = launch { connection.run() }
            try {
                val ticket = withTimeout(30.seconds) {
                    connection.client.state
                        .filterIsInstance<RelayState.Joined>()
                        .first { it.ticket != null }
                        .ticket
                }
                val ref = RelayMailboxRef(checkNotNull(ticket), MailboxKind.BLOB, item = hex(32))
                val mailbox = KtorKeyguardRelayMailboxes(http).get(endpoint)
                val body = Random.nextBytes(ITEM_SIZE)
                assertIs<RelayMailboxResult.Done>(mailbox.put(ref, tag = hex(16), ttlSeconds = 60L, body = body))

                val etag = http.head(endpoint.mailboxUrl(MailboxKind.BLOB)) {
                    header(HttpHeaders.Authorization, "Bearer ${relay.token}")
                    header(RelayMailboxClient.HEADER_TICKET, ref.ticket)
                    header(RelayMailboxClient.HEADER_ITEM, ref.item)
                }.headers[HttpHeaders.ETag]
                // A download that got the start of the item, before its link was cut.
                val download = RelayMailboxDownload(ref.item).apply {
                    this.etag = assertNotNull(etag, "The relay names no ETag")
                    total = body.size.toLong()
                    append(body, OFFSET)
                }
                val found = mailbox.get(ref, ifNoneMatch = null, maxBytes = body.size, download = download)
                assertContentEquals(body, assertIs<RelayMailboxResult.Found>(found).body)
                val partial = withContext(Dispatchers.IO) { relay.mailboxRequests()["get/partial_content"] }
                assertEquals(1L, partial)
            } finally {
                joined.cancel()
                http.close()
            }
        }

    private companion object {
        const val ITEM_SIZE = 100_000
        const val OFFSET = 40_000

        /** [bytes] random bytes in lowercase hex. */
        fun hex(bytes: Int): String = Random.nextBytes(bytes).joinToString("") { "%02x".format(it) }
    }
}
