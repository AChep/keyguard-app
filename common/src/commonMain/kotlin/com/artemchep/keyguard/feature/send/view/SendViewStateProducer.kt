package com.artemchep.keyguard.feature.send.view

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.outlined.Email
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.BarcodeImageFormat
import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.common.model.LinkInfo
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.download.DownloadManager
import com.artemchep.keyguard.common.service.extract.LinkInfoExtractor
import com.artemchep.keyguard.common.service.twofa.TwoFaService
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.DownloadAttachment
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetConcealFields
import com.artemchep.keyguard.common.usecase.GetEnvSendUrl
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetGravatarUrl
import com.artemchep.keyguard.common.usecase.GetMarkdown
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.common.usecase.GetSends
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.common.usecase.RetryCipher
import com.artemchep.keyguard.common.usecase.SendToolbox
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.attachments.util.createAttachmentItem
import com.artemchep.keyguard.feature.barcodetype.BarcodeTypeRoute
import com.artemchep.keyguard.ui.icons.FaviconIcon
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.home.vault.model.Visibility
import com.artemchep.keyguard.feature.home.vault.model.transformShapes
import com.artemchep.keyguard.feature.largetype.LargeTypeRoute
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.keyboard.KeyShortcut
import com.artemchep.keyguard.feature.navigation.keyboard.interceptKeyEvents
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.copy
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.send.action.createSendActionOrNull
import com.artemchep.keyguard.feature.send.action.createShareAction
import com.artemchep.keyguard.feature.send.add.SendAddRoute
import com.artemchep.keyguard.feature.send.toVaultItemIcon
import com.artemchep.keyguard.feature.send.util.SendUtil
import com.artemchep.keyguard.feature.send.util.SendUtil.deleteActionOrNull
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.autoclose.launchAutoPopSelfHandler
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.icons.KeyguardView
import com.artemchep.keyguard.ui.text.annotate
import com.halilibo.richtext.commonmark.CommonmarkAstNodeParser
import io.ktor.http.Url
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.kodein.di.allInstances
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun sendViewScreenState(
    contentColor: Color,
    disabledContentColor: Color,
    sendId: String,
    accountId: String,
) = with(localDI().direct) {
    sendViewScreenState(
        getAccounts = instance(),
        getCanWrite = instance(),
        getSends = instance(),
        getCollections = instance(),
        getOrganizations = instance(),
        getFolders = instance(),
        getConcealFields = instance(),
        getMarkdown = instance(),
        getAppIcons = instance(),
        getWebsiteIcons = instance(),
        getPasswordStrength = instance(),
        retryCipher = instance(),
        toolbox = instance(),
        downloadManager = instance(),
        downloadAttachment = instance(),
        tfaService = instance(),
        clipboardService = instance(),
        getGravatarUrl = instance(),
        getEnvSendUrl = instance(),
        dateFormatter = instance(),
        windowCoroutineScope = instance(),
        linkInfoExtractors = allInstances(),
        contentColor = contentColor,
        disabledContentColor = disabledContentColor,
        sendId = sendId,
        accountId = accountId,
    )
}

private class SendSauce(
    val send: DSend,
    val canEdit: Boolean,
    val canDelete: Boolean,
)

