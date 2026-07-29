package com.artemchep.keyguard.common.service.credentialexchange.model

/**
 * The full set of credential-type discriminator strings defined by the FIDO
 * Credential Exchange Format (CXF) v1.0 specification.
 *
 * Used to filter which credentials an importer requested. [serialName] is the
 * exact string used both as the [CxfCredential] `type` discriminator and in an
 * importer's requested-type list.
 */
enum class CxfCredentialType(
    val serialName: String,
) {
    Address(serialName = "address"),
    ApiKey(serialName = "api-key"),
    BasicAuth(serialName = "basic-auth"),
    CreditCard(serialName = "credit-card"),
    CustomFields(serialName = "custom-fields"),
    DriversLicense(serialName = "drivers-license"),
    File(serialName = "file"),
    GeneratedPassword(serialName = "generated-password"),
    IdentityDocument(serialName = "identity-document"),
    ItemReference(serialName = "item-reference"),
    Note(serialName = "note"),
    Passkey(serialName = "passkey"),
    Passport(serialName = "passport"),
    PersonName(serialName = "person-name"),
    SshKey(serialName = "ssh-key"),
    Totp(serialName = "totp"),
    Wifi(serialName = "wifi"),
    ;

    companion object {
        /**
         * Every credential type. Callers that want an unfiltered export (the
         * CXP §3.2 "`credentialTypes` is not present" case, meaning the
         * importer requests all credential types) MUST pass this set
         * explicitly — an empty set means the opposite: nothing may be
         * exported.
         */
        val ALL: Set<CxfCredentialType> = entries.toSet()

        /**
         * The credential types Keyguard's export mappers can emit — the set
         * the Android registry advertises.
         */
        val EXPORTABLE: Set<CxfCredentialType> = setOf(
            BasicAuth,
            Passkey,
            Totp,
            CreditCard,
            Address,
            PersonName,
            Note,
            SshKey,
            CustomFields,
        )

        /**
         * The credential types Keyguard's import mappers can represent as
         * vault data — the set requested from a source provider. Deliberately
         * not [ALL]: requesting kinds that would only ever be counted-skipped
         * (api-key, wifi, ...) would make the source app export data Keyguard
         * then throws away.
         */
        val IMPORTABLE: Set<CxfCredentialType> = EXPORTABLE + setOf(
            Passport,
            DriversLicense,
            IdentityDocument,
        )

        /**
         * The WebAuthn credential-type string. Some importers request passkeys
         * using this alias instead of the CXF [Passkey.serialName].
         */
        private const val ALIAS_PUBLIC_KEY = "public-key"

        private val bySerialName = entries.associateBy { it.serialName }

        /**
         * Parses a single importer-requested type string into a
         * [CxfCredentialType], or returns `null` when it is not recognized.
         *
         * Matching is case-insensitive and also accepts the WebAuthn
         * `public-key` alias for [Passkey].
         */
        fun parse(
            value: String,
        ): CxfCredentialType? {
            val normalized = value.trim().lowercase()
            if (normalized == ALIAS_PUBLIC_KEY) {
                return Passkey
            }
            return bySerialName[normalized]
        }

        /**
         * Parses a collection of importer-requested type strings, silently
         * dropping any that are not recognized.
         */
        fun parseAll(
            values: Iterable<String>,
        ): Set<CxfCredentialType> = values
            .mapNotNull(::parse)
            .toSet()
    }
}
