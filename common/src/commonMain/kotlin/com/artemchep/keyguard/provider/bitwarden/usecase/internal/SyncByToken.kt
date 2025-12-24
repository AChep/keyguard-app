package com.artemchep.keyguard.provider.bitwarden.usecase.internal

import app.cash.sqldelight.coroutines.asFlow
import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.getEntries
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.database.modifiers.modifyGroup
import app.keemobile.kotpass.database.modifiers.removeEntry
import app.keemobile.kotpass.database.modifiers.removeGroup
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.TimeData
import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.biFlatTap
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.AccountTask
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.keepass.KeePassUtil
import com.artemchep.keyguard.common.service.keepass.generateAttachmentUrl
import com.artemchep.keyguard.common.service.keepass.getPublicCustomDataStringOrNull
import com.artemchep.keyguard.common.service.keepass.getVersionString
import com.artemchep.keyguard.common.service.keepass.modifyEntryWithTimes
import com.artemchep.keyguard.common.service.keepass.openKeePassDatabase
import com.artemchep.keyguard.common.service.keepass.saveKeePassDatabase
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.Base32Service
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.common.usecase.Watchdog
import com.artemchep.keyguard.common.util.to0DigitsNanosOfSecond
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.core.store.DatabaseSyncer
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.core.store.bitwarden.BitwardenMeta
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import com.artemchep.keyguard.provider.bitwarden.api.merge
import com.artemchep.keyguard.provider.bitwarden.api.syncX
import com.artemchep.keyguard.provider.bitwarden.sync.SyncManager
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import okio.ByteString
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.util.UUID
import kotlin.String
import kotlin.collections.filter
import kotlin.collections.map
import kotlin.collections.orEmpty
import kotlin.collections.plus
import kotlin.sequences.map
import kotlin.text.orEmpty
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

interface SyncByToken : (ServiceToken) -> IO<Unit>

class SyncByTokenImpl(
    private val syncByBitwardenToken: SyncByBitwardenToken,
    private val syncByKeePassToken: SyncByKeePassToken,
) : SyncByToken {
    constructor(directDI: DirectDI) : this(
        syncByBitwardenToken = directDI.instance(),
        syncByKeePassToken = directDI.instance(),
    )

    override fun invoke(token: ServiceToken): IO<Unit> = when (token) {
        is BitwardenToken -> syncByBitwardenToken(token)
        is KeePassToken -> syncByKeePassToken(token)
    }
}

