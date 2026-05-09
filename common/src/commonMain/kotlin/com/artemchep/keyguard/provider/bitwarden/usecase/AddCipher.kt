package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.flatTap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.canDelete
import com.artemchep.keyguard.common.model.canEdit
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.AddCipher
import com.artemchep.keyguard.common.usecase.AddFolder
import com.artemchep.keyguard.common.usecase.ArchiveCipherById
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.common.usecase.TrashCipherById
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.getUrlChecksumBase64
import com.artemchep.keyguard.feature.confirmation.organization.FolderInfo
import com.artemchep.keyguard.provider.bitwarden.crypto.makeCipherAttachmentCryptoKeyMaterial
import com.artemchep.keyguard.provider.bitwarden.crypto.keyBase64OrGenerate
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import com.artemchep.keyguard.provider.bitwarden.repository.ServiceTokenRepository
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyDatabase
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadTarget
import kotlin.time.Clock
import kotlin.time.Instant
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class AddCipherImpl(
    private val modifyDatabase: ModifyDatabase,
    private val addFolder: AddFolder,
    private val archiveCipherById: ArchiveCipherById,
    private val trashCipherById: TrashCipherById,
    private val cryptoGenerator: CryptoGenerator,
    private val getPasswordStrength: GetPasswordStrength,
    private val base64Service: Base64Service,
    private val pendingUploadCoordinator: PendingUploadCoordinator,
    private val tokenRepository: ServiceTokenRepository,
) : AddCipher {
    companion object {
        private const val TAG = "AddCipher.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        modifyDatabase = directDI.instance(),
        addFolder = directDI.instance(),
        archiveCipherById = directDI.instance(),
        trashCipherById = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        getPasswordStrength = directDI.instance(),
        base64Service = directDI.instance(),
        pendingUploadCoordinator = directDI.instance(),
        tokenRepository = directDI.instance(),
    )

    override fun invoke(
        cipherIdsToRequests: Map<String?, CreateRequest>,
    ): IO<List<String>> = ioEffect {
        cipherIdsToRequests
            .mapValues { (key, request) ->
                val value = request.ownership2!!
                val folderId = when (val folder = value.folder) {
                    is FolderInfo.None -> null
                    is FolderInfo.New -> {
                        val accountId = AccountId(value.accountId!!)
                        val rq = mapOf(
                            accountId to folder.name,
                        )
                        val rs = addFolder(rq)
                            .bind()
                        rs.first()
                    }

                    is FolderInfo.Id -> folder.id
                }
                val own = CreateRequest.Ownership(
                    accountId = value.accountId,
                    folderId = folderId,
                    organizationId = value.organizationId,
                    collectionIds = value.collectionIds,
                )
                request.copy(ownership = own)
            }
    }.flatMap { map ->
        ioEffect {
            map.values
                .mapNotNull { it.ownership?.accountId }
                .distinct()
                .associateWith { accountId ->
                    tokenRepository
                        .getById(AccountId(accountId))
                        .bind() !is KeePassToken
                }
        }.flatMap { stagePendingUploadsByAccountId ->
            modifyDatabase { database ->
                val dao = database.cipherQueries
                val now = Clock.System.now()

            val oldCiphersMap = map
                .keys
                .filterNotNull()
                .mapNotNull { cipherId ->
                    dao
                        .getByCipherId(cipherId)
                        .executeAsOneOrNull()
                }
                .associateBy { it.cipherId }

            val models = map
                .map { (cipherId, request) ->
                    val old = oldCiphersMap[cipherId]?.data_
                    val cipher = BitwardenCipher.of(
                        cryptoGenerator = cryptoGenerator,
                        base64Service = base64Service,
                        getPasswordStrength = getPasswordStrength,
                        now = now,
                        request = request,
                        old = old,
                    )
                    prepareCipherPendingUploads(
                        request = request,
                        old = old,
                        cipher = cipher,
                        base64Service = base64Service,
                        pendingUploadCoordinator = pendingUploadCoordinator,
                        stagePendingUploads = stagePendingUploadsByAccountId[cipher.accountId] ?: true,
                    )
                }
            if (models.isEmpty()) {
                return@modifyDatabase ModifyDatabase.Result(
                    changedAccountIds = emptySet(),
                    value = emptyList(),
                )
            }
            val createdPendingUploads = models
                .flatMap { it.createdPendingUploads }
            val removedPendingUploads = models
                .flatMap { it.removedPendingUploads }
            pendingUploadCoordinator.persist(
                createdPendingUploads = createdPendingUploads,
                removedPendingUploads = removedPendingUploads,
            ) {
                dao.transaction {
                    models.forEach { prepared ->
                        val cipher = prepared.cipher
                        dao.insert(
                            cipherId = cipher.cipherId,
                            accountId = cipher.accountId,
                            folderId = cipher.folderId,
                            data = cipher,
                            updatedAt = cipher.revisionDate,
                        )
                    }
                }
            }

            val changedAccountIds = models
                .map { AccountId(it.cipher.accountId) }
                .toSet()
            ModifyDatabase.Result(
                changedAccountIds = changedAccountIds,
                value = models
                    .map { it.cipher.cipherId },
            )
            }
        }
    }.flatTap {
        val ciphersIdsToTrash = mutableSetOf<String>()
        val ciphersIdsToArchive = mutableSetOf<String>()

        cipherIdsToRequests.values.forEach {
            val ciphers = it.merge?.ciphers
                ?: return@forEach

            val postAction = it.merge.postAction
                ?: return@forEach
            when (postAction) {
                CreateRequest.Merge.PostAction.TRASH -> {
                    ciphers.forEach { cipher ->
                        val allow = cipher.canDelete() &&
                                !cipher.deleted
                        if (!allow) return@forEach
                        ciphersIdsToTrash += cipher.id
                    }
                }

                CreateRequest.Merge.PostAction.ARCHIVE -> {
                    ciphers.forEach { cipher ->
                        val allow = cipher.canEdit() &&
                                !cipher.archived
                        if (!allow) return@forEach
                        ciphersIdsToArchive += cipher.id
                    }
                }
            }
        }

        ioEffect {
            if (ciphersIdsToArchive.isNotEmpty()) {
                archiveCipherById(ciphersIdsToArchive)
                    .bind()
            }
            if (ciphersIdsToTrash.isNotEmpty()) {
                trashCipherById(ciphersIdsToTrash)
                    .bind()
            }
        }
    }
}

