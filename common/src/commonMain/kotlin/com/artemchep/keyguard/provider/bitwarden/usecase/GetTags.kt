package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DTag
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetTags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class GetTagsImpl(
    private val getCiphers: GetCiphers,
) : GetTags {
    companion object {
        private const val TAG = "GetTags"
    }

    constructor(directDI: DirectDI) : this(
        getCiphers = directDI.instance(),
    )

    override fun invoke(): Flow<List<DTag>> = getCiphers()
        .map { ciphers ->
            ciphers
                .asSequence()
                .flatMap {
                    it.tags
                }
                .map { tag ->
                    DTag(
                        name = tag,
                    )
                }
                .distinct()
                .toList()
        }
}
