package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.AddCipher
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.TrashCipherById
import kotlinx.coroutines.CoroutineScope

fun interface EnvironmentSessionAccess {
    operator fun invoke(session: MasterSession.Key): EnvironmentSessionDependencies?
}

class EnvironmentSessionDependencies(
    val getCiphers: GetCiphers,
    val getProfiles: GetProfiles,
    val getCollections: GetCollections,
    val addCipher: AddCipher,
    val trashCipher: TrashCipherById,
    val scope: CoroutineScope,
)
