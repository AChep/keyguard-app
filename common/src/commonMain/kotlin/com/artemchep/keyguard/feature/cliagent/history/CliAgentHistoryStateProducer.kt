package com.artemchep.keyguard.feature.cliagent.history

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryClient
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryMode
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryRequestType
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryResponseType
import com.artemchep.keyguard.common.model.DCliAgentUsageHistory
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_NAME_LENGTH
import com.artemchep.keyguard.common.service.agent.sanitizedAgentDisplayValue
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCliAgentUsageHistory
import com.artemchep.keyguard.common.usecase.RemoveCliAgentUsageHistory
import com.artemchep.keyguard.feature.agent.decodeAgentUsageHistoryCallerInfo
import com.artemchep.keyguard.feature.agent.history.AgentHistoryDetails
import com.artemchep.keyguard.feature.agent.history.navigateToAgentHistoryDetails
import com.artemchep.keyguard.feature.cli.MAX_CLI_COMMAND_DISPLAY_LENGTH
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.createConfirmationDialogIntent
import com.artemchep.keyguard.feature.decorator.ItemDecoratorDate
import com.artemchep.keyguard.feature.decorator.forEachWithDecorUniqueSectionsOnly
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.search.mapListShape
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import org.koin.compose.currentKoinScope

@Composable
fun produceCliAgentHistoryState(
    client: CliAgentUsageHistoryClient?,
    cipherId: String?,
) = with(currentKoinScope()) {
    produceCliAgentHistoryState(
        client = client,
        cipherId = cipherId,
        getCliAgentUsageHistory = get(),
        removeCliAgentUsageHistory = get(),
        getCiphers = get(),
        dateFormatter = get(),
        confirmationRouteFactory = get(),
        json = get(),
    )
}

@Composable
fun produceCliAgentHistoryState(
    client: CliAgentUsageHistoryClient?,
    cipherId: String?,
    getCliAgentUsageHistory: GetCliAgentUsageHistory,
    removeCliAgentUsageHistory: RemoveCliAgentUsageHistory,
    getCiphers: GetCiphers,
    dateFormatter: DateFormatter,
    confirmationRouteFactory: ConfirmationRouteFactory,
    json: Json,
): Loadable<CliAgentHistoryState> = produceScreenState(
    key = cipherId
        ?.let { "cli_agent_history.cipher.$it" }
        ?: "cli_agent_history.${client?.name}",
    initial = Loadable.Loading,
    args = arrayOf(
        client,
        cipherId,
        getCliAgentUsageHistory,
        removeCliAgentUsageHistory,
        getCiphers,
        dateFormatter,
        confirmationRouteFactory,
        json,
    ),
) {
    cliAgentHistoryStateProducer(
        client = client,
        cipherId = cipherId,
        getCliAgentUsageHistory = getCliAgentUsageHistory,
        removeCliAgentUsageHistory = removeCliAgentUsageHistory,
        getCiphers = getCiphers,
        dateFormatter = dateFormatter,
        confirmationRouteFactory = confirmationRouteFactory,
        json = json,
    )
}

