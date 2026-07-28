package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentFields
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class GpgRevocationKeyCandidatesTest {
    @Test
    fun `collects unique non-deleted native and legacy keys in encounter order`() {
        val ciphers = listOf(
            secret(
                id = "native",
                gpgKey = DSecret.GpgKey(publicKeyArmored = "public-native"),
            ),
            secret(
                id = "legacy",
                type = DSecret.Type.SecureNote,
                fields = listOf(publicKeyField("public-legacy")),
            ),
            secret(
                id = "duplicate",
                gpgKey = DSecret.GpgKey(publicKeyArmored = "public-native"),
            ),
            secret(
                id = "blank",
                gpgKey = DSecret.GpgKey(publicKeyArmored = "  "),
            ),
            secret(
                id = "missing",
                type = DSecret.Type.SecureNote,
            ),
            secret(
                id = "deleted",
                deletedDate = Instant.fromEpochSeconds(1),
                gpgKey = DSecret.GpgKey(publicKeyArmored = "public-deleted"),
            ),
        )

        assertEquals(
            listOf(
                GpgOpenPgpPublicKey("public-native"),
                GpgOpenPgpPublicKey("public-legacy"),
            ),
            ciphers.toGpgRevocationKeyCandidates(),
        )
    }

    private fun publicKeyField(value: String) = DSecret.Field(
        name = GpgAgentFields.PUBLIC_KEY_ARMORED,
        value = value,
        type = DSecret.Field.Type.Text,
    )

    private fun secret(
        id: String,
        deletedDate: Instant? = null,
        type: DSecret.Type = DSecret.Type.GpgKey,
        fields: List<DSecret.Field> = emptyList(),
        gpgKey: DSecret.GpgKey? = null,
    ) = DSecret(
        id = id,
        accountId = "account",
        folderId = null,
        organizationId = null,
        collectionIds = emptySet(),
        revisionDate = Instant.fromEpochSeconds(0),
        createdDate = null,
        archivedDate = null,
        deletedDate = deletedDate,
        service = BitwardenService(),
        name = "GPG key",
        notes = "",
        favorite = false,
        reprompt = false,
        synced = true,
        fields = fields,
        type = type,
        gpgKey = gpgKey,
    )
}
