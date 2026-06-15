package com.artemchep.keyguard.feature.home.vault.search.engine

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultFacetField
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultTextField

internal fun VaultTextField.profile(): SearchTokenizerProfile =
    when (this) {
        VaultTextField.Title,
        VaultTextField.IdentityName,
        VaultTextField.CardholderName,
        VaultTextField.CardBrand,
        VaultTextField.PasskeyDisplayName,
        VaultTextField.AttachmentName,
        VaultTextField.FieldName,
        VaultTextField.Note,
        VaultTextField.Field,
        -> SearchTokenizerProfile.TEXT

        VaultTextField.Url -> SearchTokenizerProfile.URL

        VaultTextField.Host,
        VaultTextField.PasskeyRpId,
        -> SearchTokenizerProfile.HOST

        VaultTextField.Username -> SearchTokenizerProfile.IDENTIFIER

        VaultTextField.Email -> SearchTokenizerProfile.EMAIL

        VaultTextField.Phone -> SearchTokenizerProfile.IDENTIFIER

        VaultTextField.Ssh,
        VaultTextField.Password,
        VaultTextField.CardNumber,
        -> SearchTokenizerProfile.SENSITIVE
    }

internal fun VaultTextField.boost(): Double =
    when (this) {
        VaultTextField.Title -> 6.0

        VaultTextField.Host,
        VaultTextField.PasskeyRpId,
        -> 2.5

        VaultTextField.Url,
        VaultTextField.Username,
        VaultTextField.Email,
        -> 2.0

        VaultTextField.IdentityName,
        VaultTextField.CardholderName,
        VaultTextField.PasskeyDisplayName,
        -> 1.5

        VaultTextField.Phone,
        VaultTextField.CardBrand,
        VaultTextField.Note,
        VaultTextField.FieldName,
        VaultTextField.Field,
        -> 1.0

        VaultTextField.AttachmentName -> 0.75

        VaultTextField.Ssh,
        VaultTextField.Password,
        VaultTextField.CardNumber,
        -> 0.8
    }

internal fun VaultFacetField.profile(): SearchTokenizerProfile =
    when (this) {
        VaultFacetField.Type,
        VaultFacetField.Account,
        -> SearchTokenizerProfile.IDENTIFIER

        VaultFacetField.Folder,
        VaultFacetField.Tag,
        VaultFacetField.Organization,
        VaultFacetField.Collection,
        -> SearchTokenizerProfile.TEXT
    }

internal fun typeKey(type: DSecret.Type): String =
    type.name
        .lowercase()
        .replace("_", "")
        .replace("-", "")
        .replace(" ", "")

internal fun extractUriHost(value: String): String? {
    val withoutScheme = value.substringAfter("://", missingDelimiterValue = value)
    val host =
        withoutScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
            .trim()
    return host.takeIf { it.isNotBlank() }
}
