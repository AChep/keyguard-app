package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.CipherFieldSwitchToggleRequest
import com.artemchep.keyguard.common.usecase.CipherFieldSwitchToggle
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.fields
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class CipherFieldSwitchToggleImpl(
    private val modifyCipherById: ModifyCipherById,
) : CipherFieldSwitchToggle {
    constructor(directDI: DirectDI) : this(
        modifyCipherById = directDI.instance(),
    )

    override fun invoke(
        cipherIdsToRequests: Map<String, List<CipherFieldSwitchToggleRequest>>,
    ): IO<Unit> = modifyCipherById(
        cipherIdsToRequests
            .keys,
    ) { model ->
        val fieldsTargets = cipherIdsToRequests[model.cipherId].orEmpty()
        val fields = model.data_.fields
            .mapIndexed { index, field ->
                if (field.type != BitwardenCipher.Field.Type.Boolean) {
                    return@mapIndexed field
                }

                val target = fieldsTargets
                    .firstOrNull { it.fieldIndex == index && it.fieldName == field.name }
                if (target != null) {
                    field.copy(value = target.value.toString())
                } else {
                    field
                }
            }
        var new = model
        new = new.copy(
            data_ = BitwardenCipher.fields.set(new.data_, fields),
        )
        new
    }.map { Unit }
}
