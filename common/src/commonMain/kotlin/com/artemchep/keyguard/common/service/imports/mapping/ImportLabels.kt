package com.artemchep.keyguard.common.service.imports.mapping

/**
 * The title given to a folder whose source names none, e.g. a collection with
 * a blank title. Not translated: it names external data on the parsing layer,
 * which has no translator access, and a blank source title is a degenerate case.
 */
internal const val IMPORT_DEFAULT_FOLDER_TITLE = "Folder"

/**
 * The name given to an imported field whose source carries no label and that
 * has no fallback of its own. Keyguard refuses to store a nameless field, and
 * it refuses it while building the cipher — that is, it would fail the whole
 * import rather than the one field — so a generic name is the only lossless
 * option. Not translated, for the same reason as [IMPORT_DEFAULT_FOLDER_TITLE].
 */
internal const val IMPORT_DEFAULT_FIELD_LABEL = "Field"

/**
 * The label a card's valid-from date travels under when the source carries no
 * label of its own. Not translated, for the same reason as
 * [IMPORT_DEFAULT_FIELD_LABEL].
 *
 * The vault has nowhere else to keep it: `CreateRequest.Card` declares
 * `fromMonth`/`fromYear`, but `AddCipher` builds `BitwardenCipher.Card` from
 * six of its eight members and never reads those two, so a value written there
 * is dropped at the vault write. A labelled custom field is the only place it
 * survives.
 */
internal const val IMPORT_CARD_VALID_FROM_LABEL = "Valid from"

/**
 * The labels of a card's PIN, which the vault has no member for, and of an
 * expiry date the card could not hold, kept as written. Not translated, for
 * the same reason as [IMPORT_DEFAULT_FIELD_LABEL].
 */
internal const val IMPORT_CARD_PIN_LABEL = "PIN"
internal const val IMPORT_CARD_EXPIRY_LABEL = "Expiry date"

/**
 * The labels an SSH key travels under when it is kept as fields, either
 * because it could not be converted or because the source's public key
 * disagrees with the private key. Not translated, for the same reason as
 * [IMPORT_DEFAULT_FIELD_LABEL].
 */
internal const val IMPORT_SSH_PRIVATE_KEY_LABEL = "Private key"
internal const val IMPORT_SSH_PUBLIC_KEY_LABEL = "Public key"
internal const val IMPORT_SSH_FINGERPRINT_LABEL = "Fingerprint"
