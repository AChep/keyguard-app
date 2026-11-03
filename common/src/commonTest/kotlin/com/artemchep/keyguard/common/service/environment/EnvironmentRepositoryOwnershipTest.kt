package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.feature.confirmation.organization.FolderInfo
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentFolder
import com.artemchep.keyguard.feature.environments.EnvironmentOwnership
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class EnvironmentRepositoryOwnershipTest {
    private val collection = DCollection(
        id = "collection", externalId = null, organizationId = "organization", accountId = "account",
        revisionDate = Instant.fromEpochSeconds(0), name = "Collection", readOnly = false, hidePasswords = false,
    )

    @Test
    fun `a new environment is saved with the chosen ownership`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        val ownership = EnvironmentOwnership(
            folder = EnvironmentFolder.New("Work"),
            organizationId = "organization",
            collectionIds = setOf("collection"),
        )
        fixture.repository.save(null, "account", "name", EnvironmentDefinition(), ownership = ownership)
        val request = fixture.saved.single()
        assertEquals(
            CreateRequest.Ownership2("account", FolderInfo.New("Work"), "organization", setOf("collection")),
            request.ownership2,
        )
    }

    @Test
    fun `an edit keeps the stored ownership unless the folder changes`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ciphers.value = listOf(
            fixture.secret.copy(
                folderId = "folder",
                organizationId = "organization",
                collectionIds = setOf("collection"),
            ),
        )
        fixture.collections.value = listOf(collection)
        fixture.ready()
        val stored = fixture.repository.load("environment").ownership
        assertEquals(
            EnvironmentOwnership(EnvironmentFolder.Id("folder"), "organization", setOf("collection")),
            stored,
        )

        fixture.repository.save("environment", "account", "name", EnvironmentDefinition())
        assertEquals(
            CreateRequest.Ownership2("account", FolderInfo.Id("folder"), "organization", setOf("collection")),
            fixture.saved.last().ownership2,
        )

        val moved = stored.copy(folder = EnvironmentFolder.None)
        fixture.repository.save("environment", "account", "name", EnvironmentDefinition(), ownership = moved)
        assertEquals(
            CreateRequest.Ownership2("account", FolderInfo.None, "organization", setOf("collection")),
            fixture.saved.last().ownership2,
        )
    }

    @Test
    fun `an edit can not move the environment to other collections`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        val ownership = EnvironmentOwnership(organizationId = "organization", collectionIds = setOf("collection"))
        assertEquals("unsupported_environment", assertFailsWith<EnvironmentServiceException> {
            fixture.repository.save("environment", "account", "name", EnvironmentDefinition(), ownership = ownership)
        }.code)
        assertTrue(fixture.saved.isEmpty())
    }
}
