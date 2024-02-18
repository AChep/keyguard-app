package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.combine
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.flatTap
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.canDelete
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.usecase.AddCipher
import com.artemchep.keyguard.common.usecase.AddFolder
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.common.usecase.TrashCipherById
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.feature.confirmation.organization.FolderInfo
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyDatabase
import kotlinx.collections.immutable.toPersistentList
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class AddCipherImpl(
    private val modifyDatabase: ModifyDatabase,
    private val addFolder: AddFolder,
    private val trashCipherById: TrashCipherById,
    private val cryptoGenerator: CryptoGenerator,
    private val getPasswordStrength: GetPasswordStrength,
) : AddCipher {
    companion object {
        private const val TAG = "AddCipher.bitwarden"

        private const val FILES_DIR = "attachments_pending"
    }

    constructor(directDI: DirectDI) : this(
        modifyDatabase = directDI.instance(),
        addFolder = directDI.instance(),
        trashCipherById = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        getPasswordStrength = directDI.instance(),
    )

    override fun invoke(
        cipherIdsToRequests: Map<String?, CreateRequest>,
    ): IO<List<String>> = ioEffect {
        cipherIdsToRequests
            .mapValues { (_, request) ->
                copyLocalFilesToInternalStorageIo(request)
                    .bind()
            }
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
                    BitwardenCipher.of(
                        cryptoGenerator = cryptoGenerator,
                        getPasswordStrength = getPasswordStrength,
                        now = now,
                        request = request,
                        old = old,
                    )
                }
            if (models.isEmpty()) {
                return@modifyDatabase ModifyDatabase.Result(
                    changedAccountIds = emptySet(),
                    value = emptyList(),
                )
            }
            dao.transaction {
                models.forEach { cipher ->
                    dao.insert(
                        cipherId = cipher.cipherId,
                        accountId = cipher.accountId,
                        folderId = cipher.folderId,
                        data = cipher,
                    )
                }
            }

            val changedAccountIds = models
                .map { AccountId(it.accountId) }
                .toSet()
            ModifyDatabase.Result(
                changedAccountIds = changedAccountIds,
                value = models
                    .map { it.cipherId },
            )
        }
    }.flatTap {
        val cipherIdsToTrash = cipherIdsToRequests
            .values
            .asSequence()
            .mapNotNull {
                val ciphers = it.merge?.ciphers
                    ?: return@mapNotNull null
                // Ignore the request if we do not need to trash the
                // origin ciphers.
                if (!it.merge.removeOrigin) {
                    return@mapNotNull null
                }
                ciphers
            }
            .flatten()
            .filter { cipher ->
                cipher.canDelete() &&
                        !cipher.deleted
            }
            .map { cipher ->
                cipher.id
            }
            .toSet()
        if (cipherIdsToTrash.isEmpty()) {
            return@flatTap ioUnit()
        }
        trashCipherById(cipherIdsToTrash)
    }

    private fun copyLocalFilesToInternalStorageIo(
        request: CreateRequest,
    ): IO<CreateRequest> = request
        .attachments
        .mapNotNull { attachment ->
            when (attachment) {
                is CreateRequest.Attachment.Remote -> io(attachment)
                is CreateRequest.Attachment.Local -> null
//                    copyLocalFileToInternalStorageIo(attachment.uri)
//                        .effectMap { file ->
//                            val uri = file.toUri()
//                            val size = attachment.size
//                                ?: withContext(Dispatchers.IO) { file.length() }
//                            attachment.copy(
//                                uri = uri,
//                                size = size,
//                            )
//                        }
            }
        }
        .combine(bucket = 2)
        .effectMap { attachments ->
            val list = attachments
                .toPersistentList()
            request.copy(attachments = list)
        }

//    private fun copyLocalFileToInternalStorageIo(
//        uri: Uri,
//    ): IO<File> = ioEffect(Dispatchers.IO) {
//        val dstDir = context.filesDir.resolve("$FILES_DIR/")
//        // If the URI points to a file, check if it is already in the
//        // internal storage.
//        if (uri.scheme == "file") {
//            val srcFile = uri.toFile()
//
//            // A parent directory must match the destination directory.
//            val existsInDstDir = generateSequence(srcFile) { it.parentFile }
//                .any { it == dstDir }
//            if (existsInDstDir) {
//                return@ioEffect srcFile
//            }
//        }
//
//        val dstFileName = cryptoGenerator.uuid() + ".tmp"
//        val dstFile = dstDir.resolve(dstFileName)
//        // In case the dst folder does not exist.
//        dstFile.parentFile?.mkdirs()
//
//        // Copy source resource into the dst file.
//        val srcInputStream = context.contentResolver.openInputStream(uri)
//        requireNotNull(srcInputStream) {
//            "The content provider has crashed, failed to access the resource!"
//        }
//        srcInputStream.use { input ->
//            dstFile.outputStream().use { output ->
//                input.copyTo(output)
//            }
//        }
//
//        dstFile
//    }
}

