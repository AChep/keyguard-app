package com.artemchep.keyguard.provider.bitwarden.usecase

import app.keemobile.kotpass.database.modifiers.modifyMeta
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.keepass.openKeePassDatabase
import com.artemchep.keyguard.common.service.keepass.saveKeePassDatabase
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.name
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenProfileRepository
import org.kodein.di.DirectDI
import org.kodein.di.instance

internal actual fun createPutKeePassAccountNameById(
    directDI: DirectDI,
): PutKeePassAccountNameById = PutKeePassAccountNameByIdImpl(directDI)

internal class PutKeePassAccountNameByIdImpl(
    directDI: DirectDI,
) : PutKeePassAccountNameById {
    private val profileRepository: BitwardenProfileRepository = directDI.instance()
    private val base64Service: Base64Service = directDI.instance()
    private val fileService: FileService = directDI.instance()

    override operator fun invoke(
        accountName: String,
        token: KeePassToken,
        profile: BitwardenProfile,
    ): IO<Unit> = ioEffect {
        val curDatabase = openKeePassDatabase(
            token = token,
            fileService = fileService,
            base64Service = base64Service,
        )
        val newDatabase = curDatabase.modifyMeta {
            copy(
                name = accountName,
            )
        }
        saveKeePassDatabase(
            fileService = fileService,
            token = token,
            database = newDatabase,
        )

        // TODO: Instead of using cached profile model, use the one returned
        //  from the avatar update call.
        val newProfile = BitwardenProfile.name.set(profile, accountName)
        profileRepository.put(newProfile)
            .bind()
    }
}
