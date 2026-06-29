package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.database.BinaryIndex
import app.keemobile.kotpass.database.BinaryIndexEntry
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.TimeData
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.keepass.KeePassUtil
import com.artemchep.keyguard.common.service.keepass.generateAttachmentUrl
import com.artemchep.keyguard.common.service.keepass.parseAttachmentUrl
import com.artemchep.keyguard.common.service.text.Base32Service
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.common.util.to0DigitsNanosOfSecond
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.CipherSourceCanonicalPaths
import com.artemchep.keyguard.core.store.bitwarden.CipherSourceData
import com.artemchep.keyguard.core.store.bitwarden.CipherSourceProviderIds
import com.artemchep.keyguard.core.store.bitwarden.KeePassIcon
import com.artemchep.keyguard.core.store.bitwarden.SourceBinding
import com.artemchep.keyguard.core.store.bitwarden.withoutCardCanonicalPaths
import com.artemchep.keyguard.core.store.bitwarden.withoutCanonicalPath
import com.artemchep.keyguard.core.store.bitwarden.withoutIdentityCanonicalPaths
import com.artemchep.keyguard.feature.fileupload.KEEPASS_FILE_UPLOAD_MAX_BYTES
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import okio.ByteString
import kotlin.uuid.Uuid
import kotlin.time.Instant

/**
 * Parser contract for a full KeePass entry <-> Bitwarden cipher round-trip.
 *
 * Scope:
 * - Owns entry-level fields, tags, binaries, history, times, icon metadata, and
 *   type detection for one KeePass [Entry].
 * - Delegates login subfields to [KeePassUrlCodec], [KeePassTotpCodec], and
 *   [KeePassPasskeyCodec]; delegates card, identity, and SSH payloads to their
 *   dedicated codecs.
 *
 * Entry fields consumed or written directly:
 *
 * | KeePass field/tag                  | Direction | Parser use                                  |
 * |------------------------------------|-----------|---------------------------------------------|
 * | `Title`                            | both      | Cipher name.                                |
 * | `Notes`                            | both      | Cipher notes; empty notes are omitted.      |
 * | `UserName`                         | login     | Login username.                             |
 * | `Password`                         | login     | Login password, written concealed.          |
 * | `Keyguard: Entry Type`             | both      | Preferred explicit Bitwarden type marker.   |
 * | `Authentication Re-Prompt`         | both      | `Password` enables Bitwarden reprompt.      |
 * | `Archived Date`                    | both      | ISO instant; malformed values stay custom.  |
 * | `Password Revision Date`           | login     | ISO instant; falls back to entry history.   |
 * | `Favorite` tag                     | both      | Bitwarden favorite flag.                    |
 * | `FIDO2 Credentials Blob #<index>`  | login     | Concealed JSON/base64 fallback for passkeys. |
 *
 * Decode is field-consuming: recognized fields and tags are removed from the
 * scope. Remaining fields become Bitwarden custom fields: concealed values map
 * to hidden fields, `true`/`false` map to boolean fields, and
 * `linkedId|<LinkedId>|<value>` maps to linked fields. Invalid parser sidecars
 * are deliberately put back into the scope so user data is preserved.
 *
 * Attachments are parsed from KeePass binary references. Decode resolves hashes
 * through [BinaryIndex], creates hashref-backed remote attachments, preserves
 * reference order, and reuses local ids by URL/name/size when possible. Encode
 * writes local attachments as new uncompressed binaries within the KeePass
 * upload limit and only reuses remote attachments whose hashref URL still points
 * to an existing binary.
 *
 * [CipherSourceData] stores field/projection bindings observed by delegated
 * parsers so later writes can preserve foreign field names, representations, and
 * concealment without storing secret values. Encode prunes bindings for sections
 * that no longer exist and falls back to Keyguard fields or concealed blobs when
 * a value cannot be represented by a foreign projection.
 */
