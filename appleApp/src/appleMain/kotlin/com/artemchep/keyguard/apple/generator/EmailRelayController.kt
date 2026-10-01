package com.artemchep.keyguard.apple.generator

import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DGeneratorEmailRelay
import com.artemchep.keyguard.common.service.relays.EmailRelayRegistry
import com.artemchep.keyguard.common.service.relays.api.EmailRelay
import com.artemchep.keyguard.common.usecase.AddEmailRelay
import com.artemchep.keyguard.common.usecase.GetEmailRelays
import com.artemchep.keyguard.common.usecase.RemoveEmailRelayById
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.platform.LeContext
import kotlin.time.Clock
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Provider forms and mutations only; navigation entries own the list state and its commands. */
internal class EmailRelayController(private val ctx: CoreContext) {
    suspend fun loadEmailRelayServices(): List<EmailRelayFormSnapshot> = withContext(Dispatchers.Default) {
        val main = ctx.awaitMain()
        val leContext = ctx.koin.get<LeContext>()
        val emailRelays = main.sessionKoin.get<EmailRelayRegistry>().values
        emailRelays
            .map { relay -> relay.toFormSnapshot(entity = null, leContext = leContext) }
            .sortedBy { it.serviceName.lowercase() }
    }

    suspend fun loadEmailRelay(id: String): EmailRelayFormSnapshot? = withContext(Dispatchers.Default) {
        val main = ctx.awaitMain()
        val leContext = ctx.koin.get<LeContext>()
        val getEmailRelays = main.sessionKoin.get<GetEmailRelays>()
        val entity = getEmailRelays().first().firstOrNull { it.id == id }
            ?: return@withContext null
        val emailRelays = main.sessionKoin.get<EmailRelayRegistry>().values
        val relay = emailRelays.firstOrNull { it.type == entity.type }
            ?: return@withContext null
        relay.toFormSnapshot(entity = entity, leContext = leContext)
    }

    private suspend fun EmailRelay.toFormSnapshot(
        entity: DGeneratorEmailRelay?,
        leContext: LeContext,
    ): EmailRelayFormSnapshot {
        val fields = schema.map { (key, s) ->
            EmailRelayFieldSnapshot(
                key = key,
                title = textResource(s.title, leContext),
                hint = s.hint?.let { textResource(it, leContext) },
                fieldDescription = s.description?.let { textResource(it, leContext) },
                secret = s.type == ConfirmationRoute.Args.Item.StringItem.Type.Password,
                canBeEmpty = s.canBeEmpty,
                value = entity?.data?.get(key).orEmpty(),
            )
        }
        return EmailRelayFormSnapshot(
            id = entity?.id,
            type = type,
            serviceName = name,
            docUrl = docUrl,
            name = entity?.name ?: name,
            fields = fields,
        )
    }

    suspend fun saveEmailRelay(
        id: String?,
        type: String,
        name: String,
        values: Map<String, String>,
    ) {
        val main = ctx.awaitMain()
        val addEmailRelay = main.sessionKoin.get<AddEmailRelay>()
        val model = DGeneratorEmailRelay(
            id = id,
            name = name,
            type = type,
            data = values.toPersistentMap(),
            createdDate = Clock.System.now(),
        )
        addEmailRelay(model).bind()
    }

    suspend fun duplicateEmailRelay(id: String) {
        val main = ctx.awaitMain()
        val getEmailRelays = main.sessionKoin.get<GetEmailRelays>()
        val addEmailRelay = main.sessionKoin.get<AddEmailRelay>()
        val entity = getEmailRelays().first().firstOrNull { it.id == id }
            ?: return
        val model = entity.copy(id = null, createdDate = Clock.System.now())
        addEmailRelay(model).bind()
    }

    suspend fun deleteEmailRelays(ids: List<String>) {
        val main = ctx.awaitMain()
        val removeEmailRelayById = main.sessionKoin.get<RemoveEmailRelayById>()
        removeEmailRelayById(ids.toSet()).bind()
    }
}
