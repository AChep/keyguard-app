package com.artemchep.keyguard.feature.home.vault.apple

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VaultUniverseStoreTest {
    private val keyA = UniverseKey(
        mode = AppleVaultListConfig.MODE_MAIN,
        trash = AppleVaultListConfig.TRISTATE_OFF,
        archive = AppleVaultListConfig.TRISTATE_OFF,
        filterKey = "",
        searchByPassword = false,
    )

    private val keyB = keyA.copy(trash = AppleVaultListConfig.TRISTATE_ON)

    private class Handle(
        val name: String,
    )

    @Test
    fun `same key shares one handle and runs the factory once`() {
        val store = VaultUniverseStore<Handle>(log = {})
        var factoryCalls = 0
        val first = store.acquire(keyA) {
            factoryCalls += 1
            Handle("a")
        }
        val second = store.acquire(keyA) {
            factoryCalls += 1
            Handle("a2")
        }
        assertSame(first, second)
        assertEquals(1, factoryCalls)
        assertEquals(2, store.refCountOf(keyA))
        assertEquals(1, store.size())
    }

    @Test
    fun `different keys get different handles`() {
        val store = VaultUniverseStore<Handle>(log = {})
        var factoryCalls = 0
        val a = store.acquire(keyA) {
            factoryCalls += 1
            Handle("a")
        }
        val b = store.acquire(keyB) {
            factoryCalls += 1
            Handle("b")
        }
        assertNotSame(a, b)
        assertEquals(2, factoryCalls)
        assertEquals(1, store.refCountOf(keyA))
        assertEquals(1, store.refCountOf(keyB))
        assertEquals(2, store.size())
    }

    @Test
    fun `release drops the handle only after the last reference`() {
        val store = VaultUniverseStore<Handle>(log = {})
        var factoryCalls = 0
        val factory = {
            factoryCalls += 1
            Handle("a$factoryCalls")
        }
        val first = store.acquire(keyA, factory)
        store.acquire(keyA, factory)

        assertFalse(store.release(keyA), "the first release must keep the handle")
        assertEquals(1, store.refCountOf(keyA))

        // Re-acquiring a still-live handle reuses it.
        val reacquired = store.acquire(keyA, factory)
        assertSame(first, reacquired)
        assertEquals(1, factoryCalls)

        assertFalse(store.release(keyA))
        assertTrue(store.release(keyA), "the last release must drop the handle")
        assertEquals(0, store.refCountOf(keyA))
        assertEquals(0, store.size())

        // The next acquisition starts a fresh universe.
        val fresh = store.acquire(keyA, factory)
        assertNotSame(first, fresh)
        assertEquals(2, factoryCalls)
    }

    @Test
    fun `unbalanced release logs loudly and returns false`() {
        val logs = mutableListOf<String>()
        val store = VaultUniverseStore<Handle>(log = { logs += it })
        assertFalse(store.release(keyA))
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("VaultUniverseStore"))
    }

    @Test
    fun `the universe handle shape pairs a shared flow with a row cache`() {
        // Exercises the intended handle type end-to-end: the store is
        // generic, the facade is expected to wire this exact shape.
        val store = VaultUniverseStore<UniverseHandle<Int>>(log = {})
        val handle = store.acquire(keyA) {
            UniverseHandle(
                key = keyA,
                universe = MutableSharedFlow(),
                rowCache = VaultRowCache(
                    rebuild = { error("never rebuilt in this test") },
                ),
            )
        }
        assertEquals(keyA, handle.key)
        assertTrue(store.release(keyA))
    }
}
