package app.keemobile.kotpass.xml

import app.keemobile.kotpass.extensions.fromByteArray
import app.keemobile.kotpass.extensions.toByteArray
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.io.decodeBase64ToArray
import app.keemobile.kotpass.io.encodeBase64
import app.keemobile.kotpass.models.XmlContext
import nl.adaptivity.xmlutil.XmlReader
import kotlin.time.Instant

private const val EpochSecondsFromAD = 62135596800

internal fun XmlReader.readInstantOrNull(): Instant? {
    val name = localName
    val value = readTrimmedElementTextOrNull() ?: return null
    return try {
        parseInstant(value)
    } catch (_: Exception) {
        throw FormatError.InvalidXml("Element '$name' contains an invalid timestamp.")
    }
}

private fun parseInstant(text: String): Instant {
    // Check if ISO text or binary timestamp
    return if (text.indexOf(':') > 0) {
        Instant.parse(text)
    } else {
        val seconds = Long.fromByteArray(text.decodeBase64ToArray())
        Instant.fromEpochSeconds(seconds - EpochSecondsFromAD)
    }
}

internal fun Instant.marshal(context: XmlContext.Encode): String {
    val binary = context.version.major >= 4 && context !is XmlContext.Encode.Plain

    return if (binary) {
        (epochSeconds + EpochSecondsFromAD).toByteArray().encodeBase64()
    } else {
        toString()
    }
}