internal data class PreparedCipher(
    val cipher: BitwardenCipher,
    val createdPendingUploads: List<PendingUploadFile>,
    val removedPendingUploads: List<PendingUploadFile>,
)

internal suspend fun prepareCipherPendingUploads(
    request: CreateRequest,
    old: BitwardenCipher?,
    cipher: BitwardenCipher,
    base64Service: Base64Service,
    pendingUploadCoordinator: PendingUploadCoordinator,
    stagePendingUploads: Boolean = true,
): PreparedCipher {
    val localRequestAttachmentsById = request.attachments
        .asSequence()
        .filterIsInstance<CreateRequest.Attachment.Local>()
        .associateBy { it.id }
    val oldPendingUploadsByPath = old?.attachments
        .orEmpty()
        .asSequence()
        .filterIsInstance<BitwardenCipher.Attachment.Local>()
        .mapNotNull { attachment ->
            attachment.pendingUpload?.let { pendingUpload ->
                pendingUpload.path to pendingUpload
            }
        }
        .toMap()

    if (!stagePendingUploads) {
        return PreparedCipher(
            cipher = cipher.copy(
                attachments = cipher.attachments
                    .map { attachment ->
                        when (attachment) {
                            is BitwardenCipher.Attachment.Remote -> attachment
                            is BitwardenCipher.Attachment.Local -> attachment.copy(
                                pendingUpload = null,
                            )
                        }
                    },
            ),
            createdPendingUploads = emptyList(),
            removedPendingUploads = oldPendingUploadsByPath
                .values
                .toList(),
        )
    }

    val createdPendingUploads = mutableListOf<PendingUploadFile>()
    val attachments = try {
        cipher.attachments.map { attachment ->
            when (attachment) {
                is BitwardenCipher.Attachment.Remote -> attachment
                is BitwardenCipher.Attachment.Local -> {
                    val pendingUpload = attachment.pendingUpload
                    if (pendingUpload != null) {
                        attachment.copy(
                            size = attachment.size ?: pendingUpload.plainSize,
                        )
                    } else {
                        val requestAttachment = requireNotNull(localRequestAttachmentsById[attachment.id]) {
                            "A local cipher attachment must have a source request."
                        }
                        val fileKey = requireNotNull(attachment.keyBase64) {
                            "A local cipher attachment must have an encryption key."
                        }.let(base64Service::decode)
                        val stagedPendingUpload = pendingUploadCoordinator.stage(
                            target = PendingUploadTarget.CipherAttachment(
                                accountId = cipher.accountId,
                                cipherId = cipher.cipherId,
                                attachmentId = attachment.id,
                            ),
                            sourceUri = requestAttachment.uri.toString(),
                            fileKey = fileKey,
                        )
                        createdPendingUploads += stagedPendingUpload
                        attachment.copy(
                            size = attachment.size ?: stagedPendingUpload.plainSize,
                            pendingUpload = stagedPendingUpload,
                        )
                    }
                }
            }
        }
    } catch (e: Throwable) {
        createdPendingUploads.forEach { pendingUpload ->
            runCatching {
                pendingUploadCoordinator.delete(pendingUpload)
            }
        }
        throw e
    }

    val currentPendingUploadPaths = attachments
        .asSequence()
        .filterIsInstance<BitwardenCipher.Attachment.Local>()
        .mapNotNull { it.pendingUpload?.path }
        .toSet()
    val removedPendingUploads = oldPendingUploadsByPath
        .filterKeys { path -> path !in currentPendingUploadPaths }
        .values
        .toList()

    return PreparedCipher(
        cipher = cipher.copy(
            attachments = attachments,
        ),
        createdPendingUploads = createdPendingUploads,
        removedPendingUploads = removedPendingUploads,
    )
}

