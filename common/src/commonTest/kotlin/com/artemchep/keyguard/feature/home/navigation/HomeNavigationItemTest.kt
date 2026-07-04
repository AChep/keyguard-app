package com.artemchep.keyguard.feature.home.navigation

import com.artemchep.keyguard.common.model.DCipherFilter
import com.artemchep.keyguard.common.model.NavItemRef
import com.artemchep.keyguard.common.model.NavItemSpec
import com.artemchep.keyguard.common.model.NavItemsConfig
import com.artemchep.keyguard.common.model.NavItemsConfigDefaults
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.localization.TextHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class HomeNavigationItemTest {
    @Test
    fun `resolver maps built-ins in configured order`() {
        val config = NavItemsConfigDefaults.defaultConfig()

        val items = resolveHomeNavigationItems(
            config = config,
            cipherFilters = emptyList(),
        )

        assertEquals(
            NavItemsConfigDefaults.builtInKeys,
            items.map { (it.spec.ref as NavItemRef.BuiltIn).key },
        )
        assertEquals(homeVaultRoute, items.first().route)
    }

    @Test
    fun `resolver excludes user-hidden items`() {
        val config = NavItemsConfig(
            items = listOf(
                NavItemSpec(
                    ref = NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_SENDS),
                    visible = false,
                ),
                NavItemSpec(
                    ref = NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_SETTINGS),
                    visible = true,
                ),
            ),
        )

        val items = resolveHomeNavigationItems(
            config = config,
            cipherFilters = emptyList(),
        )

        assertEquals(1, items.size)
        assertEquals(
            NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_SETTINGS),
            items.single().spec.ref,
        )
    }

    @Test
    fun `resolver creates filtered vault routes for custom filters`() {
        val filter = cipherFilter(
            id = 42L,
            name = "Work",
        )
        val config = NavItemsConfig(
            items = listOf(
                NavItemSpec(
                    ref = NavItemRef.CipherFilter(filter.id),
                ),
            ),
        )

        val item = resolveHomeNavigationItems(
            config = config,
            cipherFilters = listOf(filter),
        ).single()

        val route = assertIs<VaultRoute>(item.route)
        assertEquals("Work", route.args.appBar?.title)
        assertEquals(false, route.args.preselect)
        assertEquals(false, route.args.canAddSecrets)
        assertEquals("cipher_filter:42", item.stackId)
        assertEquals(TextHolder.Value("Work"), item.label)
    }

    @Test
    fun `resolver omits missing custom filters but keeps other items`() {
        val config = NavItemsConfig(
            items = listOf(
                NavItemSpec(
                    ref = NavItemRef.CipherFilter("missing"),
                ),
                NavItemSpec(
                    ref = NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_VAULT),
                ),
            ),
        )

        val items = resolveHomeNavigationItems(
            config = config,
            cipherFilters = emptyList(),
        )

        assertEquals(1, items.size)
        assertEquals(
            NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_VAULT),
            items.single().spec.ref,
        )
    }

    @Test
    fun `resolver ignores future predefined routes until implemented`() {
        val config = NavItemsConfig(
            items = listOf(
                NavItemSpec(
                    ref = NavItemRef.PredefinedRoute("future"),
                ),
            ),
        )

        val items = resolveHomeNavigationItems(
            config = config,
            cipherFilters = emptyList(),
        )

        assertTrue(items.isEmpty())
    }

    @Test
    fun `main vault and custom filter vault use different stacks`() {
        val filter = cipherFilter(
            id = 7L,
            name = "Personal",
        )
        val config = NavItemsConfig(
            items = listOf(
                NavItemSpec(
                    ref = NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_VAULT),
                ),
                NavItemSpec(
                    ref = NavItemRef.CipherFilter(filter.id),
                ),
            ),
        )

        val items = resolveHomeNavigationItems(
            config = config,
            cipherFilters = listOf(filter),
        )

        assertNotEquals(items[0].stackId, items[1].stackId)
    }

    @Test
    fun `renamed custom filters change resolved route descriptors`() {
        val ref = NavItemRef.CipherFilter("5")
        val config = NavItemsConfig(
            items = listOf(
                NavItemSpec(ref = ref),
            ),
        )

        val before = resolveHomeNavigationItems(
            config = config,
            cipherFilters = listOf(
                cipherFilter(
                    id = 5L,
                    name = "Old name",
                ),
            ),
        ).single()
        val after = resolveHomeNavigationItems(
            config = config,
            cipherFilters = listOf(
                cipherFilter(
                    id = 5L,
                    name = "New name",
                ),
            ),
        ).single()

        assertNotEquals(before.route.descriptor, after.route.descriptor)
    }

    private fun cipherFilter(
        id: Long,
        name: String,
    ) = DCipherFilter(
        idRaw = id,
        icon = null,
        name = name,
        filter = emptyMap(),
        updatedDate = Instant.fromEpochMilliseconds(0),
        createdDate = Instant.fromEpochMilliseconds(0),
    )
}
