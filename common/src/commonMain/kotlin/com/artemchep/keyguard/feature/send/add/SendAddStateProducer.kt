package com.artemchep.keyguard.feature.send.add

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.KeyboardType
import arrow.core.flatten
import arrow.core.partially1
import arrow.core.partially2
import arrow.optics.Optional
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.create.CreateSendRequest
import com.artemchep.keyguard.common.model.create.deletionDate
import com.artemchep.keyguard.common.model.create.disabled
import com.artemchep.keyguard.common.model.create.expirationDate
import com.artemchep.keyguard.common.model.create.hidden
import com.artemchep.keyguard.common.model.create.hideEmail
import com.artemchep.keyguard.common.model.create.maxAccessCount
import com.artemchep.keyguard.common.model.create.note
import com.artemchep.keyguard.common.model.create.nullableDeletionDateAsDuration
import com.artemchep.keyguard.common.model.create.nullableExpirationDateAsDuration
import com.artemchep.keyguard.common.model.create.password
import com.artemchep.keyguard.common.model.create.text
import com.artemchep.keyguard.common.model.requiresPremium
import com.artemchep.keyguard.common.model.titleH
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.AddSend
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetGravatarUrl
import com.artemchep.keyguard.common.usecase.GetMarkdown
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetSends
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.common.util.flow.combineToList
import com.artemchep.keyguard.common.util.flow.foldAsList
import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.feature.add.AddStateItem
import com.artemchep.keyguard.feature.add.AddStateOwnership
import com.artemchep.keyguard.feature.add.LocalStateItem
import com.artemchep.keyguard.feature.add.OwnershipState
import com.artemchep.keyguard.feature.add.accountFlow
import com.artemchep.keyguard.feature.add.ownershipHandle
import com.artemchep.keyguard.feature.add.produceItemFlow
import com.artemchep.keyguard.feature.auth.common.SwitchFieldModel
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.auth.common.util.validatedInteger
import com.artemchep.keyguard.feature.auth.common.util.validatedTitle
import com.artemchep.keyguard.feature.confirmation.organization.OrganizationConfirmationResult
import com.artemchep.keyguard.feature.confirmation.organization.OrganizationConfirmationRoute
import com.artemchep.keyguard.feature.home.vault.add.createItem
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.send.view.SendViewRoute
import com.artemchep.keyguard.platform.parcelize.LeParcelable
import com.artemchep.keyguard.platform.parcelize.LeParcelize
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.format
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance
import kotlin.time.Duration

@kotlinx.serialization.Serializable
@LeParcelize
data class SendAddOwnershipData(
    override val accountId: String?,
) : LeParcelable, OwnershipState

data class TmpText(
    val text: AddStateItem.Text<CreateSendRequest>,
    val hidden: AddStateItem.Switch<CreateSendRequest>,
    val items: List<AddStateItem>,
)

data class TmpOptions(
    val deletionDate: AddStateItem.DateTime<CreateSendRequest>,
    val expirationDate: AddStateItem.DateTime<CreateSendRequest>,
    val password: AddStateItem.Password<CreateSendRequest>,
    val items: Flow<List<AddStateItem>>,
)

@Composable
fun produceSendAddScreenState(
    args: SendAddRoute.Args,
) = with(localDI().direct) {
    produceSendAddScreenState(
        args = args,
        getAccounts = instance(),
        getProfiles = instance(),
        getOrganizations = instance(),
        getCollections = instance(),
        getFolders = instance(),
        getCiphers = instance(),
        getSends = instance(),
        getTotpCode = instance(),
        getGravatarUrl = instance(),
        getMarkdown = instance(),
        logRepository = instance(),
        clipboardService = instance(),
        dateFormatter = instance(),
        addSend = instance(),
    )
}

