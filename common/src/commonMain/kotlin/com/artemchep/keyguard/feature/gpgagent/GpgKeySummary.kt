package com.artemchep.keyguard.feature.gpgagent

import com.artemchep.keyguard.common.service.gpgagent.chunkedGpgFingerprint
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope

internal suspend fun RememberStateFlowScope.gpgKeyDescription(
    fingerprint: String?,
): String? {
    return fingerprint?.chunkedGpgFingerprint()
}
