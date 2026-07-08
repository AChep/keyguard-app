package com.artemchep.keyguard.feature.gpgagent.history

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.DGpgUsageHistory
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GpgUsageHistoryMode
import com.artemchep.keyguard.common.model.GpgUsageHistoryRequestType
import com.artemchep.keyguard.common.model.GpgUsageHistoryResponseType
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetGpgUsageHistory
import com.artemchep.keyguard.common.usecase.RemoveGpgUsageHistory
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.createConfirmationDialogIntent
import com.artemchep.keyguard.feature.decorator.ItemDecoratorDate
import com.artemchep.keyguard.feature.decorator.forEachWithDecorUniqueSectionsOnly
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.feature.search.search.mapListShape
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun produceGpgAgentHistoryState(
    cipherId: String?,
) = with(localDI().direct) {
    produceGpgAgentHistoryState(
        cipherId = cipherId,
        getGpgUsageHistory = instance(),
        removeGpgUsageHistory = instance(),
        getCiphers = instance(),
        dateFormatter = instance(),
        confirmationRouteFactory = instance(),
        json = instance(),
    )
}

@Composable
fun produceGpgAgentHistoryState(
    cipherId: String?,
    getGpgUsageHistory: GetGpgUsageHistory,
    removeGpgUsageHistory: RemoveGpgUsageHistory,
    getCiphers: GetCiphers,
    dateFormatter: DateFormatter,
    confirmationRouteFactory: ConfirmationRouteFactory,
    json: Json,
): Loadable<GpgAgentHistoryState> = produceScreenState(
    key = cipherId
        ?.let { "gpg_agent_history.$it" }
        ?: "gpg_agent_history",
    initial = Loadable.Loading,
    args = arrayOf(
        cipherId,
        getGpgUsageHistory,
        removeGpgUsageHistory,
        getCiphers,
        dateFormatter,
        confirmationRouteFactory,
        json,
    ),
) {
    val mode = cipherId
        ?.let(GpgUsageHistoryMode::Cipher)
        ?: GpgUsageHistoryMode.Recent
    val historyFlow = getGpgUsageHistory(mode)
        .shareInScreenScope()
    val ciphersFlow = getCiphers()
        .shareInScreenScope()

    suspend fun onDeleteAll() {
        val intent = createConfirmationDialogIntent(
            confirmationRouteFactory = confirmationRouteFactory,
            icon = icon(Icons.Outlined.Delete),
            title = translate(Res.string.gpg_agent_history_clear_history_confirmation_title),
            message = translate(Res.string.gpg_agent_history_clear_history_confirmation_text),
        ) {
            removeGpgUsageHistory()
                .launchIn(appScope)
        }
        navigate(intent)
    }

    val optionsFlow = historyFlow
        .map { history ->
            if (cipherId != null || history.isEmpty()) {
                persistentListOf()
            } else {
                persistentListOf(
                    FlatItemAction(
                        leading = icon(Icons.Outlined.Delete),
                        title = Res.string.gpg_agent_history_clear_history_title.wrap(),
                        onClick = onClick {
                            onDeleteAll()
                        },
                    ),
                )
            }
        }
        .distinctUntilChanged()

    val subtitleFlow = ciphersFlow
        .map { ciphers ->
            cipherId
                ?.let { id ->
                    ciphers.firstOrNull { it.id == id }
                }
                ?.name
        }
        .distinctUntilChanged()

    val itemsFlow = combine(
        historyFlow,
        ciphersFlow,
    ) { history, ciphers ->
        val ciphersById = ciphers.associateBy { it.id }
        val values = history
            .sortedByDescending { it.instant }
            .map { event ->
                toItem(
                    event = event,
                    ciphersById = ciphersById,
                    dateFormatter = dateFormatter,
                    json = json,
                )
            }

        val decorator = ItemDecoratorDate<GpgAgentHistoryItem, GpgAgentHistoryItem.Value>(
            dateFormatter = dateFormatter,
            selector = { it.createdAt },
            factory = { id, text ->
                GpgAgentHistoryItem.Section(
                    id = id,
                    text = text,
                )
            },
        )
        val out = mutableListOf<GpgAgentHistoryItem>()
        values.forEachWithDecorUniqueSectionsOnly(
            decorator = decorator,
            tag = "GpgAgentHistory",
            provideItemId = GpgAgentHistoryItem::id,
        ) { item ->
            out += item
        }

        out
            .mapListShape()
            .toImmutableList()
    }

    combine(
        subtitleFlow,
        optionsFlow,
        itemsFlow,
    ) { subtitle, options, items ->
        val state = GpgAgentHistoryState(
            subtitle = subtitle,
            options = options,
            items = items,
        )
        Loadable.Ok(state)
    }
}

