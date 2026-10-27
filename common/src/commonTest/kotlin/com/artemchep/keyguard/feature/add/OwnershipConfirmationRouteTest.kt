package com.artemchep.keyguard.feature.add

import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.feature.confirmation.organization.FolderInfo
import com.artemchep.keyguard.feature.confirmation.organization.OrganizationConfirmationRoute
import kotlin.test.Test
import kotlin.test.assertEquals

class OwnershipConfirmationRouteTest {
    @Test
    fun pinningAnAccountLeavesTheOtherOwnershipChoicesEditable() {
        val route = ownershipConfirmationRoute(
            title = "Save to",
            readOnly = false,
            ownership = ownership,
            readOnlyAccount = true,
        )

        assertEquals("account", route.args.accountId)
        assertEquals(OrganizationConfirmationRoute.Args.RO_ACCOUNT, route.args.flags)
    }

    @Test
    fun existingItemsRemainFolderOnlyEvenWhenAccountRestrictionIsNotRequested() {
        val route = ownershipConfirmationRoute(
            title = "Save to",
            readOnly = true,
            ownership = ownership,
            readOnlyAccount = false,
        )

        assertEquals(
            OrganizationConfirmationRoute.Args.RO_ACCOUNT or
                OrganizationConfirmationRoute.Args.RO_ORGANIZATION or
                OrganizationConfirmationRoute.Args.RO_COLLECTION,
            route.args.flags,
        )
    }

    private companion object {
        val ownership = CreateRequest.Ownership2(
            accountId = "account",
            folder = FolderInfo.None,
            organizationId = null,
            collectionIds = emptySet(),
        )
    }
}