class KeePassCipherCodec(
    private val cryptoGenerator: CryptoGenerator,
    private val base32Service: Base32Service,
    private val base64Service: Base64Service,
    private val fileService: FileService,
    private val getPasswordStrength: GetPasswordStrength,
    private val json: Json,
) {
    private val totpCodec = KeePassTotpCodec(
        base32Service = base32Service,
        base64Service = base64Service,
    )
    private val passkeyCodec = KeePassPasskeyCodec(
        base64Service = base64Service,
    )
    private val urlCodec = KeePassUrlCodec()
    private val cardCodec = KeePassCardCodec()
    private val identityCodec = KeePassIdentityCodec()
    private val sshKeyCodec = KeePassSshKeyCodec()

    companion object {
        private const val KEYGUARD_PREFIX = "Keyguard: "
        private const val ATTACHMENT_BUFFER_SIZE = 8 * 1024
    }

    data class EncodedCipher(
        val entry: Entry,
        val attachments: List<BitwardenCipher.Attachment.Remote>,
        val binaryAdditions: Map<ByteString, BinaryData>,
        val sourceData: CipherSourceData?,
    )

    suspend fun encode(
        local: BitwardenCipher,
        remote: Entry?,
        existingBinaries: Map<ByteString, BinaryData>,
    ): EncodedCipher {
        val scope = EncodeToCipherScope()
        val tags = buildSet {
            val nativeTagsSeq = local.tags
                .asSequence()
                .map { tag -> tag.name }
            addAll(nativeTagsSeq)

            if (local.favorite) {
                val favoriteTag = KeePassFieldKey.TAG_FAVORITE
                add(favoriteTag)
            }
        }.toList()

        scope.setPlain(BasicField.Title(), local.name)
        scope.setPlain(BasicField.Notes(), local.notes?.takeIf { it.isNotEmpty() })

        val archivedDate = local.archivedDate
        if (archivedDate != null) {
            scope.setPlain(KeePassFieldKey.ARCHIVED_DATE, archivedDate.toString())
        }

        // Persist the Keyguard soft-trash state. Rounded to match the value
        // pushToServer stores in the local model (and service.remote.deletedDate)
        // so the differ's raw effective-date comparison stays stable across syncs.
        val deletedDate = local.deletedDate?.to0DigitsNanosOfSecond()
        if (deletedDate != null) {
            scope.setPlain(KeePassFieldKey.SOFT_DELETED_DATE, deletedDate.toString())
        }

        run {
            val value = local.type.verboseKey
            scope.setPlain("${KEYGUARD_PREFIX}Entry Type", value)
        }

        if (local.reprompt == BitwardenCipher.RepromptType.Password) {
            scope.setPlain(KeePassFieldKey.AUTH_REPROMPT, "Password")
        }

        var sourceData = local.sourceData
        if (local.login != null) {
            sourceData = encodeLogin(
                scope = scope,
                local = local,
                login = local.login,
            )
        } else {
            sourceData = sourceData
                ?.withoutCanonicalPath(CipherSourceCanonicalPaths.LOGIN_TOTP)
                ?.withoutCanonicalPath(CipherSourceCanonicalPaths.LOGIN_FIDO2_CREDENTIALS)
                ?.takeUnless { it.bindings.isEmpty() }
        }
        if (local.card != null) {
            val encodedCard = cardCodec.encode(
                card = local.card,
                sourceData = sourceData,
            )
            encodedCard.writes.forEach { write ->
                scope.setValue(write.key, write.value)
            }
            sourceData = encodedCard.sourceData
        } else {
            sourceData = sourceData
                ?.withoutCardCanonicalPaths()
                ?.takeUnless { it.bindings.isEmpty() }
        }
        if (local.identity != null) {
            val encodedIdentity = identityCodec.encode(
                identity = local.identity,
                sourceData = sourceData,
            )
            encodedIdentity.writes.forEach { write ->
                scope.setValue(write.key, write.value)
            }
            sourceData = encodedIdentity.sourceData
        } else {
            sourceData = sourceData
                ?.withoutIdentityCanonicalPaths()
                ?.takeUnless { it.bindings.isEmpty() }
        }
        if (local.sshKey != null) {
            sshKeyCodec.encode(local.sshKey).forEach { scope.setValue(it.key, it.value) }
        }

        local.fields.forEach { field ->
            fun putTextField(key: String?, value: String?, concealed: Boolean) {
                if (key == null || value == null) return
                if (concealed) {
                    scope.setConcealed(key, value)
                } else scope.setPlain(key, value)
            }

            when (field.type) {
                BitwardenCipher.Field.Type.Hidden -> putTextField(
                    key = field.name,
                    value = field.value,
                    concealed = true,
                )

                BitwardenCipher.Field.Type.Text -> putTextField(
                    key = field.name,
                    value = field.value,
                    concealed = false,
                )

                BitwardenCipher.Field.Type.Boolean -> putTextField(
                    key = field.name,
                    value = field.value,
                    concealed = false,
                )

                BitwardenCipher.Field.Type.Linked -> {
                    val value =
                        "linkedId|${field.linkedId?.name.orEmpty()}|${field.value.orEmpty()}"
                    putTextField(
                        key = field.name,
                        value = value,
                        concealed = false,
                    )
                }
            }
        }

        val uuid = remote?.uuid ?: Uuid.random()
        val icon = local.customIcon?.toPredefinedIcon()
            ?: remote?.icon
            ?: PredefinedIcon.Key
        val customIconUuid = remote?.customIconUuid
        val foregroundColor = remote?.foregroundColor
        val backgroundColor = remote?.backgroundColor
        val overrideUrl = remote?.overrideUrl.orEmpty()
        val autoType = remote?.autoType
        val encodedAttachments = encodeAttachments(
            local = local,
            existingBinaries = existingBinaries,
        )
        val binaries = encodedAttachments.references
        val history = if (remote != null) {
            val historicEntry = remote.copy(history = listOf())
            remote.history + historicEntry
        } else {
            emptyList()
        }
        val customData = remote?.customData.orEmpty()
        val previousParentGroup = remote?.previousParentGroup
        val qualityCheck = remote?.qualityCheck ?: true

        val fields = run {
            val pairs = scope.getAvailableFields()
                .map { it.key to it.value }
            EntryFields.of(*pairs.toTypedArray())
        }
        val times = run {
            val revisionDate = local.revisionDate
            val base = remote?.times
                ?: TimeData.create(
                    now = local.createdDate
                        ?: revisionDate,
                )
            base.copy(
                lastAccessTime = revisionDate,
                lastModificationTime = revisionDate,
                expiryTime = local.expiredDate ?: base.expiryTime,
                expires = local.expiredDate != null || base.expires,
            )
        }
        return EncodedCipher(
            entry = Entry(
                uuid = uuid,
                icon = icon,
                customIconUuid = customIconUuid,
                foregroundColor = foregroundColor,
                backgroundColor = backgroundColor,
                overrideUrl = overrideUrl,
                times = times,
                autoType = autoType,
                fields = fields,
                tags = tags,
                binaries = binaries,
                history = history,
                customData = customData,
                previousParentGroup = previousParentGroup,
                qualityCheck = qualityCheck,
            ),
            attachments = encodedAttachments.attachments,
            binaryAdditions = encodedAttachments.additions,
            sourceData = sourceData,
        )
    }

    suspend fun decode(
        accountId: String,
        folderId: String?,
        cipherId: String,
        remote: Entry,
        local: BitwardenCipher?,
        revisionDate: Instant,
        binaries: Map<ByteString, BinaryData>,
    ): BitwardenCipher {
        val scope = DecodeToCipherScope(remote)
        val binaryIndex = BinaryIndex(binaries)
        val type = local?.type
            ?: decodeCipherType(remote)
        scope.consumeField("${KEYGUARD_PREFIX}Entry Type")

        val sourceBindings = mutableListOf<SourceBinding>()
        var login: BitwardenCipher.Login? = null
        var card: BitwardenCipher.Card? = null
        var identity: BitwardenCipher.Identity? = null
        var sshKey: BitwardenCipher.SshKey? = null

        when (type) {
            BitwardenCipher.Type.Login -> {
                val decodedLogin = decodeLogin(scope, remote, local)
                login = decodedLogin.login
                sourceBindings += decodedLogin.sourceBindings
            }

            BitwardenCipher.Type.Card -> {
                val decodedCard = cardCodec.decode(scope, remote)
                card = decodedCard.card
                sourceBindings += decodedCard.sourceBindings
            }

            BitwardenCipher.Type.Identity -> {
                val decodedIdentity = identityCodec.decode(scope, remote)
                identity = decodedIdentity.identity
                sourceBindings += decodedIdentity.sourceBindings
            }

            BitwardenCipher.Type.SshKey -> {
                sshKey = sshKeyCodec.decode(scope)
            }

            BitwardenCipher.Type.SecureNote -> {}
        }

        val name = scope.consumeFieldAndReturnContent(BasicField.Title())
        val notes = scope.consumeFieldAndReturnContent(BasicField.Notes())
        val favorite = scope.consumeTag(KeePassFieldKey.TAG_FAVORITE) != null

        val archivedDate = scope
            .consumeFieldAndReturnContent(KeePassFieldKey.ARCHIVED_DATE)
            ?.let { raw ->
                val parsedDate = Instant.parseOrNull(raw)
                if (parsedDate == null) {
                    scope.spitField(KeePassFieldKey.ARCHIVED_DATE)
                }
                parsedDate
            }

        val deletedDate = scope
            .consumeFieldAndReturnContent(KeePassFieldKey.SOFT_DELETED_DATE)
            ?.let { raw ->
                val parsedDate = Instant.parseOrNull(raw)
                if (parsedDate == null) {
                    scope.spitField(KeePassFieldKey.SOFT_DELETED_DATE)
                }
                parsedDate
            }

        fun getPassword(entry: Entry) = entry.fields.password?.content
            .takeUnless { it.isNullOrEmpty() }

        val passwordHistory = run {
            val history = mutableListOf<BitwardenCipher.Login.PasswordHistory>()
            var lastPassword = getPassword(remote)
            remote.history
                .sortedByDescending { it.times?.lastModificationTime }
                .forEach { entry ->
                    val curPassword = getPassword(entry)
                    if (curPassword != null && curPassword != lastPassword) {
                        lastPassword = curPassword
                        history += BitwardenCipher.Login.PasswordHistory(
                            password = curPassword,
                            lastUsedDate = entry.times?.lastModificationTime,
                        )
                    }
                }
            history
        }

        val reprompt = run {
            val enabled = scope
                .consumeFieldAndReturnContent(KeePassFieldKey.AUTH_REPROMPT) == "Password"
            if (enabled) {
                BitwardenCipher.RepromptType.Password
            } else BitwardenCipher.RepromptType.None
        }

        val customFields = scope.getAvailableFields()
            .asSequence()
            .map { field ->
                var type2: BitwardenCipher.Field.Type = BitwardenCipher.Field.Type.Text
                var linkedId: BitwardenCipher.Field.LinkedId? = null
                var value = field.value.content

                val concealed = field.value.isConcealed()
                if (concealed) {
                    type2 = BitwardenCipher.Field.Type.Hidden
                } else {
                    val isBool = value.toBooleanStrictOrNull() != null
                    if (isBool) {
                        type2 = BitwardenCipher.Field.Type.Boolean
                    } else run {
                        val prefix = "linkedId|"
                        if (!value.startsWith(prefix)) return@run
                        val parts = value.substring(prefix.length).split('|')
                        if (parts.size < 2) return@run
                        val idStr = parts.first()
                        val id = BitwardenCipher.Field.LinkedId.entries
                            .firstOrNull { it.name == idStr }
                            ?: return@run
                        val data = parts.subList(1, parts.size).joinToString("|")
                        linkedId = id
                        value = data
                        type2 = BitwardenCipher.Field.Type.Linked
                    }
                }
                BitwardenCipher.Field(
                    name = field.key,
                    value = value,
                    type = type2,
                    linkedId = linkedId,
                )
            }
            .toList()

        val attachments = remote.binaries
            .let { remoteBinaries ->
                val usedLocalAttachmentIds = mutableSetOf<String>()
                remoteBinaries.mapNotNull { binary ->
                    val data = binaryIndex
                        .getByHash(binary.hash)
                        ?.data
                        ?.getContent()
                        ?: return@mapNotNull null

                    val url = KeePassUtil.generateAttachmentUrl(
                        data = data,
                        cryptoGenerator = cryptoGenerator,
                        base32Service = base32Service,
                    )
                    val size = data.size.toLong()
                    val id = findAttachmentId(
                        local = local,
                        usedIds = usedLocalAttachmentIds,
                        url = url,
                        fileName = binary.name,
                        size = size,
                    ) ?: cryptoGenerator.uuid()
                    usedLocalAttachmentIds += id
                    BitwardenCipher.Attachment.Remote(
                        id = id,
                        url = url,
                        fileName = binary.name,
                        keyBase64 = "",
                        size = size,
                    )
                }
            }
        return BitwardenCipher(
            accountId = accountId,
            cipherId = cipherId,
            folderId = folderId,
            service = BitwardenService(
                remote = BitwardenService.Remote(
                    id = remote.uuid.toString(),
                    revisionDate = revisionDate,
                    deletedDate = deletedDate,
                ),
                deleted = false,
                version = BitwardenService.VERSION,
            ),
            name = name.orEmpty(),
            notes = notes,
            tags = scope.getAvailableTags().map { BitwardenCipher.Tag(name = it) },
            attachments = attachments,
            passwordHistory = passwordHistory,
            login = login,
            card = card,
            identity = identity,
            sshKey = sshKey,
            fields = customFields,
            sourceData = CipherSourceData(
                providerId = CipherSourceProviderIds.KEEPASS,
                bindings = sourceBindings,
            ).takeIf { it.bindings.isNotEmpty() },
            customIcon = remote.icon.toKeePassIcon()
                .takeIf { it != KeePassIcon.Key },
            favorite = favorite,
            reprompt = reprompt,
            type = type,
            createdDate = remote.times?.creationTime
                ?.takeIf { it.toEpochMilliseconds() != 0L }
                ?: local?.createdDate,
            expiredDate = remote.times?.expiryTime
                ?.takeIf { it.toEpochMilliseconds() != 0L },
            archivedDate = archivedDate,
            deletedDate = deletedDate,
            revisionDate = revisionDate,
        )
    }

    internal fun decodeCipherType(remote: Entry): BitwardenCipher.Type {
        val typeField = remote.fields["${KEYGUARD_PREFIX}Entry Type"]
        if (typeField != null) {
            val type = BitwardenCipher.Type.entries
                .firstOrNull { it.verboseKey == typeField.content }
            if (type != null) return type
        }

        if (urlCodec.hasLoginUriFields(remote)) return BitwardenCipher.Type.Login

        when {
            cardCodec.hasCardFields(remote) -> return BitwardenCipher.Type.Card
            identityCodec.detects(remote) -> return BitwardenCipher.Type.Identity
            sshKeyCodec.detects(remote) -> return BitwardenCipher.Type.SshKey
        }

        val isLogin = run {
            val hasCredentials = remote.fields.any { entry ->
                val credentialsField = entry.key == BasicField.Password() ||
                        entry.key == BasicField.UserName()
                credentialsField && !entry.value.isEmpty()
            }
            if (hasCredentials) return@run true
            remote.fields.notes == null
        }
        return if (isLogin) BitwardenCipher.Type.Login else BitwardenCipher.Type.SecureNote
    }

    // region Encode helpers

    private fun encodeLogin(
        scope: EncodeToCipherScope,
        local: BitwardenCipher,
        login: BitwardenCipher.Login,
    ): CipherSourceData? {
        scope.setPlain(BasicField.UserName(), login.username)
        scope.setConcealed(BasicField.Password(), login.password)
        val totp = totpCodec.encode(
            canonicalTotp = login.totp,
            sourceData = local.sourceData,
        )
        totp.writes.forEach { write ->
            scope.setValue(write.key, write.value)
        }
        val passkeys = passkeyCodec.encode(
            credentials = login.fido2Credentials,
            sourceData = totp.sourceData,
        )
        passkeys.writes.forEach { write ->
            scope.setValue(write.key, write.value)
        }
        scope.setPlain(
            KeePassFieldKey.PASSWORD_REVISION_DATE,
            login.passwordRevisionDate?.toString(),
        )

        val prefix = "FIDO2 Credentials Blob #"
        login.fido2Credentials
            .filterNot { fido2 -> fido2.credentialId in passkeys.encodedCredentialIds }
            .forEachIndexed { index, fido2 ->
                val key = "$prefix$index"
                val value = run {
                    val data = json.encodeToString<BitwardenCipher.Login.Fido2Credentials>(fido2)
                    base64Service.encodeToString(data)
                }
                scope.setConcealed(key, value)
            }

        urlCodec.encode(login.uris).forEach { scope.setValue(it.key, it.value) }
        return passkeys.sourceData
    }

    // endregion

    // region Decode helpers

    private suspend fun decodeLogin(
        scope: DecodeToCipherScope,
        remote: Entry,
        local: BitwardenCipher?,
    ): DecodedLogin {
        val uris = urlCodec.decode(scope, remote)

        val totp = totpCodec.decode(scope, remote)
        val passkeys = passkeyCodec.decode(
            scope = scope,
            remote = remote,
            creationDate = remote.times?.creationTime ?: remote.times?.lastModificationTime ?: local?.createdDate
                ?: local?.revisionDate
                ?: kotlin.time.Clock.System.now(),
        )

        fun getPassword(entry: Entry) = entry.fields.password?.content
            .takeUnless { it.isNullOrEmpty() }

        val passwordRevDate = run {
            val explicitRevDateRaw = scope
                .consumeFieldAndReturnContent(KeePassFieldKey.PASSWORD_REVISION_DATE)
            val explicitRevDate = explicitRevDateRaw?.let(Instant::parseOrNull)
            if (explicitRevDateRaw != null && explicitRevDate == null) {
                scope.spitField(KeePassFieldKey.PASSWORD_REVISION_DATE)
            }
            if (explicitRevDate != null) return@run explicitRevDate

            val lastPassword = getPassword(remote)
            // The index is positional within the sorted list, so it must be read
            // back from that same sorted list — not the unsorted history, whose
            // order foreign clients do not guarantee (CORR-1).
            val sortedHistory = remote.history
                .sortedByDescending { it.times?.lastModificationTime }
            val index = sortedHistory
                .indexOfFirst { entry ->
                    val curPassword = getPassword(entry)
                    curPassword != null && curPassword != lastPassword
                } - 1
            val revDate = sortedHistory.getOrNull(index)?.times?.lastModificationTime
                ?: sortedHistory.lastOrNull()?.times?.lastModificationTime
                    ?.takeIf { lastPassword != null }
                ?: remote.times?.lastModificationTime
                    ?.takeIf { lastPassword != null }
            revDate
        }

        val fido2Credentials = run {
            val prefix = "FIDO2 Credentials Blob #"
            val blobs = remote.fields
                .filter { it.key.startsWith(prefix) }
                .mapNotNull { field ->
                    val ordinal = field.key.removePrefix(prefix).toIntOrNull()
                        ?: return@mapNotNull null
                    val credentials = runCatching {
                        val data = base64Service.decodeToString(field.value.content)
                        json.decodeFromString<BitwardenCipher.Login.Fido2Credentials>(data)
                    }.getOrElse { return@mapNotNull null }
                    Triple(listOf(field.key), ordinal, credentials)
                }
                .sortedBy { it.second }
                .onEach { entry -> entry.first.forEach { scope.consumeField(it) } }
                .map { it.third }
            passkeys.credentials + blobs
        }

        val password = scope.consumeFieldAndReturnContent(BasicField.Password())
        val passwordStrength = if (password != null) {
            local?.login?.passwordStrength
                .takeIf { local?.login?.password == password }
                ?: getPasswordStrength(password)
                    .attempt()
                    .bind()
                    .getOrNull()
                    ?.let { ps ->
                        BitwardenCipher.Login.PasswordStrength(
                            password = password,
                            crackTimeSeconds = ps.crackTimeSeconds,
                            version = ps.version,
                        )
                    }
        } else null
        return DecodedLogin(
            login = BitwardenCipher.Login(
                username = scope.consumeFieldAndReturnContent(BasicField.UserName()),
                password = password,
                passwordStrength = passwordStrength,
                passwordRevisionDate = passwordRevDate,
                fido2Credentials = fido2Credentials,
                uris = uris,
                totp = totp?.value,
            ),
            sourceBindings = listOfNotNull(totp?.binding) + passkeys.bindings,
        )
    }

    private data class DecodedLogin(
        val login: BitwardenCipher.Login,
        val sourceBindings: List<SourceBinding>,
    )

    // endregion

    // region Attachments

    private data class EncodedAttachments(
        val references: List<BinaryReference>,
        val attachments: List<BitwardenCipher.Attachment.Remote>,
        val additions: Map<ByteString, BinaryData>,
    )

    private data class EncodedLocalAttachment(
        val binary: BinaryData,
        val url: String,
        val size: Long,
    )

    private suspend fun encodeAttachments(
        local: BitwardenCipher,
        existingBinaries: Map<ByteString, BinaryData>,
    ): EncodedAttachments {
        val additions = mutableMapOf<ByteString, BinaryData>()
        val localAttachmentsByIndex = mutableMapOf<Int, EncodedLocalAttachment>()
        val references = mutableListOf<BinaryReference>()
        val attachments = mutableListOf<BitwardenCipher.Attachment.Remote>()

        local.attachments.forEachIndexed { index, attachment ->
            if (attachment !is BitwardenCipher.Attachment.Local) return@forEachIndexed

            val data = readAttachmentData(attachment.url)
            val binary = BinaryData.Uncompressed(
                memoryProtection = false,
                rawContent = data,
            )
            additions[binary.hash] = binary
            val url = KeePassUtil.generateAttachmentUrl(
                data = data,
                cryptoGenerator = cryptoGenerator,
                base32Service = base32Service,
            )
            localAttachmentsByIndex[index] = EncodedLocalAttachment(
                binary = binary,
                url = url,
                size = data.size.toLong(),
            )
        }

        val binaryIndex = BinaryIndex(existingBinaries + additions)

        local.attachments.forEachIndexed { index, attachment ->
            when (attachment) {
                is BitwardenCipher.Attachment.Remote -> {
                    val url = attachment.url ?: return@forEachIndexed
                    val binary = findBinaryByAttachmentUrl(
                        binaryIndex = binaryIndex,
                        url = url,
                    ) ?: return@forEachIndexed
                    val data = binary.data.getContent()
                    references += BinaryReference(hash = binary.hash, name = attachment.fileName)
                    attachments += attachment.copy(
                        url = url,
                        keyBase64 = "",
                        size = data.size.toLong(),
                    )
                }

                is BitwardenCipher.Attachment.Local -> {
                    val encoded = localAttachmentsByIndex[index]
                        ?: return@forEachIndexed
                    references += BinaryReference(hash = encoded.binary.hash, name = attachment.fileName)
                    attachments += BitwardenCipher.Attachment.Remote(
                        id = attachment.id,
                        url = encoded.url,
                        fileName = attachment.fileName,
                        keyBase64 = "",
                        size = encoded.size,
                    )
                }
            }
        }

        return EncodedAttachments(
            references = references,
            attachments = attachments,
            additions = additions,
        )
    }

    private suspend fun findBinaryByAttachmentUrl(
        binaryIndex: BinaryIndex,
        url: String,
    ): BinaryIndexEntry? {
        val hash = KeePassUtil.parseAttachmentUrl(
            url = url,
            base32Service = base32Service,
        )
        return binaryIndex.findByContentSha256(hash)
    }

    private fun readAttachmentData(uri: String): ByteArray =
        fileService.readFromFile(uri).use { source ->
            val buffer = ByteArray(ATTACHMENT_BUFFER_SIZE)
            val output = Buffer()
            var total = 0L
            while (true) {
                val read = source.readAtMostTo(buffer)
                if (read == -1) break
                total += read
                require(total <= KEEPASS_FILE_UPLOAD_MAX_BYTES) {
                    "KeePass attachment file must be 1 MB or smaller."
                }
                output.write(buffer, 0, read)
            }
            output.readByteArray()
        }

    private fun findAttachmentId(
        local: BitwardenCipher?,
        usedIds: Set<String>,
        url: String,
        fileName: String,
        size: Long,
    ): String? {
        val localAttachments = local?.attachments.orEmpty()
            .filter { it.id !in usedIds }
        return localAttachments.firstOrNull { it.url == url && it.keepassFileName() == fileName }?.id
            ?: localAttachments.firstOrNull { it.url == url }?.id
            ?: localAttachments.firstOrNull { it.keepassFileName() == fileName && it.keepassFileSize() == size }?.id
    }

    private fun BitwardenCipher.Attachment.keepassFileName(): String = when (this) {
        is BitwardenCipher.Attachment.Remote -> fileName
        is BitwardenCipher.Attachment.Local -> fileName
    }

    private fun BitwardenCipher.Attachment.keepassFileSize(): Long? = when (this) {
        is BitwardenCipher.Attachment.Remote -> size
        is BitwardenCipher.Attachment.Local -> size
    }

    // endregion

}
