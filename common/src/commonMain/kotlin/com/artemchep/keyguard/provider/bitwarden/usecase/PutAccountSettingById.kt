package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.combineIo
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.io.parallel
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenProfileRepository
import com.artemchep.keyguard.provider.bitwarden.repository.ServiceTokenRepository
import kotlinx.coroutines.Dispatchers

/**
 * @author Artem Chepurnyi
 */
internal class PutAccountSettingById<T>(
    private val logRepository: LogRepository,
    private val tokenRepository: ServiceTokenRepository,
    private val profileRepository: BitwardenProfileRepository,
    private val tag: String,
    private val changeLogSubject: String,
    private val putBitwarden: (T, BitwardenToken, BitwardenProfile) -> IO<Unit>,
    private val putKeePass: (T, KeePassToken, BitwardenProfile) -> IO<Unit>,
) {
    operator fun invoke(
        request: Map<AccountId, T>,
    ): IO<Unit> = request
        .entries
        .map { entry ->
            putAccountSettingIo(
                accountId = entry.key,
                value = entry.value,
            )
        }
        .parallel(Dispatchers.Default)
        .map {
            // Do not return the result.
        }

    private fun putAccountSettingIo(
        accountId: AccountId,
        value: T,
    ) = combineIo(
        tokenRepository
            .getById(accountId),
        profileRepository
            .getById(accountId)
            .toIO(),
    ) { token, profile ->
        val accountToken = requireNotNull(token) {
            "Failed to find the account tokens!"
        }
        val accountProfile = requireNotNull(profile) {
            "Failed to find the account profile!"
        }

        when (accountToken) {
            is BitwardenToken -> putBitwarden(
                value,
                accountToken,
                accountProfile,
            )

            is KeePassToken -> putKeePass(
                value,
                accountToken,
                accountProfile,
            )
        }
            .measure { duration, _ ->
                val msg = "Submitted the $changeLogSubject change to remote in $duration."
                logRepository.post(
                    tag = tag,
                    message = msg,
                )
            }
            .bind()
    }
}