internal fun CreateRequest.Attachment.Local.toBitwardenLocalAttachment(
    existingAttachment: BitwardenCipher.Attachment.Local?,
    cryptoGenerator: CryptoGenerator,
    base64Service: Base64Service,
): BitwardenCipher.Attachment.Local {
    val attachmentKeyBase64 = keyBase64
        ?: existingAttachment?.keyBase64
        ?: cryptoGenerator
            .makeCipherAttachmentCryptoKeyMaterial()
            .let(base64Service::encodeToString)
    return BitwardenCipher.Attachment.Local(
        id = id,
        url = uri.toString(),
        fileName = name,
        size = size,
        keyBase64 = attachmentKeyBase64,
        pendingUpload = existingAttachment?.pendingUpload,
    )
}

private suspend fun BitwardenCipher.Companion.of(
    cryptoGenerator: CryptoGenerator,
    base64Service: Base64Service,
    getPasswordStrength: GetPasswordStrength,
    request: CreateRequest,
    now: Instant,
    old: BitwardenCipher? = null,
): BitwardenCipher {
    val accountId = request.ownership?.accountId
    val folderId = request.ownership?.folderId
    val organizationId = request.ownership?.organizationId
    val collectionIds = request.ownership?.collectionIds.orEmpty()
    require(old?.service?.deleted != true) {
        "Can not modify deleted cipher!"
    }
    requireNotNull(accountId) { "Cipher must have an account!" }
    require(organizationId == null || collectionIds.isNotEmpty()) {
        "When a cipher belongs to an organization, it must have at " +
                "least one collection!"
    }

    // We may want to preserve the metadata of the origin ciphers
    // during the merge process -- for example merging the password
    // history.
    val originCiphers = request.merge?.ciphers.orEmpty()

    val favourite = request.favorite!!
    val reprompt = when (request.reprompt!!) {
        true -> BitwardenCipher.RepromptType.Password
        false -> BitwardenCipher.RepromptType.None
    }
    val type = when (request.type) {
        DSecret.Type.Login -> BitwardenCipher.Type.Login
        DSecret.Type.SecureNote -> BitwardenCipher.Type.SecureNote
        DSecret.Type.Card -> BitwardenCipher.Type.Card
        DSecret.Type.Identity -> BitwardenCipher.Type.Identity
        DSecret.Type.SshKey -> BitwardenCipher.Type.SshKey
        null,
        DSecret.Type.None,
        -> error("Cipher must have a type!")
    }

    var login: BitwardenCipher.Login? = null
    var secureNote: BitwardenCipher.SecureNote? = null
    var card: BitwardenCipher.Card? = null
    var identity: BitwardenCipher.Identity? = null
    var sshKey: BitwardenCipher.SshKey? = null
    when (type) {
        BitwardenCipher.Type.Login -> {
            login = BitwardenCipher.Login.of(
                cryptoGenerator = cryptoGenerator,
                base64Service = base64Service,
                getPasswordStrength = getPasswordStrength,
                request = request,
                now = now,
                old = old,
            )
        }

        BitwardenCipher.Type.SecureNote -> {
            secureNote = BitwardenCipher.SecureNote.of(
                request = request,
            )
        }

        BitwardenCipher.Type.Card -> {
            card = BitwardenCipher.Card.of(
                request = request,
            )
        }

        BitwardenCipher.Type.Identity -> {
            identity = BitwardenCipher.Identity.of(
                request = request,
            )
        }

        BitwardenCipher.Type.SshKey -> {
            sshKey = BitwardenCipher.SshKey.of(
                request = request,
            )
        }
    }

    val fields = request
        .fields
        .map { field ->
            requireNotNull(field.name)

            val fieldType = when (field.type) {
                DSecret.Field.Type.Text -> BitwardenCipher.Field.Type.Text
                DSecret.Field.Type.Hidden -> BitwardenCipher.Field.Type.Hidden
                DSecret.Field.Type.Boolean -> BitwardenCipher.Field.Type.Boolean
                DSecret.Field.Type.Linked -> BitwardenCipher.Field.Type.Linked
            }
            when (fieldType) {
                BitwardenCipher.Field.Type.Text,
                BitwardenCipher.Field.Type.Hidden,
                -> {
                    requireNotNull(field.value)
                    // no additional validation
                }

                BitwardenCipher.Field.Type.Boolean -> {
                    requireNotNull(field.value)
                    // must be a valid boolean
                    field.value.toBooleanStrict()
                }

                BitwardenCipher.Field.Type.Linked -> {
                    requireNotNull(field.linkedId)
                }
            }
            BitwardenCipher.Field(
                type = fieldType,
                name = field.name,
                value = field.value
                    ?.takeIf {
                        fieldType == BitwardenCipher.Field.Type.Text ||
                                fieldType == BitwardenCipher.Field.Type.Hidden ||
                                fieldType == BitwardenCipher.Field.Type.Boolean
                    },
                linkedId = field.linkedId
                    ?.takeIf {
                        fieldType == BitwardenCipher.Field.Type.Linked
                    }
                    ?.let(BitwardenCipher.Field.LinkedId::of),
            )
        }

    val tags = request.tags
        .filter { it.isNotBlank() }
        .map { tag ->
            BitwardenCipher.Tag(
                name = tag,
            )
        }

    val attachments = kotlin.run {
        val existingAttachmentsMap = old?.attachments
            .orEmpty()
            .associateBy { it.id }
        request
            .attachments
            .mapNotNull { attachment ->
                val existingAttachment = existingAttachmentsMap[attachment.id]
                val newAttachment: BitwardenCipher.Attachment? = when (attachment) {
                    is CreateRequest.Attachment.Remote -> {
                        // Remote attachment can not be created here, it can only
                        // be obtained from the remote server!
                        val out = existingAttachment as? BitwardenCipher.Attachment.Remote
                        out?.copy(fileName = attachment.name)
                    }

                    is CreateRequest.Attachment.Local -> {
                        val existingLocalAttachment = existingAttachment as? BitwardenCipher.Attachment.Local
                        attachment.toBitwardenLocalAttachment(
                            existingAttachment = existingLocalAttachment,
                            cryptoGenerator = cryptoGenerator,
                            base64Service = base64Service,
                        )
                    }
                }
                newAttachment
            }
    }

    val passwordHistory = if (old != null || originCiphers.isNotEmpty()) {
        val list: MutableList<BitwardenCipher.Login.PasswordHistory> = mutableListOf()

        // 1. Add all the password histories from the source
        // ciphers to preserve those.
        originCiphers
            .flatMap { it.passwordHistory }
            .map {
                BitwardenCipher.Login.PasswordHistory(
                    password = it.password,
                    lastUsedDate = it.lastUsedDate,
                )
            }
            .toCollection(list)

        // 2. Add all the password histories from the old cipher.
        old?.passwordHistory
            ?.toCollection(list)

        // 3. Reflect the changes in the old -> current passwords.
        val newPassword = request.login.password
            ?.takeIf { it.isNotEmpty() }
        sequence {
            val oldPassword = old?.login?.password
            if (oldPassword != null) {
                yield(oldPassword)
            }

            val originPasswords = originCiphers
                .asSequence()
                .mapNotNull { it.login?.password }
            yieldAll(originPasswords)
        }
            .distinct()
            .filter { oldPassword ->
                oldPassword != newPassword &&
                        oldPassword.isNotEmpty()
            }
            .map { oldPassword ->
                BitwardenCipher.Login.PasswordHistory(
                    password = oldPassword,
                    lastUsedDate = now,
                )
            }
            .toCollection(list)

        //
        // Track changed fields as well.
        //

        fun BitwardenCipher.Field.shouldBeTracked() =
            this.type == BitwardenCipher.Field.Type.Hidden &&
                    !this.value.isNullOrBlank()

        val oldFieldsNoDuplicates = kotlin.run {
            val originCiphersFields = originCiphers
                .asSequence()
                .flatMap { it.fields }
                .map {
                    val linkedId = it.linkedId?.let(BitwardenCipher.Field.LinkedId::of)
                    val type = when (it.type) {
                        DSecret.Field.Type.Text -> BitwardenCipher.Field.Type.Text
                        DSecret.Field.Type.Hidden -> BitwardenCipher.Field.Type.Hidden
                        DSecret.Field.Type.Boolean -> BitwardenCipher.Field.Type.Boolean
                        DSecret.Field.Type.Linked -> BitwardenCipher.Field.Type.Linked
                    }
                    BitwardenCipher.Field(
                        name = it.name,
                        value = it.value,
                        linkedId = linkedId,
                        type = type,
                    )
                }
            val oldFields = old?.fields
                .orEmpty()
            sequence {
                yieldAll(originCiphersFields)
                yieldAll(oldFields)
            }
                .filter { oldField ->
                    oldField.shouldBeTracked()
                }
                .distinct()
        }
        oldFieldsNoDuplicates.forEach { oldField ->
            val shouldBeTracked = fields
                .none { oldField == it }
            if (shouldBeTracked) {
                val password = oldField.name.orEmpty() + ": " + oldField.value.orEmpty()
                list += BitwardenCipher.Login.PasswordHistory(
                    password = password,
                    lastUsedDate = now,
                )
            }
        }

        val shouldDeDuplicateAndSort =
            originCiphers.isNotEmpty()
        if (shouldDeDuplicateAndSort) {
            list
                .distinctBy { it.id }
                .sortedBy { it.lastUsedDate }
        } else {
            list
        }
    } else {
        emptyList()
    }

    val keyBase64 = old.keyBase64OrGenerate(
        cryptoGenerator = cryptoGenerator,
        base64Service = base64Service,
    )
    val cipherId = old?.cipherId ?: cryptoGenerator.uuid()
    val createdDate = old?.createdDate ?: request.now
    val deletedDate = old?.deletedDate
    val archivedDate = old?.archivedDate
    return BitwardenCipher(
        accountId = accountId,
        cipherId = cipherId,
        folderId = folderId,
        organizationId = organizationId,
        collectionIds = collectionIds,
        revisionDate = now,
        createdDate = createdDate,
        deletedDate = deletedDate,
        archivedDate = archivedDate,
        keyBase64 = keyBase64,
        // service fields
        service = BitwardenService(
            remote = old?.service?.remote,
            deleted = false,
            version = BitwardenService.VERSION,
        ),
        remoteEntity = old?.remoteEntity,
        // common
        name = request.title,
        notes = request.note?.takeIf { it.isNotEmpty() },
        favorite = favourite,
        fields = fields,
        tags = tags,
        attachments = attachments,
        reprompt = reprompt,
        // types
        type = type,
        login = login,
        secureNote = secureNote,
        card = card,
        identity = identity,
        sshKey = sshKey,
        // other
        passwordHistory = passwordHistory,
    )
}

