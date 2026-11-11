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

    /** The part of the rule of a blocked URI: the URI, and the mode that matches it. */
    const val URL_BLOCK_RULE_PART: String = "/uri"

    /**
     * The parts of a blocked URI: `{"name", "description", "uri", "mode", "enabled",
     * "exposed"}`. The URI and the mode change as one part, because a URI is written for the
     * mode that matches it, and the two must never mix from two concurrent edits.
     */
    val urlBlock: PartSchema = PartSchema(
        parts = listOf(
            PartSpec.of("/name"),
            PartSpec.of("/description"),
            PartSpec(
                key = URL_BLOCK_RULE_PART,
                paths = listOf("/uri", "/mode").map(JsonPointer::parse),
            ),
            PartSpec.of("/enabled"),
            PartSpec.of("/exposed"),
        ),
    )

    /** The part of a privileged app: its package, and the certificate that signs it. */
    const val PRIVILEGED_APP_IDENTITY_PART: String = "/packageName"

    /**
     * The parts of a privileged app: `{"packageName", "certFingerprintSha256"}`. The package
     * and the fingerprint change as one part, because a fingerprint is trusted for the
     * package it signs, and the two must never mix from two concurrent edits.
     */
    val privilegedApp: PartSchema = PartSchema(
        parts = listOf(
            PartSpec(
                key = PRIVILEGED_APP_IDENTITY_PART,
                paths = listOf("/packageName", "/certFingerprintSha256").map(JsonPointer::parse),
            ),
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
