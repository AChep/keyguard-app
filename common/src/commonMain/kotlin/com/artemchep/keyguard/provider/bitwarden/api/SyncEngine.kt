package com.artemchep.keyguard.provider.bitwarden.api

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.SyncScope
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.core.store.DatabaseSyncer
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCollection
import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.core.store.bitwarden.BitwardenOptionalStringNullable
import com.artemchep.keyguard.core.store.bitwarden.BitwardenOrganization
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile
import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.login
import com.artemchep.keyguard.core.store.bitwarden.uris
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.create
import com.artemchep.keyguard.provider.bitwarden.api.builder.delete
import com.artemchep.keyguard.provider.bitwarden.api.builder.get
import com.artemchep.keyguard.provider.bitwarden.api.builder.post
import com.artemchep.keyguard.provider.bitwarden.api.builder.put
import com.artemchep.keyguard.provider.bitwarden.api.builder.removePassword
import com.artemchep.keyguard.provider.bitwarden.api.builder.restore
import com.artemchep.keyguard.provider.bitwarden.api.builder.sync
import com.artemchep.keyguard.provider.bitwarden.api.builder.trash
import com.artemchep.keyguard.provider.bitwarden.api.entity.SyncResponse
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCr
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrCta
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrImpl
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrKey
import com.artemchep.keyguard.provider.bitwarden.crypto.CryptoKey
import com.artemchep.keyguard.provider.bitwarden.crypto.appendOrganizationToken
import com.artemchep.keyguard.provider.bitwarden.crypto.appendProfileToken
import com.artemchep.keyguard.provider.bitwarden.crypto.appendUserToken
import com.artemchep.keyguard.provider.bitwarden.crypto.decodeSymmetricOrThrow
import com.artemchep.keyguard.provider.bitwarden.crypto.encrypted
import com.artemchep.keyguard.provider.bitwarden.crypto.makeSendCryptoKey
import com.artemchep.keyguard.provider.bitwarden.crypto.transform
import com.artemchep.keyguard.provider.bitwarden.entity.CipherEntity
import com.artemchep.keyguard.provider.bitwarden.entity.CollectionEntity
import com.artemchep.keyguard.provider.bitwarden.entity.FolderEntity
import com.artemchep.keyguard.provider.bitwarden.entity.OrganizationEntity
import com.artemchep.keyguard.provider.bitwarden.entity.SyncProfile
import com.artemchep.keyguard.provider.bitwarden.entity.SyncSends
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherUpdate
import com.artemchep.keyguard.provider.bitwarden.entity.request.FolderUpdate
import com.artemchep.keyguard.provider.bitwarden.entity.request.SendUpdate
import com.artemchep.keyguard.provider.bitwarden.entity.request.of
import com.artemchep.keyguard.provider.bitwarden.sync.SyncManager
import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.RuntimeException
import kotlin.String
import kotlin.TODO
import kotlin.Throwable
import kotlin.Unit
import kotlin.apply
import kotlin.let
import kotlin.require
import kotlin.requireNotNull
import kotlin.takeIf
import kotlin.to

