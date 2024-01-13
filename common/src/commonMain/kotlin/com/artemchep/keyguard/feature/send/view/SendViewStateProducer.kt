package com.artemchep.keyguard.feature.send.view

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.android.downloader.journal.room.DownloadInfoEntity2
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.model.BarcodeImageFormat
import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.common.model.LinkInfo
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.download.DownloadManager
import com.artemchep.keyguard.common.service.extract.LinkInfoExtractor
import com.artemchep.keyguard.common.service.twofa.TwoFaService
import com.artemchep.keyguard.common.usecase.AddCipherOpenedHistory
import com.artemchep.keyguard.common.usecase.ChangeCipherNameById
import com.artemchep.keyguard.common.usecase.ChangeCipherPasswordById
import com.artemchep.keyguard.common.usecase.CipherExpiringCheck
import com.artemchep.keyguard.common.usecase.CipherFieldSwitchToggle
import com.artemchep.keyguard.common.usecase.CipherIncompleteCheck
import com.artemchep.keyguard.common.usecase.CipherUnsecureUrlAutoFix
import com.artemchep.keyguard.common.usecase.CipherUnsecureUrlCheck
import com.artemchep.keyguard.common.usecase.CipherUrlCheck
import com.artemchep.keyguard.common.usecase.CopyCipherById
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.DownloadAttachment
import com.artemchep.keyguard.common.usecase.FavouriteCipherById
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
import com.artemchep.keyguard.common.usecase.MoveCipherToFolderById
import com.artemchep.keyguard.common.usecase.RemoveAttachment
import com.artemchep.keyguard.common.usecase.RemoveCipherById
import com.artemchep.keyguard.common.usecase.RestoreCipherById
import com.artemchep.keyguard.common.usecase.RetryCipher
import com.artemchep.keyguard.common.usecase.TrashCipherById
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.attachments.util.createAttachmentItem
import com.artemchep.keyguard.feature.barcodetype.BarcodeTypeRoute
import com.artemchep.keyguard.feature.favicon.FaviconImage
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.largetype.LargeTypeRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.copy
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.send.action.createSendActionOrNull
import com.artemchep.keyguard.feature.send.action.createShareAction
import com.artemchep.keyguard.feature.send.toVaultItemIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.icons.KeyguardView
import com.artemchep.keyguard.ui.selection.SelectionHandle
import com.artemchep.keyguard.ui.selection.selectionHandle
import com.artemchep.keyguard.ui.text.annotate
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
        cipherUnsecureUrlCheck = instance(),
        cipherUnsecureUrlAutoFix = instance(),
        cipherFieldSwitchToggle = instance(),
        moveCipherToFolderById = instance(),
        changeCipherNameById = instance(),
        changeCipherPasswordById = instance(),
        retryCipher = instance(),
        copyCipherById = instance(),
        restoreCipherById = instance(),
        trashCipherById = instance(),
        removeCipherById = instance(),
        favouriteCipherById = instance(),
        downloadManager = instance(),
        downloadAttachment = instance(),
        removeAttachment = instance(),
        cipherExpiringCheck = instance(),
        cipherIncompleteCheck = instance(),
        cipherUrlCheck = instance(),
        tfaService = instance(),
        clipboardService = instance(),
        getGravatarUrl = instance(),
        getEnvSendUrl = instance(),
        dateFormatter = instance(),
        addCipherOpenedHistory = instance(),
        windowCoroutineScope = instance(),
        linkInfoExtractors = allInstances(),
        contentColor = contentColor,
        disabledContentColor = disabledContentColor,
        sendId = sendId,
        accountId = accountId,
    )
}

private class Holder(
    val uri: DSecret.Uri,
    val info: List<LinkInfo>,
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
    cipherUnsecureUrlCheck: CipherUnsecureUrlCheck,
    cipherUnsecureUrlAutoFix: CipherUnsecureUrlAutoFix,
    cipherFieldSwitchToggle: CipherFieldSwitchToggle,
    moveCipherToFolderById: MoveCipherToFolderById,
    changeCipherNameById: ChangeCipherNameById,
    changeCipherPasswordById: ChangeCipherPasswordById,
    retryCipher: RetryCipher,
    copyCipherById: CopyCipherById,
    restoreCipherById: RestoreCipherById,
    trashCipherById: TrashCipherById,
    removeCipherById: RemoveCipherById,
    favouriteCipherById: FavouriteCipherById,
    downloadManager: DownloadManager,
    downloadAttachment: DownloadAttachment,
    removeAttachment: RemoveAttachment,
    cipherExpiringCheck: CipherExpiringCheck,
    cipherIncompleteCheck: CipherIncompleteCheck,
    cipherUrlCheck: CipherUrlCheck,
    tfaService: TwoFaService,
    clipboardService: ClipboardService,
    getGravatarUrl: GetGravatarUrl,
    getEnvSendUrl: GetEnvSendUrl,
    dateFormatter: DateFormatter,
    addCipherOpenedHistory: AddCipherOpenedHistory,
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

    val selectionHandle = selectionHandle("selection")
    val markdown = getMarkdown().first()

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
                SendViewState.Content.Cipher(
                    data = secretOrNull,
                    icon = icon,
                    synced = true,
                    actions = emptyList(),
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
                    items = oh(
                        sharingScope = screenScope,
                        selectionHandle = selectionHandle,
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
                    ).toList(),
                )
            }
        }
        SendViewState(
            content = content,
        )
    }
}

