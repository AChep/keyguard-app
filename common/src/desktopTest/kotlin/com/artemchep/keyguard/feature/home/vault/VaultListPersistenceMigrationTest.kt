package com.artemchep.keyguard.feature.home.vault

import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.home.vault.screen.ComparatorHolder
import com.artemchep.keyguard.feature.home.vault.screen.VaultListPersistence
import com.artemchep.keyguard.feature.home.vault.search.filter.FilterHolder
import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import com.artemchep.keyguard.feature.home.vault.search.sort.LastCreatedSort
import com.artemchep.keyguard.feature.home.vault.search.sort.LastModifiedSort
import com.artemchep.keyguard.feature.home.vault.search.sort.PasswordLastModifiedSort
import com.artemchep.keyguard.feature.home.vault.search.sort.PasswordSort
import com.artemchep.keyguard.feature.home.vault.search.sort.PasswordStrengthSort
import com.artemchep.keyguard.feature.home.vault.search.sort.Sort
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class VaultListPersistenceMigrationTest {
    // Mirrors the encode-side configuration of the Json
    // instance bound in the global DI module.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        prettyPrint = false
        isLenient = true
    }

    @Test
    fun screenNamesAreStable() {
        assertEquals("vault_list", VaultListPersistence.SCREEN_COMPOSE)
        assertEquals("vaultlist", VaultListPersistence.SCREEN_APPLE)
        assertEquals("menuvaultlist", VaultListPersistence.SCREEN_APPLE_MENU)
    }

    @Test
    fun diskHandleNamesAreStable() {
        assertEquals("vault.list", VaultListPersistence.DISK)
        assertEquals("ciphers.filter", VaultListPersistence.DISK_FILTER)
    }

    @Test
    fun flowKeysAreStable() {
        assertEquals("query", VaultListPersistence.KEY_QUERY)
        assertEquals("lole", VaultListPersistence.KEY_ITEM)
        assertEquals("selection", VaultListPersistence.KEY_SELECTION)
        assertEquals("keyboard", VaultListPersistence.KEY_KEYBOARD)
        assertEquals("sort", VaultListPersistence.KEY_SORT)
        assertEquals("sort_persistent", VaultListPersistence.KEY_SORT_PERSISTENT)
        assertEquals("sort_persistent_enabled", VaultListPersistence.KEY_SORT_PERSISTENT_ENABLED)
        assertEquals("scroll_state", VaultListPersistence.KEY_SCROLL_STATE)
        assertEquals("ciphers.filters", VaultListPersistence.KEY_FILTERS)
        assertEquals("ciphers.sections", VaultListPersistence.KEY_SECTIONS)
    }

    @Test
    fun sortIdsAreStable() {
        val ids = mapOf(
            "alphabetical" to AlphabeticalSort,
            "last_created" to LastCreatedSort,
            "last_modified" to LastModifiedSort,
            "password" to PasswordSort,
            "password_last_modified" to PasswordLastModifiedSort,
            "password_strength" to PasswordStrengthSort,
        )
        ids.forEach { (id, sort) ->
            assertEquals(id, sort.id)
            assertSame(sort, Sort.valueOf(id))
        }
    }

    @Test
    fun comparatorHolderDefaultFormatIsStable() {
        val holder = ComparatorHolder(
            comparator = AlphabeticalSort,
            favourites = true,
        )
        val blob = mapOf(
            "comparator" to "alphabetical",
            "reversed" to "false",
            "favourites" to "true",
        )
        assertEquals(blob, ComparatorHolder.serialize(json, holder))
        assertEquals(holder, ComparatorHolder.deserialize(json, blob))
        // A state persisted by an older version stored the flags as real
        // Booleans; it must still restore.
        assertEquals(
            holder,
            ComparatorHolder.of(
                mapOf(
                    "comparator" to "alphabetical",
                    "reversed" to false,
                    "favourites" to true,
                ),
            ),
        )
    }

    @Test
    fun comparatorHolderFormatIsStable() {
        val holder = ComparatorHolder(
            comparator = PasswordLastModifiedSort,
            reversed = true,
            favourites = true,
        )
        val blob = mapOf(
            "comparator" to "password_last_modified",
            "reversed" to "true",
            "favourites" to "true",
        )
        assertEquals(blob, ComparatorHolder.serialize(json, holder))
        assertEquals(holder, ComparatorHolder.deserialize(json, blob))
    }

    @Test
    fun filterHolderFormatIsStable() {
        val holder = FilterHolder(
            state = mapOf(
                "account" to setOf(
                    DFilter.ById(
                        id = "a1b2c3",
                        what = DFilter.ById.What.ACCOUNT,
                    ),
                ),
                "folder" to setOf(
                    DFilter.ById(
                        id = null,
                        what = DFilter.ById.What.FOLDER,
                    ),
                ),
                "type" to setOf(
                    DFilter.ByType(DSecret.Type.Login),
                ),
                "misc" to setOf(
                    DFilter.ByFavorite,
                ),
            ),
        )
        val blob = "{\"state\":{" +
                "\"account\":[{\"type\":\"by_id\",\"id\":\"a1b2c3\",\"what\":\"account\"}]," +
                "\"folder\":[{\"type\":\"by_id\",\"id\":null,\"what\":\"folder\"}]," +
                "\"type\":[{\"type\":\"by_type\",\"cipherType\":\"Login\"}]," +
                "\"misc\":[{\"type\":\"by_favorite\"}]" +
                "}}"
        assertEquals(blob, json.encodeToString(FilterHolder.serializer(), holder))
        assertEquals(holder, json.decodeFromString(FilterHolder.serializer(), blob))
    }
}
