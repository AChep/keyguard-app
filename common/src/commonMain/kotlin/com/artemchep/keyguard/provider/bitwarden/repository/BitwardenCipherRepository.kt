package com.artemchep.keyguard.provider.bitwarden.repository

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher

interface BitwardenCipherRepository : BaseRepository<BitwardenCipher> {
    fun getById(id: String): IO<BitwardenCipher?>
}
