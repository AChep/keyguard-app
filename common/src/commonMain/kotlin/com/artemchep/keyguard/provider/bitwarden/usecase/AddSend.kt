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
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.common.model.canDelete
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.model.create.CreateSendRequest
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.AddFolder
import com.artemchep.keyguard.common.usecase.AddSend
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.common.usecase.TrashCipherById
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.crypto.makeSendCryptoKey
import com.artemchep.keyguard.provider.bitwarden.crypto.makeSendCryptoKeyMaterial
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyDatabase
import kotlinx.collections.immutable.toPersistentList
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Duration

/**
 * @author Artem Chepurnyi
 */
class AddSendImpl(
    private val modifyDatabase: ModifyDatabase,
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
) : AddSend {
    companion object {
        private const val TAG = "AddSend.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        modifyDatabase = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        base64Service = directDI.instance(),
    )

    override fun invoke(
        sendIdsToRequests: Map<String?, CreateSendRequest>,
    ): IO<List<String>> = ioEffect {
        sendIdsToRequests
    }.flatMap { map ->
        modifyDatabase { database ->
            val dao = database.sendQueries
            val now = Clock.System.now()

            val oldSendsMap = map
                .keys
                .filterNotNull()
                .mapNotNull { sendId ->
                    dao
                        .getBySendId(sendId)
                        .executeAsOneOrNull()
                }
                .associateBy { it.sendId }

            val models = map
                .map { (sendId, request) ->
                    val old = oldSendsMap[sendId]?.data_
                    BitwardenSend.of(
                        cryptoGenerator = cryptoGenerator,
                        base64Service = base64Service,
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
                models.forEach { send ->
                    dao.insert(
                        sendId = send.sendId,
                        accountId = send.accountId,
                        data = send,
                    )
                }
            }

            val changedAccountIds = models
                .map { AccountId(it.accountId) }
                .toSet()
            ModifyDatabase.Result(
                changedAccountIds = changedAccountIds,
                value = models
                    .map { it.sendId },
            )
        }
    }
}

private suspend fun BitwardenSend.Companion.of(
    cryptoGenerator: CryptoGenerator,
    base64Service: Base64Service,
    request: CreateSendRequest,
    now: Instant,
    old: BitwardenSend? = null,
): BitwardenSend {
    val accountId = request.ownership?.accountId
    require(old?.service?.deleted != true) {
        "Can not modify deleted send!"
    }
    requireNotNull(accountId) { "Send must have an account!" }

    val type = when (request.type) {
        DSend.Type.Text -> BitwardenSend.Type.Text
        DSend.Type.File -> BitwardenSend.Type.File
        null,
        DSend.Type.None,
        -> error("Send must have a type!")
    }

    var text: BitwardenSend.Text? = null
    var file: BitwardenSend.File? = null
    when (type) {
        BitwardenSend.Type.Text -> {
            text = BitwardenSend.Text.of(
                request = request,
                old = old,
            )
        }

        BitwardenSend.Type.File -> {
            file = BitwardenSend.File.of(
                request = request,
            )
        }

        BitwardenSend.Type.None -> {
            error("Send must have a type!")
        }
    }

    val keyBase64 = old?.keyBase64
        ?: run {
            val key = cryptoGenerator.makeSendCryptoKeyMaterial()
            base64Service.encodeToString(key)
        }
    val cipherId = old?.sendId
        ?: cryptoGenerator.uuid()
    val createdDate = old?.createdDate ?: request.now
    val deletedDate = old?.deletedDate ?: request.now.plus(with(Duration) {3L.days})
    return BitwardenSend(
        accountId = accountId,
        sendId = cipherId,
        accessId = "",
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
        keyBase64 = keyBase64,
        name = request.title,
        notes = request.note,
        accessCount = 0,
        maxAccessCount = null,
        password = null,
        disabled = false,
        hideEmail = null,
        // types
        type = type,
        text = text,
        file = file,
    )
}

private suspend fun BitwardenSend.Text.Companion.of(
    request: CreateSendRequest,
    old: BitwardenSend? = null,
): BitwardenSend.Text {
    return BitwardenSend.Text(
        text = request.text.text,
        hidden = request.text.hidden,
    )
}

private suspend fun BitwardenSend.File.Companion.of(
    request: CreateSendRequest,
): BitwardenSend.File {
    TODO()
}
