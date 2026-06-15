package com.artemchep.keyguard.android

import android.app.Activity

interface CredentialProviderPlatformConfig {
    val getPasswordActivityClass: Class<out Activity>

    val getPasskeyActivityClass: Class<out Activity>

    val getUnlockCredentialActivityClass: Class<out Activity>

    val createCredentialActivityClass: Class<out Activity>?
}

object PhoneCredentialProviderPlatformConfig : CredentialProviderPlatformConfig {
    override val getPasswordActivityClass = PasswordGetActivity::class.java

    override val getPasskeyActivityClass = PasskeyGetActivity::class.java

    override val getUnlockCredentialActivityClass = CredentialGetUnlockActivity::class.java

    override val createCredentialActivityClass = PasskeyCreateActivity::class.java
}