@Composable
fun produceSendAddScreenState(
    args: SendAddRoute.Args,
    getAccounts: GetAccounts,
    getProfiles: GetProfiles,
    getOrganizations: GetOrganizations,
    getCollections: GetCollections,
    getFolders: GetFolders,
    getCiphers: GetCiphers,
    getSends: GetSends,
    getTotpCode: GetTotpCode,
    getGravatarUrl: GetGravatarUrl,
    getMarkdown: GetMarkdown,
    logRepository: LogRepository,
    clipboardService: ClipboardService,
    dateFormatter: DateFormatter,
    addSend: AddSend,
): Loadable<SendAddState> = produceScreenState(
    key = "send_add",
    initial = Loadable.Loading,
    args = arrayOf(
        args,
        getAccounts,
        getOrganizations,
        getCollections,
        getFolders,
        getCiphers,
        getTotpCode,
    ),
) {
    val markdown = getMarkdown().first()

    val title = if (args.ownershipRo) {
        translate(Res.strings.addsend_header_edit_title)
    } else {
        translate(Res.strings.addsend_header_new_title)
    }

    val ownershipFlow = produceOwnershipFlow(
        args = args,
        getProfiles = getProfiles,
        getSends = getSends,
        getCiphers = getCiphers,
    )

    val textHolder = produceTextState(
        args = args,
    )
    val optionsHolder = produceOptionsState(
        args = args,
        markdown = markdown,
        dateFormatter = dateFormatter,
    )

    val typeFlow = kotlin.run {
        val initialValue = args.type
        // this should never happen
            ?: DSend.Type.None
        flowOf(initialValue)
    }
    val typeItemsFlow = typeFlow
        .flatMapLatest { type ->
            when (type) {
                DSend.Type.Text -> flowOf(textHolder.items)
                DSend.Type.File -> flowOf(emptyList())
                DSend.Type.None -> flowOf(emptyList())
            }
        }
    val optionsFlow = optionsHolder.items

    val titleItem = AddStateItem.Title<CreateSendRequest>(
        id = "title",
        state = LocalStateItem(
            flow = kotlin.run {
                val key = "title"
                val sink = mutablePersistedFlow(key) {
                    args.initialValue?.name
                        ?: args.name
                        ?: ""
                }
                val state = asComposeState<String>(key)
                combine(
                    sink
                        .validatedTitle(this),
                    typeFlow,
                ) { validatedTitle, type ->
                    TextFieldModel2.of(
                        state = state,
                        hint = translate(type.titleH()),
                        validated = validatedTitle,
                        onChange = state::value::set,
                    )
                }
                    .persistingStateIn(
                        scope = screenScope,
                        started = SharingStarted.WhileSubscribed(1000L),
                        initialValue = TextFieldModel2.empty,
                    )
            },
            populator = { field ->
                copy(
                    title = field.text,
                )
            },
        ),
    )
    val items1 = listOfNotNull(
        titleItem,
    )

    val deactivate =
        AddStateItem.Switch(
            id = "deactivate",
            title = translate(Res.strings.sends_action_disable_title),
            text = translate(Res.strings.sends_action_disable_text),
            state = LocalStateItem<SwitchFieldModel, CreateSendRequest>(
                flow = kotlin.run {
                    val sink = mutablePersistedFlow("deactivate") {
                        args.initialValue?.disabled
                            ?: false
                    }
                    sink.map {
                        SwitchFieldModel(
                            checked = it,
                            onChange = sink::value::set,
                        )
                    }
                        .persistingStateIn(
                            scope = screenScope,
                            started = SharingStarted.WhileSubscribed(),
                            initialValue = SwitchFieldModel(),
                        )
                },
                populator = { state ->
                    CreateSendRequest.disabled.set(this, state.checked)
                },
            ),
        )
    val actionsFlow = listOf(deactivate)
        .map { item ->
            when (item) {
                is AddStateItem.Switch ->
                    item
                        .state.flow
                        .map { model ->
                            FlatItemAction(
                                id = item.id,
                                title = item.title,
                                text = item.text,
                                trailing = {
                                    Switch(
                                        checked = model.checked,
                                        onCheckedChange = model.onChange,
                                    )
                                },
                                onClick = model.onChange?.partially1(!model.checked),
                            )
                        }

                else -> {
                    TODO()
                }
            }
        }
        .foldAsList()

    fun stetify(flow: Flow<List<AddStateItem>>) = flow
        .map { items ->
            items
                .mapNotNull { item ->
                    val stateHolder = item as? AddStateItem.HasState<Any?, CreateSendRequest>
                        ?: return@mapNotNull null

                    val state = stateHolder.state
                    val flow = state.flow
                        .map { v ->
                            state.populator
                                .partially2(v)
                        }
                    item.id to flow
                }
        }

    val itfff = combine(
        typeItemsFlow,
        optionsFlow,
    ) { arr ->
        arr.toList().flatten()
    }
        .onEach { l ->
            logRepository.post("Foo3", "combine ${l.size}")
        }

    val outputFlow = combine(
        stetify(itfff),
        stetify(flowOf(items1 + deactivate)),
    ) { arr ->
        arr
            .flatMap {
                it
            }
    }
        .flatMapLatest { populatorFlows ->
            val typePopulator =
                typeFlow
                    .map { type ->
                        val f = fun(r: CreateSendRequest): CreateSendRequest {
                            return r.copy(type = type)
                        }
                        f
                    }
            val ownershipPopulator =
                ownershipFlow
                    .map { ownership ->
                        val f = fun(r: CreateSendRequest): CreateSendRequest {
                            val requestOwnership = CreateSendRequest.Ownership(
                                accountId = ownership.data.accountId,
                            )
                            return r.copy(ownership = requestOwnership)
                        }
                        f
                    }
            (populatorFlows.map { it.second } + ownershipPopulator + typePopulator)
                .combineToList()
        }
        .map { populators ->
            populators.fold(
                initial = CreateSendRequest(
                    now = Clock.System.now(),
                ),
            ) { y, x -> x(y) }
        }
        .distinctUntilChanged()

    combine(
        actionsFlow,
        ownershipFlow,
        outputFlow,
        itfff,
    ) { actions, ownership, output, ddd ->
        val state = SendAddState(
            title = title,
            ownership = ownership,
            actions = actions,
            items = items1 + ddd,
            onSave = {
                val request = output
                val sendIdToRequestMap = mapOf(
                    args.initialValue?.id?.takeIf { args.ownershipRo } to request,
                )
                addSend(sendIdToRequestMap)
                    .effectTap {
                        val intent = kotlin.run {
                            val list = mutableListOf<NavigationIntent>()
                            list += NavigationIntent.PopById(screenId, exclusive = false)
                            if (args.behavior.launchEditedCipher) {
                                val sendId = it.first()
                                val accountId = ownership.data.accountId!!
                                val route = SendViewRoute(
                                    sendId = sendId,
                                    accountId = accountId,
                                )
                                list += NavigationIntent.NavigateToRoute(route)
                            }
                            NavigationIntent.Composite(
                                list = list,
                            )
                        }
                        navigate(intent)
                    }
                    .launchIn(appScope)
            },
        )
        Loadable.Ok(state)
    }
}

