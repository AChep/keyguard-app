package com.artemchep.keyguard.feature.p2psync.testing

import kotlinx.serialization.json.JsonObject
import kotlin.random.Random

/** The kinds of random user operations of the simulations. */
enum class EditKind {
    RENAME,
    RETYPE,
    SET_DATA,
    REMOVE_DATA,
}

/** Random user edits of email forwarder bodies. */
class RandomEdits(private val random: Random) {
    private val types = listOf("anonaddy", "simplelogin", "fastmail")
    private val keys = listOf("apiKey", "domain", "account")

    /** Returns [body] with one random edit of the [kind] applied. */
    fun apply(body: JsonObject, kind: EditKind = EditKind.entries.random(random)): JsonObject {
        val token = random.nextInt(1_000)
        return when (kind) {
            EditKind.RENAME -> ForwarderBodies.rename(body, "name-$token")
            EditKind.RETYPE -> ForwarderBodies.retype(body, types.random(random))
            EditKind.SET_DATA -> ForwarderBodies.setData(body, keys.random(random), "value-$token")
            EditKind.REMOVE_DATA -> ForwarderBodies.setData(body, keys.random(random), null)
        }
    }
}
