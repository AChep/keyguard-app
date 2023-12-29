package com.artemchep.keyguard.android

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

abstract class BaseApp : Application() {
    init {
        val bcProvider = BouncyCastleProvider()
        Security.addProvider(bcProvider)
    }
}