@Composable
fun sendViewScreenState(
    contentColor: Color,
    disabledContentColor: Color,
    getAccounts: GetAccounts,
    getCanWrite: GetCanWrite,
    getSends: GetSends,
    getCollections: GetCollections,
    getOrganizations: GetOrganizations,
    getFolders: GetFolders,
    getConcealFields: GetConcealFields,
    getMarkdown: GetMarkdown,
    getAppIcons: GetAppIcons,
    getWebsiteIcons: GetWebsiteIcons,
    getPasswordStrength: GetPasswordStrength,
    retryCipher: RetryCipher,
    toolbox: SendToolbox,
    downloadManager: DownloadManager,
    downloadAttachment: DownloadAttachment,
    tfaService: TwoFaService,
    clipboardService: ClipboardService,
    getGravatarUrl: GetGravatarUrl,
    getEnvSendUrl: GetEnvSendUrl,
    dateFormatter: DateFormatter,
    windowCoroutineScope: WindowCoroutineScope,
    linkInfoExtractors: List<LinkInfoExtractor<LinkInfo, LinkInfo>>,
    sendId: String,
    accountId: String,
) = produceScreenState(
    key = "send_view",
    initial = SendViewState(),
    args = arrayOf(
        getAccounts,
        getSends,
        getCollections,
        getOrganizations,
        getFolders,
        getConcealFields,
        getWebsiteIcons,
        getPasswordStrength,
        downloadAttachment,
        clipboardService,
        dateFormatter,
        windowCoroutineScope,
        linkInfoExtractors,
        sendId,
        accountId,
    ),
) {
    val copy = copy(
        clipboardService = clipboardService,
    )

    val markdown = getMarkdown().first()
    val markdownParser = CommonmarkAstNodeParser()

    val accountFlow = getAccounts()
        .map { accounts ->
            accounts
                .firstOrNull { it.id.id == accountId }
        }
        .distinctUntilChanged()
    val secretFlow = getSends()
        .map { secrets ->
            secrets
                .firstOrNull { it.id == sendId && it.accountId == accountId }
        }
        .distinctUntilChanged()
    launchAutoPopSelfHandler(secretFlow)

    val sendExtraFlow = combine(
        secretFlow,
        getCanWrite(),
    ) { send, hasWriteAccess ->
        send
            ?: return@combine null

        // Find sends that have some limitations
        val canEdit = hasWriteAccess
        val canDelete = hasWriteAccess

        SendSauce(
            send = send,
            canEdit = canEdit,
            canDelete = canDelete,
        )
    }

    fun pairUnlessEmpty(
        value: String?,
        type: CopyText.Type,
    ): Pair<String, CopyText.Type>? {
        if (value.isNullOrEmpty()) {
            return null
        }
        return value to type
    }

    interceptKeyEvents(
        // Ctrl+C: Copy the public URL
        KeyShortcut(
            key = Key.C,
            isCtrlPressed = true,
        ) to secretFlow
            .map { send ->
                send
                    ?: return@map null

                val url = getEnvSendUrl(send)
                    .attempt()
                    .bind()
                    .getOrNull()
                val primaryFieldPair =
                    pairUnlessEmpty(url, CopyText.Type.URL)
                if (primaryFieldPair == null) {
                    return@map null
                }

                // lambda
                {
                    val (value, type) = primaryFieldPair
                    copy.copy(value, false, type)
                }
            },
        // Ctrl+Shift+F: Open website
        KeyShortcut(
            key = Key.F,
            isCtrlPressed = true,
            isShiftPressed = true,
        ) to secretFlow
            .map { send ->
                send
                    ?: return@map null

                val shortcutIntent = getEnvSendUrl(send)
                    .attempt()
                    .bind()
                    .map { url ->
                        NavigationIntent.NavigateToBrowser(url)
                    }
                    .getOrNull()
                    ?: return@map null
                // lambda
                {
                    navigate(shortcutIntent)
                }
            },
        // Delete: Ask to move an item to trash
        KeyShortcut(
            key = Key.Delete,
        ) to sendExtraFlow
            .map { sendExtra ->
                sendExtra
                    ?: return@map null

                val action = flow<FlatItemAction> {
                    val deleteAction = deleteActionOrNull(
                        removeSendById = toolbox.removeSendById,
                        sends = listOf(sendExtra.send),
                        canDelete = sendExtra.canDelete,
                    )
                    if (deleteAction != null) {
                        emit(deleteAction)
                    }
                }.firstOrNull()
                    ?.onClick
                    ?: return@map null
                // lambda
                shortcut@{
                    action()
                }
            },
        // Ctrl+Delete: Ask to delete an item
        KeyShortcut(
            key = Key.Delete,
            isCtrlPressed = true,
        ) to sendExtraFlow
            .map { sendExtra ->
                sendExtra
                    ?: return@map null

                val action = flow<FlatItemAction> {
                    val deleteAction = deleteActionOrNull(
                        removeSendById = toolbox.removeSendById,
                        sends = listOf(sendExtra.send),
                        canDelete = sendExtra.canDelete,
                    )
                    if (deleteAction != null) {
                        emit(deleteAction)
                    }
                }.firstOrNull()
                    ?.onClick
                    ?: return@map null
                // lambda
                shortcut@{
                    action()
                }
            },
    )

    combine(
        accountFlow,
        secretFlow,
        getConcealFields(),
        getAppIcons(),
        getWebsiteIcons(),
        getCanWrite(),
    ) { array ->
        val accountOrNull = array[0] as DAccount?
        val secretOrNull = array[1] as DSend?
        val concealFields = array[2] as Boolean
        val appIcons = array[3] as Boolean
        val websiteIcons = array[4] as Boolean
        val canAddSecret = array[5] as Boolean

        val content = when {
            accountOrNull == null || secretOrNull == null -> SendViewState.Content.NotFound
            else -> {
                val icon = secretOrNull.toVaultItemIcon(
                    appIcons = appIcons,
                    websiteIcons = websiteIcons,
                )
                val url = getEnvSendUrl(secretOrNull)
                    .attempt()
                    .bind()
                    .getOrNull()
                val info = if (url != null) {
                    buildString {
                        append(secretOrNull.name)
                        append(": ")
                        append(url)
                    }
                } else {
                    null
                }

                val actions = SendUtil.actions(
                    toolbox = toolbox,
                    sends = listOf(secretOrNull),
                    canEdit = canAddSecret,
                )
                SendViewState.Content.Cipher(
                    data = secretOrNull,
                    icon = icon,
                    synced = true,
                    actions = actions,
                    onCopy = if (info != null) {
                        // lambda
                        {
                            copy.copy(info, hidden = false)
                        }
                    } else {
                        null
                    },
                    onShare = if (info != null) {
                        // lambda
                        {
                            val intent = NavigationIntent.NavigateToShare(info)
                            navigate(intent)
                        }
                    } else {
                        null
                    },
                    onEdit = {
                        val route = SendAddRoute(
                            args = SendAddRoute.Args(
                                behavior = SendAddRoute.Args.Behavior(
                                    // When you edit a send, you do not know what field
                                    // user is targeting, so it's better to not show the
                                    // keyboard automatically.
                                    autoShowKeyboard = false,
                                    launchEditedCipher = false,
                                ),
                                type = secretOrNull.type,
                                initialValue = secretOrNull,
                            ),
                        )
                        val intent = NavigationIntent.NavigateToRoute(route)
                        navigate(intent)
                    },
                    items = oh(
                        markdownParser = markdownParser,
                        canEdit = canAddSecret,
                        contentColor = contentColor,
                        disabledContentColor = disabledContentColor,
                        tfaService = tfaService,
                        retryCipher = retryCipher,
                        markdown = markdown,
                        concealFields = concealFields,
                        websiteIcons = websiteIcons,
                        downloadManager = downloadManager,
                        getGravatarUrl = getGravatarUrl,
                        getEnvSendUrl = getEnvSendUrl,
                        copy = copy,
                        dateFormatter = dateFormatter,
                        account = accountOrNull,
                        send = secretOrNull,
                    ).toList().transformShapes(),
                )
            }
        }
        SendViewState(
            content = content,
        )
    }
}