private suspend fun RememberStateFlowScope.produceOwnershipFlow(
    args: SendAddRoute.Args,
    getProfiles: GetProfiles,
    getSends: GetSends,
    getCiphers: GetCiphers,
): Flow<SendAddState.Ownership> {
    val readOnly = args.ownershipRo
    val requiresPremium = DSend.requiresPremium(args.type)

    val ownershipHandle = ownershipHandle(
        key = "new_send",
        profilesFlow = getProfiles()
            .run {
                // If premium, then filter the options to
                // only include the accounts with active premium.
                if (requiresPremium) {
                    this
                        .map { profiles ->
                            profiles
                                .filter { it.premium }
                        }
                } else this
            },
        ciphersFlow = getCiphers(),
        initialValue = args.initialValue
            ?.let { value ->
                SendAddOwnershipData(
                    accountId = value.accountId,
                )
            },
        factory = { accountId ->
            SendAddOwnershipData(
                accountId = accountId,
            )
        },
    )

    val accountFlow = ownershipHandle.accountFlow(readOnly = readOnly)
    return combine(
        accountFlow,
    ) { (account) ->
        val flagsPremium =
            OrganizationConfirmationRoute.Args.PREMIUM_ACCOUNT.takeIf { requiresPremium } ?: 0
        val flags = if (readOnly) {
            OrganizationConfirmationRoute.Args.RO_ACCOUNT
        } else {
            0
        } or
                OrganizationConfirmationRoute.Args.HIDE_ORGANIZATION or
                OrganizationConfirmationRoute.Args.HIDE_COLLECTION or
                OrganizationConfirmationRoute.Args.HIDE_FOLDER or
                flagsPremium
        val data = SendAddState.Ownership.Data(
            accountId = account.value,
        )
        val ui = AddStateOwnership(
            account = account.element,
            onClick = if (!readOnly) {
                // lambda
                {
                    val route = registerRouteResultReceiver(
                        route = OrganizationConfirmationRoute(
                            args = OrganizationConfirmationRoute.Args(
                                decor = OrganizationConfirmationRoute.Args.Decor(
                                    title = translate(Res.strings.save_to),
                                    icon = Icons.Outlined.AccountBox,
                                    note = if (requiresPremium) {
                                        SimpleNote(
                                            text = translate(Res.strings.bitwarden_premium_required),
                                            type = SimpleNote.Type.INFO,
                                        )
                                    } else {
                                        null
                                    },
                                ),
                                flags = flags,
                                accountId = account.value,
                            ),
                        ),
                    ) { result ->
                        if (result is OrganizationConfirmationResult.Confirm) {
                            val newState = SendAddOwnershipData(
                                accountId = result.accountId,
                            )
                            ownershipHandle.stateSink.value = newState
                        }
                    }
                    val intent = NavigationIntent.NavigateToRoute(route)
                    navigate(intent)
                }
            } else {
                null
            },
        )
        SendAddState.Ownership(
            data = data,
            ui = ui,
        )
    }
}

