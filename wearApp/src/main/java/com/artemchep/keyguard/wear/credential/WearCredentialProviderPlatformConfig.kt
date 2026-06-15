package com.artemchep.keyguard.wear.credential

import com.artemchep.keyguard.android.CredentialProviderPlatformConfig
import com.artemchep.keyguard.wear.WearPasskeyGetActivity
import com.artemchep.keyguard.wear.WearPasskeyGetUnlockActivity
import com.artemchep.keyguard.wear.WearPasswordGetActivity

object WearCredentialProviderPlatformConfig : CredentialProviderPlatformConfig {
    override val getPasswordActivityClass = WearPasswordGetActivity::class.java

    override val getPasskeyActivityClass = WearPasskeyGetActivity::class.java

    override val getUnlockCredentialActivityClass = WearPasskeyGetUnlockActivity::class.java

    override val createCredentialActivityClass = null
}
