package com.artemchep.keyguard.feature.home.vault.search.engine

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.fileName
import com.artemchep.keyguard.feature.auth.common.util.ValidationEmail
import com.artemchep.keyguard.feature.auth.common.util.validateEmail
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultTextField

internal fun hotFieldValues(
    source: DSecret,
): Map<VaultTextField, List<String>> = buildMap {
    put(VaultTextField.Title, listOf(source.name.trim()))

    source.uris
        .map(DSecret.Uri::uri)
        .filter(String::isNotBlank)
        .takeIf(List<String>::isNotEmpty)
        ?.let { urls ->
            put(VaultTextField.Url, urls)
        }

    buildList {
        source.uris
            .mapNotNull { extractUriHost(it.uri) }
            .filter(String::isNotBlank)
            .let(::addAll)
        source.login?.fido2Credentials
            .orEmpty()
            .map(DSecret.Login.Fido2Credentials::rpId)
            .filter(String::isNotBlank)
            .let(::addAll)
    }
        .takeIf(List<String>::isNotEmpty)
        ?.let { values ->
            put(VaultTextField.Host, values)
            put(VaultTextField.PasskeyRpId, values)
        }

    buildList {
        listOfNotNull(
            source.identity?.firstName,
            source.identity?.middleName,
            source.identity?.lastName,
        )
            .filter(String::isNotBlank)
            .takeIf(List<String>::isNotEmpty)
            ?.joinToString(separator = " ")
            ?.let(::add)
    }
        .takeIf(List<String>::isNotEmpty)
        ?.let { values ->
            put(VaultTextField.IdentityName, values)
        }

    buildList {
        source.identity?.phone
            ?.takeIf { it.isNotBlank() }
            ?.let(::add)
    }
        .takeIf(List<String>::isNotEmpty)
        ?.let { values ->
            put(VaultTextField.Phone, values)
        }

    buildList {
        source.card?.cardholderName
            ?.takeIf { it.isNotBlank() }
            ?.let(::add)
    }
        .takeIf(List<String>::isNotEmpty)
        ?.let { values ->
            put(VaultTextField.CardholderName, values)
        }

    buildList {
        source.card?.brand
            ?.takeIf { it.isNotBlank() }
            ?.let(::add)
    }
        .takeIf(List<String>::isNotEmpty)
        ?.let { values ->
            put(VaultTextField.CardBrand, values)
        }

    buildList {
        source.login?.fido2Credentials
            .orEmpty()
            .mapNotNull(DSecret.Login.Fido2Credentials::userDisplayName)
            .filter(String::isNotBlank)
            .let(::addAll)
    }
        .takeIf(List<String>::isNotEmpty)
        ?.let { values ->
            put(VaultTextField.PasskeyDisplayName, values)
        }

    buildList {
        source.login?.username
            ?.takeIf { it.isNotBlank() }
            ?.let(::add)
        source.identity?.username
            ?.takeIf { it.isNotBlank() }
            ?.let(::add)
    }
        .takeIf(List<String>::isNotEmpty)
        ?.let { values ->
            put(VaultTextField.Username, values)
        }

    buildList {
        source.login?.username
            ?.takeIf { validateEmail(it) == ValidationEmail.OK }
            ?.let(::add)
        source.identity?.email
            ?.takeIf { it.isNotBlank() }
            ?.let(::add)
    }
        .takeIf(List<String>::isNotEmpty)
        ?.let { values ->
            put(VaultTextField.Email, values)
        }

    searchableCustomFieldNames(source)
        .takeIf(List<String>::isNotEmpty)
        ?.let { values ->
            put(VaultTextField.FieldName, values)
        }

    source.attachments
        .map { attachment -> attachment.fileName() }
        .filter(String::isNotBlank)
        .takeIf(List<String>::isNotEmpty)
        ?.let { values ->
            put(VaultTextField.AttachmentName, values)
        }
}

internal fun searchableCustomFieldName(
    field: DSecret.Field,
): String? = when (field.type) {
    DSecret.Field.Type.Text,
    DSecret.Field.Type.Hidden,
    DSecret.Field.Type.Boolean,
        -> joinSearchParts(field.name)

    DSecret.Field.Type.Linked -> null
}

internal fun presentationCustomFieldName(
    field: DSecret.Field,
): String? = when (field.type) {
    DSecret.Field.Type.Text,
    DSecret.Field.Type.Hidden,
    DSecret.Field.Type.Boolean,
        -> joinSearchParts(field.name)

    DSecret.Field.Type.Linked -> null
}

internal fun searchableCustomFieldNames(
    source: DSecret,
): List<String> = source.fields
    .mapNotNull(::searchableCustomFieldName)
    .orEmpty()

internal fun searchableCustomFieldValue(
    field: DSecret.Field,
): String? = when (field.type) {
    DSecret.Field.Type.Text,
    DSecret.Field.Type.Hidden,
    DSecret.Field.Type.Boolean,
        -> joinSearchParts(field.value)

    DSecret.Field.Type.Linked -> null
}