private fun RememberStateFlowScope.oh(
    markdownParser: CommonmarkAstNodeParser,
    canEdit: Boolean,
    contentColor: Color,
    disabledContentColor: Color,
    tfaService: TwoFaService,
    retryCipher: RetryCipher,
    markdown: Boolean,
    concealFields: Boolean,
    websiteIcons: Boolean,
    downloadManager: DownloadManager,
    getGravatarUrl: GetGravatarUrl,
    getEnvSendUrl: GetEnvSendUrl,
    copy: CopyText,
    dateFormatter: DateFormatter,
    account: DAccount,
    send: DSend,
) = flow<VaultViewItem> {
    if (send.disabled) {
        val model = VaultViewItem.Info(
            id = "disabled",
            name = translate(Res.string.deactivated),
            message = translate(Res.string.sends_action_disabled_note),
        )
        emit(model)
    }

    val text = send.text
    if (text != null) {
        val model = create(
            copy = copy,
            id = "text.text",
            title = translate(Res.string.text),
            send = send.name,
            value = text.text.orEmpty(),
            elevated = true,
        )
        emit(model)
    }

    val file = send.file
    if (file != null) {
        val actualItem = createAttachmentItem(
            attachment = file,
        )
        val model = VaultViewItem.Attachment(
            id = "file.file",
            item = actualItem,
        )
        emit(model)
    }

    val url = aaaa(
        disabledContentColor = disabledContentColor,
        websiteIcons = websiteIcons,
        getEnvSendUrl = getEnvSendUrl,
        id = "url.url",
        accountId = account.accountId(),
        send = send,
        copy = copy,
    )
    if (url != null) {
        val section = VaultViewItem.Section(
            id = "url",
            text = translate(Res.string.public_url),
        )
        emit(section)
        emit(url)

        if (send.hasPassword) {
            val w = VaultViewItem.Label(
                id = "url.password",
                text = AnnotatedString(
                    text = translate(Res.string.send_password_is_required_to_access_label),
                ),
            )
            emit(w)
        }
    }

    val section = VaultViewItem.Section(
        id = "iiinfo",
        text = translate(Res.string.info),
    )
    emit(section)

    val accessCount = send.accessCount
    val w = VaultViewItem.Value(
        id = "info",
        title = translate(Res.string.access_count),
        value = "$accessCount" + send.maxAccessCount?.let { " / $it" }.orEmpty(),
        leading = {
            IconBox(Icons.Outlined.KeyguardView)
        },
    )
    emit(w)

    val w22 = VaultViewItem.Value(
        id = "info.email",
        title = translate(Res.string.email_visibility),
        value = if (send.hideEmail) {
            translate(Res.string.hidden)
        } else {
            translate(Res.string.visible)
        },
        leading = {
            IconBox(Icons.Outlined.Email)
        },
    )
    emit(w22)

    if (send.notes.isNotEmpty()) {
        val section = VaultViewItem.Section(
            id = "note",
            text = translate(Res.string.notes),
        )
        emit(section)
        val content = VaultViewItem.Note.Content.of(
            parser = markdownParser,
            markdown = markdown,
            text = send.notes,
        )
        val note = VaultViewItem.Note(
            id = "note.text",
            content = content,
        )
        emit(note)
    }

    val s = VaultViewItem.Spacer(
        id = "end.spacer",
        height = 24.dp,
    )
    emit(s)
    val x = VaultViewItem.Label(
        id = "account",
        text = if (account.username != null) {
            annotate(
                Res.string.vault_view_saved_to_label,
                account.username to SpanStyle(
                    color = contentColor,
                ),
                account.host to SpanStyle(
                    color = contentColor,
                ),
            )
        } else {
            annotate(
                Res.string.vault_view_saved_to_account_name_label,
                account.host to SpanStyle(
                    color = contentColor,
                ),
            )
        },
    )
    emit(x)
    val createdDate = send.createdDate
    if (createdDate != null) {
        val w = VaultViewItem.Label(
            id = "created",
            text = AnnotatedString(
                translate(
                    Res.string.vault_view_created_at_label,
                    dateFormatter.formatDateTime(createdDate),
                ),
            ),
        )
        emit(w)
    }

    val a = VaultViewItem.Label(
        id = "revision",
        text = AnnotatedString(
            translate(
                Res.string.vault_view_revision_label,
                dateFormatter.formatDateTime(send.revisionDate),
            ),
        ),
    )
    emit(a)
    val expirationDate = send.expirationDate
    if (expirationDate != null) {
        val b = VaultViewItem.Label(
            id = "expiration",
            text = AnnotatedString(
                translate(
                    Res.string.vault_view_expiration_scheduled_at_label,
                    dateFormatter.formatDateTime(expirationDate),
                ),
            ),
        )
        emit(b)
    }
    val deletedDate = send.deletedDate
    if (deletedDate != null) {
        val b = VaultViewItem.Label(
            id = "deleted",
            text = AnnotatedString(
                translate(
                    Res.string.vault_view_deletion_scheduled_at_label,
                    dateFormatter.formatDateTime(deletedDate),
                ),
            ),
        )
        emit(b)
    }
}