class SyncByKeePassTokenImpl(
    private val logRepository: LogRepository,
    private val cipherEncryptor: CipherEncryptor,
    private val cryptoGenerator: CryptoGenerator,
    private val base32Service: Base32Service,
    private val base64Service: Base64Service,
    private val fileService: FileService,
    private val getPasswordStrength: GetPasswordStrength,
    private val json: Json,
    private val httpClient: HttpClient,
    private val db: VaultDatabaseManager,
    private val dbSyncer: DatabaseSyncer,
    private val watchdog: Watchdog,
) : SyncByKeePassToken {
    companion object {
        private const val TAG = "SyncById.keepass"

        private const val KEYGUARD_PREFIX = "Keyguard: "
    }

    enum class TranslationTag(
        val key: String,
    ) {
        FAVORITE(key = "Favorite"),
    }

    enum class TranslationField(
        val key: String,
    ) {
        AUTH_REPROMPT(key = "Authentication Re-Prompt"),
        PASSWORD_REVISION_DATE(key = "Password Revision Date"),
        PASSWORD_N_TEMPLATE(key = "Password #%"),
        PASSWORD_N_LAST_USED_TEMPLATE(key = "Password #% Last Used"),

        // Card
        CARD_CARDHOLDER_NAME(key = "card_cardholderName"),
        CARD_BRAND(key = "card_brand"),
        CARD_NUMBER(key = "card_number"),
        CARD_EXP_MONTH(key = "card_expMonth"),
        CARD_EXP_YEAR(key = "card_expYear"),
        CARD_CODE(key = "card_code"),

        // Identity
        IDENTITY_TITLE(key = "identity_title"),
        IDENTITY_NAME(key = "identity_name"),
        IDENTITY_FIRST_NAME(key = "identity_firstName"),
        IDENTITY_MIDDLE_NAME(key = "identity_middleName"),
        IDENTITY_LAST_NAME(key = "identity_lastName"),
        IDENTITY_ADDRESS(key = "identity_address"),
        IDENTITY_ADDRESS1(key = "identity_address1"),
        IDENTITY_ADDRESS2(key = "identity_address2"),
        IDENTITY_ADDRESS3(key = "identity_address3"),
        IDENTITY_CITY(key = "identity_city"),
        IDENTITY_STATE(key = "identity_state"),
        IDENTITY_POSTAL_CODE(key = "identity_postalCode"),
        IDENTITY_COUNTRY(key = "identity_country"),
        IDENTITY_COMPANY(key = "identity_company"),
        IDENTITY_EMAIL(key = "identity_email"),
        IDENTITY_PHONE(key = "identity_phone"),
        IDENTITY_SSN(key = "identity_ssn"),
        IDENTITY_PASSPORT_NUMBER(key = "identity_passportNumber"),
        IDENTITY_LICENSE_NUMBER(key = "identity_licenseNumber"),

        // SSH Key
        SSH_PRIVATE_KEY(key = "ssh_privateKey"),
        SSH_PUBLIC_KEY(key = "ssh_publicKey"),
        SSH_FINGERPRINT(key = "ssh_fingerprint"),
    }

    private val mutex = Mutex()

    constructor(directDI: DirectDI) : this(
        logRepository = directDI.instance(),
        cipherEncryptor = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        base32Service = directDI.instance(),
        base64Service = directDI.instance(),
        fileService = directDI.instance(),
        getPasswordStrength = directDI.instance(),
        json = directDI.instance(),
        httpClient = directDI.instance(),
        db = directDI.instance(),
        dbSyncer = directDI.instance(),
        watchdog = directDI.instance(),
    )

    data class KeePassFolder(
        val group: Group,
        val name: String,
        val revisionDate: Instant,
    ) {
        val id = group.uuid.toString()
    }

    data class KeePassCipher(
        val group: Group,
        val cipher: Entry,
        val revisionDate: Instant,
    ) {
        val id = cipher.uuid.toString()
    }

    override fun invoke(user: KeePassToken): IO<Unit> = watchdog
        .track(
            accountId = AccountId(user.id),
            accountTask = AccountTask.SYNC,
        ) {
            val initialKeePassDb = openKeePassDatabase(
                token = user,
                fileService = fileService,
                base64Service = base64Service,
            )
            var keePassDb = initialKeePassDb
            val now = Clock.System.now()

            val remoteFolders = kotlin.run {
                data class GroupNode(
                    val parent: String?,
                    val group: Group,
                )

                val groups = mutableMapOf<String, GroupNode>()

                // Collect all the groups, EXCEPT the root group. I don't want
                // all groups to be nested in a single root group.

                fun traverse(
                    parent: String? = null,
                    group: Group,
                ) {
                    val id = group.uuid.toString()
                    groups[id] = GroupNode(
                        parent = parent,
                        group = group,
                    )
                    group.groups.forEach { child ->
                        traverse(
                            parent = id,
                            group = child,
                        )
                    }
                }

                val rootGroup = keePassDb.content.group
                val rootGroupId = rootGroup.uuid
                    .toString()
                rootGroup.groups.forEach { group ->
                    traverse(
                        parent = rootGroupId,
                        group = group,
                    )
                }

                fun traverseUpToRoot(groupNode: GroupNode): Sequence<Group> {
                    val parent = groupNode.parent?.let(groups::get)
                        ?: return emptySequence()
                    return sequence {
                        yield(parent.group)

                        // Also emit all of the parents
                        // groups.
                        val seq = traverseUpToRoot(parent)
                        yieldAll(seq)
                    }
                }

                fun getRevisionDate(group: Group): Instant {
                    return group.times?.lastModificationTime?.toKotlinInstant()
                        ?: group.times?.creationTime?.toKotlinInstant()
                        ?: now // should never happen
                }

                groups
                    .values
                    .map { groupNode ->
                        val name = groupNode.group.name
                        val revisionDate =
                            getRevisionDate(groupNode.group)
                        KeePassFolder(
                            group = groupNode.group,
                            name = name,
                            revisionDate = revisionDate,
                        )
                    }
            }
            val remoteFoldersIds = remoteFolders
                .asSequence()
                .map { it.id }
                .toSet()
            val remoteCiphers = kotlin.run {
                fun getRevisionDate(entry: Entry): Instant {
                    val revDate = entry.times?.lastModificationTime?.toKotlinInstant()
                        ?: entry.times?.creationTime?.toKotlinInstant()
                    return revDate?.takeIf { it.toEpochMilliseconds() != 0L }
                        ?: now
                }

                val entries = keePassDb.getEntries { true }
                entries
                    .flatMap { (group, groupEntries) ->
                        groupEntries
                            .map { entry ->
                                val revisionDate =
                                    getRevisionDate(entry)
                                KeePassCipher(
                                    group = group,
                                    cipher = entry,
                                    revisionDate = revisionDate,
                                )
                            }
                    }
            }
            db.mutate(TAG) { localDb ->
                // Read all of the groups and convert them to the
                // Keyguard folders.
                val localFoldersDao = localDb
                    .folderQueries
                val localFolders = localFoldersDao
                    .getByAccountId(
                        accountId = user.id,
                    )
                    .executeAsList()
                    .map { it.data_ }
                syncX(
                    name = "folder",
                    localItems = localFolders,
                    localLens = SyncManager.LensLocal(
                        getLocalId = { it.folderId },
                        getLocalRevisionDate = { it.revisionDate },
                    ),
                    localReEncoder = { model ->
                        model
                    },
                    localDecoder = { local, remote ->
                        local to remote
                    },
                    localDeleteById = { ids ->
                        localFoldersDao.transaction {
                            ids.forEach { folderId ->
                                localFoldersDao.deleteByFolderId(
                                    folderId = folderId,
                                )
                            }
                        }
                    },
                    localPut = { models ->
                        localFoldersDao.transaction {
                            models.forEach { folder ->
                                localFoldersDao.insert(
                                    folderId = folder.folderId,
                                    accountId = folder.accountId,
                                    data = folder,
                                )
                            }
                        }
                    },
                    remoteItems = remoteFolders,
                    remoteLens = SyncManager.Lens<KeePassFolder>(
                        getId = { it.id },
                        getRevisionDate = { it.revisionDate },
                    ),
                    remoteDecoder = { remote, local ->
                        val folderId = local?.folderId
                            ?: cryptoGenerator.uuid()
                        decodeToFolderEntity(
                            accountId = user.id,
                            folderId = folderId,
                            remote = remote.group,
                            local = local,
                            revisionDate = remote.revisionDate,
                        )
                    },
                    remoteDeleteById = { id ->
                        val database = keePassDb.removeGroup(UUID.fromString(id))
                        keePassDb = saveKeePassDatabase(
                            fileService = fileService,
                            token = user,
                            database = database,
                        )
                    },
                    remoteDecodedFallback = { remote, localOrNull, e ->
                        val msg = "At this point the remote should " +
                                "already be decoded, so this block doesn't make much sense here."
                        throw IllegalStateException(msg)
                    },
                    remotePut = { (local, remote) ->
                        var remoteUuid = runCatching {
                            remote?.id
                                ?.let(UUID::fromString)
                        }.getOrElse {
                            null
                        }

                        val revisionDate = local.revisionDate

                        if (remoteUuid !== null) kotlin.run update@{
                            // Check if we have actually found the entry in the
                            // database and have changed it.
                            var updated = false
                            val database = keePassDb.modifyGroup(remoteUuid) {
                                updated = true
                                copy(
                                    name = local.name,
                                )
                            }
                            if (!updated) {
                                return@update
                            }

                            keePassDb = saveKeePassDatabase(
                                fileService = fileService,
                                token = user,
                                database = database,
                            )
                            // If the save is successful then we exit
                            // the save process.
                            return@syncX local
                        }

                        remoteUuid = UUID.randomUUID()
                        // Add a new group
                        val database = keePassDb.modifyContent {
                            val newGroup = Group(
                                uuid = remoteUuid,
                                name = local.name,
                            )

                            val childGroups = group.groups + newGroup
                            val rootGroup = group.copy(
                                groups = childGroups,
                            )
                            copy(group = rootGroup)
                        }
                        keePassDb = saveKeePassDatabase(
                            fileService = fileService,
                            token = user,
                            database = database,
                        )
                        local.copy(
                            service = BitwardenService(
                                remote = BitwardenService.Remote(
                                    id = remoteUuid.toString(),
                                    revisionDate = revisionDate,
                                    deletedDate = null,
                                ),
                                version = BitwardenService.VERSION,
                            ),
                        )
                    },
                    onLog = { msg, logLevel ->
                        logRepository.add(TAG, msg, logLevel)
                    },
                )

                // Read all of the entries and convert them to the
                // Keyguard ciphers.
                val localCiphersDao = localDb
                    .cipherQueries
                val localCiphers = localCiphersDao
                    .getByAccountId(
                        accountId = user.id,
                    )
                    .executeAsList()
                    .map { it.data_ }
                syncX(
                    name = "cipher",
                    localItems = localCiphers,
                    localLens = SyncManager.LensLocal(
                        getLocalId = { it.cipherId },
                        getLocalRevisionDate = { it.revisionDate },
                    ),
                    localReEncoder = { model ->
                        model
                    },
                    localDecoder = { local, remote ->
                        local to remote
                    },
                    localDeleteById = { ids ->
                        localCiphersDao.transaction {
                            ids.forEach { cipherId ->
                                localCiphersDao.deleteByCipherId(
                                    cipherId = cipherId,
                                )
                            }
                        }
                    },
                    localPut = { models ->
                        localCiphersDao.transaction {
                            models.forEach { cipher ->
                                localCiphersDao.insert(
                                    folderId = cipher.folderId,
                                    accountId = cipher.accountId,
                                    cipherId = cipher.cipherId,
                                    data = cipher,
                                    updatedAt = cipher.revisionDate,
                                )
                            }
                        }
                    },
                    remoteItems = remoteCiphers,
                    remoteLens = SyncManager.Lens<KeePassCipher>(
                        getId = { it.id },
                        getRevisionDate = { it.revisionDate },
                    ),
                    remoteDecoder = { remote, local ->
                        val folderId = remote.group.uuid.toString()
                            .takeIf { it in remoteFoldersIds }
                        val cipherId = local?.cipherId
                            ?: cryptoGenerator.uuid()
                        decodeToCipherEntity(
                            accountId = user.id,
                            folderId = folderId,
                            cipherId = cipherId,
                            remote = remote.cipher,
                            local = local,
                            revisionDate = remote.revisionDate,
                            binaries = keePassDb.binaries,
                        )
                    },
                    remoteDeleteById = { id ->
                        val database = keePassDb.removeEntry(UUID.fromString(id))
                        keePassDb = saveKeePassDatabase(
                            fileService = fileService,
                            token = user,
                            database = database,
                        )
                    },
                    remoteDecodedFallback = { remote, localOrNull, e ->
                        val msg = "At this point the remote should " +
                                "already be decoded, so this block doesn't make much sense here."
                        throw IllegalStateException(msg)
                    },
                    remotePut = { (local, remote) ->
                        var remoteUuid = runCatching {
                            remote?.id
                                ?.let(UUID::fromString)
                        }.getOrElse {
                            null
                        }
                        val revisionDate = local.revisionDate
                            .to0DigitsNanosOfSecond()
                        val newEntry = encodeCipherEntity(local, remote?.cipher)

                        val newLocal = local.copy(
                            service = BitwardenService(
                                remote = BitwardenService.Remote(
                                    id = newEntry.uuid.toString(),
                                    revisionDate = revisionDate,
                                    deletedDate = null,
                                ),
                                version = BitwardenService.VERSION,
                            ),
                            revisionDate = revisionDate,
                            expiredDate = local.expiredDate?.to0DigitsNanosOfSecond(),
                            deletedDate = local.deletedDate?.to0DigitsNanosOfSecond(),
                        )

                        if (remoteUuid !== null) kotlin.run update@{
                            // Check if we have actually found the entry in the
                            // database and have changed it.
                            var updated = false
                            val database = keePassDb.modifyEntryWithTimes(remoteUuid) {
                                updated = true
                                newEntry
                            }
                            if (!updated) {
                                return@update
                            }

                            keePassDb = saveKeePassDatabase(
                                fileService = fileService,
                                token = user,
                                database = database,
                            )
                            // If the save is successful then we exit
                            // the save process.
                            return@syncX newLocal
                        }

                        // Add a new group
                        val database = keePassDb.modifyContent {
                            val entries = buildList {
                                addAll(group.entries)
                                add(newEntry)
                            }
                            val group = group.copy(
                                entries = entries,
                            )
                            copy(group = group)
                        }
                        keePassDb = saveKeePassDatabase(
                            fileService = fileService,
                            token = user,
                            database = database,
                        )
                        newLocal
                    },
                    onLog = { msg, logLevel ->
                        logRepository.add(TAG, msg, logLevel)
                    },
                )
            }.bind()

            // CVV
            // Card holder
            // Number
            // PIN
            // + expiry date

            val profileName = keePassDb.content.meta.name.takeIf { it.isNotEmpty() }
                ?: keePassDb.header.getPublicCustomDataStringOrNull("KPXC_PUBLIC_NAME")
            val profileColor = keePassDb.content.meta.color?.takeIf { it.isNotEmpty() }
                ?: keePassDb.header.getPublicCustomDataStringOrNull("KPXC_PUBLIC_COLOR")
            val profile = BitwardenProfile(
                accountId = user.id,
                profileId = user.id,
                name = profileName.orEmpty(),
                description = keePassDb.content.meta.description,
                avatarColor = profileColor,
                keyBase64 = base64Service.encodeToString(""),
                privateKeyBase64 = base64Service.encodeToString(""),
                email = keePassDb.content.meta.defaultUser,
                securityStamp = keePassDb.header.cipherId.toString(),
                masterPasswordHint = null,
                masterPasswordHintEnabled = null,
                unofficialServer = false,
                serverVersion = keePassDb.header.getVersionString(),
            )
            db.mutate(TAG) {
                val existingProfile = it
                    .profileQueries
                    .getByAccountId(
                        accountId = user.id,
                    )
                    .executeAsOneOrNull()
                // Insert updated profile.
                val newMergedProfile = merge(
                    remote = profile,
                    local = existingProfile?.data_,
                )
                if (newMergedProfile != existingProfile?.data_) {
                    it.profileQueries.insert(
                        profileId = newMergedProfile.profileId,
                        accountId = newMergedProfile.accountId,
                        data = newMergedProfile,
                    ).await()
                }
            }.bind()
        }
        .biFlatTap(
            ifException = { e ->
                db.mutate(TAG) {
                    val dao = it.metaQueries
                    val existingMeta = dao
                        .getByAccountId(accountId = user.id)
                        .asFlow()
                        .map { it.executeAsList().firstOrNull()?.data_ }
                        .toIO()
                        .bind()

                    val reason = e.localizedMessage ?: e.message
                    val requiresAuthentication = e is HttpException &&
                            (
                                    e.statusCode == HttpStatusCode.Unauthorized ||
                                            e.statusCode == HttpStatusCode.Forbidden
                                    )

                    val now = Clock.System.now()
                    val meta = BitwardenMeta(
                        accountId = user.id,
                        // Copy the existing successful sync timestamp
                        // into a new model.
                        lastSyncTimestamp = existingMeta?.lastSyncTimestamp,
                        lastSyncResult = BitwardenMeta.LastSyncResult.Failure(
                            timestamp = now,
                            reason = reason,
                            requiresAuthentication = requiresAuthentication,
                        ),
                    )

                    dao.insert(
                        accountId = user.id,
                        data = meta,
                    )
                }
            },
            ifSuccess = {
                db.mutate(TAG) {
                    val now = Clock.System.now()
                    val meta = BitwardenMeta(
                        accountId = user.id,
                        lastSyncTimestamp = now,
                        lastSyncResult = BitwardenMeta.LastSyncResult.Success,
                    )

                    val dao = it.metaQueries
                    dao.insert(
                        accountId = user.id,
                        data = meta,
                    )
                }
            },
        )
        .measure { duration, _ ->
            val message = "Synced user ${user.id} in $duration."
            logRepository.post(TAG, message)
        }

    //
    // Encode
    //

    private suspend fun encodeFolderEntity(
        local: BitwardenFolder,
        remote: Group,
    ) {
    }

    private suspend fun encodeCipherEntity(
        local: BitwardenCipher,
        remote: Entry?,
    ): Entry {
        val scope = EncodeToCipherScope()
        val tags = buildSet {
            val nativeTagsSeq = local.tags
                .asSequence()
                .map { tag -> tag.name }
            addAll(nativeTagsSeq)

            // We encode the Favorite flag as a
            // tag, as per KeePassXC export.
            if (local.favorite) {
                val favoriteTag = TranslationTag.FAVORITE.key
                add(favoriteTag)
            }
        }.toList()

        scope.setPlain(BasicField.Title(), local.name)
        scope.setPlain(BasicField.Notes(), local.notes?.takeIf { it.isNotEmpty() })

        // Type
        kotlin.run {
            val value = local.type.verboseKey
            scope.setPlain("${KEYGUARD_PREFIX}Entry Type", value)
        }

        // Re-prompt
        if (local.reprompt == BitwardenCipher.RepromptType.Password) {
            val value = "Password"
            scope.setPlain(TranslationField.AUTH_REPROMPT.key, value)
        }

        if (local.login != null) {
            encodeCipherLoginEntity(
                scope = scope,
                login = local.login,
            )
        }
        if (local.card != null) {
            encodeCipherCardEntity(
                scope = scope,
                card = local.card,
            )
        }
        if (local.identity != null) {
            encodeCipherIdentityEntity(
                scope = scope,
                identity = local.identity,
            )
        }
        if (local.sshKey != null) {
            encodeCipherSshKeyEntity(
                scope = scope,
                sshKey = local.sshKey,
            )
        }

        // Put in the custom fields and ensure that there are
        // no naming conflicts.

        local.fields.forEach { field ->
            fun putTextField(key: String?, value: String?, concealed: Boolean) {
                if (key == null || value == null)
                    return
                if (concealed) {
                    scope.setConcealed(key, value)
                } else scope.setPlain(key, value)
            }

            when (field.type) {
                BitwardenCipher.Field.Type.Hidden -> {
                    putTextField(
                        key = field.name,
                        value = field.value,
                        concealed = true,
                    )
                }

                BitwardenCipher.Field.Type.Text -> {
                    putTextField(
                        key = field.name,
                        value = field.value,
                        concealed = false,
                    )
                }

                BitwardenCipher.Field.Type.Boolean -> {
                    // Convert the value into a string, hoping that
                    // the parser will also convert it back to string.
                    putTextField(
                        key = field.name,
                        value = field.value,
                        concealed = false,
                    )
                }

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

        val uuid = remote?.uuid
            ?: UUID.randomUUID()
        val icon = remote?.icon
            ?: PredefinedIcon.Key
        val customIconUuid = remote?.customIconUuid
        val foregroundColor = remote?.foregroundColor
        val backgroundColor = remote?.backgroundColor
        val overrideUrl = remote?.overrideUrl.orEmpty()
        val autoType = remote?.autoType
        val binaries = remote?.binaries.orEmpty()
        val history = remote?.history.orEmpty()
        val customData = remote?.customData.orEmpty()
        val previousParentGroup = remote?.previousParentGroup
        val qualityCheck = remote?.qualityCheck ?: true

        val fields = kotlin.run {
            val pairs = scope.getAvailableFields()
                .map { it.key to it.value }
            EntryFields.of(*pairs.toTypedArray())
        }
        val times = kotlin.run {
            val revisionDateJvm = local.revisionDate.toJavaInstant()

            val base = remote?.times
                ?: TimeData.create(
                    now = local.createdDate?.toJavaInstant()
                        ?: revisionDateJvm,
                )
            base.copy(
                lastAccessTime = revisionDateJvm,
                lastModificationTime = revisionDateJvm,
            )
        }
        return Entry(
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
        )
    }

    private suspend fun encodeCipherLoginEntity(
        scope: EncodeToCipherScope,
        login: BitwardenCipher.Login,
    ) {
        scope.setPlain(BasicField.UserName(), login.username)
        scope.setConcealed(BasicField.Password(), login.password)
        scope.setConcealed("otp", login.totp)
        scope.setPlain(
            TranslationField.PASSWORD_REVISION_DATE.key,
            login.passwordRevisionDate?.toString(),
        )

        // FIDO 2 Credentials
        kotlin.run {
            val prefix = "FIDO2 Credentials Blob #"
            login.fido2Credentials.forEachIndexed { index, fido2Credentials ->
                val key = "$prefix$index"
                val value = kotlin.run {
                    val data =
                        json.encodeToString<BitwardenCipher.Login.Fido2Credentials>(fido2Credentials)
                    base64Service.encodeToString(data)
                }
                scope.setConcealed(key, value)
            }
        }

        // URL
        kotlin.run {
            val prefix = "KP2A_URL_"

            fun setUri(
                ordinal: Int,
                uri: BitwardenCipher.Login.Uri,
            ) {
                val key = if (ordinal == 0) {
                    "URL"
                } else {
                    "$prefix$ordinal"
                }
                scope.setPlain(key, uri.uri)

                val matchTypeKey = "${key}_MATCH_TYPE"
                val matchType = uri.match?.verboseKey
                scope.setPlain(matchTypeKey, matchType)
            }

            login.uris.forEachIndexed { index, uri ->
                setUri(
                    ordinal = index,
                    uri = uri,
                )
            }
        }
    }

    private suspend fun encodeCipherCardEntity(
        scope: EncodeToCipherScope,
        card: BitwardenCipher.Card,
    ) {
        scope.setPlain(TranslationField.CARD_CARDHOLDER_NAME.key, card.cardholderName)
        scope.setPlain(TranslationField.CARD_BRAND.key, card.brand)
        scope.setConcealed(TranslationField.CARD_NUMBER.key, card.number)
        scope.setPlain(TranslationField.CARD_EXP_MONTH.key, card.expMonth)
        scope.setPlain(TranslationField.CARD_EXP_YEAR.key, card.expYear)
        scope.setConcealed(TranslationField.CARD_CODE.key, card.code)
    }

    private suspend fun encodeCipherIdentityEntity(
        scope: EncodeToCipherScope,
        identity: BitwardenCipher.Identity,
    ) {
        scope.setPlain(
            TranslationField.IDENTITY_TITLE.key, identity.title,
            mappingKey = BitwardenCipher.Mapping.IDENTITY_TITLE.key,
        )
        scope.setPlain(
            TranslationField.IDENTITY_FIRST_NAME.key, identity.firstName,
            mappingKey = BitwardenCipher.Mapping.IDENTITY_FIRST_NAME.key,
        )
        scope.setPlain(
            TranslationField.IDENTITY_MIDDLE_NAME.key, identity.middleName,
            mappingKey = BitwardenCipher.Mapping.IDENTITY_MIDDLE_NAME.key,
        )
        scope.setPlain(
            TranslationField.IDENTITY_LAST_NAME.key, identity.lastName,
            mappingKey = BitwardenCipher.Mapping.IDENTITY_LAST_NAME.key,
        )
        scope.setPlain(
            TranslationField.IDENTITY_ADDRESS1.key, identity.address1,
            mappingKey = BitwardenCipher.Mapping.IDENTITY_ADDRESS1.key,
        )
        scope.setPlain(
            TranslationField.IDENTITY_ADDRESS2.key, identity.address2,
            mappingKey = BitwardenCipher.Mapping.IDENTITY_ADDRESS2.key,
        )
        scope.setPlain(
            TranslationField.IDENTITY_ADDRESS3.key, identity.address3,
            mappingKey = BitwardenCipher.Mapping.IDENTITY_ADDRESS3.key,
        )
        scope.setPlain(
            TranslationField.IDENTITY_CITY.key, identity.city,
            mappingKey = BitwardenCipher.Mapping.IDENTITY_CITY.key,
        )
        scope.setPlain(
            TranslationField.IDENTITY_STATE.key, identity.state,
            mappingKey = BitwardenCipher.Mapping.IDENTITY_STATE.key,
        )
        scope.setPlain(
            TranslationField.IDENTITY_POSTAL_CODE.key, identity.postalCode,
            mappingKey = BitwardenCipher.Mapping.IDENTITY_POSTAL_CODE.key,
        )
        scope.setPlain(
            TranslationField.IDENTITY_COUNTRY.key, identity.country,
            mappingKey = BitwardenCipher.Mapping.IDENTITY_COUNTRY.key,
        )
        scope.setPlain(
            TranslationField.IDENTITY_COMPANY.key, identity.company,
            mappingKey = BitwardenCipher.Mapping.IDENTITY_COMPANY.key,
        )
        scope.setPlain(
            TranslationField.IDENTITY_EMAIL.key, identity.email,
            mappingKey = BitwardenCipher.Mapping.IDENTITY_EMAIL.key,
        )
        scope.setPlain(
            TranslationField.IDENTITY_PHONE.key, identity.phone,
            mappingKey = BitwardenCipher.Mapping.IDENTITY_PHONE.key,
        )
        scope.setConcealed(
            TranslationField.IDENTITY_SSN.key, identity.ssn,
            mappingKey = BitwardenCipher.Mapping.IDENTITY_SSN.key,
        )
        scope.setConcealed(
            TranslationField.IDENTITY_PASSPORT_NUMBER.key, identity.passportNumber,
            mappingKey = BitwardenCipher.Mapping.IDENTITY_PASSPORT_NUMBER.key,
        )
        scope.setConcealed(
            TranslationField.IDENTITY_LICENSE_NUMBER.key, identity.licenseNumber,
            mappingKey = BitwardenCipher.Mapping.IDENTITY_LICENSE_NUMBER.key,
        )
    }

    private suspend fun encodeCipherSshKeyEntity(
        scope: EncodeToCipherScope,
        sshKey: BitwardenCipher.SshKey,
    ) {
        scope.setConcealed(
            TranslationField.SSH_PRIVATE_KEY.key, sshKey.privateKey,
            mappingKey = BitwardenCipher.Mapping.SSH_PRIVATE_KEY.key,
        )
        scope.setPlain(
            TranslationField.SSH_PUBLIC_KEY.key, sshKey.publicKey,
            mappingKey = BitwardenCipher.Mapping.SSH_PUBLIC_KEY.key,
        )
        scope.setPlain(
            TranslationField.SSH_FINGERPRINT.key, sshKey.fingerprint,
            mappingKey = BitwardenCipher.Mapping.SSH_FINGERPRINT.key,
        )
    }

    private class EncodeToCipherScope(
    ) {
        private val fields = mutableMapOf<String, EntryValue>()

        fun setPlain(key: String, value: String?, mappingKey: String? = null) =
            setValue(
                key = key,
                value = value
                    ?.let(EntryValue::Plain),
                mappingKey = mappingKey,
            )

        fun setConcealed(key: String, value: String?, mappingKey: String? = null) =
            setValue(
                key = key,
                value = value
                    ?.let(EncryptedValue::fromString)
                    ?.let(EntryValue::Encrypted),
                mappingKey = mappingKey,
            )

        private fun setValue(key: String, value: EntryValue?, mappingKey: String?) {
            if (value == null) return
            // Make sure that the key is unique. This is needed because internally we have custom
            // fields as the list of items, and not as dictionary.
            val restoredKey = key
            val uniqueKey = ensureUniqueKey(restoredKey)
            fields[uniqueKey] = value
        }

        private fun ensureUniqueKey(key: String, index: Int = 0): String {
            val finalKey = if (index <= 0) key else "$key #$index"
            if (finalKey !in fields) return finalKey

            // Recursively increment the suffix, until it is unique. This should
            // produce something like that:
            // Tag
            // Tag #1
            // Tag #2
            // ...
            return ensureUniqueKey(
                key = key,
                index = index + 1,
            )
        }

        fun getAvailableFields() = fields
    }

    //
    // Decode
    //

    private suspend fun decodeToFolderEntity(
        accountId: String,
        folderId: String,
        remote: Group,
        local: BitwardenFolder?,
        revisionDate: Instant,
    ): BitwardenFolder {
        val service = BitwardenService(
            remote = BitwardenService.Remote(
                id = remote.uuid.toString(),
                revisionDate = revisionDate,
                deletedDate = null, // can not be trashed
            ),
            deleted = false,
            version = BitwardenService.VERSION,
        )
        return BitwardenFolder(
            accountId = accountId,
            folderId = folderId,
            revisionDate = revisionDate,
            service = service,
            name = remote.name,
        )
    }

    private suspend fun decodeToCipherEntity(
        accountId: String,
        folderId: String?,
        cipherId: String,
        remote: Entry,
        local: BitwardenCipher?,
        revisionDate: Instant,
        binaries: Map<ByteString, BinaryData>,
    ): BitwardenCipher {
        val scope = DecodeToCipherScope(remote)
        // We do not allow to change the entry type!
        // Force it between the syncs.
        val type = local?.type
            ?: decodeCipherType(remote)
        scope.consumeField("${KEYGUARD_PREFIX}Entry Type")

        var login: BitwardenCipher.Login? = null
        var card: BitwardenCipher.Card? = null
        var identity: BitwardenCipher.Identity? = null
        var sshKey: BitwardenCipher.SshKey? = null

        when (type) {
            BitwardenCipher.Type.Login -> {
                login = decodeToCipherLoginEntity(
                    scope = scope,
                    remote = remote,
                )
            }

            BitwardenCipher.Type.Card -> {
                card = decodeToCipherCardEntity(
                    scope = scope,
                    remote = remote,
                )
            }

            BitwardenCipher.Type.Identity -> {
                identity = decodeToCipherIdentityEntity(
                    scope = scope,
                    remote = remote,
                )
            }

            BitwardenCipher.Type.SshKey -> {
                sshKey = decodeToCipherSshKeyEntity(
                    scope = scope,
                    remote = remote,
                )
            }

            BitwardenCipher.Type.SecureNote -> {
                // Do nothing.
            }
        }

        val name = scope.consumeFieldAndReturnContent(BasicField.Title())
        val notes = scope.consumeFieldAndReturnContent(BasicField.Notes())

        // We move favorite field into the tags, similarly as the
        // import tool does in the KeePassXC. Makes sense to me.
        val favorite = scope.consumeTag(TranslationTag.FAVORITE.key) != null

        val customFields = scope.getAvailableFields()
            .asSequence()
            .map { field ->
                var type: BitwardenCipher.Field.Type = BitwardenCipher.Field.Type.Text
                var linkedId: BitwardenCipher.Field.LinkedId? = null
                var value = field.value.content

                val concealed = field.value is EntryValue.Encrypted
                if (concealed) {
                    type = BitwardenCipher.Field.Type.Hidden
                } else {
                    // This can be either the boolean type or the
                    // linked id. We true to parse the text in hope
                    // of getting a boolean out of it.
                    val isBool = value.toBooleanStrictOrNull() != null
                    if (isBool) {
                        type = BitwardenCipher.Field.Type.Boolean
                    } else kotlin.run {
                        val prefix = "linkedId|"
                        if (!value.startsWith(prefix)) {
                            return@run
                        }

                        // Unfortunately now we have to parse for the
                        // linked id type.
                        val parts = value
                            .substring(prefix.length)
                            .split('|')
                        if (parts.size < 2) {
                            return@run
                        }

                        val idStr = parts.first()
                        val id = BitwardenCipher.Field.LinkedId
                            .entries
                            .firstOrNull { linkedId ->
                                linkedId.name == idStr
                            }
                            ?: return@run
                        val data = parts.subList(1, parts.size)
                            .joinToString("")

                        // Handled!
                        linkedId = id
                        value = data
                        type = BitwardenCipher.Field.Type.Linked
                    }
                }

                BitwardenCipher.Field(
                    name = field.key,
                    value = value,
                    type = type,
                    linkedId = linkedId,
                )
            }
            .toList()

        val reprompt = kotlin.run {
            val enabled =
                remote.fields[TranslationField.AUTH_REPROMPT.key]?.content == "Password"
            if (enabled) {
                BitwardenCipher.RepromptType.Password
            } else BitwardenCipher.RepromptType.None
        }
        val attachments = remote.binaries
            .mapNotNull { binary ->
                val id = cryptoGenerator.uuid()
                val data = binaries
                    .entries
                    .firstOrNull { binaryContent ->
                        binaryContent.key == binary.hash
                    }
                    ?.value?.getContent()
                // could not find the referenced attachment
                    ?: return@mapNotNull null

                val url = KeePassUtil.generateAttachmentUrl(
                    data = data,
                    cryptoGenerator = cryptoGenerator,
                    base32Service = base32Service,
                )
                val size = data.size.toLong()
                BitwardenCipher.Attachment.Remote(
                    id = id,
                    url = url,
                    fileName = binary.name,
                    keyBase64 = "", // unencrypted
                    size = size,
                )
            }
        return BitwardenCipher(
            accountId = accountId,
            cipherId = cipherId,
            folderId = folderId,
            service = BitwardenService(
                remote = BitwardenService.Remote(
                    id = remote.uuid.toString(),
                    revisionDate = revisionDate,
                    deletedDate = null, // can not be trashed
                ),
                deleted = false,
                version = BitwardenService.VERSION,
            ),
            name = name.orEmpty(),
            notes = notes,
            tags = scope
                .getAvailableTags()
                .map { tag ->
                    BitwardenCipher.Tag(
                        name = tag,
                    )
                },
            attachments = attachments,
            login = login,
            card = card,
            identity = identity,
            sshKey = sshKey,
            fields = customFields,
            mapping = scope.getMapping(),
            favorite = favorite,
            reprompt = reprompt,
            type = type,
            createdDate = remote.times?.creationTime
                ?.takeIf { it.toEpochMilli() != 0L }
                ?.toKotlinInstant()
                ?: local?.createdDate,
            expiredDate = remote.times?.expiryTime
                ?.takeIf { it.toEpochMilli() != 0L }
                ?.toKotlinInstant(),
            revisionDate = revisionDate,
        )
    }

    private fun decodeToCipherLoginEntity(
        scope: DecodeToCipherScope,
        remote: Entry,
    ): BitwardenCipher.Login {
        val uris = kotlin.run {
            val prefix = "KP2A_URL_"

            data class ExtractedUriData(
                val keys: List<String>,
                val ordinal: Int,
                val uri: String,
                val matchType: BitwardenCipher.Login.Uri.MatchType?,
            )

            fun extractUri(
                ordinal: Int,
                key: String,
                uri: String,
            ): ExtractedUriData {
                val matchTypeKey = "${key}_MATCH_TYPE"
                val matchType = remote
                    .fields[matchTypeKey]
                    ?.let { f ->
                        val matchType = decodeToCipherUrlMatchTypeEntityOrNull(f.content)
                        matchType
                    }

                val keys = listOfNotNull(
                    key,
                    matchTypeKey
                        .takeIf { matchType != null },
                )
                return ExtractedUriData(
                    keys = keys,
                    ordinal = ordinal,
                    uri = uri,
                    matchType = matchType,
                )
            }

            val primaryUris = remote.fields.url
                ?.content
                ?.takeIf { it.isNotEmpty() }
                ?.let { uri ->
                    val item = extractUri(
                        ordinal = 0,
                        key = BasicField.Url(),
                        uri = uri,
                    )
                    listOf(item)
                }
                .orEmpty()
            // One of the popular solutions for KeePass's lack of multiple
            // URI fields is to use the KP2A_URL_# field.
            //
            // https://github.com/keepassium/KeePassium/issues/180
            //
            // It has originated from the Keepass2Android and has become the
            // de-facto standard.
            val secondaryUris = remote.fields
                .filter { field ->
                    field.key
                        .startsWith(prefix)
                }
                .mapNotNull { field ->
                    val ordinalStr = field.key
                        .removePrefix(prefix)
                    val ordinal = ordinalStr.toIntOrNull()
                        ?: return@mapNotNull null // ignore URIs with incorrect ordinal
                    extractUri(
                        ordinal = ordinal,
                        key = field.key,
                        uri = field.value.content,
                    )
                }
            // Combine URIs and convert them to
            // the Bitwarden representation.
            (primaryUris + secondaryUris)
                .filter { it.uri.isNotEmpty() }
                .sortedBy { it.ordinal }
                .onEach { entry ->
                    entry.keys.forEach { key ->
                        scope.consumeField(key)
                    }
                }
                .map { entry ->
                    BitwardenCipher.Login.Uri(
                        uri = entry.uri,
                        match = entry.matchType,
                    )
                }
        }

        val totp = kotlin.run {
            val otpKeys = setOf(
                "otp",
                "OTPAuth",
            )
            val otpFieldValue = otpKeys
                .firstNotNullOfOrNull { key ->
                    scope.consumeField(
                        key = key,
                        mappingKey = BitwardenCipher.Mapping.LOGIN_OTP.key,
                    )
                }
            otpFieldValue?.content
        }

        fun getPassword(entry: Entry) = entry.fields.password?.content
            .takeUnless { it.isNullOrEmpty() }

        val passwordRevDate = kotlin.run {
            val lastPassword = getPassword(remote)

            val index = remote.history
                // Make sure that we have the data in a
                // chronological order.
                .sortedByDescending {
                    it.times?.lastModificationTime
                }
                .indexOfFirst { entry ->
                    val curPassword = getPassword(entry)
                    curPassword != null && curPassword != lastPassword
                } - 1 // the password change has happened afterwards
            val revDate = remote.history.getOrNull(index)?.times?.lastModificationTime
                ?: remote.history.lastOrNull()?.times?.lastModificationTime
                    ?.takeIf { lastPassword != null }
                ?: remote.times?.lastModificationTime
                    ?.takeIf { lastPassword != null }
            revDate?.toKotlinInstant()
        }
        val passwordHistory = kotlin.run {
            val history = mutableListOf<BitwardenCipher.Login.PasswordHistory>()

            var lastPassword = getPassword(remote)
            remote.history
                // Make sure that we have the data in a
                // chronological order.
                .sortedByDescending {
                    it.times?.lastModificationTime
                }
                .forEach { entry ->
                    val curPassword = getPassword(entry)
                    if (curPassword != null && curPassword != lastPassword) {
                        lastPassword = curPassword
                        history += BitwardenCipher.Login.PasswordHistory(
                            password = curPassword,
                            lastUsedDate = entry.times?.lastModificationTime?.toKotlinInstant(),
                        )
                    }
                }
            history
        }

        val fido2Credentials = kotlin.run {
            val prefix = "FIDO2 Credentials Blob #"

            data class ExtractedFido2CredentialsData(
                val keys: List<String>,
                val ordinal: Int,
                val credentials: BitwardenCipher.Login.Fido2Credentials,
            )

            fun extractFido2CredentialsEntry(
                ordinal: Int,
                key: String,
                dataBase64: String,
            ): ExtractedFido2CredentialsData? {
                val credentials = kotlin.runCatching {
                    val data = base64Service.decodeToString(dataBase64)
                    json.decodeFromString<BitwardenCipher.Login.Fido2Credentials>(data)
                }.getOrElse {
                    return null
                }
                return ExtractedFido2CredentialsData(
                    keys = listOf(key),
                    ordinal = ordinal,
                    credentials = credentials,
                )
            }

            val credentials = remote.fields
                .filter { field ->
                    field.key
                        .startsWith(prefix)
                }
                .mapNotNull { field ->
                    val ordinalStr = field.key
                        .removePrefix(prefix)
                    val ordinal = ordinalStr.toIntOrNull()
                        ?: return@mapNotNull null // ignore fields with incorrect ordinal
                    extractFido2CredentialsEntry(
                        ordinal = ordinal,
                        key = field.key,
                        dataBase64 = field.value.content,
                    )
                }
            credentials
                .sortedBy { it.ordinal }
                .onEach { entry ->
                    entry.keys.forEach { key ->
                        scope.consumeField(key)
                    }
                }
                .map { entry -> entry.credentials }
        }

        return BitwardenCipher.Login(
            username = scope.consumeFieldAndReturnContent(key = BasicField.UserName()),
            password = scope.consumeFieldAndReturnContent(key = BasicField.Password()),
            passwordRevisionDate = passwordRevDate,
            passwordHistory = passwordHistory,
            fido2Credentials = fido2Credentials,
            uris = uris,
            totp = totp,
        )
    }

    private fun decodeToCipherCardEntity(
        scope: DecodeToCipherScope,
        remote: Entry,
    ): BitwardenCipher.Card {
        return BitwardenCipher.Card(
            cardholderName = scope.consumeFieldAndReturnContent(TranslationField.CARD_CARDHOLDER_NAME.key),
            brand = scope.consumeFieldAndReturnContent(TranslationField.CARD_BRAND.key),
            number = scope.consumeFieldAndReturnContent(TranslationField.CARD_NUMBER.key),
            expMonth = scope.consumeFieldAndReturnContent(TranslationField.CARD_EXP_MONTH.key),
            expYear = scope.consumeFieldAndReturnContent(TranslationField.CARD_EXP_YEAR.key),
            code = scope.consumeFieldAndReturnContent(TranslationField.CARD_CODE.key),
        )
    }

    private fun decodeToCipherIdentityEntity(
        scope: DecodeToCipherScope,
        remote: Entry,
    ): BitwardenCipher.Identity {
        val username = scope.consumeFieldAndReturnContent(
            key = BasicField.UserName(),
        )

        return BitwardenCipher.Identity(
            title = scope.consumeFieldAndReturnContent(TranslationField.IDENTITY_TITLE.key),
            firstName = scope.consumeFieldAndReturnContent(TranslationField.IDENTITY_NAME.key)
                ?: scope.consumeFieldAndReturnContent(TranslationField.IDENTITY_FIRST_NAME.key),
            middleName = scope.consumeFieldAndReturnContent(TranslationField.IDENTITY_MIDDLE_NAME.key),
            lastName = scope.consumeFieldAndReturnContent(TranslationField.IDENTITY_LAST_NAME.key),
            address1 = scope.consumeFieldAndReturnContent(TranslationField.IDENTITY_ADDRESS.key)
                ?: scope.consumeFieldAndReturnContent(TranslationField.IDENTITY_ADDRESS1.key),
            address2 = scope.consumeFieldAndReturnContent(TranslationField.IDENTITY_ADDRESS2.key),
            address3 = scope.consumeFieldAndReturnContent(TranslationField.IDENTITY_ADDRESS3.key),
            city = scope.consumeFieldAndReturnContent(TranslationField.IDENTITY_CITY.key),
            state = scope.consumeFieldAndReturnContent(TranslationField.IDENTITY_STATE.key),
            postalCode = scope.consumeFieldAndReturnContent(TranslationField.IDENTITY_POSTAL_CODE.key),
            country = scope.consumeFieldAndReturnContent(TranslationField.IDENTITY_COUNTRY.key),
            company = scope.consumeFieldAndReturnContent(TranslationField.IDENTITY_COMPANY.key),
            email = scope.consumeFieldAndReturnContent(TranslationField.IDENTITY_EMAIL.key),
            phone = scope.consumeFieldAndReturnContent(TranslationField.IDENTITY_PHONE.key),
            ssn = scope.consumeFieldAndReturnContent(TranslationField.IDENTITY_SSN.key),
            username = username,
            passportNumber = scope.consumeFieldAndReturnContent(TranslationField.IDENTITY_PASSPORT_NUMBER.key),
            licenseNumber = scope.consumeFieldAndReturnContent(TranslationField.IDENTITY_LICENSE_NUMBER.key),
        )
    }

    private fun decodeToCipherSshKeyEntity(
        scope: DecodeToCipherScope,
        remote: Entry,
    ): BitwardenCipher.SshKey {
        return BitwardenCipher.SshKey(
            privateKey = scope.consumeFieldAndReturnContent(TranslationField.SSH_PRIVATE_KEY.key),
            publicKey = scope.consumeFieldAndReturnContent(TranslationField.SSH_PUBLIC_KEY.key),
            fingerprint = scope.consumeFieldAndReturnContent(TranslationField.SSH_FINGERPRINT.key),
        )
    }

    private fun decodeToCipherUrlMatchTypeEntityOrNull(
        matchType: String,
    ): BitwardenCipher.Login.Uri.MatchType? = BitwardenCipher.Login.Uri.MatchType
        .entries
        .firstOrNull { entry ->
            entry.verboseKey == matchType
        }

    private class DecodeToCipherScope(
        private val remote: Entry,
    ) {
        private val consumedFields = mutableSetOf<String>()


        private val consumedFieldsMapping = mutableMapOf<String, String>()

        private val consumedTags = mutableSetOf<String>()

        /**
         * Marks the field as consumed. This helps in a scenario where
         * we take that field and use it as a special field: username,
         * password and others.
         */
        fun consumeField(key: String, mappingKey: String? = null): EntryValue? {
            val added = consumedFields.add(key)
            if (!added) {
                return null
            }
            return remote.fields[key]
                // All the basic item types do exit, no matter if they
                // were created in the database or not. Clear these out
                // if they are empty!
                ?.takeIf { value ->
                    key !in BasicField.keys ||
                            !value.isEmpty()
                }
                ?.also {
                    if (mappingKey != null)
                        consumedFieldsMapping[mappingKey] = key
                }
        }

        fun consumeTag(tag: String): String? {
            val added = consumedTags.add(tag)
            if (!added) {
                return null
            }
            return tag.takeIf { remote.tags.contains(tag) }
        }

        fun getAvailableFields() = remote
            .fields
            .filterKeys { it !in consumedFields }
            // All the basic item types do exit, no matter if they
            // were created in the database or not. Clear these out
            // if they are empty!
            .filter { entry ->
                val key = entry.key
                val value = entry.value
                key !in BasicField.keys ||
                        !value.isEmpty()
            }

        fun getAvailableTags() = remote
            .tags
            .filter { tag ->
                tag !in consumedTags
            }

        fun getMapping() = consumedFieldsMapping
    }

    private fun DecodeToCipherScope.consumeFieldAndReturnContent(
        key: String,
        mappingKey: String? = null,
    ) = consumeField(key, mappingKey = mappingKey)?.content

    private fun decodeCipherType(
        remote: Entry,
    ): BitwardenCipher.Type {
        // Fast path:
        // All Keyguard entries have a specific field that marks the
        // type of the entry.
        val typeField = remote.fields["${KEYGUARD_PREFIX}Entry Type"]
        if (typeField != null) {
            val typeFieldValue = typeField.content
            val type = BitwardenCipher.Type
                .entries
                .firstOrNull { type ->
                    type.verboseKey == typeFieldValue
                }
            if (type != null) return type
        }

        // Slow path:
        // Try to detect the type of the entry looking at the type of
        // the available fields.

        val fieldKeys = remote.fields.keys

        val hasUrls = kotlin.run hasUrls@{
            val hasPrimaryUrl = !remote.fields.url?.content.isNullOrEmpty()
            if (hasPrimaryUrl) return@hasUrls true
            fieldKeys.any { key ->
                key.startsWith("KP2A_URL_")
            }
        }
        if (hasUrls) {
            return BitwardenCipher.Type.Login // only the Login is allowed to have URLs
        }

        fun anyHasPrefix(prefix: String) = fieldKeys
            .any { key ->
                key.startsWith(prefix)
            }

        when {
            anyHasPrefix("card_") -> return BitwardenCipher.Type.Card
            anyHasPrefix("identity_") -> return BitwardenCipher.Type.Identity
            anyHasPrefix("ssh_") -> return BitwardenCipher.Type.SshKey
        }

        // We almost never want to choose the Secure note type, so ensure that we
        // are only using the custom fields and that no ones that match the login
        // do exist.

        val isLogin = kotlin.run {
            val hasCredentials = remote.fields
                .any { entry ->
                    val credentialsField = entry.key == BasicField.Password() ||
                            entry.key == BasicField.UserName()
                    credentialsField && !entry.value.isEmpty()
                }
            if (hasCredentials) {
                return@run true
            }

            // Make sure that notes do exist for a
            // non-login entry.
            remote.fields.notes == null
        }
        return if (isLogin) {
            BitwardenCipher.Type.Login
        } else BitwardenCipher.Type.SecureNote
    }
}

interface SyncByBitwardenToken : (BitwardenToken) -> IO<Unit>

interface SyncByKeePassToken : (KeePassToken) -> IO<Unit>