private suspend fun RememberStateFlowScope.produceTextState(
    args: SendAddRoute.Args,
): TmpText {
    val prefix = "text"

    suspend fun createItem(
        key: String,
        label: String? = null,
        initialValue: String? = null,
        singleLine: Boolean = false,
        autocompleteOptions: ImmutableList<String> = persistentListOf(),
        keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
        lens: Optional<CreateSendRequest, String>,
    ) = createItem<CreateSendRequest>(
        prefix = prefix,
        key = key,
        label = label,
        initialValue = initialValue,
        singleLine = singleLine,
        autocompleteOptions = autocompleteOptions,
        keyboardOptions = keyboardOptions,
        populator = {
            lens.set(this, it.value.text)
        },
    )

    val txt = args.initialValue?.text
    val text = createItem(
        key = "text",
        label = translate(Res.strings.text),
        initialValue = args.text ?: txt?.text,
        lens = CreateSendRequest.text.text,
    )
    val hidden = kotlin.run {
        val key = "$prefix.hidden_by_default"
        val initialValue = args.initialValue?.text?.hidden == true
        AddStateItem.Switch.produceItemFlow<CreateSendRequest>(
            key = key,
            initialValue = initialValue,
            populator = { state ->
                CreateSendRequest.text.hidden.set(this, state.checked)
            },
            factory = { id, state ->
                AddStateItem.Switch(
                    id = id,
                    title = translate(Res.strings.addsend_text_hide_by_default_title),
                    state = state,
                )
            },
        )
    }
    return TmpText(
        text = text,
        hidden = hidden,
        items = listOfNotNull<AddStateItem>(
            text,
            hidden,
        ),
    )
}

