package com.artemchep.keyguard.feature.navigation

import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.feature.watchtower.alerts.WatchtowerAlertsRoute
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchtowerAlertsRouteTest {
    @Test
    fun `alert filter survives descriptor serialization and route restoration`() {
        val filter = DFilter.ByFavorite
        val descriptor: RouteDescriptor = WatchtowerAlertsRoute(
            WatchtowerAlertsRoute.Args(filter = filter),
        ).descriptor
        val restored = Json.decodeFromString<RouteDescriptor>(Json.encodeToString(descriptor))
        assertEquals(filter, (restored.toRoute() as WatchtowerAlertsRoute).args.filter)
        assertNull(restored.toDeepLink())
    }

    @Test
    fun `unfiltered alert descriptor retains deep link`() {
        assertEquals("keyguard://watchtower/alerts", RouteDescriptor.WatchtowerAlerts().toDeepLink())
    }
}