suspend fun RememberStateFlowScope.create(
    copy: CopyText,
    id: String,
    title: String?,
    send: String,
    value: String,
    badge: VaultViewItem.Value.Badge? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    private: Boolean = false,
    hidden: Boolean = false,
    monospace: Boolean = false,
    colorize: Boolean = false,
    elevated: Boolean = false,
): VaultViewItem {
    val dropdown = if (!hidden) {
        buildContextItems {
            section {
                this += copy.FlatItemAction(
                    title = Res.string.copy.wrap(),
                    value = value,
                    hidden = private,
                )
            }
            section {
                this += LargeTypeRoute.showInLargeTypeActionOrNull(
                    translator = this@create,
                    text = value,
                    colorize = colorize,
                    navigate = ::navigate,
                )
                this += LargeTypeRoute.showInLargeTypeActionAndLockOrNull(
                    translator = this@create,
                    text = value,
                    colorize = colorize,
                    navigate = ::navigate,
                )
                this += BarcodeTypeRoute.showInBarcodeTypeActionOrNull(
                    translator = this@create,
                    data = value,
                    format = BarcodeImageFormat.QR_CODE,
                    navigate = ::navigate,
                )
                this += createShareAction(
                    translator = this@create,
                    text = value,
                    navigate = ::navigate,
                )
                this += createSendActionOrNull(
                    translator = this@create,
                    name = send,
                    text = value,
                    navigate = ::navigate,
                )
            }
        }
    } else {
        emptyList()
    }
    return VaultViewItem.Value(
        id = id,
        elevation = if (elevated) 1.dp else 0.dp,
        title = title,
        value = value,
        visibility = Visibility(
            concealed = private,
            hidden = hidden,
        ),
        monospace = monospace,
        colorize = colorize,
        leading = leading,
        badge = badge,
        dropdown = dropdown,
    )
}