class SyncEngine(
    private val httpClient: HttpClient,
    private val dbManager: DatabaseManager,
    private val json: Json,
    private val base64Service: Base64Service,
    private val cryptoGenerator: CryptoGenerator,
    private val cipherEncryptor: CipherEncryptor,
    private val logRepository: LogRepository,
    private val getPasswordStrength: GetPasswordStrength,
    private val user: BitwardenToken,
    private val syncer: DatabaseSyncer,
) {
    companion object {
        private const val TAG = "SyncEngine"
    }

    class EmptyVaultException(message: String) : RuntimeException(message)

    class DecodeVaultException(message: String, e: Throwable) : RuntimeException(message, e)

    private inline fun <reified T> createDecodingFailedServiceModel(
        now: Instant,
        model: T,
        lens: SyncManager.Lens<T>,
    ): BitwardenService {
        val revisionDate = lens.getRevisionDate(model)
            .takeUnless { it == Instant.DISTANT_FUTURE }
            ?: now

        val blob = json.encodeToString(model)
        return BitwardenService(
            remote = BitwardenService.Remote(
                id = lens.getId(model),
                revisionDate = revisionDate,
                deletedDate = lens.getDeletedDate(model),
            ),
            error = BitwardenService.Error(
                code = BitwardenService.Error.CODE_DECODING_FAILED,
                blob = blob,
                revisionDate = now,
            ),
            deleted = false,
            version = BitwardenService.VERSION,
        )
    }

    context(SyncScope)
    suspend fun sync() = kotlin.run {
        val env = user.env.back()
        val api = env.api
        val token = requireNotNull(user.token).accessToken

        post(
            title = "Send send request.",
        )
        val response = api.sync(
            httpClient = httpClient,
            env = env,
            token = token,
        )
        val db = dbManager.get().bind()

        // There's a bug in the web vault that sometimes displays a
        // user empty vault. This check logs these kind of events, I need
        // it to know if that's what once caused a user sync to completely
        // empty vault.
        if (
            response.ciphers.isNullOrEmpty() &&
            response.folders.isNullOrEmpty()
        ) {
            val existingCiphers = db.cipherQueries
                .getByAccountId(user.id)
                .executeAsList()
            if (existingCiphers.isNotEmpty()) {
                val isSelfHosted = env.webVaultUrl.isNotBlank()
                val isUnofficial = response.unofficialServer == true

                val m = "Backend returned empty cipher list, while there's " +
                        "${existingCiphers.size} ciphers in the local storage: " +
                        "official=${!isUnofficial}, self-hosted=${isSelfHosted}"
                val e = EmptyVaultException(m)
                recordException(e)
            }
        }

        val now = Clock.System.now()
        val crypto = crypto(
            profile = response.profile,
        )

        fun getCodec(
            mode: BitwardenCrCta.Mode,
            organizationId: String? = null,
            sendId: String? = null,
        ) = kotlin.run {
            val envEncryptionType = CipherEncryptor.Type.AesCbc256_HmacSha256_B64
            val env = if (sendId != null) {
                val key = BitwardenCrKey.SendToken(sendId)
                BitwardenCrCta.BitwardenCrCtaEnv(
                    key = key,
                    encryptionType = envEncryptionType,
                )
            } else if (organizationId != null) {
                val key = BitwardenCrKey.OrganizationToken(organizationId)
                BitwardenCrCta.BitwardenCrCtaEnv(
                    key = key,
                    encryptionType = envEncryptionType,
                )
            } else {
                val key = BitwardenCrKey.UserToken
                BitwardenCrCta.BitwardenCrCtaEnv(
                    key = key,
                    encryptionType = envEncryptionType,
                )
            }
            crypto.cta(
                env = env,
                mode = mode,
            )
        }

        fun getCodecPair(
            mode: BitwardenCrCta.Mode,
            key: ByteArray,
        ) = kotlin.run {
            val itemCrypto = kotlin.run {
                val symmetricCryptoKey = key
                    .let(cryptoGenerator::makeSendCryptoKey)
                    .let(CryptoKey.Companion::decodeSymmetricOrThrow)
                val cryptoKey = BitwardenCrKey.CryptoKey(
                    symmetricCryptoKey = symmetricCryptoKey,
                )
                val cryptoKeyEnv = BitwardenCrCta.BitwardenCrCtaEnv(
                    key = cryptoKey,
                )
                crypto.cta(
                    env = cryptoKeyEnv,
                    mode = mode,
                )
            }
            val globalCrypto = getCodec(
                mode = mode,
            )
            itemCrypto to globalCrypto
        }

        fun getCodecPairFromEncrypted(
            mode: BitwardenCrCta.Mode,
            keyCipherText: String,
        ) = kotlin.run {
            val key = crypto.decoder(BitwardenCrKey.UserToken)(keyCipherText)
                .data
            getCodecPair(
                key = key,
                mode = mode,
            )
        }

        fun getCipherCodecPair(
            mode: BitwardenCrCta.Mode,
            key: ByteArray?,
            organizationId: String?,
        ) = kotlin.run {
            val globalCrypto = getCodec(
                mode = mode,
                organizationId = organizationId,
            )
            val itemCrypto = if (key != null) kotlin.run {
                val symmetricCryptoKey = key
                    .let(CryptoKey.Companion::decodeSymmetricOrThrow)
                val cryptoKey = BitwardenCrKey.CryptoKey(
                    symmetricCryptoKey = symmetricCryptoKey,
                )
                val cryptoKeyEnv = BitwardenCrCta.BitwardenCrCtaEnv(
                    key = cryptoKey,
                )
                crypto.cta(
                    env = cryptoKeyEnv,
                    mode = mode,
                )
            } else {
                globalCrypto
            }
            itemCrypto to globalCrypto
        }

        fun getCipherCodecPairFromEncrypted(
            mode: BitwardenCrCta.Mode,
            keyCipherText: String?,
            organizationId: String?,
        ) = kotlin.run {
            val key = if (keyCipherText != null) {
                val decoderKey = if (organizationId != null) {
                    BitwardenCrKey.OrganizationToken(
                        id = organizationId,
                    )
                } else {
                    BitwardenCrKey.UserToken
                }
                crypto.decoder(decoderKey)(keyCipherText)
                    .data
            } else {
                null
            }
            getCipherCodecPair(
                mode = mode,
                key = key,
                organizationId = organizationId,
            )
        }

        //
        // Profile
        //

        post(
            title = "Syncing a profile entity.",
        )

        val newProfile = BitwardenProfile
            .encrypted(
                accountId = user.id,
                entity = response.profile,
                unofficialServer = response.unofficialServer == true,
            )
            .transform(
                crypto = getCodec(
                    mode = BitwardenCrCta.Mode.DECRYPT,
                ),
            )
        syncer.withLock(DatabaseSyncer.Key.Profile(response.profile.id)) {
            val profileDao = db.profileQueries

            // Check the security timestamp.
            val existingProfile = profileDao
                .getByAccountId(
                    accountId = user.id,
                )
                .executeAsOneOrNull()
                ?.takeIf { it.profileId == response.profile.id }
            if (existingProfile != null) {
                val existingSecurityStamp = existingProfile.data_.securityStamp
                require(existingSecurityStamp == response.profile.securityStamp) {
                    "Local security stamp differs from a remote one. " +
                            "You might be in a man-in-the-middle attack!"
                }
            }

            // Insert updated profile.
            val newMergedProfile = merge(
                remote = newProfile,
                local = existingProfile?.data_,
            )
            if (newMergedProfile != existingProfile?.data_) {
                profileDao.insert(
                    profileId = newProfile.profileId,
                    accountId = newProfile.accountId,
                    data = newProfile,
                )
            }
        }

        //
        // Folder
        //

        fun BitwardenCrCta.folderDecoder(
            entity: FolderEntity,
            localFolderId: String?,
        ) = kotlin.run {
            val folderId = localFolderId
                ?: cryptoGenerator.uuid()
            BitwardenFolder
                .encrypted(
                    accountId = user.id,
                    folderId = folderId,
                    entity = entity,
                )
                .transform(this)
        }

        post(
            title = "Syncing folder entities.",
        )

        val folderDao = db.folderQueries
        val folderRemoteLens = SyncManager.Lens<FolderEntity>(
            getId = { it.id },
            getRevisionDate = { it.revisionDate },
        )
        val existingFolders = folderDao
            .getByAccountId(
                accountId = user.id,
            )
            .executeAsList()
            .map { it.data_ }
        syncX(
            name = "folder",
            localItems = existingFolders,
            localLens = SyncManager.LensLocal(
                getLocalId = { it.folderId },
                getLocalRevisionDate = { it.revisionDate },
            ),
            localReEncoder = { model ->
                model
            },
            localDecoder = { local, remote ->
                val encryptor = getCodec(
                    mode = BitwardenCrCta.Mode.ENCRYPT,
                )
                val encryptedFolder = local.transform(encryptor)
                FolderUpdate.of(
                    model = encryptedFolder,
                ) to local
            },
            localDeleteById = { ids ->
                folderDao.transaction {
                    ids.forEach { folderId ->
                        folderDao.deleteByFolderId(
                            folderId = folderId,
                        )
                    }
                }
            },
            localPut = { models ->
                folderDao.transaction {
                    models.forEach { folder ->
                        folderDao.insert(
                            folderId = folder.folderId,
                            accountId = folder.accountId,
                            data = folder,
                        )
                    }
                }
            },
            remoteItems = response.folders.orEmpty(),
            remoteLens = SyncManager.Lens<FolderEntity>(
                getId = { it.id },
                getRevisionDate = { it.revisionDate },
            ),
            remoteDecoder = { remote, local ->
                val codec = getCodec(
                    mode = BitwardenCrCta.Mode.DECRYPT,
                )
                codec
                    .folderDecoder(
                        entity = remote,
                        localFolderId = local?.folderId,
                    )
            },
            remoteDeleteById = { id ->
                user.env.back().api.folders.delete(
                    httpClient = httpClient,
                    env = env,
                    token = user.token.accessToken,
                    id = id,
                )
            },
            remoteDecodedFallback = { remote, localOrNull, e ->
                val logObj = mapOf(
                    // this should include the encryption type
                    "name" to remote.name.take(2),
                )
                val logE = DecodeVaultException(
                    message = "Failed to decrypt a folder. Structure: $logObj",
                    e = e,
                )
                recordException(logE)

                val localId = localOrNull?.folderId
                    ?: cryptoGenerator.uuid()
                val service = createDecodingFailedServiceModel(
                    now = now,
                    model = remote,
                    lens = folderRemoteLens,
                )
                val model = localOrNull?.copy(service = service) ?: BitwardenFolder(
                    accountId = user.id,
                    folderId = localId,
                    revisionDate = remote.revisionDate,
                    service = service,
                    name = "⚠️ Unsupported Folder",
                )
                model
            },
            remotePut = { (update, local) ->
                require(update.source.folderId == local.folderId)
                val folderApi = user.env.back().api.folders
                val folderResponse = when (update) {
                    is FolderUpdate.Create ->
                        folderApi.post(
                            httpClient = httpClient,
                            env = env,
                            token = user.token.accessToken,
                            body = update.folderRequest,
                        )

                    is FolderUpdate.Modify ->
                        folderApi.put(
                            httpClient = httpClient,
                            env = env,
                            token = user.token.accessToken,
                            id = update.folderId,
                            body = update.folderRequest,
                        )
                }
                val codec = getCodec(
                    mode = BitwardenCrCta.Mode.DECRYPT,
                )
                codec
                    .folderDecoder(
                        entity = folderResponse,
                        localFolderId = update.source.folderId,
                    )
            },
            onLog = { msg, logLevel ->
                logRepository.add(TAG, msg, logLevel)
            },
        )

        val folder1 = folderDao.get().executeAsList()
        val localToRemoteFolders = folder1
            .associate { folder ->
                val remoteId = folder.data_.service.remote?.id
                val localId = folder.data_.folderId
                localId to remoteId
            }
        val remoteToLocalFolders = folder1
            .mapNotNull { folder ->
                val remoteId = folder.data_.service.remote?.id
                    ?: return@mapNotNull null
                val localId = folder.data_.folderId
                remoteId to localId
            }
            .toMap()

        //
        // Cipher
        //

        fun BitwardenCrCta.cipherDecoder(
            entity: CipherEntity,
            codec2: BitwardenCrCta,
            localCipherId: String?,
        ) = kotlin.run {
            val folderId = entity.folderId
                ?.let { remoteFolderId ->
                    val localFolderId = remoteToLocalFolders[remoteFolderId]
                    if (localFolderId != null) return@let localFolderId

                    val folderExists = response.folders
                        .orEmpty()
                        .any { it.id == remoteFolderId }
                    if (folderExists) {
                        // TODO: The folder exists, but we failed to create an
                        //  entry for it... this should not happen, but if it does
                        //  then try to decode the object without a folder and then
                        //  declare model as incomplete.
                    }

                    // Remove folder from the cipher.
                    null
                }
            val cipherId = localCipherId
                ?: cryptoGenerator.uuid()
            BitwardenCipher
                .encrypted(
                    accountId = user.id,
                    cipherId = cipherId,
                    folderId = folderId,
                    entity = entity,
                )
                .transform(this, codec2)
        }

        post(
            title = "Syncing cipher entities.",
        )

        val cipherDao = db.cipherQueries
        val cipherRemoteLens = SyncManager.Lens<CipherEntity>(
            getId = { it.id },
            getRevisionDate = { cipher -> cipher.revisionDate },
            getDeletedDate = { cipher -> cipher.deletedDate },
        )
        val existingCipher = cipherDao
            .getByAccountId(
                accountId = user.id,
            )
            .executeAsList()
            .map { it.data_ }
        syncX(
            name = "cipher",
            localItems = existingCipher,
            localLens = SyncManager.LensLocal(
                getLocalId = { it.cipherId },
                getLocalRevisionDate = { cipher -> cipher.revisionDate },
                getLocalDeletedDate = { cipher -> cipher.deletedDate },
            ),
            localReEncoder = { model ->
                model
            },
            localDecoder = { rawLocal, remote ->
                // Inject the URL checksums into the list of URLs before
                // processing the entry.
                val local = BitwardenCipher.login.uris.modify(rawLocal) { uris ->
                    uris
                        .map { uri ->
                            if (uri.uriChecksumBase64 != null) return@map uri
                            val uriChecksumBase64 = kotlin.run {
                                val rawHash =
                                    cryptoGenerator.hashSha256(uri.uri.orEmpty().toByteArray())
                                base64Service.encodeToString(rawHash)
                            }
                            uri.copy(
                                uriChecksumBase64 = uriChecksumBase64,
                            )
                        }
                }

                val itemKey = local.keyBase64
                    ?.let(base64Service::decode)
                val (
                    itemCrypto,
                    globalCrypto,
                ) = getCipherCodecPair(
                    mode = BitwardenCrCta.Mode.ENCRYPT,
                    key = itemKey,
                    organizationId = local.organizationId,
                )
                val encryptedCipher = local.transform(
                    itemCrypto = itemCrypto,
                    globalCrypto = globalCrypto,
                )
                CipherUpdate.of(
                    model = encryptedCipher,
                    folders = localToRemoteFolders,
                ) to local
            },
            localDeleteById = { ids ->
                cipherDao.transaction {
                    ids.forEach { cipherId ->
                        cipherDao.deleteByCipherId(
                            cipherId = cipherId,
                        )
                    }
                }
            },
            localPut = { models ->
                cipherDao.transaction {
                    models.forEach { cipher ->
                        cipherDao.insert(
                            cipherId = cipher.cipherId,
                            accountId = cipher.accountId,
                            folderId = cipher.folderId,
                            data = cipher,
                            updatedAt = cipher.revisionDate,
                        )
                    }
                }
            },
            shouldOverwrite = { local, remote ->
                val remoteAttachments = remote.attachments
                    .orEmpty()
                // fast path:
                if (local.attachments.size != remoteAttachments.size) {
                    return@syncX true
                }

                // slow path:
                val localAttachmentIds = local.attachments
                    .asSequence()
                    .map { it.id }
                    .toSet()
                val remoteAttachmentIds = remoteAttachments
                    .asSequence()
                    .map { it.id }
                    .toSet()
                if (!localAttachmentIds.containsAll(remoteAttachmentIds)) {
                    return@syncX true
                }

                false
            },
            remoteItems = response.ciphers.orEmpty(),
            remoteLens = cipherRemoteLens,
            remoteDecoder = { remote, local ->
                val (
                    itemCrypto,
                    globalCrypto,
                ) = getCipherCodecPairFromEncrypted(
                    mode = BitwardenCrCta.Mode.DECRYPT,
                    keyCipherText = remote.key,
                    organizationId = remote.organizationId,
                )
                itemCrypto
                    .cipherDecoder(
                        entity = remote,
                        codec2 = globalCrypto,
                        localCipherId = local?.cipherId,
                    )
                    .let { remoteDecoded ->
                        // inject the local model into newly decoded remote one
                        merge(remoteDecoded, local, getPasswordStrength)
                    }
            },
            remoteDeleteById = { id ->
                user.env.back().api.ciphers.focus(id).delete(
                    httpClient = httpClient,
                    env = env,
                    token = user.token.accessToken,
                )
            },
            remoteDecodedFallback = { remote, localOrNull, e ->
                val logObj = mapOf(
                    // this should include the encryption type
                    "name" to remote.name?.take(2),
                )
                val logE = DecodeVaultException(
                    message = "Failed to decrypt a cipher. Structure: $logObj",
                    e = e,
                )
                recordException(logE)

                val localId = localOrNull?.cipherId
                    ?: cryptoGenerator.uuid()
                val folderId = remote.folderId?.let { remoteToLocalFolders[it] }
                val service = createDecodingFailedServiceModel(
                    now = now,
                    model = remote,
                    lens = cipherRemoteLens,
                )
                val model = localOrNull?.copy(service = service) ?: BitwardenCipher(
                    accountId = user.id,
                    cipherId = localId,
                    folderId = folderId,
                    organizationId = remote.organizationId,
                    revisionDate = remote.revisionDate,
                    deletedDate = remote.deletedDate,
                    // service fields
                    service = service,
                    // common
                    name = "⚠️ Unsupported Item",
                    notes = null,
                    favorite = false,
                    reprompt = BitwardenCipher.RepromptType.None,
                    // types
                    type = BitwardenCipher.Type.Card,
                )
                merge(model, localOrNull, getPasswordStrength)
            },
            remotePut = { (r, local) ->
                val ciphersApi = user.env.back().api.ciphers
                val cipherResponse = when (r) {
                    is CipherUpdate.Modify -> {
                        val cipherApi = ciphersApi.focus(r.cipherId)
                        var cipherRequest = r.cipherRequest

                        fun handleIntermediateResponse(cipherEntity: CipherEntity) {
                            cipherRequest = r.cipherRequest.copy(
                                lastKnownRevisionDate = cipherEntity.revisionDate,
                            )

                            // We might loose local changes here if the next request fails, so
                            // save the data in that case.
                            updateRemoteModel(cipherEntity)
                        }

                        val isTrashed = r.source.deletedDate != null
                        val wasTrashed = r.source.service.remote?.deletedDate != null
                        val hasChanged =
                            r.source.service.remote?.revisionDate != r.source.revisionDate
                        if (isTrashed != wasTrashed || hasChanged) {
                            // Due to Bitwarden API restrictions you can not modify
                            // trashed items: first we have to restore them.
                            if (wasTrashed) {
                                val restoredCipher = cipherApi.restore(
                                    httpClient = httpClient,
                                    env = env,
                                    token = user.token.accessToken,
                                    cipherRequest = cipherRequest,
                                )
                                handleIntermediateResponse(restoredCipher)
                            }

                            val putCipher = if (hasChanged) {
                                cipherApi.put(
                                    httpClient = httpClient,
                                    env = env,
                                    token = user.token.accessToken,
                                    body = cipherRequest,
                                )
                            } else {
                                null
                            }

                            if (isTrashed) {
                                if (putCipher != null) handleIntermediateResponse(putCipher)
                                try {
                                    cipherApi.trash(
                                        httpClient = httpClient,
                                        env = env,
                                        token = user.token.accessToken,
                                    )
                                } catch (e: HttpException) {
                                    // Trashing a cipher returns no body.
                                    if (e.cause !is NoTransformationFoundException) {
                                        throw e
                                    }
                                }
                            }
                        }

                        cipherApi.get(
                            httpClient = httpClient,
                            env = env,
                            token = user.token.accessToken,
                        )
                    }

                    is CipherUpdate.Create -> {
                        require(r.cipherRequest.organizationId == null) {
                            "To create a cipher in the organization, you must use a special API call."
                        }
                        ciphersApi.post(
                            httpClient = httpClient,
                            env = env,
                            token = user.token.accessToken,
                            body = r.cipherRequest,
                        )
                    }

                    is CipherUpdate.CreateInOrg -> {
                        require(r.cipherRequest.cipher.organizationId != null) {
                            "To create a cipher in the user's vault, you must use a special API call."
                        }
                        ciphersApi.create(
                            httpClient = httpClient,
                            env = env,
                            token = user.token.accessToken,
                            body = r.cipherRequest,
                        )
                    }
                }

                val itemKey = local.keyBase64
                    ?.let(base64Service::decode)
                val (
                    itemCrypto,
                    globalCrypto,
                ) = getCipherCodecPair(
                    mode = BitwardenCrCta.Mode.DECRYPT,
                    key = itemKey,
                    organizationId = r.source.organizationId,
                )
                itemCrypto
                    .cipherDecoder(
                        entity = cipherResponse,
                        codec2 = globalCrypto,
                        localCipherId = r.source.cipherId,
                    )
                    .let { remoteDecoded ->
                        // inject the local model into newly decoded remote one
                        merge(remoteDecoded, r.source, getPasswordStrength)
                    }
            },
            onLog = { msg, logLevel ->
                logRepository.add(TAG, msg, logLevel)
            },
        )

        //
        // Collection
        //

        fun BitwardenCrCta.collectionDecoder(
            entity: CollectionEntity,
        ) = kotlin.run {
            BitwardenCollection
                .encrypted(
                    accountId = user.id,
                    entity = entity,
                )
                .transform(this)
        }

        post(
            title = "Syncing collection entities.",
        )

        val collectionDao = db.collectionQueries
        val collectionRemoteLens = SyncManager.Lens<CollectionEntity>(
            getId = { it.id },
            getRevisionDate = { Instant.DISTANT_FUTURE },
        )
        val existingCollections = collectionDao
            .getByAccountId(
                accountId = user.id,
            )
            .executeAsList()
            .map { it.data_ }
        syncX(
            name = "collection",
            localItems = existingCollections,
            localLens = SyncManager.LensLocal(
                getLocalId = { it.collectionId },
                getLocalRevisionDate = { it.revisionDate },
            ),
            localReEncoder = { model ->
                model
            },
            localDecoder = { l, d ->
                Unit
            },
            localDeleteById = { ids ->
                collectionDao.transaction {
                    ids.forEach { collectionId ->
                        collectionDao.deleteByCollectionId(
                            collectionId = collectionId,
                        )
                    }
                }
            },
            localPut = { models ->
                collectionDao.transaction {
                    models.forEach { collection ->
                        collectionDao.insert(
                            collectionId = collection.collectionId,
                            accountId = collection.accountId,
                            data = collection,
                        )
                    }
                }
            },
            remoteItems = response.collections.orEmpty(),
            remoteLens = collectionRemoteLens,
            remoteDecoder = { remote, local ->
                val codec = getCodec(
                    mode = BitwardenCrCta.Mode.DECRYPT,
                    organizationId = remote.organizationId,
                )
                codec
                    .collectionDecoder(
                        entity = remote,
                    )
            },
            remoteDeleteById = { id ->
                TODO()
            },
            remoteDecodedFallback = { remote, localOrNull, e ->
                val logE = DecodeVaultException(
                    message = "Failed to decrypt a collection.",
                    e = e,
                )
                recordException(logE)

                val service = createDecodingFailedServiceModel(
                    now = now,
                    model = remote,
                    lens = collectionRemoteLens,
                )
                val model = localOrNull?.copy(service = service) ?: BitwardenCollection(
                    accountId = user.id,
                    collectionId = remote.id,
                    externalId = remote.externalId,
                    organizationId = remote.organizationId,
                    revisionDate = now,
                    // service fields
                    service = service,
                    // common
                    name = "⚠️ Unsupported Collection",
                    hidePasswords = remote.hidePasswords,
                    readOnly = remote.readOnly,
                )
                model
            },
            remotePut = { collections ->
                TODO()
            },
            onLog = { msg, logLevel ->
                logRepository.add(TAG, msg, logLevel)
            },
        )

        //
        // Organization
        //

        fun BitwardenCrCta.organizationDecoder(
            entity: OrganizationEntity,
        ) = kotlin.run {
            BitwardenOrganization
                .encrypted(
                    accountId = user.id,
                    entity = entity,
                )
                .transform(this)
        }

        post(
            title = "Syncing organization entities.",
        )

        val organizationDao = db.organizationQueries
        val organizationRemoteLens = SyncManager.Lens<OrganizationEntity>(
            getId = { it.id },
            getRevisionDate = { Instant.DISTANT_FUTURE },
        )
        val existingOrganizations = organizationDao
            .getByAccountId(
                accountId = user.id,
            )
            .executeAsList()
            .map { it.data_ }
        syncX(
            name = "organization",
            localItems = existingOrganizations,
            localLens = SyncManager.LensLocal(
                getLocalId = { it.organizationId },
                getLocalRevisionDate = { it.revisionDate },
            ),
            localReEncoder = { model ->
                model
            },
            localDecoder = { l, d ->
                Unit
            },
            localDeleteById = { ids ->
                organizationDao.transaction {
                    ids.forEach { organizationId ->
                        organizationDao.deleteByOrganizationId(
                            organizationId = organizationId,
                        )
                    }
                }
            },
            localPut = { models ->
                organizationDao.transaction {
                    models.forEach { organization ->
                        organizationDao.insert(
                            organizationId = organization.organizationId,
                            accountId = organization.accountId,
                            data = organization,
                        )
                    }
                }
            },
            remoteItems = response.profile.organizations.orEmpty(),
            remoteLens = organizationRemoteLens,
            remoteDecoder = { remote, local ->
                val codec = getCodec(
                    mode = BitwardenCrCta.Mode.DECRYPT,
                )
                codec
                    .organizationDecoder(
                        entity = remote,
                    )
            },
            remoteDeleteById = { id ->
                TODO()
            },
            remoteDecodedFallback = { remote, localOrNull, e ->
                val logE = DecodeVaultException(
                    message = "Failed to decrypt a organization.",
                    e = e,
                )
                recordException(logE)

                val service = createDecodingFailedServiceModel(
                    now = now,
                    model = remote,
                    lens = organizationRemoteLens,
                )
                val model = localOrNull?.copy(service = service) ?: BitwardenOrganization(
                    accountId = user.id,
                    organizationId = remote.id,
                    revisionDate = now,
                    // service fields
                    service = service,
                    // common
                    name = "⚠️ Unsupported Organization",
                    selfHost = remote.selfHost,
                )
                model
            },
            remotePut = {
                TODO()
            },
            onLog = { msg, logLevel ->
                logRepository.add(TAG, msg, logLevel)
            },
        )

        //
        // Sends
        //

        fun BitwardenCrCta.sendDecoder(
            entity: SyncSends,
            codec2: BitwardenCrCta,
            localSyncId: String?,
        ) = kotlin.run {
            val syncId = localSyncId
                ?: cryptoGenerator.uuid()
            BitwardenSend
                .encrypted(
                    accountId = user.id,
                    sendId = syncId,
                    entity = entity,
                )
                .transform(this, codec2)
        }

        post(
            title = "Syncing send entities.",
        )

        val sendDao = db.sendQueries
        val sendRemoteLens = SyncManager.Lens<SyncSends>(
            getId = { it.id },
            getRevisionDate = { it.revisionDate },
        )
        val existingSends = sendDao
            .getByAccountId(
                accountId = user.id,
            )
            .executeAsList()
            .map { it.data_ }
        syncX(
            name = "send",
            localItems = existingSends,
            localLens = SyncManager.LensLocal(
                getLocalId = { it.sendId },
                getLocalRevisionDate = { it.revisionDate },
            ),
            localReEncoder = { model ->
                model
            },
            localDecoder = { local, remote ->
                val itemKey = requireNotNull(local.keyBase64)
                    .let(base64Service::decode)
                val (
                    itemCrypto,
                    globalCrypto,
                ) = getCodecPair(
                    mode = BitwardenCrCta.Mode.ENCRYPT,
                    key = itemKey,
                )
                val encryptedSend = local.transform(
                    itemCrypto = itemCrypto,
                    globalCrypto = globalCrypto,
                )
                with(cryptoGenerator) {
                    with(base64Service) {
                        SendUpdate.of(
                            model = encryptedSend,
                            key = itemKey,
                        )
                    }
                } to local
            },
            localDeleteById = { ids ->
                sendDao.transaction {
                    ids.forEach { sendId ->
                        sendDao.deleteBySendId(
                            sendId = sendId,
                        )
                    }
                }
            },
            localPut = { models ->
                sendDao.transaction {
                    models.forEach { send ->
                        sendDao.insert(
                            accountId = send.accountId,
                            sendId = send.sendId,
                            data = send,
                        )
                    }
                }
            },
            remoteItems = response.sends.orEmpty(),
            remoteLens = sendRemoteLens,
            remoteDecoder = { remote, local ->
                val (
                    itemCrypto,
                    globalCrypto,
                ) = getCodecPairFromEncrypted(
                    mode = BitwardenCrCta.Mode.DECRYPT,
                    keyCipherText = remote.key,
                )
                itemCrypto
                    .sendDecoder(
                        entity = remote,
                        codec2 = globalCrypto,
                        localSyncId = local?.sendId,
                    )
            },
            remoteDeleteById = { id ->
                user.env.back().api.sends.focus(id).delete(
                    httpClient = httpClient,
                    env = env,
                    token = user.token.accessToken,
                )
            },
            remoteDecodedFallback = { remote, localOrNull, e ->
                e.printStackTrace()
                val service = createDecodingFailedServiceModel(
                    now = now,
                    model = remote,
                    lens = sendRemoteLens,
                )
                val model = localOrNull?.copy(service = service) ?: BitwardenSend(
                    accountId = user.id,
                    sendId = localOrNull?.sendId ?: cryptoGenerator.uuid(),
                    revisionDate = now,
                    // service fields
                    service = service,
                    // common
                    name = "⚠️ Unsupported Send",
                    notes = "",
                    accessCount = 0,
                    accessId = "",
                )
                model
            },
            remotePut = { (r, local) ->
                val sendsApi = user.env.back().api.sends
                val sendResponse = when (r) {
                    is SendUpdate.Modify -> {
                        val cipherApi = sendsApi.focus(r.sendId)
                        var cipherRequest = r.sendRequest

                        val hasChanged =
                            r.source.service.remote?.revisionDate != r.source.revisionDate
                        if (hasChanged) {
                            val putCipher = cipherApi.put(
                                httpClient = httpClient,
                                env = env,
                                token = user.token.accessToken,
                                body = cipherRequest,
                            )

                            // Removing a password has a separate endpoint and
                            // requires an additional request.
                            val shouldRemovePassword = r.source.changes?.passwordBase64
                                ?.let { it is BitwardenOptionalStringNullable.Some && it.value == null } == true
                            if (shouldRemovePassword && putCipher.password != null) {
                                cipherApi.removePassword(
                                    httpClient = httpClient,
                                    env = env,
                                    token = user.token.accessToken,
                                )
                            }
                        }

                        cipherApi.get(
                            httpClient = httpClient,
                            env = env,
                            token = user.token.accessToken,
                        )
                    }

                    is SendUpdate.Create -> {
                        sendsApi.post(
                            httpClient = httpClient,
                            env = env,
                            token = user.token.accessToken,
                            body = r.sendRequest,
                        )
                    }
                }

                val itemKey = requireNotNull(local.keyBase64)
                    .let(base64Service::decode)
                val (
                    itemCrypto,
                    globalCrypto,
                ) = getCodecPair(
                    mode = BitwardenCrCta.Mode.DECRYPT,
                    key = itemKey,
                )
                itemCrypto
                    .sendDecoder(
                        entity = sendResponse,
                        codec2 = globalCrypto,
                        localSyncId = local.sendId,
                    )
            },
            onLog = { msg, logLevel ->
                logRepository.add(TAG, msg, logLevel)
            },
        )

        post(
            title = "Syncing complete.",
        )

        Unit
    }

    // User

    private fun crypto(
        profile: SyncProfile,
    ): BitwardenCr = kotlin.run {
        val builder = BitwardenCrImpl(
            cipherEncryptor = cipherEncryptor,
            cryptoGenerator = cryptoGenerator,
            base64Service = base64Service,
        ).apply {
            // We need user keys to decrypt the
            // profile key.
            appendUserToken(
                encKey = base64Service.decode(user.key.encryptionKeyBase64),
                macKey = base64Service.decode(user.key.macKeyBase64),
            )
            appendProfileToken(
                keyCipherText = profile.key,
                privateKeyCipherText = profile.privateKey,
            )

            profile.organizations.orEmpty().forEach { organization ->
                appendOrganizationToken(
                    id = organization.id,
                    keyCipherText = organization.key,
                )
            }
        }
        builder.build()
    }

    private suspend fun requireSecurityStampMatchesOrNull(
        db: Database,
        response: SyncResponse,
    ) {
        val existingProfile = db
            .profileQueries
            .getByAccountId(
                accountId = user.id,
            )
            .executeAsOneOrNull()
            ?.takeIf { it.profileId == response.profile.id }
        if (existingProfile != null) {
            val existingSecurityStamp = existingProfile.data_.securityStamp
            require(existingSecurityStamp == response.profile.securityStamp) {
                "Local security stamp differs from a remote one. " +
                        "You might be in a man-in-the-middle attack!"
            }
        }
    }
}
