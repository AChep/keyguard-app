package com.artemchep.keyguard.wear

import com.artemchep.keyguard.wear.credential.WearCredentialProviderPlatformConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearCredentialProviderPlatformConfigTest {
    @Test
    fun `wear config points to wear provider activities and disables create`() {
        assertEquals(
            WearPasswordGetActivity::class.java,
            WearCredentialProviderPlatformConfig.getPasswordActivityClass,
        )
        assertEquals(
            WearPasskeyGetActivity::class.java,
            WearCredentialProviderPlatformConfig.getPasskeyActivityClass,
        )
        assertEquals(
            WearPasskeyGetUnlockActivity::class.java,
            WearCredentialProviderPlatformConfig.getUnlockCredentialActivityClass,
        )
        assertNull(WearCredentialProviderPlatformConfig.createCredentialActivityClass)
    }
}
