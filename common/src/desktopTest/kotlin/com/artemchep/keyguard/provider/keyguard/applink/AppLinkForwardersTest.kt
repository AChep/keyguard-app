package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class AppLinkForwardersTest : AppSyncRowContractTest(ForwarderTestRows) {
    @Test
    fun `linking identical forwarders preserves local references when the sync id changes`() = runTest {
        val a = AppLinkTestDevice("A")
        val b = AppLinkTestDevice("B")
        a.addForwarder("Work", "simplelogin", "apiKey" to "same")
        b.addForwarder("Work", "simplelogin", "apiKey" to "same")
        val before = b.forwarder("Work")
        val oldSyncId = b.forwarderSyncId("Work")

        a.link()
        b.time = a.time + 1
        b.join(a)
        a.exchangeWith(b)

        assertEquals(1, a.forwarderList().size)
        assertEquals(1, b.forwarderList().size)
        assertEquals(a.forwarderSyncId("Work"), b.forwarderSyncId("Work"))
        assertNotEquals(oldSyncId, b.forwarderSyncId("Work"))
        assertEquals(before.id, b.forwarder("Work").id)

        // A form opened before linking still edits and deletes the same local row.
        b.time += 1_000
        b.editForwarder(before, name = "Office")
        b.exchangeWith(a)

        assertEquals(listOf("Office"), a.forwarderList().map { it.name })
        assertEquals(before.id, b.forwarder("Office").id)

        b.time += 1_000
        b.removeForwarder(before)
        b.exchangeWith(a)

        assertTrue(a.forwarderList().isEmpty())
        assertTrue(b.forwarderList().isEmpty())
    }
}

/** TEST ONLY: the email forwarders, for [AppSyncRowContractTest]. */
internal object ForwarderTestRows : AppSyncTestRows {
    private val types = listOf("duckduckgo", "simplelogin", "anonaddy")

    override val kind: EntryKind get() = EntryKind.EMAIL_FORWARDER

    override suspend fun add(device: AppLinkTestDevice, name: String, variant: Int) =
        device.addForwarder(name, types[variant], "apiKey" to "key $variant")

    override suspend fun edit(device: AppLinkTestDevice, name: String, newName: String, variant: Int) =
        device.editForwarder(
            current = device.forwarder(name),
            name = newName,
            data = mapOf("apiKey" to "key $variant"),
        )

    override suspend fun remove(device: AppLinkTestDevice, name: String) =
        device.removeForwarder(device.forwarder(name))

    override suspend fun synced(device: AppLinkTestDevice): List<String> = device.forwarderList()
        .map { it.copy(id = null, createdDate = Instant.DISTANT_PAST).toString() }
        .sorted()

    override suspend fun rowId(device: AppLinkTestDevice, name: String): String =
        requireNotNull(device.forwarder(name).id)

    override suspend fun syncId(device: AppLinkTestDevice, name: String): String = device.forwarderSyncId(name)
}
