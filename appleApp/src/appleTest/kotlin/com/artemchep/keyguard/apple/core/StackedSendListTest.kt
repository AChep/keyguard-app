package com.artemchep.keyguard.apple.core

import com.artemchep.keyguard.common.model.DSendFilter
import com.artemchep.keyguard.feature.navigation.RouteDescriptor
import com.artemchep.keyguard.feature.send.SendRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StackedSendListTest {
    @Test
    fun `account filtered send list pushes a stack entry with its route args`() {
        val filter = DSendFilter.Or(
            filters = listOf(DSendFilter.ById(id = "account", what = DSendFilter.ById.What.ACCOUNT)),
        )
        val route = SendRoute(
            SendRoute.Args(
                appBar = SendRoute.Args.AppBar(title = "user@example.com"),
                filter = filter,
                preselect = false,
                canAddSecrets = false,
            ),
        )

        val kind = assertNotNull(descriptor(route).toStackedSendList())
        assertEquals(ScreenEntryKind.SEND_LIST, kind.entryKind)
        assertEquals("user@example.com", kind.title)
        assertEquals("user@example.com", kind.args.appBar?.title)
        assertEquals(filter, kind.args.filter)
        assertFalse(kind.args.preselect)
        assertFalse(kind.args.canAddSecrets)
    }

    @Test
    fun `filter without a title still pushes`() {
        val descriptor = RouteDescriptor.SendList(filter = DSendFilter.All)

        val kind = assertNotNull(descriptor.toStackedSendList())
        assertEquals("", kind.title)
        assertEquals(DSendFilter.All, kind.args.filter)
    }

    @Test
    fun `unfiltered root send list stays the section root`() {
        assertNull(descriptor(SendRoute()).toStackedSendList())
    }

    private fun descriptor(route: SendRoute) = assertIs<RouteDescriptor.SendList>(route.descriptor)
}