suspend fun RememberStateFlowScope.cliAgentHistoryStateProducer(
    client: CliAgentUsageHistoryClient?,
    cipherId: String?,
    getCliAgentUsageHistory: GetCliAgentUsageHistory,
    removeCliAgentUsageHistory: RemoveCliAgentUsageHistory,
    getCiphers: GetCiphers,
    dateFormatter: DateFormatter,
    confirmationRouteFactory: ConfirmationRouteFactory,
    json: Json,
): Flow<Loadable<CliAgentHistoryState>> {
    val mode = cipherId
        ?.let(CliAgentUsageHistoryMode::Cipher)
        ?: CliAgentUsageHistoryMode.Recent(client ?: CliAgentUsageHistoryClient.UNKNOWN)
    val historyFlow = getCliAgentUsageHistory(mode)
        .shareInScreenScope()
    // The rows show only environments, so a change of any
    // other item in the vault does not rebuild them.
    val environmentsFlow = getCiphers()
        .map { ciphers ->
            ciphers
                .filter { it.type == DSecret.Type.Environment }
                .associateBy { it.id }
        }
        .distinctUntilChanged()
        .shareInScreenScope()
    // Only a client's log can be cleared; an environment's log spans every client.
    val clearClient = (mode as? CliAgentUsageHistoryMode.Recent)?.client

    suspend fun onDeleteAll(client: CliAgentUsageHistoryClient) {
        val intent = createConfirmationDialogIntent(
            confirmationRouteFactory = confirmationRouteFactory,
            icon = icon(Icons.Outlined.Delete),
            title = translate(Res.string.cli_agent_history_clear_history_confirmation_title),
            message = translate(Res.string.cli_agent_history_clear_history_confirmation_text),
        ) {
            removeCliAgentUsageHistory(client)
                .launchIn(appScope)
        }
        navigate(intent)
    }

    val optionsFlow = historyFlow
        .map { history ->
            if (clearClient == null || history.isEmpty()) {
                persistentListOf()
            } else {
                persistentListOf(
                    FlatItemAction(
                        id = "cliAgentHistory.clearHistory",
                        leading = icon(Icons.Outlined.Delete),
                        title = Res.string.agent_history_clear_history_title.wrap(),
                        danger = true,
                        onClick = onClick {
                            onDeleteAll(clearClient)
                        },
                    ),
                )
            }
        }
        .distinctUntilChanged()

    val subtitleFlow = when (mode) {
        is CliAgentUsageHistoryMode.Cipher -> environmentsFlow
            .map { environments -> environments[mode.cipherId]?.name }
            .distinctUntilChanged()

        is CliAgentUsageHistoryMode.Recent -> flowOf(title(mode.client))
    }

    return combine(
        subtitleFlow,
        optionsFlow,
        itemsFlow(
            historyFlow = historyFlow,
            environmentsFlow = environmentsFlow,
            dateFormatter = dateFormatter,
            json = json,
            onClick = { navigateToAgentHistoryDetails(it) },
        ),
    ) { subtitle, options, items ->
        val state = CliAgentHistoryState(
            subtitle = subtitle,
            options = options,
            items = items,
        )
        Loadable.Ok(state)
    }
}

/** The rows of the [historyFlow], newest first, under a header for each day. */
private fun TranslatorScope.itemsFlow(
    historyFlow: Flow<List<DCliAgentUsageHistory>>,
    environmentsFlow: Flow<Map<String, DSecret>>,
    dateFormatter: DateFormatter,
    json: Json,
    onClick: (AgentHistoryDetails) -> Unit,
): Flow<ImmutableList<CliAgentHistoryItem>> = combine(
    historyFlow,
    environmentsFlow,
) { history, environments ->
    val values = history
        .map { event ->
            toItem(
                event = event,
                environments = environments,
                dateFormatter = dateFormatter,
                json = json,
                onClick = onClick,
            )
        }

    val decorator = ItemDecoratorDate<CliAgentHistoryItem, CliAgentHistoryItem.Value>(
        dateFormatter = dateFormatter,
        selector = { it.createdAt },
        factory = { id, text ->
            CliAgentHistoryItem.Section(
                id = id,
                text = text,
            )
        },
    )
    val out = mutableListOf<CliAgentHistoryItem>()
    values.forEachWithDecorUniqueSectionsOnly(
        decorator = decorator,
        tag = "CliAgentHistory",
        provideItemId = CliAgentHistoryItem::id,
    ) { item ->
        out += item
    }

    out
        .mapListShape()
        .toImmutableList()
}

private suspend fun TranslatorScope.toItem(
    event: DCliAgentUsageHistory,
    environments: Map<String, DSecret>,
    dateFormatter: DateFormatter,
    json: Json,
    onClick: (AgentHistoryDetails) -> Unit,
): CliAgentHistoryItem.Value {
    val environment = event.cipherId?.let(environments::get)
    val callerInfo = decodeAgentUsageHistoryCallerInfo(
        caller = event.caller,
        json = json,
    )
    val callerText = callerInfo?.primaryLabel
        ?: when (event.client) {
            // A FIFO can not identify the program that reads it.
            CliAgentUsageHistoryClient.ENV -> translate(Res.string.environments_local_process)
            else -> translate(Res.string.agent_history_unknown_caller)
        }
    val clientNameText = event.clientName
        .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH)
        ?.let { translate(Res.string.environments_approval_client, it) }
    val requestText = title(event.request)
    val description = buildCliAgentUsageHistoryDescription(
        request = requestText,
        environment = environment?.name,
        clientName = clientNameText,
        caller = callerInfo?.secondaryLabel,
        errorCode = event.errorCode
            .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH),
    )
    val id = "cli_agent_history.${event.id.orEmpty()}"
    val responseText = title(event.response)
    val formattedDate = dateFormatter.formatDateTime(event.instant)
    val historyDetails = cliAgentHistoryDetails(
        id = id,
        event = event,
        cipher = environment,
        requestText = requestText,
        responseText = responseText,
        formattedDate = formattedDate,
        json = json,
    )
    return CliAgentHistoryItem.Value(
        id = id,
        caller = callerText,
        description = description,
        commandText = commandText(event),
        formattedDate = formattedDate,
        responseText = responseText,
        request = event.request,
        response = event.response,
        createdAt = event.instant,
        details = historyDetails,
        onClick = {
            onClick(historyDetails)
        },
    )
}

