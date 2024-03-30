package com.artemchep.keyguard.feature.send.add

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.input.KeyboardType
import arrow.core.flatten
import arrow.core.partially2
import arrow.optics.Optional
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.model.create.CreateSendRequest
import com.artemchep.keyguard.common.model.create.address1
import com.artemchep.keyguard.common.model.create.address2
import com.artemchep.keyguard.common.model.create.address3
import com.artemchep.keyguard.common.model.create.city
import com.artemchep.keyguard.common.model.create.company
import com.artemchep.keyguard.common.model.create.country
import com.artemchep.keyguard.common.model.create.email
import com.artemchep.keyguard.common.model.create.firstName
import com.artemchep.keyguard.common.model.create.identity
import com.artemchep.keyguard.common.model.create.lastName
import com.artemchep.keyguard.common.model.create.licenseNumber
import com.artemchep.keyguard.common.model.create.middleName
import com.artemchep.keyguard.common.model.create.note
import com.artemchep.keyguard.common.model.create.passportNumber
import com.artemchep.keyguard.common.model.create.phone
import com.artemchep.keyguard.common.model.create.postalCode
import com.artemchep.keyguard.common.model.create.ssn
import com.artemchep.keyguard.common.model.create.state
import com.artemchep.keyguard.common.model.create.text
import com.artemchep.keyguard.common.model.create.title
import com.artemchep.keyguard.common.model.create.username
import com.artemchep.keyguard.common.model.displayName
import com.artemchep.keyguard.common.model.titleH
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.AddSend
import com.artemchep.keyguard.common.usecase.CipherUnsecureUrlCheck
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
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.util.flow.combineToList
import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.feature.add.AddStateItem
import com.artemchep.keyguard.feature.add.AddStateOwnership
import com.artemchep.keyguard.feature.add.LocalStateItem
import com.artemchep.keyguard.feature.auth.common.SwitchFieldModel
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.auth.common.util.validatedTitle
import com.artemchep.keyguard.feature.confirmation.organization.OrganizationConfirmationResult
import com.artemchep.keyguard.feature.confirmation.organization.OrganizationConfirmationRoute
import com.artemchep.keyguard.feature.home.vault.add.AddRoute
import com.artemchep.keyguard.feature.home.vault.add.TmpIdentity
import com.artemchep.keyguard.feature.home.vault.add.createItem
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.copy
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.send.view.SendViewRoute
import com.artemchep.keyguard.platform.parcelize.LeParcelable
import com.artemchep.keyguard.platform.parcelize.LeParcelize
import com.artemchep.keyguard.res.Res
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@kotlinx.serialization.Serializable
@LeParcelize
data class SendAddOwnershipData(
    val accountId: String?,
) : LeParcelable

data class TmpText(
    val text: AddStateItem.Text<CreateSendRequest>,
    val items: List<AddStateItem>,
)

data class TmpOptions(
    val deletionDate: AddStateItem.DateTime<CreateSendRequest>,
    val expirationDate: AddStateItem.DateTime<CreateSendRequest>,
    val password: AddStateItem.Password<CreateSendRequest>,
    val items: List<AddStateItem>,
)

