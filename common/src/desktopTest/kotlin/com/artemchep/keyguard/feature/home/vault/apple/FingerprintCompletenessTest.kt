package com.artemchep.keyguard.feature.home.vault.apple

import com.artemchep.keyguard.common.model.DSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FingerprintCompletenessTest {
    private val expectedExtractorNames = listOf(
        "id",
        "accountId",
        "organizationId",
        "folderId",
        "collectionIds",
        "name",
        "revisionDate",
        "createdDate",
        "deletedDate",
        "archivedDate",
        "favorite",
        "reprompt",
        "synced",
        "hasError",
        "tags",
        "uris",
        "customIcon",
        "notes",
        "type",
        "attachments",
        "login.username",
        "login.password",
        "login.passwordStrength",
        "login.passwordRevisionDate",
        "login.totp",
        "login.fido2Credentials",
        "card.brand",
        "card.number",
        "identity.firstName",
        "sshKey.fingerprint",
        "gpgKey.fingerprint",
    )

    private val coveredFields: Map<String, List<String>> = mapOf(
        "id" to listOf("id"),
        "accountId" to listOf("accountId"),
        "folderId" to listOf("folderId"),
        "organizationId" to listOf("organizationId"),
        "collectionIds" to listOf("collectionIds"),
        "revisionDate" to listOf("revisionDate"),
        "createdDate" to listOf("createdDate"),
        "archivedDate" to listOf("archivedDate"),
        "deletedDate" to listOf("deletedDate"),
        "customIcon" to listOf("customIcon"),
        "name" to listOf("name"),
        "notes" to listOf("notes"),
        "favorite" to listOf("favorite"),
        "reprompt" to listOf("reprompt"),
        "synced" to listOf("synced"),
        "tags" to listOf("tags"),
        "uris" to listOf("uris"),
        "attachments" to listOf("attachments"),
        "type" to listOf("type"),
        "login" to listOf(
            "login.username",
            "login.password",
            "login.passwordStrength",
            "login.passwordRevisionDate",
            "login.totp",
            "login.fido2Credentials",
        ),
        "card" to listOf("card.brand", "card.number"),
        "identity" to listOf("identity.firstName"),
        "sshKey" to listOf("sshKey.fingerprint"),
        "gpgKey" to listOf("gpgKey.fingerprint"),
    )

    private val excludedFields: Map<String, String> = mapOf(
        "service" to "row reads only derived hasError (covered) + accent seed via sync writes",
        // Decryption material; the row projection never renders it.
        "keyBase64" to "crypto material, not row-rendered",
        // Watchtower suppressions; read by watchtower filters/actions only.
        "ignoredAlerts" to "watchtower-only, not row-rendered",
        // Cipher links ("Linked items" / "Referenced by") render in the cipher
        // detail view, never in the row.
        "links" to "detail-view only, not row-rendered",
        // Custom fields render in the cipher detail view, never in the row.
        "fields" to "detail-view only, not row-rendered",
        // Password history renders in its own screen, never in the row.
        "passwordHistory" to "history-screen only, not row-rendered",
    )

    @Test
    fun `fingerprint extractor names match the pinned expectation`() {
        assertEquals(
            expectedExtractorNames,
            vaultRowFingerprintInputs.map { it.first },
            "vaultRowFingerprintInputs changed; re-review the coverage " +
                    "mapping of FingerprintCompletenessTest in the same commit",
        )
    }

    @Test
    fun `every DSecret constructor property is fingerprinted or explicitly excluded`() {
        // The pinned union of the two buckets IS the expected primary
        // constructor; reflection then proves the pin matches the real
        // class, so a new / renamed DSecret field fails here.
        val pinnedFields = coveredFields.keys + excludedFields.keys
        assertEquals(
            emptySet(),
            coveredFields.keys.intersect(excludedFields.keys),
            "a field must be covered OR excluded, never both",
        )

        // (1) Arity: a Kotlin data class declares one componentN per
        // primary-constructor property.
        val componentCount = DSecret::class.java.declaredMethods
            .asSequence()
            .filter { it.name.matches(Regex("component\\d+")) }
            .map { it.name }
            .distinct()
            .count()
        assertEquals(
            componentCount,
            pinnedFields.size,
            "DSecret's primary constructor has $componentCount properties but " +
                    "the coverage mapping pins ${pinnedFields.size}; a field was " +
                    "added or removed — decide whether the row projection reads " +
                    "it (extend vaultRowFingerprintInputs + coveredFields) or " +
                    "not (add an exclusion with a justification)",
        )

        // (2) Existence: every pinned field resolves to a real property
        // getter, so renames cannot slip through the arity check.
        val methodNames = DSecret::class.java.methods
            .map { it.name }
            .toSet()
        pinnedFields.forEach { field ->
            val getter = "get" + field.replaceFirstChar { it.uppercaseChar() }
            assertTrue(
                getter in methodNames,
                "pinned DSecret field '$field' has no '$getter' accessor; " +
                        "was it renamed or removed?",
            )
        }

        // (3) Consistency: every covering extractor name really exists in
        // vaultRowFingerprintInputs, and no extractor is left unmapped.
        val extractorNames = vaultRowFingerprintInputs.map { it.first }.toSet()
        coveredFields.forEach { (field, extractors) ->
            extractors.forEach { extractor ->
                assertTrue(
                    extractor in extractorNames,
                    "field '$field' claims extractor '$extractor' which is not " +
                            "in vaultRowFingerprintInputs",
                )
            }
        }
        val mappedExtractors = coveredFields.values.flatten().toSet() +
                // Derived off `service` + `revisionDate`; justified with the
                // `service` exclusion above.
                "hasError"
        val unmapped = extractorNames - mappedExtractors
        assertEquals(
            emptySet(),
            unmapped,
            "extractors not attributed to any DSecret field",
        )
    }
}
