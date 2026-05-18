package com.artemchep.keyguard.provider.bitwarden.entity

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.entity.serializer.IntEnum
import kotlin.enums.enumEntries
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(CipherTypeEntitySerializer::class)
enum class CipherTypeEntity(
    override val int: Int,
) : IntEnum {
    Login(1),
    SecureNote(2),
    Card(3),
    Identity(4),
    SshKey(5),
    Unknown(-1),
    ;

    companion object
}

object CipherTypeEntitySerializer : KSerializer<CipherTypeEntity> {
    override val descriptor = Int.serializer().descriptor

    private val choicesByInt =
        enumEntries<CipherTypeEntity>()
            .filter { it != CipherTypeEntity.Unknown }
            .associateBy { it.int }

    override fun serialize(
        encoder: Encoder,
        value: CipherTypeEntity,
    ) {
        if (value == CipherTypeEntity.Unknown) {
            throw SerializationException("Cannot serialize unknown cipher type.")
        }
        encoder.encodeInt(value.int)
    }

    override fun deserialize(decoder: Decoder): CipherTypeEntity {
        val value = decoder.decodeInt()
        return choicesByInt[value] ?: CipherTypeEntity.Unknown
    }
}

fun CipherTypeEntity.Companion.of(
    model: BitwardenCipher.Type,
) = when (model) {
    BitwardenCipher.Type.Login -> CipherTypeEntity.Login
    BitwardenCipher.Type.SecureNote -> CipherTypeEntity.SecureNote
    BitwardenCipher.Type.Card -> CipherTypeEntity.Card
    BitwardenCipher.Type.Identity -> CipherTypeEntity.Identity
    BitwardenCipher.Type.SshKey -> CipherTypeEntity.SshKey
}

fun CipherTypeEntity.Companion.of(
    model: DSecret.Type,
) = when (model) {
    DSecret.Type.Login -> CipherTypeEntity.Login
    DSecret.Type.SecureNote -> CipherTypeEntity.SecureNote
    DSecret.Type.Card -> CipherTypeEntity.Card
    DSecret.Type.Identity -> CipherTypeEntity.Identity
    DSecret.Type.SshKey -> CipherTypeEntity.SshKey
    DSecret.Type.None -> null
}

fun CipherTypeEntity.domain() = when (this) {
    CipherTypeEntity.Login -> BitwardenCipher.Type.Login
    CipherTypeEntity.SecureNote -> BitwardenCipher.Type.SecureNote
    CipherTypeEntity.Card -> BitwardenCipher.Type.Card
    CipherTypeEntity.Identity -> BitwardenCipher.Type.Identity
    CipherTypeEntity.SshKey -> BitwardenCipher.Type.SshKey
    CipherTypeEntity.Unknown -> error("Unknown cipher type cannot be converted to a domain model.")
}