private suspend fun RememberStateFlowScope.aaaa(
    disabledContentColor: Color,
    websiteIcons: Boolean,
    getEnvSendUrl: GetEnvSendUrl,
    id: String,
    accountId: String,
    send: DSend,
    copy: CopyText,
): VaultViewItem.Uri? {
    val url = getEnvSendUrl(send)
        .attempt()
        .bind()
        .getOrNull()
        ?: return null
    val dropdown = buildContextItems {
        section {
            this += copy.FlatItemAction(
                title = Res.string.copy_url.wrap(),
                value = url,
            )
        }
        section {
            this += FlatItemAction(
                icon = Icons.AutoMirrored.Outlined.Launch,
                title = Res.string.uri_action_launch_browser_title.wrap(),
                text = TextHolder.Value(url),
                trailing = {
                    ChevronIcon()
                },
                onClick = {
                    val intent = NavigationIntent.NavigateToBrowser(url)
                    navigate(intent)
                },
            )
        }
        section {
            this += BarcodeTypeRoute.showInBarcodeTypeActionOrNull(
                translator = this@aaaa,
                data = url,
                single = true,
                navigate = ::navigate,
            )
            this += createShareAction(
                translator = this@aaaa,
                text = url,
                navigate = ::navigate,
            )
        }
    }
    val faviconUrl = FaviconUrl(
        serverId = accountId,
        url = url,
    ).takeIf { websiteIcons }
    return VaultViewItem.Uri(
        id = id,
        icon = {
            FaviconIcon(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape),
                imageModel = { faviconUrl },
            )
        },
        title = buildAnnotatedString {
            append(url)

            val host = Url(url).host
            val hostIndex = url.indexOf(host)
            if (hostIndex != -1) {
                addStyle(
                    style = SpanStyle(
                        color = disabledContentColor,
                    ),
                    start = 0,
                    end = hostIndex,
                )
                addStyle(
                    style = SpanStyle(
                        color = disabledContentColor,
                    ),
                    start = hostIndex + host.length,
                    end = url.length,
                )
            }
        },
        dropdown = dropdown,
    )
}
