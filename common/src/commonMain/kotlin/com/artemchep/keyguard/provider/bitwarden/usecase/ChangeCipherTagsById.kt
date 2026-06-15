package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.usecase.ChangeCipherTagsById
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.tags
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import org.kodein.di.DirectDI
import org.kodein.di.instance

class ChangeCipherTagsByIdImpl(
    private val modifyCipherById: ModifyCipherById,
) : ChangeCipherTagsById {
    constructor(directDI: DirectDI) : this(
        modifyCipherById = directDI.instance(),
    )

    override fun invoke(
        cipherIdsToTags: Map<String, List<String>>,
    ): IO<Unit> = modifyCipherById(
        cipherIdsToTags.keys,
    ) { model ->
        val tags = normalizeCipherTags(cipherIdsToTags.getValue(model.cipherId))
            .map { tag ->
                BitwardenCipher.Tag(
                    name = tag,
                )
            }
        model.copy(
            data_ = BitwardenCipher.tags.set(model.data_, tags),
        )
    }.map { Unit }
}

internal fun normalizeCipherTags(
    tags: List<String>,
): List<String> {
    val seen = linkedSetOf<String>()
    return buildList {
        tags.forEach { tag ->
            val value = tag.trim()
            if (value.isBlank()) {
                return@forEach
            }
            if (seen.add(value)) {
                add(value)
            }
        }
    }
}
