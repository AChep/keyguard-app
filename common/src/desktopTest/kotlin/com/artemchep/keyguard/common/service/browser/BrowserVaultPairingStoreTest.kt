package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.VaultSettingsKeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The vault's record of the pairings, which each request of a paired session reads to confirm its pairing. */
class BrowserVaultPairingStoreTest {
    @Test
    fun `reads the record by its key alone, and fails while the vault can't be read`() = browserTest {
        val settings = JsonKeyValueStore(MemoryJsonStore())
        var keyed = 0
        var unreadable = false
        val vault = object : VaultSettingsKeyValueStore, KeyValueStore by settings {
            override fun getAll(): IO<Map<String, Any?>> = ioRaise(AssertionError("Read every setting"))

            override fun getByKey(key: String): IO<Any?> = ioEffect {
                keyed++
                if (unreadable) throw IOException("The vault can't be read")
                settings.getAll().bind()[key]
            }
        }
        val store = BrowserVaultPairingStore(vault)
        val identity = BrowserPairingIdentity(id = "x", publicKey = "AAAA", origin = CHROME_ORIGIN)
        assertTrue(store.update { it + identity })
        assertEquals(listOf(identity), store.records())
        assertEquals(2, keyed)

        // A failed read must remain distinguishable from an empty list.
        unreadable = true
        assertFailsWith<IOException> { store.records() }
    }
}