private fun BitwardenCipher.Field.LinkedId.Companion.of(
    linkedId: DSecret.Field.LinkedId,
) = when (linkedId) {
    DSecret.Field.LinkedId.Login_Username -> BitwardenCipher.Field.LinkedId.Login_Username
    DSecret.Field.LinkedId.Login_Password -> BitwardenCipher.Field.LinkedId.Login_Password
    DSecret.Field.LinkedId.Card_CardholderName -> BitwardenCipher.Field.LinkedId.Card_CardholderName
    DSecret.Field.LinkedId.Card_ExpMonth -> BitwardenCipher.Field.LinkedId.Card_ExpMonth
    DSecret.Field.LinkedId.Card_ExpYear -> BitwardenCipher.Field.LinkedId.Card_ExpYear
    DSecret.Field.LinkedId.Card_Code -> BitwardenCipher.Field.LinkedId.Card_Code
    DSecret.Field.LinkedId.Card_Brand -> BitwardenCipher.Field.LinkedId.Card_Brand
    DSecret.Field.LinkedId.Card_Number -> BitwardenCipher.Field.LinkedId.Card_Number
    DSecret.Field.LinkedId.Identity_Title -> BitwardenCipher.Field.LinkedId.Identity_Title
    DSecret.Field.LinkedId.Identity_MiddleName -> BitwardenCipher.Field.LinkedId.Identity_MiddleName
    DSecret.Field.LinkedId.Identity_Address1 -> BitwardenCipher.Field.LinkedId.Identity_Address1
    DSecret.Field.LinkedId.Identity_Address2 -> BitwardenCipher.Field.LinkedId.Identity_Address2
    DSecret.Field.LinkedId.Identity_Address3 -> BitwardenCipher.Field.LinkedId.Identity_Address3
    DSecret.Field.LinkedId.Identity_City -> BitwardenCipher.Field.LinkedId.Identity_City
    DSecret.Field.LinkedId.Identity_State -> BitwardenCipher.Field.LinkedId.Identity_State
    DSecret.Field.LinkedId.Identity_PostalCode -> BitwardenCipher.Field.LinkedId.Identity_PostalCode
    DSecret.Field.LinkedId.Identity_Country -> BitwardenCipher.Field.LinkedId.Identity_Country
    DSecret.Field.LinkedId.Identity_Company -> BitwardenCipher.Field.LinkedId.Identity_Company
    DSecret.Field.LinkedId.Identity_Email -> BitwardenCipher.Field.LinkedId.Identity_Email
    DSecret.Field.LinkedId.Identity_Phone -> BitwardenCipher.Field.LinkedId.Identity_Phone
    DSecret.Field.LinkedId.Identity_Ssn -> BitwardenCipher.Field.LinkedId.Identity_Ssn
    DSecret.Field.LinkedId.Identity_Username -> BitwardenCipher.Field.LinkedId.Identity_Username
    DSecret.Field.LinkedId.Identity_PassportNumber -> BitwardenCipher.Field.LinkedId.Identity_PassportNumber
    DSecret.Field.LinkedId.Identity_LicenseNumber -> BitwardenCipher.Field.LinkedId.Identity_LicenseNumber
    DSecret.Field.LinkedId.Identity_FirstName -> BitwardenCipher.Field.LinkedId.Identity_FirstName
    DSecret.Field.LinkedId.Identity_LastName -> BitwardenCipher.Field.LinkedId.Identity_LastName
    DSecret.Field.LinkedId.Identity_FullName -> BitwardenCipher.Field.LinkedId.Identity_FullName
}

