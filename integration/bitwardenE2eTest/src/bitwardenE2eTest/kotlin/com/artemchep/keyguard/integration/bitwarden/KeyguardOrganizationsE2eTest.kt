package com.artemchep.keyguard.integration.bitwarden

import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.create
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrKey
import com.artemchep.keyguard.provider.bitwarden.entity.CipherEntity
import com.artemchep.keyguard.provider.bitwarden.entity.OrganizationUserStatusTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.OrganizationUserTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherCreateRequest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Drives Keyguard's organization support against a real server: the org key, collections and shared ciphers. */
class KeyguardOrganizationsE2eTest {
    @Test
    fun `owner sees the organization collection and org cipher`() = runE2eTest {
        val owner = accounts.register()
        val ownerToken = accounts.login(owner).accessToken
        val org = organizations.createOrganization(owner, ownerToken, name = ORGANIZATION_NAME)
        val session = keyguardLogin(owner)
        val created = createOrganizationCipher(session, org, name = CIPHER_NAME)

        val sync = keyguardSync(session)
        val organization = sync.profile.organizations.orEmpty().single()
        assertEquals(org.id, organization.id)
        assertEquals(ORGANIZATION_NAME, organization.name)
        assertEquals(OrganizationUserStatusTypeEntity.Confirmed, organization.status)
        assertEquals(OrganizationUserTypeEntity.Owner, organization.type)
        val collection = sync.collections.orEmpty().single { it.organizationId == org.id }
        assertEquals(org.collectionId, collection.id)
        val cipher = sync.ciphers.orEmpty().single { it.id == created.id }
        assertEquals(org.id, cipher.organizationId)
        assertContains(cipher.collectionIds.orEmpty(), org.collectionId)

        // The organization key is RSA-wrapped; Keyguard unwraps it with the user's private key.
        val cr = crypto(session, sync)
        assertContentEquals(org.key, decryptWith(cr, BitwardenCrKey.UserToken, organization.key))
        val orgKey = BitwardenCrKey.OrganizationToken(org.id)
        val collectionName = decryptWith(cr, orgKey, collection.name).decodeToString()
        assertEquals(VaultwardenOrganizations.DEFAULT_COLLECTION_NAME, collectionName)
        assertEquals(CIPHER_NAME, decryptWith(cr, orgKey, assertNotNull(cipher.name)).decodeToString())
    }

    @Test
    fun `confirmed member decrypts the shared cipher`() = runE2eTest {
        val owner = accounts.register()
        val ownerToken = accounts.login(owner).accessToken
        val org = organizations.createOrganization(owner, ownerToken, name = ORGANIZATION_NAME)
        val created = createOrganizationCipher(keyguardLogin(owner), org, name = CIPHER_NAME)
        val member = accounts.register()
        organizations.invite(ownerToken, org, member.email)
        val invited = organizations.membership(ownerToken, org, member.email)
        assertEquals(Membership.ACCEPTED, invited.status)
        organizations.confirm(ownerToken, org, invited.id, invited.userId)

        val session = keyguardLogin(member)
        val sync = keyguardSync(session)
        val organization = sync.profile.organizations.orEmpty().single { it.id == org.id }
        assertEquals(OrganizationUserStatusTypeEntity.Confirmed, organization.status)
        assertEquals(OrganizationUserTypeEntity.User, organization.type)
        val cipher = sync.ciphers.orEmpty().single { it.id == created.id }
        val orgKey = BitwardenCrKey.OrganizationToken(org.id)
        val name = decryptWith(crypto(session, sync), orgKey, assertNotNull(cipher.name)).decodeToString()
        assertEquals(CIPHER_NAME, name)
    }

    companion object {
        private const val ORGANIZATION_NAME = "Keyguard E2E"
        private const val CIPHER_NAME = "Shared"

        /** Creates a login cipher in the default collection of [org] through Keyguard's API. */
        private suspend fun VaultwardenFixture.createOrganizationCipher(
            session: KeyguardSession,
            org: TestOrganization,
            name: String,
        ): CipherEntity {
            val token = session.login.accessToken
            return session.env.api.ciphers.create(
                httpClient = httpClient,
                env = session.env,
                token = token,
                body = CipherCreateRequest(
                    cipher = loginCipherRequest(
                        key = org.key,
                        encryptedFor = accounts.userId(token),
                        name = name,
                        organizationId = org.id,
                    ),
                    collectionIds = setOf(org.collectionId),
                ),
            )
        }
    }
}
