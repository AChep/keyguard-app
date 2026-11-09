package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.feature.p2psync.session.BlobExchange
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AppLinkBlobsTest {
    @Test
    fun `pulls wait while the blobs that wait for their records could outgrow the cap`() = runTest {
        AppLinkTestDevice("A").use { device -> check(device) }
    }

    @Test
    fun `the blobs wanted are the ones the records need that did not arrive`() = runTest {
        AppLinkTestDevice("A").use { device ->
            val blobs = blobsOf(device)
            val bytes = wordBlobs(device)
            assertEquals(emptySet(), blobs.wanted())
            blobs.onWanted(bytes.keys)
            assertEquals(bytes.keys, blobs.wanted())

            // The relay is one more source: it may hold every blob that is wanted.
            blobs.onPeerBlobs("relay", blobs.wanted())
            val first = assertNotNull(blobs.claimNext("relay"))
            blobs.onReceived("relay", first, bytes.getValue(first))
            assertEquals(bytes.keys - first, blobs.wanted())
            // A pass wrote the record of the blob: no record needs it any longer.
            blobs.onWanted(bytes.keys - first)
            assertEquals(bytes.keys - first, blobs.wanted())
            blobs.onWanted(emptySet())
            assertEquals(emptySet(), blobs.wanted())
        }
    }

    @Test
    fun `a pull cut short goes on first, also while it is passed over`() = runTest {
        AppLinkTestDevice("A").use { device ->
            val blobs = blobsOf(device)
            val bytes = wordBlobs(device)
            blobs.onWanted(bytes.keys)
            blobs.onPeerBlobs("relay", bytes.keys)
            val (first, second) = bytes.keys.sorted()

            // The pull of the first blob is cut short: it is passed over for a while.
            assertEquals(first, blobs.claimNext("relay"))
            blobs.onFailed("relay", first)
            assertEquals(second, blobs.claimNext("relay"))
            blobs.onFailed("relay", second)
            // What arrived of it waits, so it goes on before any other blob.
            assertEquals(first, blobs.claimNext("relay", resume = first))
            // Claimed once only.
            assertNull(blobs.claimNext("relay", resume = first)?.takeIf { it == first })
        }
    }

    @Test
    fun `a peer that named as many blobs as it may is asked for others too, after those it named`() = runTest {
        AppLinkTestDevice("A").use { device ->
            val blobs = blobsOf(device)
            val bytes = wordBlobs(device)
            // The blob the peer did not name comes first by id.
            val (unnamed, named) = bytes.keys.sorted()
            blobs.onWanted(setOf(named, unnamed))
            val filler = List(BlobExchange.MAX_ADVERTISED - 1) { "filler$it" }

            // A list that is not full names every blob the peer holds.
            blobs.onPeerBlobs("partial", filler.drop(1) + named)
            assertEquals(named, blobs.claimNext("partial"))
            blobs.onFailed("partial", named)
            assertNull(blobs.claimNext("partial"))
            blobs.onSessionEnded("partial")

            // A full one may leave some out: the blobs it named go first.
            blobs.onPeerBlobs("full", filler + named)
            assertEquals(named, blobs.claimNext("full"))
            assertEquals(unnamed, blobs.claimNext("full"))
            // It does not hold it, so it is passed over for that blob for a while.
            blobs.onFailed("full", unnamed)
            assertNull(blobs.claimNext("full"))
        }
    }

    private fun blobsOf(
        device: AppLinkTestDevice,
        maxReceivedBytes: Long = AppLinkBlobs.MAX_RECEIVED_BYTES,
    ): AppLinkBlobs {
        val received = AppLinkReceivedBlobs()
        return AppLinkBlobs(
            db = device.dbManager,
            categories = AppSyncCategories.default(
                hashOf = { bytes -> BlobExchange.hashOf(device.crypto, bytes) },
                received = received,
            ),
            switches = device.switches,
            received = received,
            queueSync = { ioEffect { } },
            maxReceivedBytes = maxReceivedBytes,
        )
    }

    /** One blob of words per word, by id. */
    private fun wordBlobs(device: AppLinkTestDevice): Map<String, ByteArray> =
        listOf("cat", "dog", "ant").associate { word ->
            val blob = WordlistBlob.encode(listOf(word))
            BlobExchange.hashOf(device.crypto, blob) to blob
        }

    private suspend fun check(device: AppLinkTestDevice) {
        val blobs = blobsOf(device, maxReceivedBytes = 2L * AppLinkBlobs.MAX_BLOB_SIZE)
        val bytes = wordBlobs(device)
        blobs.onWanted(bytes.keys)
        blobs.onPeerBlobs("peer", bytes.keys)

        // Each pull may take the largest size: two fit the cap.
        val first = assertNotNull(blobs.claimNext("peer"))
        assertNotNull(blobs.claimNext("peer"))
        assertNull(blobs.claimNext("peer"))
        // A blob that waits for its record holds its place until a pass writes the record.
        blobs.onReceived("peer", first, bytes.getValue(first))
        assertNull(blobs.claimNext("peer"))
        blobs.onWanted(bytes.keys - first)
        assertNotNull(blobs.claimNext("peer"))
    }
}
