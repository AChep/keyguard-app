package com.artemchep.keyguard.common.service.licensekey

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.licensekey.model.LicenseClaimCandidate
import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement
import kotlinx.coroutines.flow.Flow

/**
 * @author Artem Chepurnyi
 */
interface LicenseManager {

    // Redeeming

    /**
     * Observes the manually redeemed entitlement, or `null` when no manual
     * license key has been redeemed.
     */
    val redeemed: Flow<LicenseEntitlement?>

    /**
     * Verifies a manually entered license key locally and stores it when the
     * key is decodable. Server status is checked separately.
     */
    fun redeem(licenseKey: String): IO<LicenseEntitlement>

    /**
     * Refreshes the stored entitlement when it is stale. Any network/server
     * failure should keep previous entitlement.
     *
     * @param force refresh even when the snapshot is not yet stale.
     */
    fun refreshRedeemedIfNeeded(force: Boolean = false): IO<LicenseEntitlement?>

    /** Removes the manually redeemed license key and entitlement snapshot. */
    fun clearRedeemed(): IO<Unit>

    // Claiming

    /**
     * Observes the latest claimed store license, or `null` when no store key
     * has been claimed. Claimed license keys are display-only.
     */
    val claimed: Flow<LicenseEntitlement?>

    fun claim(
        claim: LicenseClaimCandidate,
        force: Boolean = false,
    ): IO<LicenseEntitlement?>
}