private suspend fun BitwardenCipher.Login.Companion.of(
    cryptoGenerator: CryptoGenerator,
    base64Service: Base64Service,
    getPasswordStrength: GetPasswordStrength,
    request: CreateRequest,
    now: Instant,
    old: BitwardenCipher? = null,
): BitwardenCipher.Login {
    return of(
        cryptoGenerator = cryptoGenerator,
        base64Service = base64Service,
        getPasswordStrength = getPasswordStrength,
        now = now,
        old = old,
        // new fields
        _username = request.login.username,
        _password = request.login.password,
        _totp = request.login.totp,
        _uris = request.uris,
        _fido2Credentials = request.fido2Credentials,
    )
}

internal suspend fun BitwardenCipher.Login.Companion.of(
    cryptoGenerator: CryptoGenerator,
    base64Service: Base64Service,
    getPasswordStrength: GetPasswordStrength,
    now: Instant,
    old: BitwardenCipher? = null,
    // new fields
    _username: String? = old?.login?.username,
    _password: String? = old?.login?.password,
    _totp: String? = old?.login?.totp,
    _uris: List<DSecret.Uri>? = old?.login?.uris
        ?.map { uri ->
            uri.toDomain()
        },
    _fido2Credentials: List<DSecret.Login.Fido2Credentials>? = old?.login?.fido2Credentials
        ?.map { credentials ->
            credentials.toDomain()
        },
): BitwardenCipher.Login {
    val oldLogin = old?.login

    val username = _username?.takeIf { it.isNotEmpty() }
    val password = _password?.takeIf { it.isNotEmpty() }

    val passwordRevisionDate = now
        .takeIf {
            // The password must be v2+ to have the revision date.
            oldLogin != null &&
                    oldLogin.password != password &&
                    (oldLogin.passwordRevisionDate != null || oldLogin.password != null)
        }
        ?: oldLogin?.passwordRevisionDate
        // Password revision date:
        // When we edit or create an item with a password, we must set the
        // passwords revision date. Otherwise you loose the info of when the
        // password was created.
        ?: (old?.revisionDate ?: now)
            .takeIf { password != null }
    val passwordStrength = password
        ?.let { pwd ->
            getPasswordStrength(pwd)
                .map { ps ->
                    BitwardenCipher.Login.PasswordStrength(
                        password = pwd,
                        crackTimeSeconds = ps.crackTimeSeconds,
                        version = ps.version,
                    )
                }
        }?.attempt()?.bind()?.getOrNull()

    val uris = _uris
        .orEmpty()
        .mapNotNull { uri ->
            // Filter out urls that are empty
            if (uri.uri.isEmpty()) {
                return@mapNotNull null
            }

            val match = when (uri.match) {
                null -> null
                DSecret.Uri.MatchType.Domain -> BitwardenCipher.Login.Uri.MatchType.Domain
                DSecret.Uri.MatchType.Host -> BitwardenCipher.Login.Uri.MatchType.Host
                DSecret.Uri.MatchType.StartsWith -> BitwardenCipher.Login.Uri.MatchType.StartsWith
                DSecret.Uri.MatchType.Exact -> BitwardenCipher.Login.Uri.MatchType.Exact
                DSecret.Uri.MatchType.RegularExpression -> BitwardenCipher.Login.Uri.MatchType.RegularExpression
                DSecret.Uri.MatchType.Never -> BitwardenCipher.Login.Uri.MatchType.Never
            }
            val uriChecksumBase64 = BitwardenCipher.Login.Uri.getUrlChecksumBase64(
                cryptoGenerator = cryptoGenerator,
                base64Service = base64Service,
                uri = uri.uri,
            )
            BitwardenCipher.Login.Uri(
                uri = uri.uri,
                uriChecksumBase64 = uriChecksumBase64,
                match = match,
            )
        }
    val fido2Credentials = _fido2Credentials
        .orEmpty()
        .map {
            BitwardenCipher.Login.Fido2Credentials(
                credentialId = it.credentialId,
                keyType = it.keyType,
                keyAlgorithm = it.keyAlgorithm,
                keyCurve = it.keyCurve,
                keyValue = it.keyValue,
                rpId = it.rpId,
                rpName = it.rpName,
                counter = it.counter?.toString() ?: "",
                userHandle = it.userHandle,
                userName = it.userName,
                userDisplayName = it.userDisplayName,
                discoverable = it.discoverable.toString(),
                creationDate = it.creationDate,
            )
        }

    val totp = _totp?.takeIf { it.isNotEmpty() }
    return BitwardenCipher.Login(
        username = username,
        password = password,
        passwordStrength = passwordStrength,
        passwordRevisionDate = passwordRevisionDate,
        uris = uris,
        fido2Credentials = fido2Credentials,
        totp = totp,
    )
}

