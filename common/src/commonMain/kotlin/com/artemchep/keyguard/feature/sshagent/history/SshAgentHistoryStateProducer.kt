package com.artemchep.keyguard.feature.sshagent.history

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DSshUsageHistory
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.SshUsageHistoryMode
import com.artemchep.keyguard.common.model.SshUsageHistoryRequestType
import com.artemchep.keyguard.common.model.SshUsageHistoryResponseType
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetSshUsageHistory
import com.artemchep.keyguard.common.usecase.RemoveSshUsageHistory
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.createConfirmationDialogIntent
import com.artemchep.keyguard.feature.decorator.ItemDecoratorDate
import com.artemchep.keyguard.feature.decorator.forEachWithDecorUniqueSectionsOnly
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun produceSshAgentHistoryState(
    cipherId: String?,
) = with(localDI().direct) {
    produceSshAgentHistoryState(
        cipherId = cipherId,
        getSshUsageHistory = instance(),
        removeSshUsageHistory = instance(),
        getCiphers = instance(),
        dateFormatter = instance(),
        confirmationRouteFactory = instance(),
        json = instance(),
    )
}

@Composable
fun produceSshAgentHistoryState(
    cipherId: String?,
    getSshUsageHistory: GetSshUsageHistory,
    removeSshUsageHistory: RemoveSshUsageHistory,
    getCiphers: GetCiphers,
    dateFormatter: DateFormatter,
    confirmationRouteFactory: ConfirmationRouteFactory,
    json: Json,
): Loadable<SshAgentHistoryState> = produceScreenState(
    key = cipherId
        ?.let { "ssh_agent_history.$it" }
        ?: "ssh_agent_history",
    initial = Loadable.Loading,
    args = arrayOf(
        cipherId,
        getSshUsageHistory,
        removeSshUsageHistory,
        getCiphers,
        dateFormatter,
        confirmationRouteFactory,
        json,
    ),
) {
    sshAgentHistoryStateProducer(
        cipherId = cipherId,
        getSshUsageHistory = getSshUsageHistory,
        removeSshUsageHistory = removeSshUsageHistory,
        getCiphers = getCiphers,
        dateFormatter = dateFormatter,
        confirmationRouteFactory = confirmationRouteFactory,
        json = json,
    )
}

suspend fun RememberStateFlowScope.sshAgentHistoryStateProducer(
    cipherId: String?,
    getSshUsageHistory: GetSshUsageHistory,
    removeSshUsageHistory: RemoveSshUsageHistory,
    getCiphers: GetCiphers,
    dateFormatter: DateFormatter,
    confirmationRouteFactory: ConfirmationRouteFactory,
    json: Json,
): Flow<Loadable<SshAgentHistoryState>> {
    val mode = cipherId
        ?.let(SshUsageHistoryMode::Cipher)
        ?: SshUsageHistoryMode.Recent
    val historyFlow = getSshUsageHistory(mode)
        .shareInScreenScope()
    val ciphersFlow = getCiphers()
        .shareInScreenScope()

    suspend fun onDeleteAll() {
        val intent = createConfirmationDialogIntent(
            confirmationRouteFactory = confirmationRouteFactory,
            icon = icon(Icons.Outlined.Delete),
            title = translate(Res.string.ssh_agent_history_clear_history_confirmation_title),
            message = translate(Res.string.ssh_agent_history_clear_history_confirmation_text),
        ) {
            removeSshUsageHistory()
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
                        title = Res.string.ssh_agent_history_clear_history_title.wrap(),
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

        val decorator = ItemDecoratorDate<SshAgentHistoryItem, SshAgentHistoryItem.Value>(
            dateFormatter = dateFormatter,
            selector = { it.createdAt },
            factory = { id, text ->
                SshAgentHistoryItem.Section(
                    id = id,
                    text = text,
                )
            },
        )
        val out = mutableListOf<SshAgentHistoryItem>()
        values.forEachWithDecorUniqueSectionsOnly(
            decorator = decorator,
            tag = "SshAgentHistory",
            provideItemId = SshAgentHistoryItem::id,
        ) { item ->
            out += item
        }

        out
            .mapListShape()
            .toImmutableList()
    }

    return combine(
        subtitleFlow,
        optionsFlow,
        itemsFlow,
    ) { subtitle, options, items ->
        val state = SshAgentHistoryState(
            subtitle = subtitle,
            options = options,
            items = items,
        )
        Loadable.Ok(state)
    }
}

private suspend fun TranslatorScope.toItem(
    event: DSshUsageHistory,
    ciphersById: Map<String, DSecret>,
    dateFormatter: DateFormatter,
    json: Json,
): SshAgentHistoryItem.Value {
    val cipher = event.cipherId?.let(ciphersById::get)
    val requestText = title(event.request)
    val responseText = title(event.response)
    val callerInfo = buildSshUsageHistoryCallerInfo(
        caller = event.caller,
        json = json,
    )
    val callerText = callerInfo?.primaryLabel
        ?: translate(Res.string.ssh_agent_history_unknown_caller)

    val fingerprintText = event.fingerprint
        ?.takeIf { it.isNotBlank() }
        ?: cipher?.sshKey?.fingerprint
            ?.takeIf { it.isNotBlank() }
    val keyText = when {
        cipher != null -> cipher.name
        event.request == SshUsageHistoryRequestType.AGENT_LIST_KEYS -> null

        else -> translate(Res.string.ssh_agent_history_unknown_key)
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

    return SshAgentHistoryItem.Value(
        id = "ssh_history.${event.id.orEmpty()}",
        caller = callerText,
        description = details,
        formattedDate = formattedDate,
        responseText = responseText,
        request = event.request,
        response = event.response,
        createdAt = event.instant,
    )
}

private suspend fun TranslatorScope.title(request: SshUsageHistoryRequestType): String = when (request) {
    SshUsageHistoryRequestType.AGENT_LIST_KEYS ->
        translate(Res.string.ssh_agent_history_request_list_keys)

    SshUsageHistoryRequestType.AGENT_SIGN_DATA ->
        translate(Res.string.ssh_agent_history_request_sign_data)
}

private suspend fun TranslatorScope.title(response: SshUsageHistoryResponseType): String = when (response) {
    SshUsageHistoryResponseType.SUCCESS ->
        translate(Res.string.ssh_agent_history_response_success)

    SshUsageHistoryResponseType.USER_DENIED ->
        translate(Res.string.ssh_agent_history_response_user_denied)

    SshUsageHistoryResponseType.KEY_NOT_FOUND ->
        translate(Res.string.ssh_agent_history_response_key_not_found)

    SshUsageHistoryResponseType.FAILURE ->
        translate(Res.string.ssh_agent_history_response_failure)
}
