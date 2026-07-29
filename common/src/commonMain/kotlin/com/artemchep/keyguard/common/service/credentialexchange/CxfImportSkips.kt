package com.artemchep.keyguard.common.service.credentialexchange

typealias CxfImportSkips = CxfSkips<CxfImportSkipReason>

/**
 * Builds an import tally; zero and negative pairs are dropped.
 */
fun cxfImportSkips(vararg entries: Pair<CxfImportSkipReason, Int>): CxfImportSkips =
    CxfSkips.of(CxfImportSkipReason::class, entries)

/**
 * Everything the source document contained that did not become vault data.
 *
 * The contract: every source account, item, credential and collection that does
 * not survive the crossing is counted under exactly one reason, and nothing is
 * ever silently substituted — what cannot be carried across faithfully is
 * dropped and counted rather than downgraded into something that happens to fit.
 * Field-level losses *inside a surviving item* (a second certificate
 * fingerprint, a custom-field type with no equivalent) are deliberately not
 * counted; the round-trip suite covers those instead. A loss that is neither
 * counted here nor registered in `CxfImportSilentDropTest` is a bug.
 *
 * **Declaration order is user-visible**: it is the order the review screen
 * renders the warning rows in. Do not reorder to tidy.
 */
enum class CxfImportSkipReason {
    /**
     * A passkey that could not be reconstructed: an empty or undecodable
     * credential id or private key, an undecodable user handle, or a blank
     * relying-party id. Its item still imports if anything else in it did.
     *
     * An *empty* user handle is deliberately not one of these: CXF makes the
     * member required with no way to spell "absent", so an empty value is read
     * as absence and the passkey is kept.
     */
    Passkey,

    /**
     * A TOTP credential that could not be reassembled into a valid otpauth
     * configuration: an algorithm that is neither a §3.3.16.1 `OTPHashAlgorithm`
     * member nor the `steam` extension, a secret that is not base32, a period
     * outside `1..Int.MAX_VALUE`, or a digit count outside `1..9`. Such a
     * configuration is skipped, never downgraded.
     */
    Otp,

    /**
     * An SSH key whose PKCS#8 private key could not be converted into the
     * OpenSSH form Keyguard stores — encrypted, malformed, oversized,
     * unsupported algorithm, or a failing native backend.
     */
    SshKey,

    /**
     * A credential of a kind this implementation does not model (api-key,
     * wifi, …) or whose JSON failed to decode.
     */
    UnknownCredential,

    /**
     * An extra credential of a single-instance kind — a second basic-auth in
     * one item — that the combination rules ignore.
     */
    DuplicateCredential,

    /**
     * A source item that yielded no vault item at all, for a reason the
     * credential reasons above do not already explain — an item that vanished
     * because its only credential was already counted is never counted twice.
     * The exporter applies the same rule, so a round trip does not change the
     * shape of the warning.
     */
    Item,

    /**
     * A source collection (folder) that could not be read: the account's
     * `collections` member was not an array, an entry was not an object, or a
     * node's JSON failed to decode. Counted per unreadable node, except for an
     * unreadable `collections` member itself, which is one count for unknowably
     * many. An unreadable node is replaced by its children, so only that node's
     * own title and item links are lost and the items it linked still import,
     * folder-less — an organization loss, never a credential loss.
     */
    Collection,

    /**
     * A source account that could not be read: the entry was not a JSON object,
     * or its `items` member was absent, null, or not an array (CXF v1.0 §2.1.2
     * makes `[]` the only conforming spelling of an empty account). This is the
     * one reason that cannot say how much was lost — an unreadable account takes
     * an unknown number of items with it — hence its own row rather than [Item].
     */
    Account,
}
