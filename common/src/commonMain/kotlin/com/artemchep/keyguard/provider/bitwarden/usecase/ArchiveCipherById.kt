package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.usecase.ArchiveCipherById
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.archivedDate
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import kotlin.time.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class ArchiveCipherByIdImpl(
    private val modifyCipherById: ModifyCipherById,
) : ArchiveCipherById {
    companion object {
        private const val TAG = "ArchiveCipherById.bitwarden"

        // As of right now it seems like Bitwarden is not enforcing the
        // archive functionality under the paywall lock.
        private const val RESPECT_BW_ARCHIVE_PREMIUM_GATE = false
    }

    constructor(directDI: DirectDI) : this(
        modifyCipherById = directDI.instance(),
    )

    override fun invoke(
        cipherIds: Set<String>,
    ): IO<Unit> = performArchiveCipher(
        cipherIds = cipherIds,
    ).map { Unit }

    private fun performArchiveCipher(
        cipherIds: Set<String>,
    ) = modifyCipherById(
        cipherIds,
    ) { model ->
        if (RESPECT_BW_ARCHIVE_PREMIUM_GATE) {
            // Bitwarden archive/un-archive functionality is
            // gated behind the paywall.
            val premium = hasPremium.bind()
            if (!premium) {
                return@modifyCipherById model
            }
        }

        var new = model
        // Add the archived instant to mark the model as archived.
        val now = Clock.System.now()
        new = new.copy(
            data_ = BitwardenCipher.archivedDate.set(new.data_, now),
        )
        new
    }
}
