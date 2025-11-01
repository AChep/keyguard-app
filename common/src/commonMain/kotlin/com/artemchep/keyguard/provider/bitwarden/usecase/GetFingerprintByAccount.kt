package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.PrivateKey
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.GetFingerprint
import com.artemchep.keyguard.common.usecase.GetFingerprintByAccount
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.coroutines.CoroutineContext

/**
 * @author Artem Chepurnyi
 */
class GetFingerprintByAccountImpl(
    private val profileRepository: BitwardenProfileRepository,
    private val base64Service: Base64Service,
    private val getFingerprint: GetFingerprint,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : GetFingerprintByAccount {
    companion object {
        private const val TAG = "GetFingerprint.bitwarden"
    }

    private data class ProfileFingerprintData(
        val profileId: String,
        val privateKeyBase64: String,
    )

    constructor(directDI: DirectDI) : this(
        profileRepository = directDI.instance(),
        base64Service = directDI.instance(),
        getFingerprint = directDI.instance(),
    )

    override fun invoke(
        accountId: AccountId,
    ): Flow<String> = profileRepository
        .getById(accountId)
        .map { profileOrNull ->
            val profile = requireNotNull(profileOrNull) { "Profile not found." }
            ProfileFingerprintData(
                profileId = profile.profileId,
                privateKeyBase64 = profile.privateKeyBase64,
            )
        }
        .distinctUntilChanged()
        .map { args ->
            val privateKey = kotlin.run {
                val data = base64Service.decode(args.privateKeyBase64)
                PrivateKey(data)
            }
            getFingerprint(
                privateKey,
                args.profileId,
            ).bind()
        }
        .flowOn(dispatcher)
}
