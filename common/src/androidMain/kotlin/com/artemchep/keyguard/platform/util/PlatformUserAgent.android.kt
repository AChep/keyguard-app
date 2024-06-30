package com.artemchep.keyguard.platform.util

import android.os.Build
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.provider.bitwarden.api.BitwardenPersona

actual val Platform.userAgent: String
    get() = "Bitwarden_Mobile/${BitwardenPersona.CLIENT_VERSION} (Android ${Build.VERSION.RELEASE}; SDK ${Build.VERSION.SDK_INT}; Model ${Build.MODEL})"
