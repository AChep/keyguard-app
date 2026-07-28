package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.gpgagent.getGpgAgentPublicKeyArmored

internal fun Iterable<DSecret>.toGpgRevocationKeyCandidates(): List<GpgOpenPgpPublicKey> =
    asSequence()
        .filterNot { cipher -> cipher.deleted }
        .mapNotNull { cipher ->
            cipher.getGpgAgentPublicKeyArmored()
                ?.takeIf(String::isNotBlank)
        }
        .distinct()
        .map(::GpgOpenPgpPublicKey)
        .toList()
