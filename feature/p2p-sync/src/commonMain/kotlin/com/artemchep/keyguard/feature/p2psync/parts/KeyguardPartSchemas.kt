package com.artemchep.keyguard.feature.p2psync.parts

/**
 * The part schemas of the records Keyguard syncs; each
 * [com.artemchep.keyguard.feature.p2psync.model.EntryKind] names its own.
 */
object KeyguardPartSchemas {
    /** The part of the service and the settings of an email forwarder. */
    const val EMAIL_FORWARDER_CONFIG_PART: String = "/data"

    /**
     * The parts of an email forwarder: `{"name", "type", "data": {key: value}}`. The service
     * and its settings change as one part, because an API key and the domain or account it
     * belongs to must never mix from two concurrent edits.
     */
    val emailForwarder: PartSchema = PartSchema(
        parts = listOf(
            PartSpec.of("/name"),
            PartSpec(
                key = EMAIL_FORWARDER_CONFIG_PART,
                paths = listOf("/type", "/data").map(JsonPointer::parse),
            ),
        ),
    )

    /**
     * The parts of a wordlist: `{"name", "words"}`, where the words are the id of the blob
     * that holds them, see [com.artemchep.keyguard.feature.p2psync.session.BlobExchange].
     */
    val wordlist: PartSchema = PartSchema(
        parts = listOf(
            PartSpec.of("/name"),
            PartSpec.of("/words"),
        ),
    )

    /** The part of the rule of a URL override: what it matches, and what it makes of it. */
    const val URL_OVERRIDE_RULE_PART: String = "/command"

    /**
     * The parts of a URL override: `{"name", "regex", "command", "enabled"}`. The regex and
     * the command change as one part, because a command is written for the URLs its regex
     * matches, and the two must never mix from two concurrent edits.
     */
    val urlOverride: PartSchema = PartSchema(
        parts = listOf(
            PartSpec.of("/name"),
            PartSpec(
                key = URL_OVERRIDE_RULE_PART,
                paths = listOf("/regex", "/command").map(JsonPointer::parse),
            ),
            PartSpec.of("/enabled"),
        ),
    )

    /** The part of an object key: the key, and the devices it covers. */
    const val OBJECT_KEY_PART: String = "/key"

    /**
     * The parts of an object key: `{"key", "covers"}`, see
     * [com.artemchep.keyguard.feature.p2psync.model.ObjectKey]. The key and the devices it
     * covers change as one part, because a key must never take the devices of another
     * key from a concurrent edit.
     */
    val objectKey: PartSchema = PartSchema(
        parts = listOf(
            PartSpec(
                key = OBJECT_KEY_PART,
                paths = listOf("/key", "/covers").map(JsonPointer::parse),
            ),
        ),
    )
}
