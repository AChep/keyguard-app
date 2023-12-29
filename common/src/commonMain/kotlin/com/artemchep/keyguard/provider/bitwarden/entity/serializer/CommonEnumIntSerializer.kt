package com.artemchep.keyguard.provider.bitwarden.entity.serializer

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.reflect.KClass

interface IntEnum {
    val int: Int
}

open class CommonEnumIntSerializer<T>(
    clazz: KClass<T>,
) : KSerializer<T> where T : Enum<T>, T : IntEnum {
    override val descriptor = Int.serializer().descriptor

    private val choices = clazz.java.enumConstants!!

    init {
        val uniqueChoices = choices.distinctBy { it.int }
        require(uniqueChoices.size == choices.size)
    }

    final override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeInt(value.int)
    }

    final override fun deserialize(decoder: Decoder): T {
        val value = decoder.decodeInt()
        return choices.first { it.int == value }
    }
}
