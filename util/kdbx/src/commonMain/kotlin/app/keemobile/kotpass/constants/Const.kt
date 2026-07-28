package app.keemobile.kotpass.constants

import okio.ByteString.Companion.toByteString

internal object Const {
    const val TagsSeparator = ";"
    val TagsSeparatorsRegex = Regex("""\s*[;,:]\s*""")

    fun bytes(vararg values: Number) = values
        .map(Number::toByte)
        .toByteArray()
        .toByteString()
}