private suspend fun RememberStateFlowScope.produceOptionsState(
    args: SendAddRoute.Args,
    markdown: Boolean,
    dateFormatter: DateFormatter,
): TmpOptions {
    val prefix = "options"

    val opts = with(Duration) {
        listOf<Duration>(
            1.hours,
            1.days,
            2.days,
            3.days,
            7.days,
            30.days,
        )
    }

    val maxAccessCountItem = kotlin.run {
        val id = "$prefix.max_access_count"
        val state = LocalStateItem<AddStateItem.Text.State, CreateSendRequest>(
            flow = kotlin.run {
                val sink = mutablePersistedFlow(id) {
                    ""
                }
                val state = asComposeState<String>(id)
                sink
                    .validatedInteger(this)
                    .map { validatedNumber ->
                        val textField = TextFieldModel2.of(
                            state = state,
                            validated = validatedNumber,
                            onChange = state::value::set,
                        )
                        AddStateItem.Text.State(
                            label = translate(Res.strings.max_access_count),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                            ),
                            value = textField,
                        )
                    }
                    .persistingStateIn(
                        scope = screenScope,
                        started = SharingStarted.WhileSubscribed(1000L),
                        initialValue = AddStateItem.Text.State(TextFieldModel2.empty),
                    )
            },
            populator = { state ->
                CreateSendRequest.maxAccessCount.set(this, state.value.text)
            },
        )
        AddStateItem.Text(
            id = id,
            leading = icon<RowScope>(Icons.Outlined.Visibility),
            note = translate(Res.strings.addsend_max_access_count_note),
            state = state,
        )
    }
    val passwordItem = kotlin.run {
        val id = "$prefix.password"
        val label = kotlin.run {
            val hasPassword = args.initialValue?.hasPassword == true
            if (hasPassword) {
                translate(Res.strings.new_password)
            } else null
        }
        val state = LocalStateItem<TextFieldModel2, CreateSendRequest>(
            flow = kotlin.run {
                val sink = mutablePersistedFlow(id) {
                    ""
                }
                val state = asComposeState<String>(id)
                sink
                    .validatedTitle(this)
                    .map { validatedTitle ->
                        TextFieldModel2.of(
                            state = state,
                            validated = validatedTitle,
                            onChange = state::value::set,
                        )
                    }
                    .persistingStateIn(
                        scope = screenScope,
                        started = SharingStarted.WhileSubscribed(1000L),
                        initialValue = TextFieldModel2.empty,
                    )
            },
            populator = { state ->
                CreateSendRequest.password.set(this, state.text)
            },
        )
        AddStateItem.Password(
            id = id,
            label = label,
            state = state,
        )
    }
    val deletionDateAsDurationItem = kotlin.run {
        val id = "$prefix.deletion_date_as_duration"

        val sink = mutablePersistedFlow<Duration?>(id) {
            val defaultValue = with(Duration) { 7.days }
            defaultValue
                .takeIf { args.initialValue?.deletedDate == null }
        }
        val dropdown = buildContextItems {
            section {
                opts.forEach { duration ->
                    this += FlatItemAction(
                        title = duration.format(context),
                        onClick = sink::value::set.partially1(duration),
                    )
                }
            }
            section {
                this += FlatItemAction(
                    title = translate(Res.strings.deletion_date_custom),
                    onClick = sink::value::set.partially1(null),
                )
            }
        }
        val state = LocalStateItem<AddStateItem.Enum.State, CreateSendRequest>(
            flow = sink
                .map { duration ->
                    val value = duration?.format(context)
                        ?: translate(Res.strings.deletion_date_custom)
                    AddStateItem.Enum.State(
                        data = duration,
                        value = value,
                        dropdown = dropdown,
                    )
                }
                .stateIn(screenScope),
            populator = { state ->
                val duration = state.data as Duration?
                CreateSendRequest.nullableDeletionDateAsDuration.set(this, duration)
            },
        )
        AddStateItem.Enum(
            id = id,
            leading = icon<RowScope>(Icons.Outlined.AutoDelete),
            label = translate(Res.strings.deletion_date),
            state = state,
        )
    }
    val deletionDateItem = kotlin.run {
        val id = "$prefix.deletion_date"
        val now = Clock.System.now()
            .toLocalDateTime(TimeZone.currentSystemDefault())

        val deletionDateUpperLimitDays = 31
        val deletionDateUpperLimit = LocalDateTime(
            date = now.date.plus(deletionDateUpperLimitDays, DateTimeUnit.DAY),
            time = now.time,
        )
        val deletionDateWarning by lazy {
            TextFieldModel2.Vl(
                type = TextFieldModel2.Vl.Type.ERROR,
                text = translate(
                    res = Res.strings.error_must_be_less_than_days,
                    deletionDateUpperLimitDays,
                ),
            )
        }

        AddStateItem.DateTime.produceItemFlow<CreateSendRequest>(
            key = id,
            initialValue = args.initialValue?.deletedDate
                ?.toLocalDateTime(TimeZone.currentSystemDefault()),
            selectableDates = now.date..deletionDateUpperLimit.date,
            dateFormatter = dateFormatter,
            badge = { dateTime ->
                if (dateTime > deletionDateUpperLimit) {
                    return@produceItemFlow deletionDateWarning
                }

                null
            },
            populator = { state ->
                CreateSendRequest.deletionDate.set(this, state.value)
            },
            factory = { id, state ->
                AddStateItem.DateTime(
                    id = id,
                    state = state,
                )
            },
        )
    }
    val expirationDateAsDurationItem = kotlin.run {
        val id = "$prefix.expiration_date_as_duration"

        val sink = mutablePersistedFlow<Duration?>(id) {
            val defaultValue = with(Duration) { INFINITE }
            defaultValue
                .takeIf { args.initialValue?.expirationDate == null }
        }
        val dropdown = buildContextItems {
            section {
                opts.forEach { duration ->
                    this += FlatItemAction(
                        title = duration.format(context),
                        onClick = sink::value::set.partially1(duration),
                    )
                }
                val duration = with(Duration) { INFINITE }
                this += FlatItemAction(
                    title = duration.format(context),
                    onClick = sink::value::set.partially1(duration),
                )
            }
            section {
                this += FlatItemAction(
                    title = translate(Res.strings.expiration_date_custom),
                    onClick = sink::value::set.partially1(null),
                )
            }
        }
        val state = LocalStateItem<AddStateItem.Enum.State, CreateSendRequest>(
            flow = sink
                .map { duration ->
                    val value = duration?.format(context)
                        ?: translate(Res.strings.expiration_date_custom)
                    AddStateItem.Enum.State(
                        data = duration,
                        value = value,
                        dropdown = dropdown,
                    )
                }
                .stateIn(screenScope),
            populator = { state ->
                val duration = state.data as Duration?
                CreateSendRequest.nullableExpirationDateAsDuration.set(this, duration)
            },
        )
        AddStateItem.Enum(
            id = id,
            leading = icon<RowScope>(Icons.Outlined.AccessTime),
            label = translate(Res.strings.expiration_date),
            state = state,
        )
    }
    val expirationDateItem = kotlin.run {
        val id = "$prefix.expiration_date"
        AddStateItem.DateTime.produceItemFlow<CreateSendRequest>(
            key = id,
            initialValue = args.initialValue?.expirationDate
                ?.toLocalDateTime(TimeZone.currentSystemDefault()),
            selectableDates = null,
            dateFormatter = dateFormatter,
            populator = { state ->
                CreateSendRequest.expirationDate.set(this, state.value)
            },
            factory = { id, state ->
                AddStateItem.DateTime(
                    id = id,
                    state = state,
                )
            },
        )
    }
    val hideEmailItem = kotlin.run {
        val key = "$prefix.hide_email"
        val initialValue = args.initialValue?.hideEmail == true
        AddStateItem.Switch.produceItemFlow<CreateSendRequest>(
            key = key,
            initialValue = initialValue,
            populator = { state ->
                CreateSendRequest.hideEmail.set(this, state.checked)
            },
            factory = { id, state ->
                AddStateItem.Switch(
                    id = id,
                    title = translate(Res.strings.addsend_hide_email_title),
                    state = state,
                )
            },
        )
    }
    val noteItem = kotlin.run {
        val id = "$prefix.note"

        val initialValue = args.initialValue?.notes

        val sink = mutablePersistedFlow(id) { initialValue.orEmpty() }
        val state = mutableComposeState(sink)
        val stateItem = LocalStateItem<TextFieldModel2, CreateSendRequest>(
            flow = sink
                .map { value ->
                    val model = TextFieldModel2(
                        state = state,
                        text = value,
                        hint = translate(Res.strings.additem_note_placeholder),
                        onChange = state::value::set,
                    )
                    model
                }
                .persistingStateIn(
                    scope = screenScope,
                    started = SharingStarted.WhileSubscribed(),
                    initialValue = TextFieldModel2.empty,
                ),
            populator = { state ->
                CreateSendRequest.note.set(this, state.text)
            },
        )
        AddStateItem.Note(
            id = id,
            state = stateItem,
            markdown = markdown,
        )
    }

    val itemsFlow = combine(
        deletionDateAsDurationItem
            .state.flow
            .map { state -> state.data != null }
            .distinctUntilChanged(),
        expirationDateAsDurationItem
            .state.flow
            .map { state -> state.data != null }
            .distinctUntilChanged(),
    ) { hasDeletionDateDuration, hasExpirationDateDuration ->
        listOfNotNull<AddStateItem>(
            AddStateItem.Section(
                id = "${prefix}.section.1",
            ),
            maxAccessCountItem,
            AddStateItem.Section(
                id = "${prefix}.section.2",
            ),
            passwordItem,
            deletionDateAsDurationItem,
            deletionDateItem.takeIf { !hasDeletionDateDuration },
            expirationDateAsDurationItem,
            expirationDateItem.takeIf { !hasExpirationDateDuration },
            AddStateItem.Section(
                id = "${prefix}.section.3",
            ),
            hideEmailItem,
            AddStateItem.Section(
                id = "${prefix}.section.4",
            ),
            noteItem,
        )
    }

    return TmpOptions(
        deletionDate = deletionDateItem,
        expirationDate = expirationDateItem,
        password = passwordItem,
        items = itemsFlow,
    )
}