data class TmpNote(
    val note: AddStateItem.Note<CreateSendRequest>,
    val items: List<AddStateItem>,
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
        cipherUnsecureUrlCheck = instance(),
        showMessage = instance(),
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
    cipherUnsecureUrlCheck: CipherUnsecureUrlCheck,
    showMessage: ShowMessage,
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
    val copyText = copy(clipboardService)
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
    )

    val textHolder = produceTextState(
        args = args,
    )
    val noteHolder = produceNoteState(
        args = args,
        markdown = markdown,
    )

    val typeFlow = kotlin.run {
        val initialValue = args.type
            ?: args.initialValue?.type
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
    val miscItems by lazy {
        listOf(
            AddStateItem.Section(
                id = "misc",
            ),
            AddStateItem.Note(
                id = "misc.note",
                state = noteHolder.note.state,
                markdown = markdown,
            ),
        )
    }
    val miscFlow = typeFlow
        .map { _ ->
            val hasNotes = false
            hasNotes
        }
        .distinctUntilChanged()
        .map { hasNotes ->
            miscItems.takeUnless { hasNotes }.orEmpty()
        }

    val titleItem = AddStateItem.Title<CreateSendRequest>(
        id = "title",
        state = LocalStateItem(
            flow = kotlin.run {
                val key = "title"
                val sink = mutablePersistedFlow(key) {
                    args.initialValue?.name
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
        miscFlow,
    ) { arr ->
        arr.toList().flatten()
    }
        .onEach { l ->
            logRepository.post("Foo3", "combine ${l.size}")
        }

    val outputFlow = combine(
        stetify(itfff),
        stetify(flowOf(items1)),
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


    val items = listOf(
        AddStateItem.Title<CreateSendRequest>(
            id = "title",
            state = LocalStateItem(
                flow = MutableStateFlow(TextFieldModel2(mutableStateOf(""))),
            ),
        ),
        AddStateItem.Text<CreateSendRequest>(
            id = "text",
            state = LocalStateItem(
                flow = MutableStateFlow(
                    value = AddStateItem.Text.State(
                        value = TextFieldModel2.empty,
                        label = "Text",
                        singleLine = false,
                    ),
                ),
            ),
        ),
        AddStateItem.Switch<CreateSendRequest>(
            id = "text_switch",
            title = "Conceal text by default",
            state = LocalStateItem(
                flow = MutableStateFlow(
                    value = SwitchFieldModel(
                        checked = false,
                        onChange = {},
                    ),
                ),
            ),
        ),
    )
    combine(
        ownershipFlow,
        outputFlow,
        itfff,
    ) { ownership, output, ddd ->
        val state = SendAddState(
            title = title,
            ownership = ownership,
            items = items1 + ddd,
            onSave = {
                val request = CreateSendRequest(
                    ownership = CreateSendRequest.Ownership(
                        accountId = ownership.data.accountId,
                    ),
                    title = "title",
                    note = "note",
                    // types
                    type = DSend.Type.Text,
                    text = CreateSendRequest.Text(
                        text = "text",
                    ),
                    // other
                    now = Clock.System.now(),
                )
                val sendIdToRequestMap = mapOf(
                    args.initialValue?.id?.takeIf { args.ownershipRo } to request,
                )
                addSend(sendIdToRequestMap)
                    .effectTap {
                        val intent = kotlin.run {
                            val list = mutableListOf<NavigationIntent>()
                            list += NavigationIntent.PopById(screenId, exclusive = false)
                            if (true) { // TODO: args.behavior.launchEditedCipher
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
): Flow<SendAddState.Ownership> {
    val ro = args.ownershipRo

    data class Fool<T>(
        val value: T,
        val element: AddStateOwnership.Element?,
    )

    val disk = loadDiskHandle("new_send")
    val accountIdSink = mutablePersistedFlow<String?>(
        key = "ownership.account_id",
        storage = PersistedStorage.InDisk(disk),
    ) { null }

    val initialState = kotlin.run {
        if (args.initialValue != null) {
            return@run SendAddOwnershipData(
                accountId = args.initialValue.accountId,
            )
        }

        // Make an account that has the most ciphers a
        // default account.
        val accountId = ioEffect {
            val accountIds = getProfiles().toIO().bind()
                .map { it.accountId() }
                .toSet()

            fun String.takeIfAccountIdExists() = this
                .takeIf { id ->
                    id in accountIds
                }

            val lastAccountId = accountIdSink.value?.takeIfAccountIdExists()
            if (lastAccountId != null) {
                return@ioEffect lastAccountId
            }

            val ciphers = getSends().toIO().bind()
            ciphers
                .asSequence()
                .groupBy { it.accountId }
                // the one that has the most sends
                .maxByOrNull { entry -> entry.value.size }
                // account id
                ?.key
                ?.takeIfAccountIdExists()
        }.attempt().bind().getOrNull()

        SendAddOwnershipData(
            accountId = accountId,
        )
    }
    val sink = mutablePersistedFlow("ownership") {
        initialState
    }

    // If we are creating a new item, then remember the
    // last selected account to pre-select it next time.
    // TODO: Remember only when an item is created.
    sink
        .map { it.accountId }
        .onEach(accountIdSink::value::set)
        .launchIn(screenScope)

    val accountFlow = combine(
        sink
            .map { it.accountId }
            .distinctUntilChanged(),
        getProfiles(),
    ) { accountId, profiles ->
        if (accountId == null) {
            val item = AddStateOwnership.Element.Item(
                key = "account.empty",
                title = translate(Res.strings.account_none),
                stub = true,
            )
            val el = AddStateOwnership.Element(
                readOnly = ro,
                items = listOf(item),
            )
            return@combine Fool(
                value = null,
                element = el,
            )
        }
        val profileOrNull = profiles
            .firstOrNull { it.accountId() == accountId }
        val el = AddStateOwnership.Element(
            readOnly = ro,
            items = listOfNotNull(profileOrNull)
                .map { account ->
                    val key = "account.${account.accountId()}"
                    AddStateOwnership.Element.Item(
                        key = key,
                        title = account.displayName,
                        text = account.accountHost,
                        accentColors = account.accentColor,
                    )
                },
        )
        Fool(
            value = accountId,
            element = el,
        )
    }

    return combine(
        accountFlow,
    ) { (account) ->
        val flags = if (ro) {
            OrganizationConfirmationRoute.Args.RO_ACCOUNT
        } else {
            0
        } or
                OrganizationConfirmationRoute.Args.HIDE_ORGANIZATION or
                OrganizationConfirmationRoute.Args.HIDE_COLLECTION or
                OrganizationConfirmationRoute.Args.HIDE_FOLDER or
                OrganizationConfirmationRoute.Args.PREMIUM_ACCOUNT
        val data = SendAddState.Ownership.Data(
            accountId = account.value,
        )
        val ui = AddStateOwnership(
            account = account.element,
            onClick = {
                val route = registerRouteResultReceiver(
                    route = OrganizationConfirmationRoute(
                        args = OrganizationConfirmationRoute.Args(
                            decor = OrganizationConfirmationRoute.Args.Decor(
                                title = translate(Res.strings.save_to),
                                icon = Icons.Outlined.AccountBox,
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
                        sink.value = newState
                    }
                }
                val intent = NavigationIntent.NavigateToRoute(route)
                navigate(intent)
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
    val prefix = "identity"

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
        label = translate(Res.strings.identity_title),
        initialValue = txt?.text,
        singleLine = true,
        lens = CreateSendRequest.text.text,
    )
    return TmpText(
        text = text,
        items = listOfNotNull<AddStateItem>(
            text,
        ),
    )
}

private suspend fun RememberStateFlowScope.produceOptionsState(
    args: SendAddRoute.Args,
): TmpText {
    val prefix = "options"

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
        label = translate(Res.strings.identity_title),
        initialValue = txt?.text,
        singleLine = true,
        lens = CreateSendRequest.text.text,
    )
    return TmpText(
        text = text,
        items = listOfNotNull<AddStateItem>(
            text,
        ),
    )
}

private suspend fun RememberStateFlowScope.produceNoteState(
    args: SendAddRoute.Args,
    markdown: Boolean,
): TmpNote {
    val prefix = "notes"

    val note = kotlin.run {
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
                        hint = "Add any notes about this item here",
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
    return TmpNote(
        note = note,
        items = listOfNotNull<AddStateItem>(
            note,
        ),
    )
}
