package com.artemchep.keyguard.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BillingResponseExceptionTest {
    @Test
    fun `purchase success refreshes purchases and is not reported`() {
        val result = billingResult(BillingClient.BillingResponseCode.OK)

        assertTrue(result.shouldRefreshPurchases())
        assertFalse(result.shouldRefreshPurchasesAfterLaunch())
        assertFalse(result.shouldReportPurchaseError())
        result.throwIfPurchaseFailed()
    }

    @Test
    fun `already owned purchase refreshes purchases and is not reported`() {
        val result = billingResult(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED)

        assertTrue(result.shouldRefreshPurchases())
        assertTrue(result.shouldRefreshPurchasesAfterLaunch())
        assertFalse(result.shouldReportPurchaseError())
        result.throwIfPurchaseFailed()
    }

    @Test
    fun `user cancellation is silent`() {
        val result = billingResult(BillingClient.BillingResponseCode.USER_CANCELED)

        assertFalse(result.shouldRefreshPurchases())
        assertFalse(result.shouldReportPurchaseError())
        result.throwIfPurchaseFailed()
    }

    @Test
    fun `purchase failure keeps response context`() {
        val result = billingResult(
            responseCode = BillingClient.BillingResponseCode.ERROR,
            debugMessage = "Payment failed",
            subResponseCode = BillingClient.OnPurchasesUpdatedSubResponseCode
                .PAYMENT_DECLINED_DUE_TO_INSUFFICIENT_FUNDS,
        )

        val exception = assertFailsWith<BillingResponseException> {
            result.throwIfPurchaseFailed()
        }

        assertEquals(BillingClient.BillingResponseCode.ERROR, exception.code)
        assertEquals("Payment failed", exception.debugMessage)
        assertEquals(
            BillingClient.OnPurchasesUpdatedSubResponseCode
                .PAYMENT_DECLINED_DUE_TO_INSUFFICIENT_FUNDS,
            exception.subResponseCode,
        )
        assertTrue(exception.message.orEmpty().contains("Payment failed"))
    }

    @Test
    fun `only transient setup failures retry`() {
        assertTrue(
            billingResult(BillingClient.BillingResponseCode.NETWORK_ERROR)
                .shouldRetryConnection(),
        )
        assertTrue(
            billingResult(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
                .shouldRetryConnection(),
        )
        assertTrue(
            billingResult(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)
                .shouldRetryConnection(),
        )
        assertFalse(
            billingResult(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE)
                .shouldRetryConnection(),
        )
    }

    @Test
    fun `network and unavailable classifications match Billing 9 semantics`() {
        assertTrue(BillingResponseException(BillingClient.BillingResponseCode.NETWORK_ERROR).isNetworkIssue())
        assertTrue(BillingResponseException(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE).isNetworkIssue())
        assertFalse(BillingResponseException(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE).isNetworkIssue())
        assertTrue(BillingResponseException(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE).isNotSupported())
    }

    private fun billingResult(
        responseCode: Int,
        debugMessage: String = "",
        subResponseCode: Int = BillingClient.OnPurchasesUpdatedSubResponseCode
            .NO_APPLICABLE_SUB_RESPONSE_CODE,
    ) = BillingResult.newBuilder()
        .setResponseCode(responseCode)
        .setDebugMessage(debugMessage)
        .setOnPurchasesUpdatedSubResponseCode(subResponseCode)
        .build()
}
