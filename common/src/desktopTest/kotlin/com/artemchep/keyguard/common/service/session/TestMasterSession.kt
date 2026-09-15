package com.artemchep.keyguard.common.service.session

import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import org.kodein.di.DI
import kotlin.time.Clock

internal fun testMasterSessionKey() = MasterSession.Key(
    masterKey = MasterKey(MasterKdfVersion.LATEST, byteArrayOf(1)),
    di = DI {},
    origin = MasterSession.Key.Authenticated,
    createdAt = Clock.System.now(),
)