private fun RememberStateFlowScope.oh(
    sharingScope: CoroutineScope,
    selectionHandle: SelectionHandle,
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
            name = "Deactivated",
            message = "This share is deactivated, no one can access it.",
        )
        emit(model)
    }

    val text = send.text
    if (text != null) {
        val model = create(
            copy = copy,
            id = "text.text",
            title = translate(Res.strings.text),
            value = text.text.orEmpty(),
            elevated = true,
        )
        emit(model)
    }

    val file = send.file
    if (file != null) {
        val downloadIo = kotlin.run {
            ioRaise<Unit>(RuntimeException("Downloading sends is not implemented yet."))
        }
        val removeIo = kotlin.run {
            ioUnit()
        }

        val actualItem = createAttachmentItem(
            tag = DownloadInfoEntity2.AttachmentDownloadTag(
                localCipherId = "cipher.id",
                remoteCipherId = "cipher.service.remote?.id",
                attachmentId = file.id,
            ),
            selectionHandle = selectionHandle,
            sharingScope = sharingScope,
            attachment = file,
            launchViewCipherData = null,
            downloadManager = downloadManager,
            downloadIo = downloadIo,
            removeIo = removeIo,
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
            text = translate(Res.strings.public_url),
        )
        emit(section)
        emit(url)

        val password = send.password
        if (password != null) {
            val w = VaultViewItem.Label(
                id = "url.password",
                text = AnnotatedString(
                    text = translate(Res.strings.send_password_is_required_to_access_label),
                ),
            )
            emit(w)
        }
    }

    val section = VaultViewItem.Section(
        id = "iiinfo",
        text = translate(Res.strings.info),
    )
    emit(section)

    val accessCount = send.accessCount
    val w = VaultViewItem.Value(
        id = "info",
        title = translate(Res.strings.access_count),
        value = "$accessCount" + send.maxAccessCount?.let { " / $it" }.orEmpty(),
        leading = {
            IconBox(Icons.Outlined.KeyguardView)
        },
    )
    emit(w)

    val w22 = VaultViewItem.Value(
        id = "info.email",
        title = translate(Res.strings.email_visibility),
        value = if (send.hideEmail) {
            translate(Res.strings.hidden)
        } else {
            translate(Res.strings.visible)
        },
        leading = {
            IconBox(Icons.Outlined.Email)
        },
    )
    emit(w22)

    if (send.notes.isNotEmpty()) {
        val section = VaultViewItem.Section(
            id = "note",
            text = translate(Res.strings.notes),
        )
        emit(section)
        val note = VaultViewItem.Note(
            id = "note.text",
            text = if (markdown) send.notes.trimIndent() else send.notes,
            markdown = markdown,
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
        text = annotate(
            Res.strings.vault_view_saved_to_label,
            account.username to SpanStyle(
                color = contentColor,
            ),
            account.host to SpanStyle(
                color = contentColor,
            ),
        ),
    )
    emit(x)
    val createdDate = send.createdDate
    if (createdDate != null) {
        val w = VaultViewItem.Label(
            id = "created",
            text = AnnotatedString(
                translate(
                    Res.strings.vault_view_created_at_label,
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
                Res.strings.vault_view_revision_label,
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
                    Res.strings.vault_view_expiration_scheduled_at_label,
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
                    Res.strings.vault_view_deletion_scheduled_at_label,
                    dateFormatter.formatDateTime(deletedDate),
                ),
            ),
        )
        emit(b)
    }
}

fun RememberStateFlowScope.create(
    copy: CopyText,
    id: String,
    title: String?,
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
                    title = translate(Res.strings.copy),
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
        private = private,
        hidden = hidden,
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
                title = translate(Res.strings.copy_url),
                value = url,
            )
        }
        section {
            this += FlatItemAction(
                icon = Icons.Outlined.Launch,
                title = translate(Res.strings.uri_action_launch_browser_title),
                text = url,
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
            FaviconImage(
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
