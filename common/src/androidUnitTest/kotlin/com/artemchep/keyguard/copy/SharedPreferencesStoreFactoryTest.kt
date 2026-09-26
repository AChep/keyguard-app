package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class SharedPreferencesStoreFactoryTest {
    @Test
    fun `legacy stores keep device id plaintext and cache every file independently`() {
        val plaintextFiles = mutableListOf<Files>()
        val encryptedFiles = mutableListOf<Files>()
        val factory = SharedPreferencesStoreFactoryV1(
            plaintextStore = { file ->
                plaintextFiles += file
                JsonKeyValueStore()
            },
            encryptedStore = { file ->
                encryptedFiles += file
                JsonKeyValueStore()
            },
        )

        val stores = Files.entries.associateWith { factory.get(it) }
        Files.entries.forEach { file -> assertSame(stores.getValue(file), factory.get(file)) }

        assertEquals(listOf(Files.DEVICE_ID), plaintextFiles)
        assertEquals(Files.entries.filter { it != Files.DEVICE_ID }, encryptedFiles)
        assertEquals(Files.entries.size, stores.values.toSet().size)
    }

    @Test
    fun `current stores preserve encryption routing and reuse matching legacy migration store`() {
        val legacyStores = Files.entries.associateWith { JsonKeyValueStore() }
        val legacy = object : SharedPreferencesStoreFactory {
            override fun get(file: Files): KeyValueStore = legacyStores.getValue(file)
        }
        val plaintextFiles = mutableListOf<Files>()
        val encryptedFiles = mutableListOf<Files>()
        val factory = SharedPreferencesStoreFactoryV2(
            factoryV1 = legacy,
            plaintextStore = { file, backingStore ->
                assertSame(legacyStores.getValue(file), backingStore)
                plaintextFiles += file
                JsonKeyValueStore()
            },
            encryptedStore = { file, backingStore ->
                assertSame(legacyStores.getValue(file), backingStore)
                encryptedFiles += file
                JsonKeyValueStore()
            },
        )

        val stores = Files.entries.associateWith { factory.get(it) }
        Files.entries.forEach { file ->
            assertSame(stores.getValue(file), factory.get(file))
            assertNotSame(legacyStores.getValue(file), stores.getValue(file))
        }

        val expectedPlaintext = setOf(
            Files.SESSION_METADATA,
            Files.WINDOW_STATE,
            Files.DEVICE_ID,
            Files.UI_STATE,
            Files.REVIEW,
            Files.NOTIFICATIONS,
        )
        assertEquals(expectedPlaintext, plaintextFiles.toSet())
        assertEquals(Files.entries.toSet() - expectedPlaintext, encryptedFiles.toSet())
        assertEquals(Files.entries.size, stores.values.toSet().size)
    }
}
