package com.artemchep.keyguard.common.service.session

import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.vault.testVaultSession
import kotlin.time.Clock

internal fun testMasterSessionKey() = MasterSession.Key(
    masterKey = MasterKey(MasterKdfVersion.LATEST, byteArrayOf(1)),
    session = testVaultSession {},
    origin = MasterSession.Key.Authenticated,
    createdAt = Clock.System.now(),
)