private suspend fun TranslatorScope.toItem(
    event: DGpgUsageHistory,
    ciphersById: Map<String, DSecret>,
    dateFormatter: DateFormatter,
    json: Json,
): GpgAgentHistoryItem.Value {
    val cipher = event.cipherId?.let(ciphersById::get)
    val requestText = title(event.request)
    val responseText = title(event.response)
    val callerInfo = buildGpgUsageHistoryCallerInfo(
        caller = event.caller,
        json = json,
    )
    val callerText = callerInfo?.primaryLabel
        ?: translate(Res.string.gpg_agent_history_unknown_caller)

    val fingerprintText = event.fingerprint
        ?.takeIf { it.isNotBlank() }
    val keyText = when {
        cipher != null -> cipher.name
        event.request == GpgUsageHistoryRequestType.AGENT_LIST_KEYS -> null

        else -> translate(Res.string.gpg_agent_history_unknown_key)
    }
    val formattedDate = dateFormatter.formatDateTime(event.instant)
    val details = buildList {
        add(requestText)
        callerInfo?.secondaryLabel
            ?.let(::add)
        keyText
            ?.let(::add)
        fingerprintText
            ?.takeIf { it != keyText }
            ?.let(::add)
    }.joinToString(separator = " • ")

    return GpgAgentHistoryItem.Value(
        id = "gpg_history.${event.id.orEmpty()}",
        caller = callerText,
        description = details,
        formattedDate = formattedDate,
        responseText = responseText,
        request = event.request,
        response = event.response,
        createdAt = event.instant,
    )
}

private suspend fun TranslatorScope.title(request: GpgUsageHistoryRequestType): String = when (request) {
    GpgUsageHistoryRequestType.AGENT_LIST_KEYS ->
        translate(Res.string.gpg_agent_history_request_list_keys)

    GpgUsageHistoryRequestType.AGENT_SIGN_HASH ->
        translate(Res.string.gpg_agent_history_request_sign_hash)

    GpgUsageHistoryRequestType.AGENT_DECRYPT ->
        translate(Res.string.gpg_agent_history_request_decrypt)

    GpgUsageHistoryRequestType.UNKNOWN ->
        translate(Res.string.cipher_type_unknown)
}

private suspend fun TranslatorScope.title(response: GpgUsageHistoryResponseType): String = when (response) {
    GpgUsageHistoryResponseType.SUCCESS ->
        translate(Res.string.gpg_agent_history_response_success)

    GpgUsageHistoryResponseType.USER_DENIED ->
        translate(Res.string.gpg_agent_history_response_user_denied)

    GpgUsageHistoryResponseType.KEY_NOT_FOUND ->
        translate(Res.string.gpg_agent_history_response_key_not_found)

    GpgUsageHistoryResponseType.VAULT_LOCKED ->
        translate(Res.string.gpg_agent_history_response_vault_locked)

    GpgUsageHistoryResponseType.UNSUPPORTED ->
        translate(Res.string.gpg_agent_history_response_unsupported)

    GpgUsageHistoryResponseType.FAILURE ->
        translate(Res.string.gpg_agent_history_response_failure)

    GpgUsageHistoryResponseType.UNKNOWN ->
        translate(Res.string.cipher_type_unknown)
}