/** The command line and its directory, or the path of a local `.env` file. */
private suspend fun TranslatorScope.commandText(
    event: DCliAgentUsageHistory,
): String? {
    val path = event.path
        .sanitizedAgentDisplayValue(MAX_CLI_COMMAND_DISPLAY_LENGTH)
    return buildCliAgentUsageHistoryDetails(
        command = event.command
            .sanitizedAgentDisplayValue(MAX_CLI_COMMAND_DISPLAY_LENGTH),
        path = when (event.request) {
            CliAgentUsageHistoryRequestType.RUN -> path
                ?.let { translate(Res.string.cli_approval_working_directory, it) }

            else -> path
        },
    )
}

internal suspend fun TranslatorScope.title(client: CliAgentUsageHistoryClient): String = when (client) {
    CliAgentUsageHistoryClient.CLI -> translate(Res.string.cli_title)
    CliAgentUsageHistoryClient.MCP -> translate(Res.string.protocol_mcp)
    CliAgentUsageHistoryClient.ENV -> translate(Res.string.environments_mounts)
    CliAgentUsageHistoryClient.UNKNOWN -> translate(Res.string.cipher_type_unknown)
}

private suspend fun TranslatorScope.title(request: CliAgentUsageHistoryRequestType): String = when (request) {
    CliAgentUsageHistoryRequestType.RUN ->
        translate(Res.string.cli_agent_history_request_run)

    CliAgentUsageHistoryRequestType.MCP_AUTHENTICATE ->
        translate(Res.string.environments_capability_authenticate)

    CliAgentUsageHistoryRequestType.MCP_LIST_ENVIRONMENTS ->
        translate(Res.string.environments_capability_list)

    CliAgentUsageHistoryRequestType.MCP_CREATE_ENVIRONMENT ->
        translate(Res.string.environments_capability_create)

    CliAgentUsageHistoryRequestType.MCP_RENAME_ENVIRONMENT ->
        translate(Res.string.environments_capability_rename)

    CliAgentUsageHistoryRequestType.MCP_LIST_VARIABLES ->
        translate(Res.string.environments_capability_variables)

    CliAgentUsageHistoryRequestType.MCP_APPEND_VARIABLES ->
        translate(Res.string.environments_capability_update)

    CliAgentUsageHistoryRequestType.MCP_CREATE_LOCAL_ENV_FILE ->
        translate(Res.string.environments_capability_create_mount)

    CliAgentUsageHistoryRequestType.MCP_LIST_LOCAL_ENV_FILES ->
        translate(Res.string.environments_capability_mounts)

    CliAgentUsageHistoryRequestType.READ_LOCAL_ENV_FILE ->
        translate(Res.string.cli_agent_history_request_read_local_env_file)

    CliAgentUsageHistoryRequestType.UNKNOWN ->
        translate(Res.string.cipher_type_unknown)
}

private suspend fun TranslatorScope.title(response: CliAgentUsageHistoryResponseType): String = when (response) {
    CliAgentUsageHistoryResponseType.SUCCESS ->
        translate(Res.string.agent_history_response_success)

    CliAgentUsageHistoryResponseType.USER_DENIED ->
        translate(Res.string.agent_history_response_user_denied)

    CliAgentUsageHistoryResponseType.NOT_FOUND ->
        translate(Res.string.error_not_found)

    CliAgentUsageHistoryResponseType.VAULT_LOCKED ->
        translate(Res.string.agent_history_response_vault_locked)

    CliAgentUsageHistoryResponseType.TIMED_OUT ->
        translate(Res.string.agent_history_response_timed_out)

    CliAgentUsageHistoryResponseType.CANCELLED ->
        translate(Res.string.agent_history_response_cancelled)

    CliAgentUsageHistoryResponseType.FAILURE ->
        translate(Res.string.agent_history_response_failure)

    CliAgentUsageHistoryResponseType.UNKNOWN ->
        translate(Res.string.cipher_type_unknown)
}
