package com.artemchep.keyguard.integration.bitwarden.cli

import com.artemchep.keyguard.integration.bitwarden.Membership
import com.artemchep.keyguard.integration.bitwarden.TestOrganization
import com.artemchep.keyguard.integration.bitwarden.VaultwardenApi
import com.artemchep.keyguard.integration.bitwarden.VaultwardenOrganizations
import com.artemchep.keyguard.integration.bitwarden.loginCipherRequest
import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.create
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherCreateRequest
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Creates an organization owned by the account, with a login in its collection, and
 * confirms an existing account as a member.
 */
internal suspend fun VaultwardenApi.org(command: BwCommand.Org): BwResult {
    val owner = unlock(command.account.email, command.account.password)
    val org = organizations.createOrganization(owner.account, owner.accessToken, command.name)
    val cipher = env.api.ciphers.create(
        httpClient = httpClient,
        env = env,
        token = owner.accessToken,
        body = CipherCreateRequest(
            cipher = loginCipherRequest(
                key = org.key,
                encryptedFor = accounts.userId(owner.accessToken),
                name = SHARED_LOGIN,
                organizationId = org.id,
            ),
            collectionIds = setOf(org.collectionId),
        ),
    )
    val member = command.member?.let { email -> addMember(owner.accessToken, org, email) }

    val collection = VaultwardenOrganizations.DEFAULT_COLLECTION_NAME
    val lines = buildList {
        add("Created the organization ${command.name} (${org.id}), owned by ${owner.account.email}.")
        add("Added $SHARED_LOGIN to its $collection collection.")
        if (command.member != null) {
            add("Confirmed ${command.member} as a member; a sync shows them the organization.")
        }
    }
    val json = bwJson(baseUrl) {
        put("organizationId", org.id)
        put("collectionId", org.collectionId)
        put("cipherId", cipher.id)
        if (member != null) {
            putJsonObject("member") {
                put("email", command.member)
                put("status", member.statusName())
            }
        }
    }
    return BwResult(lines, json)
}

/** Invites [email], then confirms it. Without mail the server accepts the invite of a registered account. */
private suspend fun VaultwardenApi.addMember(
    ownerToken: String,
    org: TestOrganization,
    email: String,
): Membership {
    organizations.invite(ownerToken, org, email)
    val invited = organizations.membership(ownerToken, org, email)
    check(invited.status == Membership.ACCEPTED) {
        "$email did not accept the invite to the organization ${org.id} (status: ${invited.statusName()}). " +
            "Register $email first; a server with mail needs them to accept by mail."
    }
    organizations.confirm(ownerToken, org, invited.id, invited.userId)
    return organizations.membership(ownerToken, org, email)
}

private fun Membership.statusName(): String = when (status) {
    Membership.REVOKED -> "revoked"
    Membership.INVITED -> "invited"
    Membership.ACCEPTED -> "accepted"
    Membership.CONFIRMED -> "confirmed"
    else -> status.toString()
}

private const val SHARED_LOGIN = "Shared Login"
