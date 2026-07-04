package com.artemchep.keyguard.feature.home.navigation

import com.artemchep.keyguard.common.model.NavItemRef
import com.artemchep.keyguard.common.model.NavItemSpec
import com.artemchep.keyguard.common.model.NavItemsConfig
import com.artemchep.keyguard.common.model.NavItemsConfigDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeNavigationConfigTest {
    @Test
    fun `null config resolves default order`() {
        val config = normalizeHomeNavigationConfig(
            config = null,
        )

        assertEquals(
            NavItemsConfigDefaults.builtInKeys,
            config.items.map { (it.ref as NavItemRef.BuiltIn).key },
        )
        assertTrue(config.items.all { it.visible })
    }

    @Test
    fun `availability forces unavailable sends hidden`() {
        val sendsRef = NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_SENDS)
        val config = applyHomeNavigationAvailability(
            config = NavItemsConfigDefaults.defaultConfig(),
            availability = mapOf(
                sendsRef to false,
            ),
        )

        val sends = config.items.single {
            it.ref == sendsRef
        }
        assertFalse(sends.visible)
    }

    @Test
    fun `availability preserves user hidden sends when available`() {
        val sendsRef = NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_SENDS)
        val persistedConfig = NavItemsConfig(
            items = NavItemsConfigDefaults.defaultItems()
                .map { item ->
                    if (item.ref == sendsRef) {
                        item.copy(visible = false)
                    } else {
                        item
                    }
                },
        )

        val config = applyHomeNavigationAvailability(
            config = persistedConfig,
            availability = mapOf(
                sendsRef to true,
            ),
        )

        val sends = config.items.single {
            it.ref == sendsRef
        }
        assertFalse(sends.visible)
    }

    @Test
    fun `availability preserves unrelated item order and visibility`() {
        val sendsRef = NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_SENDS)
        val generatorRef = NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_GENERATOR)
        val config = NavItemsConfig(
            items = listOf(
                NavItemSpec(
                    ref = NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_SETTINGS),
                ),
                NavItemSpec(
                    ref = generatorRef,
                    visible = false,
                ),
                NavItemSpec(
                    ref = sendsRef,
                ),
            ),
        )

        val effectiveConfig = applyHomeNavigationAvailability(
            config = config,
            availability = mapOf(
                sendsRef to false,
            ),
        )

        assertEquals(
            config.items.map { it.ref },
            effectiveConfig.items.map { it.ref },
        )
        val generator = effectiveConfig.items.single {
            it.ref == generatorRef
        }
        assertFalse(generator.visible)
    }

    @Test
    fun `normalization deduplicates specs and appends missing built-ins`() {
        val config = normalizeHomeNavigationConfig(
            config = NavItemsConfig(
                version = 0,
                items = listOf(
                    NavItemSpec(
                        ref = NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_SETTINGS),
                        visible = false,
                    ),
                    NavItemSpec(
                        ref = NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_SETTINGS),
                        visible = true,
                    ),
                ),
            ),
        )

        assertEquals(NavItemsConfigDefaults.VERSION, config.version)
        assertEquals(
            listOf(
                NavItemsConfigDefaults.BUILT_IN_SETTINGS,
                NavItemsConfigDefaults.BUILT_IN_VAULT,
                NavItemsConfigDefaults.BUILT_IN_SENDS,
                NavItemsConfigDefaults.BUILT_IN_GENERATOR,
                NavItemsConfigDefaults.BUILT_IN_WATCHTOWER,
            ),
            config.items.map { (it.ref as NavItemRef.BuiltIn).key },
        )
        assertTrue(config.items.first().visible)
    }

    @Test
    fun `normalization forces settings visible`() {
        val config = normalizeHomeNavigationConfig(
            config = NavItemsConfig(
                items = listOf(
                    NavItemSpec(
                        ref = NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_SETTINGS),
                        visible = false,
                    ),
                ),
            ),
        )

        val settings = config.items.single {
            it.ref == NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_SETTINGS)
        }
        assertTrue(settings.visible)
    }

    @Test
    fun `normalization forces vault visible`() {
        val config = normalizeHomeNavigationConfig(
            config = NavItemsConfig(
                items = listOf(
                    NavItemSpec(
                        ref = NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_VAULT),
                        visible = false,
                    ),
                ),
            ),
        )

        val vault = config.items.single {
            it.ref == NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_VAULT)
        }
        assertTrue(vault.visible)
    }

    @Test
    fun `availability cannot hide settings`() {
        val settingsRef = NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_SETTINGS)
        val config = applyHomeNavigationAvailability(
            config = NavItemsConfig(
                items = listOf(
                    NavItemSpec(
                        ref = settingsRef,
                    ),
                ),
            ),
            availability = mapOf(
                settingsRef to false,
            ),
        )

        val settings = config.items.single {
            it.ref == settingsRef
        }
        assertTrue(settings.visible)
    }

    @Test
    fun `availability cannot hide vault`() {
        val vaultRef = NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_VAULT)
        val config = applyHomeNavigationAvailability(
            config = NavItemsConfig(
                items = listOf(
                    NavItemSpec(
                        ref = vaultRef,
                    ),
                ),
            ),
            availability = mapOf(
                vaultRef to false,
            ),
        )

        val vault = config.items.single {
            it.ref == vaultRef
        }
        assertTrue(vault.visible)
    }
}
