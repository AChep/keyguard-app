package app.keemobile.kotpass.xml

import app.keemobile.kotpass.extensions.fromByteArray
import app.keemobile.kotpass.extensions.getText
import app.keemobile.kotpass.extensions.toByteArray
import app.keemobile.kotpass.io.decodeBase64ToArray
import app.keemobile.kotpass.io.encodeBase64
import app.keemobile.kotpass.models.XmlContext
import org.redundent.kotlin.xml.Node
import kotlin.time.Instant

private const val EpochSecondsFromAD = 62135596800

internal fun Node.getInstant(): Instant? = getText()?.let { text ->
    // Check if ISO text or binary timestamp
    if (text.indexOf(':') > 0) {
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