private suspend fun BitwardenCipher.Companion.of(
    cryptoGenerator: CryptoGenerator,
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
        null,
        DSecret.Type.None,
        -> error("Cipher must have a type!")
    }

    var login: BitwardenCipher.Login? = null
    var secureNote: BitwardenCipher.SecureNote? = null
    var card: BitwardenCipher.Card? = null
    var identity: BitwardenCipher.Identity? = null
    when (type) {
        BitwardenCipher.Type.Login -> {
            login = BitwardenCipher.Login.of(
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
                        BitwardenCipher.Attachment.Local(
                            id = attachment.id,
                            url = attachment.uri.toString(),
                            fileName = attachment.name,
                            size = attachment.size,
                        )
                    }
                }
                newAttachment
            }
    }

    val cipherId = old?.cipherId ?: cryptoGenerator.uuid()
    val createdDate = old?.createdDate ?: request.now
    val deletedDate = old?.deletedDate
    return BitwardenCipher(
        accountId = accountId,
        cipherId = cipherId,
        folderId = folderId,
        organizationId = organizationId,
        collectionIds = collectionIds,
        revisionDate = now,
        createdDate = createdDate,
        deletedDate = deletedDate,
        // service fields
        service = BitwardenService(
            remote = old?.service?.remote,
            deleted = false,
            version = BitwardenService.VERSION,
        ),
        // common
        name = request.title,
        notes = request.note,
        favorite = favourite,
        fields = fields,
        attachments = attachments,
        reprompt = reprompt,
        // types
        type = type,
        login = login,
        secureNote = secureNote,
        card = card,
        identity = identity,
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
    getPasswordStrength: GetPasswordStrength,
    request: CreateRequest,
    now: Instant,
    old: BitwardenCipher? = null,
): BitwardenCipher.Login {
    val oldLogin = old?.login

    val username = request.login.username?.takeIf { it.isNotEmpty() }
    val password = request.login.password?.takeIf { it.isNotEmpty() }

    val passwordHistory = if (oldLogin != null) {
        val list = oldLogin.passwordHistory.toMutableList()
        // The existing password was changed, so we should
        // add the it one to the history.
        if (password != oldLogin.password && oldLogin.password != null) {
            list += BitwardenCipher.Login.PasswordHistory(
                password = oldLogin.password,
                lastUsedDate = now,
            )
        }
        list
    } else {
        emptyList()
    }
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

    val uris = request
        .uris
        .mapNotNull { uri ->
            // Filter out urls that are empty
            if (uri.uri.isEmpty()) {
                return@mapNotNull null
            }

            val match = uri.match
                .takeUnless { it == DSecret.Uri.MatchType.default }
                ?.let {
                    when (it) {
                        DSecret.Uri.MatchType.Domain -> BitwardenCipher.Login.Uri.MatchType.Domain
                        DSecret.Uri.MatchType.Host -> BitwardenCipher.Login.Uri.MatchType.Host
                        DSecret.Uri.MatchType.StartsWith -> BitwardenCipher.Login.Uri.MatchType.StartsWith
                        DSecret.Uri.MatchType.Exact -> BitwardenCipher.Login.Uri.MatchType.Exact
                        DSecret.Uri.MatchType.RegularExpression -> BitwardenCipher.Login.Uri.MatchType.RegularExpression
                        DSecret.Uri.MatchType.Never -> BitwardenCipher.Login.Uri.MatchType.Never
                    }
                }
            BitwardenCipher.Login.Uri(
                uri = uri.uri,
                match = match,
            )
        }
    val fido2Credentials = request
        .fido2Credentials
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

    val totp = request.login.totp?.takeIf { it.isNotEmpty() }
    return BitwardenCipher.Login(
        username = username,
        password = password,
        passwordStrength = passwordStrength,
        passwordRevisionDate = passwordRevisionDate,
        passwordHistory = passwordHistory,
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
