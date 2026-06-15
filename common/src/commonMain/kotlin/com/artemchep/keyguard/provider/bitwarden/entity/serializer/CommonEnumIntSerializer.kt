package com.artemchep.keyguard.provider.bitwarden.entity.serializer

import kotlin.enums.enumEntries
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

interface IntEnum {
    val int: Int
}

open class CommonEnumIntSerializer<T>(
    choices: Iterable<T>,
) : KSerializer<T> where T : Enum<T>, T : IntEnum {
    constructor(choices: Array<T>) : this(choices.asIterable())

    override val descriptor = Int.serializer().descriptor

    private val choiceList = choices.toList()
    private val choicesByInt = choiceList.associateBy { it.int }

    init {
        require(choicesByInt.size == choiceList.size)
    }

    final override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeInt(value.int)
    }

    final override fun deserialize(decoder: Decoder): T {
        val value = decoder.decodeInt()
        return choicesByInt[value]
            ?: throw SerializationException("Unknown ${descriptor.serialName} value: $value")
    }
}

inline fun <reified T> commonEnumIntSerializer(): KSerializer<T>
    where T : Enum<T>, T : IntEnum =
    CommonEnumIntSerializer(enumEntries<T>())
