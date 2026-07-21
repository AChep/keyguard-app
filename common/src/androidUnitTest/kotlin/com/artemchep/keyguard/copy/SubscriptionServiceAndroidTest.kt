package com.artemchep.keyguard.copy

import com.android.billingclient.api.Purchase
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionServiceAndroidTest {
    @Test
    fun `unacknowledged completed purchase is acknowledged`() {
        assertTrue(
            shouldAcknowledgePurchase(
                purchaseState = Purchase.PurchaseState.PURCHASED,
                isAcknowledged = false,
            ),
        )
    }

    @Test
    fun `pending purchase is not acknowledged`() {
        assertFalse(
            shouldAcknowledgePurchase(
                purchaseState = Purchase.PurchaseState.PENDING,
                isAcknowledged = false,
            ),
        )
    }

    @Test
    fun `already acknowledged purchase is not acknowledged again`() {
        assertFalse(
            shouldAcknowledgePurchase(
                purchaseState = Purchase.PurchaseState.PURCHASED,
                isAcknowledged = true,
            ),
        )
    }
}