internal data class SearchableCustomFieldMatch(
    val field: DSecret.Field,
    val name: String?,
    val value: String?,
)

internal fun findSearchableCustomFieldMatch(
    source: DSecret,
    raw: String,
): SearchableCustomFieldMatch? = source.fields
    .firstNotNullOfOrNull { field ->
        val name = searchableCustomFieldName(field)
        val value = searchableCustomFieldValue(field)
        if (name != raw && value != raw) {
            return@firstNotNullOfOrNull null
        }
        SearchableCustomFieldMatch(
            field = field,
            name = name,
            value = value,
        )
    }

internal fun coldFieldValues(
    source: DSecret,
): Map<VaultTextField, List<String>> = buildMap {
    source.notes
        .takeIf { it.isNotBlank() && !source.reprompt }
        ?.let { put(VaultTextField.Note, listOf(it)) }

    buildList {
        source.fields.forEach { field ->
            searchableCustomFieldName(field)?.let(::add)
            searchableCustomFieldValue(field)?.let(::add)
        }
    }
        .takeIf(List<String>::isNotEmpty)
        ?.let { put(VaultTextField.Field, it) }

    buildList {
        source.sshKey?.privateKey
            ?.takeIf(String::isNotBlank)
            ?.let(::add)
        source.sshKey?.publicKey
            ?.takeIf(String::isNotBlank)
            ?.let(::add)
        source.sshKey?.fingerprint
            ?.takeIf(String::isNotBlank)
            ?.let(::add)
    }
        .takeIf(List<String>::isNotEmpty)
        ?.let { put(VaultTextField.Ssh, it) }

    source.login?.password
        ?.takeIf { !source.reprompt && it.isNotBlank() }
        ?.let { put(VaultTextField.Password, listOf(it)) }

    source.card?.number
        ?.takeIf { !source.reprompt && it.isNotBlank() }
        ?.let { put(VaultTextField.CardNumber, listOf(it)) }
}

internal fun searchFingerprint(
    source: DSecret,
): Int = buildList<String> {
    add(source.id)
    add(source.accountId)
    add(source.folderId.orEmpty())
    add(source.organizationId.orEmpty())
    addAll(source.collectionIds.sorted())
    add(source.revisionDate.toString())
    add(source.name)
    add(source.notes)
    add(source.favorite.toString())
    add(source.reprompt.toString())
    add(source.type.name)
    addAll(source.tags)
    source.uris.forEach { uri ->
        add(uri.uri)
    }
    source.fields.forEach { field ->
        add(field.name.orEmpty())
        add(field.value.orEmpty())
        add(field.type.name)
        add(field.linkedId?.name.orEmpty())
    }
    add(source.attachments.size.toString())
    source.attachments.forEach { attachment ->
        add(attachment.fileName())
    }
    source.login?.let { login ->
        add(login.username.orEmpty())
        add(login.password.orEmpty())
        add(login.totp?.token?.toString().orEmpty())
        login.fido2Credentials.forEach { credential ->
            add(credential.credentialId)
            add(credential.rpId)
            add(credential.userName.orEmpty())
            add(credential.userDisplayName.orEmpty())
        }
    }
    source.card?.let { card ->
        add(card.cardholderName.orEmpty())
        add(card.brand.orEmpty())
        add(card.number.orEmpty())
        add(card.code.orEmpty())
        add(card.expMonth.orEmpty())
        add(card.expYear.orEmpty())
    }
    source.identity?.let { identity ->
        add(identity.title.orEmpty())
        add(identity.firstName.orEmpty())
        add(identity.middleName.orEmpty())
        add(identity.lastName.orEmpty())
        add(identity.address1.orEmpty())
        add(identity.address2.orEmpty())
        add(identity.address3.orEmpty())
        add(identity.city.orEmpty())
        add(identity.state.orEmpty())
        add(identity.postalCode.orEmpty())
        add(identity.country.orEmpty())
        add(identity.company.orEmpty())
        add(identity.email.orEmpty())
        add(identity.phone.orEmpty())
        add(identity.ssn.orEmpty())
        add(identity.username.orEmpty())
        add(identity.passportNumber.orEmpty())
        add(identity.licenseNumber.orEmpty())
    }
    source.sshKey?.let { sshKey ->
        add(sshKey.privateKey.orEmpty())
        add(sshKey.publicKey.orEmpty())
        add(sshKey.fingerprint.orEmpty())
    }
}.hashCode()

private fun joinSearchParts(
    vararg parts: String?,
): String? = parts.asList()
    .map(String?::orEmpty)
    .map(String::trim)
    .filter(String::isNotBlank)
    .takeIf(List<String>::isNotEmpty)
    ?.joinToString(separator = " ")
