package com.artemchep.keyguard.feature.home.vault.folders

import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.feature.navigation.RouteDescriptor
import com.artemchep.keyguard.feature.navigation.toRoute
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FoldersRouteDescriptorTest {
    @Test
    fun `nested folder route survives serialization and reconstruction`() {
        val args = FoldersRoute.Args(
            filter = DFilter.ById("account-a", DFilter.ById.What.ACCOUNT),
            empty = true,
            parent = FoldersRoute.Args.Parent("account-a", "parent-folder"),
        )
        val descriptor: RouteDescriptor = FoldersRoute(args).descriptor
        val saved = Json.encodeToString(descriptor)
        val restored = Json.decodeFromString<RouteDescriptor>(saved)

        assertEquals(args, assertIs<FoldersRoute>(restored.toRoute()).args)
        assertEquals(args, assertIs<RouteDescriptor.Folders>(restored).toFoldersRouteArgs())
    }

    @Test
    fun `legacy folder descriptor still opens the root`() {
        val saved = """{"type":"vault.folders","empty":true}"""
        val restored = assertIs<RouteDescriptor.Folders>(Json.decodeFromString<RouteDescriptor>(saved))

        assertNull(restored.parent)
        assertEquals(FoldersRoute.Args(empty = true), assertIs<FoldersRoute>(restored.toRoute()).args)
    }

    @Test
    fun `parent account remains distinct when folder identifiers collide`() {
        for (account in listOf("account-a", "account-b")) {
            val args = FoldersRoute.Args(parent = FoldersRoute.Args.Parent(account, "same-folder-id"))
            val restored = FoldersRoute(args).descriptor.toRoute()

            assertEquals(args, assertIs<FoldersRoute>(restored).args)
        }
    }
}
