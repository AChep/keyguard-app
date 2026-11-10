package com.artemchep.keyguard.feature.home.navigation

import com.artemchep.keyguard.common.model.DCipherFilter
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.NavItemRef
import com.artemchep.keyguard.common.model.NavItemSpec
import com.artemchep.keyguard.common.model.NavItemsConfigDefaults
import com.artemchep.keyguard.feature.navigation.NavigationStack
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Instant

/**
 * Which item refreshes the root of the active stack of the home router.
 */
class HomeNavigationRootRefreshTest {
    @Test
    fun `a renamed cipher filter refreshes the root of its own stack`() {
        val before = cipherFilterItem(id = 1L, name = "Old name")
        val after = cipherFilterItem(id = 1L, name = "New name")

        val item = listOf(after).findRootRefreshItem(
            routerId = ROUTER_ID,
            stackId = stackIdOf(after),
            rootRoute = before.route,
        )

        assertSame(after, item)
    }

    @Test
    fun `cipher filters with equal filters refresh only the root of their own stack`() {
        val a = cipherFilterItem(id = 1L, name = "A")
        val b = cipherFilterItem(id = 2L, name = "B")
        val renamedA = cipherFilterItem(id = 1L, name = "A renamed")

        // B comes first and its filter matches the root of the stack of A too.
        val current = listOf(b, a).findRootRefreshItem(
            routerId = ROUTER_ID,
            stackId = stackIdOf(a),
            rootRoute = a.route,
        )
        assertNull(current)

        val items = listOf(b, renamedA)
        val renamed = items.findRootRefreshItem(
            routerId = ROUTER_ID,
            stackId = stackIdOf(a),
            rootRoute = a.route,
        )
        assertSame(renamedA, renamed)

        // Once the root has the route of A, nothing refreshes it back.
        val refreshed = items.findRootRefreshItem(
            routerId = ROUTER_ID,
            stackId = stackIdOf(a),
            rootRoute = renamedA.route,
        )
        assertNull(refreshed)
    }

    @Test
    fun `a root with another filter is not refreshed`() {
        val before = cipherFilterItem(id = 1L, name = "Work", folderId = "folder-1")
        val after = cipherFilterItem(id = 1L, name = "Work", folderId = "folder-2")

        // A click on the tab resets the stack instead.
        val item = listOf(after).findRootRefreshItem(
            routerId = ROUTER_ID,
            stackId = stackIdOf(after),
            rootRoute = before.route,
        )

        assertNull(item)
    }

    @Test
    fun `built-in items never refresh a root`() {
        val items = resolveHomeNavigationItems(
            config = NavItemsConfigDefaults.defaultConfig(),
            cipherFilters = emptyList(),
        )
        val otherRoute = cipherFilterItem(id = 1L, name = "Work").route

        items.forEach { item ->
            listOf(item.route, otherRoute).forEach { rootRoute ->
                val refresh = items.findRootRefreshItem(
                    routerId = ROUTER_ID,
                    stackId = stackIdOf(item),
                    rootRoute = rootRoute,
                )
                assertNull(refresh, "${item.key} must not refresh $rootRoute")
            }
        }
    }

    @Test
    fun `a stack that no item owns is not refreshed`() {
        val before = cipherFilterItem(id = 1L, name = "Old name")
        val after = cipherFilterItem(id = 1L, name = "New name")
        val stackIds = listOf(
            // The tab of another, hidden filter.
            NavigationStack.createId(prefix = ROUTER_ID, suffix = "cipher_filter:2"),
            // A stack keyed by the class name of its route.
            NavigationStack.createId(
                prefix = ROUTER_ID,
                suffix = NavigationStack.createIdSuffix(before.route),
            ),
            // The stack of another router.
            NavigationStack.createId(prefix = "other", suffix = after.stackId),
        )

        stackIds.forEach { stackId ->
            val item = listOf(after).findRootRefreshItem(
                routerId = ROUTER_ID,
                stackId = stackId,
                rootRoute = before.route,
            )
            assertNull(item, stackId)
        }
    }

    private fun stackIdOf(item: HomeNavigationItem): String = NavigationStack.createId(
        prefix = ROUTER_ID,
        suffix = item.stackId,
    )

    private fun cipherFilterItem(
        id: Long,
        name: String,
        folderId: String = "folder",
    ): HomeNavigationItem {
        val filter = DCipherFilter(
            idRaw = id,
            icon = null,
            name = name,
            filter = mapOf(
                "folder" to setOf(
                    DFilter.ById(
                        id = folderId,
                        what = DFilter.ById.What.FOLDER,
                    ),
                ),
            ),
            updatedDate = Instant.fromEpochMilliseconds(0),
            createdDate = Instant.fromEpochMilliseconds(0),
        )
        return createCipherFilterHomeNavigationItem(
            spec = NavItemSpec(
                ref = NavItemRef.CipherFilter(filter.id),
            ),
            filter = filter,
        )
    }

    private companion object {
        const val ROUTER_ID = "home"
    }
}