private suspend fun BitwardenCipher.SecureNote.Companion.of(
    request: CreateRequest,
): BitwardenCipher.SecureNote = BitwardenCipher.SecureNote(
    type = BitwardenCipher.SecureNote.Type.Generic,
)

private suspend fun BitwardenCipher.SshKey.Companion.of(
    request: CreateRequest,
): BitwardenCipher.SshKey = BitwardenCipher.SshKey(
    privateKey = request.sshKey.privateKey,
    publicKey = request.sshKey.publicKey,
    fingerprint = request.sshKey.fingerprint,
)

private suspend fun BitwardenCipher.Card.Companion.of(
    request: CreateRequest,
): BitwardenCipher.Card = BitwardenCipher.Card(
    cardholderName = request.card.cardholderName,
    brand = request.card.brand,
    number = request.card.number,
    expMonth = request.card.expMonth,
    expYear = request.card.expYear,
    code = request.card.code,
)

private suspend fun BitwardenCipher.Identity.Companion.of(
    request: CreateRequest,
): BitwardenCipher.Identity = BitwardenCipher.Identity(
    title = request.identity.title,
    firstName = request.identity.firstName,
    middleName = request.identity.middleName,
    lastName = request.identity.lastName,
    address1 = request.identity.address1,
    address2 = request.identity.address2,
    address3 = request.identity.address3,
    city = request.identity.city,
    state = request.identity.state,
    postalCode = request.identity.postalCode,
    country = request.identity.country,
    company = request.identity.company,
    email = request.identity.email,
    phone = request.identity.phone,
    ssn = request.identity.ssn,
    username = request.identity.username,
    passportNumber = request.identity.passportNumber,
    licenseNumber = request.identity.licenseNumber,
)
