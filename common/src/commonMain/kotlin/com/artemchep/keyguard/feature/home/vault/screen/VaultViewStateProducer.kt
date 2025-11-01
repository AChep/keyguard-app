package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Textsms
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import arrow.core.Either
import arrow.core.getOrElse
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.android.downloader.journal.room.DownloadInfoEntity2
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.shared
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.AddCipherOpenedHistoryRequest
import com.artemchep.keyguard.common.model.BarcodeImageFormat
import com.artemchep.keyguard.common.model.CheckPasswordLeakRequest
import com.artemchep.keyguard.common.model.CipherFieldSwitchToggleRequest
import com.artemchep.keyguard.common.model.CipherId
import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DFolderTree
import com.artemchep.keyguard.common.model.DGlobalUrlOverride
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DWatchtowerAlertType
import com.artemchep.keyguard.common.model.DownloadAttachmentRequest
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilder
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.LinkInfo
import com.artemchep.keyguard.common.model.LinkInfoAndroid
import com.artemchep.keyguard.common.model.LinkInfoExecute
import com.artemchep.keyguard.common.model.LinkInfoLaunch
import com.artemchep.keyguard.common.model.LinkInfoPlatform
import com.artemchep.keyguard.common.model.RemoveAttachmentRequest
import com.artemchep.keyguard.common.model.UsernameVariation
import com.artemchep.keyguard.common.model.UsernameVariation2
import com.artemchep.keyguard.common.model.UsernameVariationIcon
import com.artemchep.keyguard.common.model.alertScore
import com.artemchep.keyguard.common.model.canDelete
import com.artemchep.keyguard.common.model.canEdit
import com.artemchep.keyguard.common.model.firstOrNull
import com.artemchep.keyguard.common.model.formatH
import com.artemchep.keyguard.common.model.ignores
import com.artemchep.keyguard.common.model.titleH
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.crypto.KeyPairGenerator
import com.artemchep.keyguard.common.usecase.KeyPrivateExport
import com.artemchep.keyguard.common.usecase.KeyPublicExport
import com.artemchep.keyguard.common.service.download.DownloadManager
import com.artemchep.keyguard.common.service.execute.ExecuteCommand
import com.artemchep.keyguard.common.service.extract.LinkInfoExtractor
import com.artemchep.keyguard.common.service.extract.LinkInfoRegistry
import com.artemchep.keyguard.common.service.placeholder.Placeholder
import com.artemchep.keyguard.common.service.placeholder.PlaceholderScope
import com.artemchep.keyguard.common.service.placeholder.create
import com.artemchep.keyguard.common.service.placeholder.placeholderFormat
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.usecase.AddCipherOpenedHistory
import com.artemchep.keyguard.common.usecase.ChangeCipherNameById
import com.artemchep.keyguard.common.usecase.ChangeCipherPasswordById
import com.artemchep.keyguard.common.usecase.CheckPasswordLeak
import com.artemchep.keyguard.common.usecase.CipherExpiringCheck
import com.artemchep.keyguard.common.usecase.CipherFieldSwitchToggle
import com.artemchep.keyguard.common.usecase.CipherIncompleteCheck
import com.artemchep.keyguard.common.usecase.CipherUnsecureUrlAutoFix
import com.artemchep.keyguard.common.usecase.CipherUnsecureUrlCheck
import com.artemchep.keyguard.common.usecase.CopyCipherById
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.DownloadAttachment
import com.artemchep.keyguard.common.usecase.FavouriteCipherById
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetConcealFields
import com.artemchep.keyguard.common.usecase.GetFolderTreeById
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetGravatarUrl
import com.artemchep.keyguard.common.usecase.GetJustDeleteMeByUrl
import com.artemchep.keyguard.common.usecase.GetJustGetMyDataByUrl
import com.artemchep.keyguard.common.usecase.GetMarkdown
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetPasskeys
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.common.usecase.GetTwoFa
import com.artemchep.keyguard.common.usecase.GetUrlOverrides
import com.artemchep.keyguard.common.usecase.GetWatchtowerUnreadAlerts
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.common.usecase.MarkWatchtowerAlertAsRead
import com.artemchep.keyguard.common.usecase.MoveCipherToFolderById
import com.artemchep.keyguard.common.usecase.PasskeyTargetCheck
import com.artemchep.keyguard.common.usecase.PatchWatchtowerAlertCipher
import com.artemchep.keyguard.common.usecase.RePromptCipherById
import com.artemchep.keyguard.common.usecase.RemoveAttachment
import com.artemchep.keyguard.common.usecase.RemoveCipherById
import com.artemchep.keyguard.common.usecase.RestoreCipherById
import com.artemchep.keyguard.common.usecase.RetryCipher
import com.artemchep.keyguard.common.usecase.TrashCipherById
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.usecase.impl.WatchtowerInactivePasskey
import com.artemchep.keyguard.common.usecase.impl.WatchtowerInactiveTfa
import com.artemchep.keyguard.common.util.StringComparatorIgnoreCase
import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.core.store.bitwarden.canRetry
import com.artemchep.keyguard.core.store.bitwarden.expired
import com.artemchep.keyguard.core.store.bitwarden.message
import com.artemchep.keyguard.feature.attachments.util.createAttachmentItem
import com.artemchep.keyguard.feature.auth.common.util.REGEX_EMAIL
import com.artemchep.keyguard.feature.barcodetype.BarcodeTypeRoute
import com.artemchep.keyguard.feature.confirmation.elevatedaccess.createElevatedAccessDialogIntent
import com.artemchep.keyguard.feature.crashlytics.crashlyticsTap
import com.artemchep.keyguard.feature.emailleak.EmailLeakRoute
import com.artemchep.keyguard.ui.icons.FaviconIcon
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.artemchep.keyguard.feature.generator.sshkey.SshKeyActions
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.add.AddRoute
import com.artemchep.keyguard.feature.home.vault.add.LeAddRoute
import com.artemchep.keyguard.feature.home.vault.collections.CollectionsRoute
import com.artemchep.keyguard.feature.home.vault.component.formatCardNumber
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.home.vault.model.Visibility
import com.artemchep.keyguard.feature.home.vault.model.transformShapes
import com.artemchep.keyguard.feature.home.vault.search.sort.PasswordSort
import com.artemchep.keyguard.feature.home.vault.util.cipherChangeNameAction
import com.artemchep.keyguard.feature.home.vault.util.cipherChangePasswordAction
import com.artemchep.keyguard.feature.home.vault.util.cipherCopyToAction
import com.artemchep.keyguard.feature.home.vault.util.cipherDeleteAction
import com.artemchep.keyguard.feature.home.vault.util.cipherDisableConfirmAccessAction
import com.artemchep.keyguard.feature.home.vault.util.cipherEnableConfirmAccessAction
import com.artemchep.keyguard.feature.home.vault.util.cipherExportAction
import com.artemchep.keyguard.feature.home.vault.util.cipherMoveToFolderAction
import com.artemchep.keyguard.feature.home.vault.util.cipherRestoreAction
import com.artemchep.keyguard.feature.home.vault.util.cipherTrashAction
import com.artemchep.keyguard.feature.home.vault.util.cipherViewPasswordHistoryAction
import com.artemchep.keyguard.feature.home.vault.util.cipherWatchtowerAlerts
import com.artemchep.keyguard.feature.justdeleteme.directory.JustDeleteMeServiceViewDialogRoute
import com.artemchep.keyguard.feature.justgetdata.directory.JustGetMyDataViewDialogRoute
import com.artemchep.keyguard.feature.largetype.LargeTypeRoute
import com.artemchep.keyguard.feature.loading.getErrorReadableMessage
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.keyboard.KeyShortcut
import com.artemchep.keyguard.feature.navigation.keyboard.interceptKeyEvents
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.copy
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.passkeys.PasskeysCredentialViewRoute
import com.artemchep.keyguard.feature.passkeys.directory.PasskeysServiceViewDialogRoute
import com.artemchep.keyguard.feature.passwordleak.PasswordLeakRoute
import com.artemchep.keyguard.feature.send.action.createSendActionOrNull
import com.artemchep.keyguard.feature.send.action.createShareAction
import com.artemchep.keyguard.feature.tfa.directory.TwoFaServiceViewDialogRoute
import com.artemchep.keyguard.feature.websiteleak.WebsiteLeakRoute
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.ContextItemBuilder
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.autoclose.launchAutoPopSelfHandler
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.colorizePassword
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.icons.IconBox2
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.icons.iconSmall
import com.artemchep.keyguard.ui.selection.SelectionHandle
import com.artemchep.keyguard.ui.selection.selectionHandle
import com.artemchep.keyguard.ui.text.annotate
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.totp.formatCode2
import com.halilibo.richtext.commonmark.CommonmarkAstNodeParser
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.allInstances
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance
import java.net.URI

typealias RevealConcealFlow = Flow<Unit>

@Composable
fun vaultViewScreenState(
    mode: AppMode,
    contentColor: Color,
    disabledContentColor: Color,
    itemId: String,
    accountId: String,
) = with(localDI().direct) {
    vaultViewScreenState(
        getAccounts = instance(),
        getCanWrite = instance(),
        getCiphers = instance(),
        getCollections = instance(),
        getOrganizations = instance(),
        getFolders = instance(),
        getFolderTreeById = instance(),
        getConcealFields = instance(),
        getMarkdown = instance(),
        getAppIcons = instance(),
        getWebsiteIcons = instance(),
        getPasskeys = instance(),
        getTwoFa = instance(),
        getTotpCode = instance(),
        getPasswordStrength = instance(),
        getUrlOverrides = instance(),
        passkeyTargetCheck = instance(),
        getWatchtowerUnreadAlerts = instance(),
        markWatchtowerAlertAsRead = instance(),
        keyPairGenerator = instance(),
        keyPrivateExport = instance(),
        keyPublicExport = instance(),
        cipherUnsecureUrlCheck = instance(),
        cipherUnsecureUrlAutoFix = instance(),
        cipherFieldSwitchToggle = instance(),
        moveCipherToFolderById = instance(),
        tldService = instance(),
        equivalentDomainsBuilderFactory = instance(),
        patchWatchtowerAlertCipher = instance(),
        rePromptCipherById = instance(),
        changeCipherNameById = instance(),
        changeCipherPasswordById = instance(),
        checkPasswordLeak = instance(),
        retryCipher = instance(),
        executeCommand = instance(),
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
        clipboardService = instance(),
        getGravatarUrl = instance(),
        dateFormatter = instance(),
        addCipherOpenedHistory = instance(),
        getJustDeleteMeByUrl = instance(),
        getJustGetMyDataByUrl = instance(),
        windowCoroutineScope = instance(),
        placeholderFactories = allInstances(),
        linkInfoExtractors = allInstances(),
        mode = mode,
        contentColor = contentColor,
        disabledContentColor = disabledContentColor,
        itemId = itemId,
        accountId = accountId,
    )
}

private class Holder(
    val uri: DSecret.Uri,
    val info: List<LinkInfo>,
    val overrides: List<Override> = emptyList(),
) {
    data class Override(
        val override: DGlobalUrlOverride,
        val contentOrException: Either<Throwable, Content>,
    ) {
        data class Content(
            val uri: String,
            val info: List<LinkInfo>,
        )
    }
}

private class CipherSauce(
    val cipher: DSecret,
    val cipherUrisIo: IO<List<Holder>>,
    val canEdit: Boolean,
    val canDelete: Boolean,
)

@Composable
fun vaultViewScreenState(
    mode: AppMode,
    contentColor: Color,
    disabledContentColor: Color,
    getAccounts: GetAccounts,
    getCanWrite: GetCanWrite,
    getCiphers: GetCiphers,
    getCollections: GetCollections,
    getOrganizations: GetOrganizations,
    getFolders: GetFolders,
    getFolderTreeById: GetFolderTreeById,
    getConcealFields: GetConcealFields,
    getMarkdown: GetMarkdown,
    getAppIcons: GetAppIcons,
    getWebsiteIcons: GetWebsiteIcons,
    getPasskeys: GetPasskeys,
    getTwoFa: GetTwoFa,
    getTotpCode: GetTotpCode,
    getPasswordStrength: GetPasswordStrength,
    getUrlOverrides: GetUrlOverrides,
    passkeyTargetCheck: PasskeyTargetCheck,
    getWatchtowerUnreadAlerts: GetWatchtowerUnreadAlerts,
    markWatchtowerAlertAsRead: MarkWatchtowerAlertAsRead,
    keyPairGenerator: KeyPairGenerator,
    keyPrivateExport: KeyPrivateExport,
    keyPublicExport: KeyPublicExport,
    cipherUnsecureUrlCheck: CipherUnsecureUrlCheck,
    cipherUnsecureUrlAutoFix: CipherUnsecureUrlAutoFix,
    cipherFieldSwitchToggle: CipherFieldSwitchToggle,
    moveCipherToFolderById: MoveCipherToFolderById,
    tldService: TldService,
    equivalentDomainsBuilderFactory: EquivalentDomainsBuilderFactory,
    patchWatchtowerAlertCipher: PatchWatchtowerAlertCipher,
    rePromptCipherById: RePromptCipherById,
    changeCipherNameById: ChangeCipherNameById,
    changeCipherPasswordById: ChangeCipherPasswordById,
    checkPasswordLeak: CheckPasswordLeak,
    retryCipher: RetryCipher,
    executeCommand: ExecuteCommand,
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
    clipboardService: ClipboardService,
    getGravatarUrl: GetGravatarUrl,
    dateFormatter: DateFormatter,
    addCipherOpenedHistory: AddCipherOpenedHistory,
    getJustDeleteMeByUrl: GetJustDeleteMeByUrl,
    getJustGetMyDataByUrl: GetJustGetMyDataByUrl,
    windowCoroutineScope: WindowCoroutineScope,
    placeholderFactories: List<Placeholder.Factory>,
    linkInfoExtractors: List<LinkInfoExtractor<LinkInfo, LinkInfo>>,
    itemId: String,
    accountId: String,
) = produceScreenState(
    key = "vault_view",
    initial = VaultViewState(),
    args = arrayOf(
        getAccounts,
        getCiphers,
        getCollections,
        getOrganizations,
        getFolders,
        getFolderTreeById,
        getConcealFields,
        getWebsiteIcons,
        getPasswordStrength,
        downloadAttachment,
        clipboardService,
        dateFormatter,
        windowCoroutineScope,
        linkInfoExtractors,
        itemId,
        accountId,
    ),
) {
    addCipherOpenedHistory(
        AddCipherOpenedHistoryRequest(
            accountId = accountId,
            cipherId = itemId,
        ),
    )
        .attempt()
        .launchIn(windowCoroutineScope)

    val copy = copy(
        clipboardService = clipboardService,
    )

    val equivalentDomainsBuilder = equivalentDomainsBuilderFactory.build()
    val selectionHandle = selectionHandle("selection")
    val markdown = getMarkdown().first()
    val markdownParser = CommonmarkAstNodeParser()

    val accountFlow = getAccounts()
        .map { accounts ->
            accounts
                .firstOrNull { it.id.id == accountId }
        }
        .distinctUntilChanged()
    val secretFlow = getCiphers()
        .map { secrets ->
            secrets
                .firstOrNull { it.id == itemId && it.accountId == accountId }
        }
        .distinctUntilChanged()
        .shareInScreenScope()
    launchAutoPopSelfHandler(secretFlow)

    // Mark watchtower alert as read if the screen
    // is currently visible.
    launchUi {
        getWatchtowerUnreadAlerts()
            .map { alerts ->
                val cipherId = CipherId(itemId)
                val alert = alerts.firstOrNull(cipherId)
                cipherId.takeIf { alert != null }
            }
            .onEach { cipherId ->
                if (cipherId != null) {
                    markWatchtowerAlertAsRead(cipherId)
                        .launchIn(appScope)
                }
            }
            .launchIn(this)
    }

    val ciphersFlow = getCiphers()
        .map { secrets ->
            secrets
                .filter { it.deletedDate == null }
        }
    val folderFlow = secretFlow
        .flatMapLatest { secret ->
            val folderId = secret?.folderId
                ?: return@flatMapLatest flowOf(null)
            getFolderTreeById(folderId)
        }
        .distinctUntilChanged()
    val collectionsFlow = getCollections()
        .combine(
            secretFlow
                .map { secret -> secret?.collectionIds.orEmpty() }
                .distinctUntilChanged(),
        ) { collections, collectionIds ->
            collectionIds
                .mapNotNull { collectionId ->
                    collections
                        .firstOrNull { it.id == collectionId && it.accountId == accountId }
                }
                .sortedWith(StringComparatorIgnoreCase(descending = true) { it.name })
        }
        .distinctUntilChanged()
    val organizationFlow = getOrganizations()
        .combine(
            secretFlow
                .map { secret -> secret?.organizationId }
                .distinctUntilChanged(),
        ) { organizations, organizationIdOrNull ->
            organizationIdOrNull?.let { organizationId ->
                organizations
                    .firstOrNull { it.id == organizationId && it.accountId == accountId }
            }
        }
        .distinctUntilChanged()

    fun getCipherUrisIo(
        now: Instant,
        cipher: DSecret,
        urlOverrides: List<DGlobalUrlOverride>,
    ) = ioEffect {
        val extractors = LinkInfoRegistry(linkInfoExtractors)

        // Processes a single URI
        suspend fun processUri(
            uri: DSecret.Uri,
        ) = when (uri.match) {
            // Regular expressions may use the {} control
            // symbols already. Since we do not want to break
            // existing data we ignore the placeholders and overrides.
            DSecret.Uri.MatchType.RegularExpression -> {
                val extra = extractors.process(uri)
                Holder(
                    uri = uri,
                    info = extra,
                )
            }

            else -> {
                val newUriPlaceholders = placeholderFactories
                    .create(
                        scope = PlaceholderScope(
                            now = now,
                            cipher = cipher,
                        ),
                    )
                val newUriString = kotlin.runCatching {
                    uri.uri.placeholderFormat(newUriPlaceholders)
                }.getOrElse { uri.uri }
                val newUri = uri.copy(uri = newUriString)

                // Process URL overrides
                val urlOverridePlaceholders by lazy {
                    placeholderFactories
                        .create(
                            scope = PlaceholderScope(
                                now = now,
                                cipher = cipher,
                                url = newUriString,
                            ),
                        )
                }
                val urlOverrideList = urlOverrides
                    .filter { override ->
                        override.enabled &&
                                override.regex.matches(newUriString)
                    }
                    .map { override ->
                        val contentOrException = Either.catch {
                            val command = override.command
                                .placeholderFormat(
                                    placeholders = urlOverridePlaceholders,
                                )
                            val extra = extractors.process(
                                DSecret.Uri(
                                    uri = command,
                                    match = DSecret.Uri.MatchType.Exact,
                                ),
                            )
                            Holder.Override.Content(
                                uri = command,
                                info = extra,
                            )
                        }
                        Holder.Override(
                            override = override,
                            contentOrException = contentOrException,
                        )
                    }

                val extra = extractors.process(newUri)
                Holder(
                    uri = newUri,
                    info = extra,
                    overrides = urlOverrideList,
                )
            }
        }

        cipher
            .uris
            .map { uri ->
                processUri(uri)
            }
    }

    val cipherExtraFlow = combine(
        secretFlow,
        collectionsFlow,
        getUrlOverrides(),
        getCanWrite(),
    ) { cipher, collections, urlOverrides, hasWriteAccess ->
        cipher
            ?: return@combine null
        val now = Clock.System.now()

        // Find ciphers that have some limitations
        val belongsToReadOnlyCollection by lazy {
            collections.any { it.readOnly }
        }
        val canEdit = hasWriteAccess && cipher.canEdit() && !belongsToReadOnlyCollection
        val canDelete = hasWriteAccess && cipher.canDelete() && !belongsToReadOnlyCollection

        val hasCanNotSeePassword =
            collections.all { it.hidePasswords } && collections.isNotEmpty()

        // Process URL placeholders and extract the link info.
        // We share the underlying job, so separate parts can
        // re-use the processing result.
        val cipherUrisIo = getCipherUrisIo(
            now = now,
            cipher = cipher,
            urlOverrides = urlOverrides,
        ).shared(tag = "Cipher URIs")

        CipherSauce(
            cipher = cipher,
            cipherUrisIo = cipherUrisIo,
            canEdit = canEdit,
            canDelete = canDelete,
        )
    }

    val fff = MutableStateFlow(false)
    val bbb = fff
        .combine(secretFlow) { elevatedAccess, cipher ->
            elevatedAccess || cipher?.reprompt != true
        }
        .stateIn(appScope)

    var defaultReprompt = false

    // Observe the cipher pilling the
    // information whether we should or
    // should not show the reprompt.
    secretFlow
        .onEach { cipher ->
            defaultReprompt = cipher?.reprompt == true
        }
        .launchIn(appScope)

    fun executeWithRePrompt(
        reprompt: Boolean = defaultReprompt,
        block: () -> Unit,
    ) {
        if (reprompt) {
            // Handle the re-prompt protection
            if (!fff.value) {
                val intent = createElevatedAccessDialogIntent {
                    fff.value = true
                    block()
                }
                navigate(intent)
                return
            }
        }

        block()
    }

    fun onLaunchEdit(
        cipher: DSecret,
    ) {
        val route = LeAddRoute(
            args = AddRoute.Args(
                behavior = AddRoute.Args.Behavior(
                    // When you edit a cipher, you do not know what field
                    // user is targeting, so it's better to not show the
                    // keyboard automatically.
                    autoShowKeyboard = false,
                    launchEditedCipher = false,
                ),
                initialValue = cipher,
            ),
        )
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    val visibilityGlobalSink = mutablePersistedFlow("reveal") {
        Visibility.Event(
            value = false,
            timestamp = Clock.System.now(),
        )
    }
    val visibilityGlobalUserTransform: (Boolean, (Boolean) -> Unit) -> Unit =
        argh@{ newValue, setter ->
            if (!newValue) {
                visibilityGlobalSink.value = Visibility.Event(
                    value = false,
                    timestamp = Clock.System.now(),
                )
                return@argh Unit
            }

            // Handle the re-prompt protection
            return@argh executeWithRePrompt {
                setter(true)
            }
        }
    val visibilityGlobalConfig = Visibility.Global(
        globalRevealStateFlow = visibilityGlobalSink,
    )

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
        // Ctrl+C: Copy the primary field value
        KeyShortcut(
            key = Key.C,
            isCtrlPressed = true,
        ) to secretFlow
            .map { cipher ->
                val primaryFieldPair =
                    pairUnlessEmpty(cipher?.login?.username, CopyText.Type.USERNAME)
                        ?: pairUnlessEmpty(cipher?.card?.number, CopyText.Type.CARD_NUMBER)
                        ?: pairUnlessEmpty(cipher?.identity?.email, CopyText.Type.EMAIL)
                        ?: pairUnlessEmpty(cipher?.identity?.phone, CopyText.Type.PHONE_NUMBER)
                        ?: pairUnlessEmpty(cipher?.sshKey?.publicKey, CopyText.Type.KEY)
                        ?: pairUnlessEmpty(cipher?.notes, CopyText.Type.VALUE)
                if (primaryFieldPair == null) {
                    return@map null
                }

                // lambda
                {
                    val (value, type) = primaryFieldPair
                    copy.copy(value, false, type)
                }
            },
        // Ctrl+Shift+C: Copy the secret field value
        KeyShortcut(
            key = Key.C,
            isCtrlPressed = true,
            isShiftPressed = true,
        ) to secretFlow
            .map { cipher ->
                val secretFieldPair =
                    pairUnlessEmpty(cipher?.login?.password, CopyText.Type.PASSWORD)
                        ?: pairUnlessEmpty(cipher?.card?.code, CopyText.Type.CARD_CVV)
                        ?: pairUnlessEmpty(cipher?.sshKey?.privateKey, CopyText.Type.KEY)
                if (secretFieldPair == null) {
                    return@map null
                }

                val performCopy = {
                    val (value, type) = secretFieldPair
                    copy.copy(value, true, type)
                }
                val needsRePrompt = cipher?.reprompt != false
                // lambda
                shortcut@{
                    executeWithRePrompt(needsRePrompt, performCopy)
                }
            },
        // Ctrl+Alt+C: Copy the one time password value
        KeyShortcut(
            key = Key.C,
            isCtrlPressed = true,
            isAltPressed = true,
        ) to secretFlow
            .map { cipher ->
                val totpField = cipher?.login?.totp?.token
                    ?: return@map null
                // lambda
                {
                    // Generate the TOTP code and
                    // copy it.
                    appScope.launch {
                        val code = getTotpCode(totpField)
                            .firstOrNull()
                            ?.code
                        if (code != null) {
                            val copyType = CopyText.Type.OTP
                            copy.copy(code, false, copyType)
                        }
                    }
                }
            },
        // Ctrl+Shift+F: Open website
        KeyShortcut(
            key = Key.F,
            isCtrlPressed = true,
            isShiftPressed = true,
        ) to cipherExtraFlow
            .map { cipherExtra ->
                cipherExtra
                    ?: return@map null

                val uris = cipherExtra.cipherUrisIo
                    .attempt()
                    .bind()
                    .getOrElse {
                        return@map null
                    }

                fun getIntentForWebLinkInfo(info: LinkInfo): NavigationIntent? {
                    if (info !is LinkInfoPlatform.Web)
                        return null
                    return NavigationIntent.NavigateToBrowser(info.url.toString())
                }

                fun getIntentForAndroidLinkInfo(info: LinkInfo): NavigationIntent? {
                    if (info !is LinkInfoPlatform.Android)
                        return null
                    return NavigationIntent.NavigateToApp(info.packageName)
                }

                val handlers = buildList<(LinkInfo) -> NavigationIntent?> {
                    add(::getIntentForWebLinkInfo)
                    // On Android we can also open the app directly.
                    if (CurrentPlatform is Platform.Mobile.Android) {
                        add(::getIntentForAndroidLinkInfo)
                    }
                }

                fun findFirstIntentOrNull(
                    info: List<LinkInfo>,
                ): NavigationIntent? {
                    return info
                        .firstNotNullOfOrNull { linkInfo ->
                            handlers.firstNotNullOfOrNull { handler -> handler(linkInfo) }
                        }
                }

                val shortcutIntent = uris
                    .firstNotNullOfOrNull { uri ->
                        findFirstIntentOrNull(uri.info)
                            ?: uri.overrides
                                .firstNotNullOfOrNull { override ->
                                    val overrideContent = override.contentOrException
                                        .getOrNull()
                                        ?: return@firstNotNullOfOrNull null
                                    findFirstIntentOrNull(overrideContent.info)
                                }
                    }
                    ?: return@map null
                // lambda
                {
                    navigate(shortcutIntent)
                }
            },
        // Ctrl+R: Reveal/conceal th secret values
        KeyShortcut(
            key = Key.R,
            isCtrlPressed = true,
        ) to secretFlow
            .map { cipher ->
                val needsRePrompt = cipher?.reprompt != false
                // lambda
                shortcut@{
                    val visible = !visibilityGlobalSink.value.value
                    executeWithRePrompt(
                        reprompt = visible && needsRePrompt,
                    ) {
                        visibilityGlobalSink.value = Visibility.Event(
                            value = visible,
                            timestamp = Clock.System.now(),
                        )
                    }
                }
            },
        // Ctrl+E: Edit an item
        KeyShortcut(
            key = Key.E,
            isCtrlPressed = true,
        ) to cipherExtraFlow
            .map { cipherExtra ->
                if (cipherExtra == null || !cipherExtra.canEdit) {
                    return@map null
                }

                val cipher = cipherExtra.cipher
                // lambda
                shortcut@{
                    executeWithRePrompt(cipher.reprompt) {
                        onLaunchEdit(cipher)
                    }
                }
            },
        // Delete: Ask to move an item to trash
        KeyShortcut(
            key = Key.Delete,
        ) to cipherExtraFlow
            .map { cipherExtra ->
                if (cipherExtra == null || !cipherExtra.canDelete) {
                    return@map null
                }

                val cipher = cipherExtra.cipher
                val action = flow<FlatItemAction> {
                    if (cipher.deletedDate == null && cipher.service.remote != null) {
                        val trashAction = cipherTrashAction(
                            trashCipherById = trashCipherById,
                            ciphers = listOf(cipher),
                        )
                        emit(trashAction)
                    }
                    if (cipher.deletedDate != null ||
                        cipher.service.remote == null ||
                        cipher.hasError
                    ) {
                        val deleteAction = cipherDeleteAction(
                            removeCipherById = removeCipherById,
                            ciphers = listOf(cipher),
                        )
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
        ) to cipherExtraFlow
            .map { cipherExtra ->
                if (cipherExtra == null || !cipherExtra.canDelete) {
                    return@map null
                }

                val cipher = cipherExtra.cipher
                val action = flow<FlatItemAction> {
                    val deleteAction = cipherDeleteAction(
                        removeCipherById = removeCipherById,
                        ciphers = listOf(cipher),
                    )
                    emit(deleteAction)
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
        cipherExtraFlow,
        folderFlow,
        ciphersFlow,
        collectionsFlow,
        organizationFlow,
        getConcealFields(),
        getAppIcons(),
        getWebsiteIcons(),
        getCanWrite(),
    ) { array ->
        val accountOrNull = array[0] as DAccount?
        val secretSauceOrNull = array[1] as CipherSauce?
        val secretOrNull = secretSauceOrNull?.cipher
        val folderOrNull = array[2] as DFolderTree?
        val ciphers = array[3] as List<DSecret>
        val collections = array[4] as List<DCollection>
        val organizationOrNull = array[5] as DOrganization?
        val concealFields = array[6] as Boolean
        val appIcons = array[7] as Boolean
        val websiteIcons = array[8] as Boolean
        val canAddSecret = array[9] as Boolean

        val content = when {
            accountOrNull == null || secretOrNull == null -> VaultViewState.Content.NotFound
            else -> {
                val verify: ((() -> Unit) -> Unit)? = if (secretOrNull.reprompt) {
                    // composable
                    { block ->
                        if (!fff.value) {
                            val intent = createElevatedAccessDialogIntent {
                                fff.value = true
                                block()
                            }
                            navigate(intent)
                        } else {
                            block()
                        }
                    }
                } else {
                    null
                }

                // Find ciphers that have some limitations
                val hasCanNotWriteCiphers = collections.any { it.readOnly }
                val hasCanNotSeePassword =
                    collections.all { it.hidePasswords } && collections.isNotEmpty()
                val canEdit = canAddSecret && secretOrNull.canEdit() && !hasCanNotWriteCiphers
                val canDelete = canAddSecret && secretOrNull.canDelete() && !hasCanNotWriteCiphers

                val cipherUris = secretSauceOrNull
                    .cipherUrisIo.attempt().bind().getOrNull().orEmpty()
                val icon = secretOrNull.toVaultItemIcon(
                    appIcons = appIcons,
                    websiteIcons = websiteIcons,
                )
                val primaryAction = if (
                    mode is AppMode.HasType &&
                    mode.type != null &&
                    mode.type != secretOrNull.type
                ) {
                    null
                } else when (mode) {
                    is AppMode.PickPasskey -> null
                    is AppMode.Main -> null
                    is AppMode.Pick -> {
                        FlatItemAction(
                            title = Res.string.autofill.wrap(),
                            leading = icon(Icons.Outlined.AutoAwesome),
                            onClick = {
                                val cipher = secretOrNull
                                val extra = AppMode.Pick.Extra()
                                mode.onAutofill(cipher, extra)
                            },
                        )
                    }

                    is AppMode.Save -> null
                    is AppMode.SavePasskey -> {
                        FlatItemAction(
                            title = Res.string.passkey_save.wrap(),
                            leading = icon(Icons.Outlined.Save),
                            onClick = {
                                val cipher = secretOrNull
                                mode.onComplete(cipher)
                            },
                        )
                    }
                }
                VaultViewState.Content.Cipher(
                    locked = bbb,
                    data = secretOrNull,
                    icon = icon,
                    synced = secretOrNull.synced,
                    onFavourite = if (canEdit) {
                        // lambda
                        {
                            val cipherIds = setOf(secretOrNull.id)
                            favouriteCipherById(cipherIds, it)
                                .launchIn(windowCoroutineScope)
                        }
                    } else {
                        null
                    },
                    onEdit = if (canEdit) {
                        // lambda
                        {
                            executeWithRePrompt(secretOrNull.reprompt) {
                                onLaunchEdit(secretOrNull)
                            }
                        }
                    } else {
                        null
                    },
                    actions = listOfNotNull(
                        cipherEnableConfirmAccessAction(
                            rePromptCipherById = rePromptCipherById,
                            ciphers = listOf(secretOrNull),
                        )
                            .takeIf { !secretOrNull.reprompt },
                        cipherDisableConfirmAccessAction(
                            rePromptCipherById = rePromptCipherById,
                            ciphers = listOf(secretOrNull),
                        )
                            .takeIf { secretOrNull.reprompt }
                            ?.verify(verify),
                        cipherViewPasswordHistoryAction(
                            cipher = secretOrNull,
                        )
                            .takeIf { !secretOrNull.login?.passwordHistory.isNullOrEmpty() }
                            ?.verify(verify),
                        cipherChangeNameAction(
                            changeCipherNameById = changeCipherNameById,
                            ciphers = listOf(secretOrNull),
                        ).takeIf { canEdit },
                        cipherChangePasswordAction(
                            changeCipherPasswordById = changeCipherPasswordById,
                            ciphers = listOf(secretOrNull),
                        ).takeIf { canEdit && secretOrNull.login != null }
                            ?.verify(verify),
                        cipherCopyToAction(
                            copyCipherById = copyCipherById,
                            ciphers = listOf(secretOrNull),
                        ).takeIf { canAddSecret },
                        cipherMoveToFolderAction(
                            moveCipherToFolderById = moveCipherToFolderById,
                            accountId = AccountId(accountId),
                            ciphers = listOf(secretOrNull),
                        ).takeIf { canEdit },
                        cipherWatchtowerAlerts(
                            patchWatchtowerAlertCipher = patchWatchtowerAlertCipher,
                            ciphers = listOf(secretOrNull),
                        ),
                        cipherExportAction(
                            ciphers = listOf(secretOrNull),
                        ),
                        cipherTrashAction(
                            trashCipherById = trashCipherById,
                            ciphers = listOf(secretOrNull),
                        ).takeIf { canDelete && (secretOrNull.deletedDate == null && secretOrNull.service.remote != null) },
                        cipherRestoreAction(
                            restoreCipherById = restoreCipherById,
                            ciphers = listOf(secretOrNull),
                        ).takeIf { canDelete && secretOrNull.deletedDate != null },
                        cipherDeleteAction(
                            removeCipherById = removeCipherById,
                            ciphers = listOf(secretOrNull),
                        ).takeIf {
                            canDelete &&
                                    (secretOrNull.deletedDate != null ||
                                            secretOrNull.service.remote == null ||
                                            secretOrNull.hasError)
                        },
                    ),
                    primaryAction = primaryAction,
                    items = oh(
                        mode = mode,
                        markdownParser = markdownParser,
                        sharingScope = screenScope, // FIXME: must not be a screen scope!!
                        selectionHandle = selectionHandle,
                        canEdit = canEdit,
                        contentColor = contentColor,
                        disabledContentColor = disabledContentColor,
                        hasCanNotSeePassword = hasCanNotSeePassword,
                        downloadManager = downloadManager,
                        downloadAttachment = downloadAttachment,
                        removeAttachment = removeAttachment,
                        getPasskeys = getPasskeys,
                        getTwoFa = getTwoFa,
                        getTotpCode = getTotpCode,
                        getPasswordStrength = getPasswordStrength,
                        passkeyTargetCheck = passkeyTargetCheck,
                        keyPairGenerator = keyPairGenerator,
                        keyPrivateExport = keyPrivateExport,
                        keyPublicExport = keyPublicExport,
                        cipherUnsecureUrlCheck = cipherUnsecureUrlCheck,
                        cipherUnsecureUrlAutoFix = cipherUnsecureUrlAutoFix,
                        cipherFieldSwitchToggle = cipherFieldSwitchToggle,
                        checkPasswordLeak = checkPasswordLeak,
                        retryCipher = retryCipher,
                        executeCommand = executeCommand,
                        markdown = markdown,
                        concealFields = concealFields || secretOrNull.reprompt,
                        websiteIcons = websiteIcons,
                        getGravatarUrl = getGravatarUrl,
                        copy = copy,
                        dateFormatter = dateFormatter,
                        linkInfoExtractors = linkInfoExtractors,
                        visibilityGlobalConfig = visibilityGlobalConfig,
                        visibilityGlobalUserTransform = visibilityGlobalUserTransform,
                        account = accountOrNull,
                        cipher = secretOrNull,
                        folder = folderOrNull,
                        organization = organizationOrNull,
                        tldService = tldService,
                        equivalentDomainsBuilder = equivalentDomainsBuilder,
                        ciphers = ciphers,
                        collections = collections,
                        cipherUris = cipherUris,
                        cipherExpiringCheck = cipherExpiringCheck,
                        cipherIncompleteCheck = cipherIncompleteCheck,
                        getJustDeleteMeByUrl = getJustDeleteMeByUrl,
                        getJustGetMyDataByUrl = getJustGetMyDataByUrl,
                        verify = verify,
                    ).toList().transformShapes(),
                )
            }
        }
        VaultViewState(
            content = content,
        )
    }
}

private fun RememberStateFlowScope.oh(
    mode: AppMode,
    markdownParser: CommonmarkAstNodeParser,
    sharingScope: CoroutineScope,
    selectionHandle: SelectionHandle,
    canEdit: Boolean,
    contentColor: Color,
    disabledContentColor: Color,
    hasCanNotSeePassword: Boolean,
    downloadManager: DownloadManager,
    downloadAttachment: DownloadAttachment,
    removeAttachment: RemoveAttachment,
    getPasskeys: GetPasskeys,
    getTwoFa: GetTwoFa,
    getTotpCode: GetTotpCode,
    getPasswordStrength: GetPasswordStrength,
    passkeyTargetCheck: PasskeyTargetCheck,
    keyPairGenerator: KeyPairGenerator,
    keyPrivateExport: KeyPrivateExport,
    keyPublicExport: KeyPublicExport,
    cipherUnsecureUrlCheck: CipherUnsecureUrlCheck,
    cipherUnsecureUrlAutoFix: CipherUnsecureUrlAutoFix,
    cipherFieldSwitchToggle: CipherFieldSwitchToggle,
    checkPasswordLeak: CheckPasswordLeak,
    retryCipher: RetryCipher,
    executeCommand: ExecuteCommand,
    markdown: Boolean,
    concealFields: Boolean,
    websiteIcons: Boolean,
    getGravatarUrl: GetGravatarUrl,
    copy: CopyText,
    dateFormatter: DateFormatter,
    linkInfoExtractors: List<LinkInfoExtractor<LinkInfo, LinkInfo>>,
    visibilityGlobalConfig: Visibility.Global,
    visibilityGlobalUserTransform: (Boolean, (Boolean) -> Unit) -> Unit,
    account: DAccount,
    cipher: DSecret,
    folder: DFolderTree?,
    organization: DOrganization?,
    tldService: TldService,
    equivalentDomainsBuilder: EquivalentDomainsBuilder,
    ciphers: List<DSecret>,
    collections: List<DCollection>,
    cipherUris: List<Holder>,
    cipherExpiringCheck: CipherExpiringCheck,
    cipherIncompleteCheck: CipherIncompleteCheck,
    getJustDeleteMeByUrl: GetJustDeleteMeByUrl,
    getJustGetMyDataByUrl: GetJustGetMyDataByUrl,
    verify: ((() -> Unit) -> Unit)?,
) = flow<VaultViewItem> {
    val hasWiFi = kotlin.run {
        val ssid = cipher.login?.username
            ?: cipher.fields
                .firstNotNullOfOrNull { field ->
                    field.value?.takeIf { field.name == "WiFi SSID" }
                        ?: field.value?.takeIf { field.name == "SSID" }
                }
        if (ssid.isNullOrBlank()) {
            return@run false
        }

        val password = cipher.login?.password
        val auth = cipher.fields
            .firstNotNullOfOrNull { field ->
                field.value
                    ?.takeIf { field.name == "WiFi Authentication Type" }
            }
        val hidden = cipher.fields
            .firstNotNullOfOrNull { field ->
                field.value
                    ?.takeIf { field.name == "WiFi Hidden" }
            }
        if (auth == null && hidden == null) {
            return@run false
        }

        val specialChars = listOf(
            '\\',
            ';',
            ',',
            '"',
            ':',
        )
        val payload = listOf(
            "S" to ssid,
            "T" to auth,
            "P" to password,
            "H" to hidden,
        )
            .mapNotNull { (key, value) ->
                // Ignore empty value
                if (value == null) {
                    return@mapNotNull null
                }

                key to value
            }
            .joinToString(separator = ";") { (key, value) ->
                val escapedValue = specialChars
                    .fold(value) { v, char ->
                        v.replace("$char", "\\$char")
                    }
                "$key:$escapedValue"
            }
        val qr = VaultViewItem.Qr(
            id = "qr",
            data = "WIFI:$payload;;",
        )
        emit(qr)
        return@run true
    }

    val cipherError = cipher.service.error
    if (cipherError != null && !cipherError.expired(cipher.revisionDate)) {
        val time = dateFormatter.formatDateTime(cipherError.revisionDate)
        val model = VaultViewItem.Error(
            id = "error",
            name = "Couldn't sync the item",
            message = cipherError.message(),
            blob = cipherError.blob,
            timestamp = time,
            onRetry = if (cipherError.canRetry(cipher.revisionDate)) {
                // lambda
                {
                    val cipherIds = setOf(cipher.id)
                    retryCipher(cipherIds)
                        .launchIn(appScope)
                }
            } else {
                null
            },
            onCopyBlob = if (cipherError.blob != null) {
                // lambda
                {
                    copy.copy(
                        text = cipherError.blob,
                        hidden = false,
                    )
                }
            } else {
                null
            },
        )
        emit(model)
    }

    val incomplete by lazy {
        cipherIncompleteCheck.invoke(cipher)
    }
    if (
        !cipher.ignores(DWatchtowerAlertType.INCOMPLETE) &&
        incomplete
    ) {
        val model = VaultViewItem.Info(
            id = "info.incomplete",
            name = translate(Res.string.item_incomplete_title),
            message = translate(Res.string.item_incomplete_text),
        )
        emit(model)
    }

    val now = Clock.System.now()
    val expiring = cipherExpiringCheck.invoke(cipher, now)
    if (
        !cipher.ignores(DWatchtowerAlertType.EXPIRING) &&
        expiring != null
    ) {
        val expired = expiring <= now
        val expiringTitle = if (expired) {
            translate(Res.string.expired)
        } else {
            translate(Res.string.expiring_soon)
        }

        val expiringDate = dateFormatter.formatDate(expiring)
        val expiringMessage = when (cipher.type) {
            DSecret.Type.Card -> listOf(
                translate(Res.string.expiry_tips_card_line1, expiringDate),
                translate(Res.string.expiry_tips_card_line2, expiringDate),
                translate(Res.string.expiry_tips_card_line3, expiringDate),
                translate(Res.string.expiry_tips_card_line4, expiringDate),
            ).joinToString(separator = "\n")

            else -> translate(Res.string.expiry_tips_item_line1, expiringDate)
        }
        val model = VaultViewItem.Info(
            id = "info.expiring",
            name = expiringTitle,
            message = expiringMessage,
        )
        emit(model)
    }

    val sshKey = cipher.sshKey
    if (sshKey != null) {
        val keyPair = kotlin.run {
            if (sshKey.publicKey == null ||
                sshKey.privateKey == null
            ) {
                return@run null
            }

            ioEffect(Dispatchers.Default) {
                val gen = keyPairGenerator.parse(
                    privateKey = sshKey.privateKey,
                    publicKey = sshKey.publicKey,
                )
                keyPairGenerator.populate(gen)
            }
                .attempt()
                .bind()
                .getOrNull()
        }
        if (sshKey.publicKey != null) {
            val publicKeyItem = create(
                copy = copy,
                id = "sshKey.publicKey",
                accountId = account.id,
                title = translate(Res.string.public_key),
                value = sshKey.publicKey,
                shortcut = KeyShortcut(
                    Key.C,
                    isCtrlPressed = true,
                ),
                maxLines = 4, // public key is too long to always show
                monospace = true,
                colorize = true,
                elevated = true,
                onBuildActions = {
                    if (keyPair == null) {
                        return@create
                    }

                    this += SshKeyActions.savePublicKey(
                        keyPair = keyPair,
                        publicKeyExport = keyPublicExport,
                    )
                },
            )
            emit(publicKeyItem)
        }
        if (sshKey.fingerprint != null) {
            val fingerprintItem = create(
                copy = copy,
                id = "sshKey.fingerprint",
                accountId = account.id,
                title = translate(Res.string.fingerprint),
                value = sshKey.fingerprint,
                monospace = true,
                colorize = true,
                elevated = true,
            )
            emit(fingerprintItem)
        }
        if (sshKey.privateKey != null) {
            val privateKeyItem = create(
                copy = copy,
                id = "sshKey.privateKey",
                accountId = account.id,
                title = translate(Res.string.private_key),
                value = sshKey.privateKey,
                shortcut = KeyShortcut(
                    Key.C,
                    isCtrlPressed = true,
                    isShiftPressed = true,
                ),
                verify = verify.takeIf { concealFields },
                // Private key might be too long to show if you
                // select a 4096-bit RSA. There's no point in
                // torturing the device rendering that string.
                maxLines = 24,
                monospace = true,
                colorize = true,
                elevated = true,
                visibility = Visibility(
                    concealed = concealFields,
                    hidden = hasCanNotSeePassword,
                    transformUserEvent = visibilityGlobalUserTransform,
                    globalConfig = visibilityGlobalConfig,
                ),
                onBuildActions = {
                    if (keyPair == null) {
                        return@create
                    }

                    this += SshKeyActions.savePrivateKey(
                        keyPair = keyPair,
                        privateKeyExport = keyPrivateExport,
                    )
                },
            )
            emit(privateKeyItem)
        }
        if (keyPair != null) {
            val keyLength = keyPairGenerator.getPrivateKeyLengthOrNull(keyPair)
            val keyDesc = kotlin.run {
                if (keyLength != null) {
                    val bits = translate(Res.string.generator_key_length_item, keyLength)
                    return@run "${keyPair.type.title}, $bits"
                }

                keyPair.type.title
            }
            val typeItem = create(
                copy = copy,
                id = "sshKey.keyType",
                accountId = account.id,
                title = translate(Res.string.key_type),
                value = keyDesc,
                elevated = true,
            )
            emit(typeItem)
        }
    }

    val cipherCard = cipher.card
    if (cipherCard != null) {
        val model = create(
            copy = copy,
            copyShortcut = KeyShortcut(
                Key.C,
                isCtrlPressed = true,
            ),
            id = "card",
            verify = verify.takeIf { concealFields },
            visibility = Visibility(
                concealed = concealFields,
                transformUserEvent = visibilityGlobalUserTransform,
                globalConfig = visibilityGlobalConfig,
            ),
            name = cipher.name,
            data = cipherCard,
        )
        emit(model)
        val cipherCardFromYear = cipherCard.fromYear
        val cipherCardFromMonth = cipherCard.fromMonth
        if (cipherCardFromYear != null || cipherCardFromMonth != null) {
            val item = createExpDate(
                copy = copy,
                id = "card.from_date",
                title = translate(Res.string.card_valid_from),
                year = cipherCardFromYear,
                month = cipherCardFromMonth,
                elevated = true,
            )
            emit(item)
        }
        val cipherCardExpYear = cipherCard.expYear
        val cipherCardExpMonth = cipherCard.expMonth
        if (cipherCardExpYear != null || cipherCardExpMonth != null) {
            val item = createExpDate(
                copy = copy,
                id = "card.exp_date",
                title = translate(Res.string.card_valid_to),
                year = cipherCardExpYear,
                month = cipherCardExpMonth,
                elevated = true,
            )
            emit(item)
        }
        val cipherCardCode = cipherCard.code
        if (cipherCardCode != null) {
            val cvv = create(
                copy = copy,
                id = "card.cvv",
                accountId = account.id,
                title = translate(Res.string.vault_view_card_cvv_label),
                value = cipherCardCode,
                shortcut = KeyShortcut(
                    Key.C,
                    isCtrlPressed = true,
                    isShiftPressed = true,
                ),
                verify = verify.takeIf { concealFields },
                visibility = Visibility(
                    concealed = concealFields,
                    hidden = hasCanNotSeePassword,
                    transformUserEvent = visibilityGlobalUserTransform,
                    globalConfig = visibilityGlobalConfig,
                ),
                monospace = true,
                elevated = true,
            )
            emit(cvv)
        }
    }
    val cipherLogin = cipher.login
    if (cipherLogin != null) {
        val cipherLoginUsername = cipherLogin.username
        if (cipherLoginUsername != null) {
            val usernameVariation = if (hasWiFi) {
                UsernameVariation2.Ssid
            } else {
                UsernameVariation2.of(
                    getGravatarUrl,
                    cipherLoginUsername,
                )
            }
            val title = if (hasWiFi) {
                translate(Res.string.ssid)
            } else translate(Res.string.username)
            val model = create(
                copy = copy,
                id = "login.username",
                accountId = account.id,
                title = title,
                value = cipherLoginUsername,
                shortcut = KeyShortcut(
                    Key.C,
                    isCtrlPressed = true,
                ),
                username = !hasWiFi,
                elevated = true,
                leading = {
                    UsernameVariationIcon(usernameVariation = usernameVariation)
                },
            )
            emit(model)
        }
        val cipherLoginPassword = cipherLogin.password
        if (cipherLoginPassword != null) {
            val scoreRaw =
                getPasswordStrength(cipherLoginPassword).attempt().bind().getOrNull()?.score

            val pwnage = checkPasswordLeak(CheckPasswordLeakRequest(cipherLoginPassword))
                .attempt()
                .asFlow()
                .map {
                    val occurrences = it.getOrElse { 0 }
                    if (
                        occurrences > 0 &&
                        !cipher.ignores(DWatchtowerAlertType.PWNED_PASSWORD)
                    )
                        VaultViewItem.Value.Badge(
                            text = translate(Res.string.password_pwned_label),
                            score = 0f,
                        )
                    else null
                }
                .stateIn(sharingScope, SharingStarted.Eagerly, null)
            val model = create(
                copy = copy,
                id = "login.password",
                accountId = account.id,
                title = translate(Res.string.password),
                value = cipherLoginPassword,
                shortcut = KeyShortcut(
                    Key.C,
                    isCtrlPressed = true,
                    isShiftPressed = true,
                ),
                verify = verify.takeIf { concealFields },
                visibility = Visibility(
                    concealed = concealFields,
                    hidden = hasCanNotSeePassword,
                    transformUserEvent = visibilityGlobalUserTransform,
                    globalConfig = visibilityGlobalConfig,
                ),
                password = true,
                monospace = true,
                colorize = true,
                elevated = true,
                badge = scoreRaw
                    ?.takeUnless { hasCanNotSeePassword }
                    ?.let {
                        val text = translate(it.formatH())
                        val score = it.alertScore()
                        VaultViewItem.Value.Badge(
                            text = text,
                            score = score,
                        )
                    },
                badge2 = listOf(pwnage),
                leading = {
                    IconBox(main = Icons.Outlined.Password)
                },
            )
            emit(model)

            val reusedPasswords by lazy {
                ciphers
                    .count {
                        it.login?.password == cipherLoginPassword
                    }
            }
            if (
                !cipher.ignores(DWatchtowerAlertType.REUSED_PASSWORD) &&
                reusedPasswords > 1
            ) {
                val reusedPasswordsModel = VaultViewItem.ReusedPassword(
                    id = "login.password.reused",
                    count = reusedPasswords,
                    onClick = onClick {
                        val intent = NavigationIntent.NavigateToRoute(
                            VaultRoute.watchtower(
                                title = translate(Res.string.reused_passwords),
                                subtitle = translate(Res.string.watchtower_header_title),
                                filter = DFilter.ByPasswordValue(cipherLoginPassword),
                                sort = PasswordSort,
                            ),
                        )
                        navigate(intent)
                    },
                )
                emit(reusedPasswordsModel)
            }

            val passwordDate = cipherLogin.passwordRevisionDate
            if (passwordDate != null) {
                // vault_view_password_revision_label
                val b = VaultViewItem.Label(
                    id = "login.password.revision",
                    text = AnnotatedString(
                        translate(
                            Res.string.vault_view_password_revision_label,
                            dateFormatter.formatDateTime(passwordDate),
                        ),
                    ),
                )
                emit(b)
            }
        }
        val cipherLoginTotp = cipherLogin.totp
        if (cipherLoginTotp != null) {
            val sharing = SharingStarted.WhileSubscribed(1000L)
            val localStateFlow = getTotpCode(cipherLoginTotp.token)
                .map {
                    // Format the totp code, so it's easier to
                    // read for the user.
                    val codes = it.formatCode2()
                    val dropdown = buildContextItems {
                        section {
                            this += copy.FlatItemAction(
                                title = Res.string.copy_otp_code.wrap(),
                                value = it.code,
                            )
                            this += copy.FlatItemAction(
                                leading = iconSmall(Icons.Outlined.ContentCopy, Icons.Outlined.Key),
                                title = Res.string.copy_otp_secret_code.wrap(),
                                value = cipherLoginTotp.token.raw,
                                hidden = true,
                            ).verify(verify)
                        }
                        section {
                            this += BarcodeTypeRoute.showInBarcodeTypeActionOrNull(
                                translator = this@oh,
                                data = cipherLoginTotp.token.raw,
                                text = translate(Res.string.barcodetype_copy_otp_secret_code_note),
                                single = true,
                                navigate = ::navigate,
                            )
                            this += createShareAction(
                                translator = this@oh,
                                text = it.code,
                                navigate = ::navigate,
                            )
                        }
                    }

                    VaultViewItem.Totp.LocalState(
                        codes = codes,
                        dropdown = dropdown,
                    )
                }
                .attempt()
                .map { either ->
                    either
                        .getOrElse {
                            VaultViewItem.Totp.LocalState(
                                codes = persistentListOf(),
                                dropdown = persistentListOf(),
                            )
                        }
                }
                .persistingStateIn(appScope, sharing)
            val model = VaultViewItem.Totp(
                id = "login.totp",
                elevation = 1.dp,
                title = translate(Res.string.one_time_password),
                shortcut = KeyShortcut(
                    Key.C,
                    isCtrlPressed = true,
                    isAltPressed = true,
                ),
                copy = copy,
                totp = cipherLoginTotp.token,
                localStateFlow = localStateFlow,
            )
            emit(model)
        } else {
            kotlin.run {
                if (
                    cipherLogin.fido2Credentials.isNotEmpty() &&
                    cipherLogin.password.isNullOrEmpty() ||
                    cipher.ignores(DWatchtowerAlertType.TWO_FA_WEBSITE)
                ) {
                    return@run
                }

                val tfa = getTwoFa()
                    .crashlyticsTap()
                    .attempt()
                    .bind()
                    .getOrNull()
                    .orEmpty()

                val isUnsecure = with(tldService) {
                    val equivalentDomains = equivalentDomainsBuilder
                        .getAndCache(cipher.accountId)
                    WatchtowerInactiveTfa.match(
                        cipher = cipher,
                        tfaLibrary = tfa,
                        equivalentDomains = equivalentDomains,
                    ).firstOrNull()
                }
                if (isUnsecure != null) {
                    val model = VaultViewItem.InactiveTotp(
                        id = "error2",
                        chevron = false,
                        onClick = {
                            val route = TwoFaServiceViewDialogRoute(
                                args = TwoFaServiceViewDialogRoute.Args(isUnsecure),
                            )
                            val intent = NavigationIntent.NavigateToRoute(route)
                            navigate(intent)
                        },
                    )
                    emit(model)
                }
            }
        }
        if (
            cipher.login.fido2Credentials.isEmpty() &&
            !cipher.ignores(DWatchtowerAlertType.PASSKEY_WEBSITE)
        ) {
            val passkeyLibrary = getPasskeys()
                .crashlyticsTap()
                .attempt()
                .bind()
                .getOrNull()
                .orEmpty()

            val isUnsecure = with(tldService) {
                val equivalentDomains = equivalentDomainsBuilder
                    .getAndCache(cipher.accountId)
                WatchtowerInactivePasskey.match(
                    cipher = cipher,
                    passkeyLibrary = passkeyLibrary,
                    equivalentDomains = equivalentDomains,
                ).firstOrNull()
            }
            if (isUnsecure != null) {
                val model = VaultViewItem.InactivePasskey(
                    id = "error2.passkeys",
                    info = isUnsecure,
                    onClick = {
                        val route = PasskeysServiceViewDialogRoute(
                            args = PasskeysServiceViewDialogRoute.Args(isUnsecure),
                        )
                        val intent = NavigationIntent.NavigateToRoute(route)
                        navigate(intent)
                    },
                )
                emit(model)
            }
        }
        val fido2Credentials = cipherLogin.fido2Credentials
        if (fido2Credentials.isNotEmpty()) {
            val loginPasskeysItem = VaultViewItem.Section(
                id = "login.passkey.header",
                text = translate(Res.string.passkeys),
            )
            emit(loginPasskeysItem)
            fido2Credentials.forEachIndexed { index, item ->
                val onUse = when (mode) {
                    is AppMode.PickPasskey -> {
                        val matches = passkeyTargetCheck(item, mode.target)
                            .attempt()
                            .bind()
                            .isRight { it }
                        if (matches) {
                            // lambda
                            {
                                mode.onComplete(item)
                            }
                        } else {
                            null
                        }
                    }

                    else -> null
                }
                val model = VaultViewItem.Passkey(
                    id = "login.passkey.index$index",
                    value = item.userDisplayName
                        .takeIf { !it.isNullOrEmpty() },
                    source = item,
                    onUse = onUse,
                    onClick = {
                        val route = PasskeysCredentialViewRoute(
                            args = PasskeysCredentialViewRoute.Args(
                                cipherId = cipher.id,
                                credentialId = item.credentialId,
                                model = item,
                            ),
                        )
                        val intent = NavigationIntent.NavigateToRoute(route)
                        navigate(intent)
                    },
                )
                emit(model)
            }
        }
    }

    val cipherIdentity = cipher.identity
    if (cipherIdentity != null) {
        val identityItem = kotlin.run {
            val actions = mutableListOf<FlatItemAction>()
            if (cipherIdentity.phone != null) {
                actions += FlatItemAction(
                    icon = Icons.Outlined.Call,
                    title = Res.string.vault_view_call_phone_action.wrap(),
                    onClick = {
                        val intent = NavigationIntent.NavigateToPhone(
                            phoneNumber = cipherIdentity.phone,
                        )
                        navigate(intent)
                    },
                )
                actions += FlatItemAction(
                    icon = Icons.Outlined.Textsms,
                    title = Res.string.vault_view_text_phone_action.wrap(),
                    onClick = {
                        val intent = NavigationIntent.NavigateToSms(
                            phoneNumber = cipherIdentity.phone,
                        )
                        navigate(intent)
                    },
                )
            }
            if (cipherIdentity.email != null) {
                actions += FlatItemAction(
                    icon = Icons.Outlined.Email,
                    title = Res.string.vault_view_email_action.wrap(),
                    onClick = {
                        val intent = NavigationIntent.NavigateToEmail(
                            email = cipherIdentity.email,
                        )
                        navigate(intent)
                    },
                )
            }
            if (
                cipherIdentity.address1 != null ||
                cipherIdentity.address2 != null ||
                cipherIdentity.address3 != null ||
                cipherIdentity.city != null ||
                cipherIdentity.state != null ||
                cipherIdentity.postalCode != null ||
                cipherIdentity.country != null
            ) {
                actions += FlatItemAction(
                    icon = Icons.Outlined.Directions,
                    title = Res.string.vault_view_navigate_action.wrap(),
                    onClick = {
                        val intent = NavigationIntent.NavigateToMaps(
                            address1 = cipherIdentity.address1,
                            address2 = cipherIdentity.address2,
                            address3 = cipherIdentity.address3,
                            city = cipherIdentity.city,
                            state = cipherIdentity.state,
                            postalCode = cipherIdentity.postalCode,
                            country = cipherIdentity.country,
                        )
                        navigate(intent)
                    },
                )
            }

            VaultViewItem.Identity(
                id = "identity",
                data = cipherIdentity,
                actions = actions,
            )
        }
        emit(identityItem)

        suspend fun yieldContactField(
            key: String,
            value: String?,
            title: String? = null,
            username: Boolean = false,
        ) {
            if (value == null) {
                return
            }

            val contactFieldItem = create(
                copy = copy,
                id = "identity.contact.$key",
                accountId = account.id,
                title = title,
                value = value,
                monospace = false,
                elevated = true,
                username = username,
            )
            emit(contactFieldItem)
        }

        if (
            cipherIdentity.phone != null ||
            cipherIdentity.email != null ||
            cipherIdentity.username != null
        ) {
            val contactHeaderItem = VaultViewItem.Section(
                id = "identity.contact.header",
                text = translate(Res.string.contact_info),
            )
            emit(contactHeaderItem)
        }

        yieldContactField("phone", cipherIdentity.phone, translate(Res.string.phone_number))
        yieldContactField("email", cipherIdentity.email, translate(Res.string.email))
        yieldContactField(
            "username",
            value = cipherIdentity.username,
            title = translate(Res.string.username),
            username = true,
        )

        suspend fun yieldMiscField(
            key: String,
            value: String?,
            title: String? = null,
            conceal: Boolean = false,
        ) {
            if (value == null) {
                return
            }

            val miscFieldItem = create(
                copy = copy,
                id = "identity.misc.$key",
                accountId = account.id,
                title = title,
                value = value,
                visibility = Visibility(
                    concealed = conceal,
                    transformUserEvent = visibilityGlobalUserTransform,
                    globalConfig = visibilityGlobalConfig,
                ),
                monospace = false,
                elevated = false,
            )
            emit(miscFieldItem)
        }

        if (
            cipherIdentity.company != null ||
            cipherIdentity.ssn != null ||
            cipherIdentity.passportNumber != null ||
            cipherIdentity.licenseNumber != null
        ) {
            val miscHeaderItem = VaultViewItem.Section(
                id = "identity.misc.header",
                text = translate(Res.string.misc),
            )
            emit(miscHeaderItem)
        }

        yieldMiscField("company", cipherIdentity.company, translate(Res.string.company))
        yieldMiscField(
            "ssn",
            cipherIdentity.ssn,
            title = translate(Res.string.ssn),
            conceal = concealFields,
        )
        yieldMiscField(
            "passportNumber",
            cipherIdentity.passportNumber,
            title = translate(Res.string.passport_number),
            conceal = concealFields,
        )
        yieldMiscField(
            "licenseNumber",
            cipherIdentity.licenseNumber,
            title = translate(Res.string.license_number),
            conceal = concealFields,
        )

        suspend fun yieldAddressField(
            key: String,
            value: String?,
            title: String? = null,
        ) {
            if (value == null) {
                return
            }

            val addressFieldModel = create(
                copy = copy,
                id = "identity.address.$key",
                accountId = account.id,
                title = title,
                value = value,
                monospace = false,
                elevated = false,
            )
            emit(addressFieldModel)
        }

        if (
            cipherIdentity.address1 != null ||
            cipherIdentity.address2 != null ||
            cipherIdentity.address3 != null ||
            cipherIdentity.city != null ||
            cipherIdentity.state != null ||
            cipherIdentity.postalCode != null ||
            cipherIdentity.country != null
        ) {
            val addressSectionModel = VaultViewItem.Section(
                id = "identity.address.header",
                text = translate(Res.string.address),
            )
            emit(addressSectionModel)
        }

        yieldAddressField("address1", cipherIdentity.address1)
        yieldAddressField("address2", cipherIdentity.address2)
        yieldAddressField("address3", cipherIdentity.address3)
        yieldAddressField("city", cipherIdentity.city, translate(Res.string.city))
        yieldAddressField("state", cipherIdentity.state, translate(Res.string.state))
        yieldAddressField(
            "postalCode",
            cipherIdentity.postalCode,
            translate(Res.string.postal_code),
        )
        yieldAddressField("country", cipherIdentity.country, translate(Res.string.country))
    }

    if (cipher.type == DSecret.Type.SecureNote && cipher.notes.isNotEmpty()) {
        val content = VaultViewItem.Note.Content.of(
            parser = markdownParser,
            markdown = markdown,
            text = cipher.notes,
        )
        val note = VaultViewItem.Note(
            id = "note.text",
            content = content,
            visibility = Visibility(
                concealed = verify != null,
                transformUserEvent = visibilityGlobalUserTransform,
                globalConfig = visibilityGlobalConfig,
            ),
        )
        emit(note)
    }

    val linkedApps = cipherUris
        .filter { holder ->
            holder
                .info
                .any { info ->
                    info is LinkInfoPlatform.Android ||
                            info is LinkInfoPlatform.IOS
                }
        }
    if (linkedApps.isNotEmpty()) {
        val sectionText = if (linkedApps.size > 1) {
            translate(Res.string.linked_apps)
        } else translate(Res.string.linked_app)
        val section = VaultViewItem.Section(
            id = "link.app",
            text = sectionText,
        )
        emit(section)
        // items
        linkedApps
            .mapIndexed { index, holder ->
                val id = "link.app.$index"
                val item = createUriItem(
                    canEdit = canEdit,
                    contentColor = contentColor,
                    disabledContentColor = disabledContentColor,
                    websiteIcons = websiteIcons,
                    cipherUnsecureUrlCheck = cipherUnsecureUrlCheck,
                    cipherUnsecureUrlAutoFix = cipherUnsecureUrlAutoFix,
                    getJustDeleteMeByUrl = getJustDeleteMeByUrl,
                    getJustGetMyDataByUrl = getJustGetMyDataByUrl,
                    executeCommand = executeCommand,
                    holder = holder,
                    id = id,
                    accountId = account.accountId(),
                    cipher = cipher,
                    copy = copy,
                )
                item
            }
            .forEach { item ->
                emit(item)
            }
    }

    val linkedWebsites = cipherUris
        .filter { holder ->
            holder
                .info
                .any { info ->
                    info is LinkInfoPlatform.Web ||
                            info is LinkInfoPlatform.Other
                }
        }
    if (linkedWebsites.isNotEmpty()) {
        val sectionText = if (linkedWebsites.size > 1) {
            translate(Res.string.linked_uris)
        } else translate(Res.string.linked_uri)
        val section = VaultViewItem.Section(
            id = "link.website",
            text = sectionText,
        )
        emit(section)
        // items
        linkedWebsites
            .mapIndexed { index, holder ->
                val id = "link.website.$index"
                val item = createUriItem(
                    canEdit = canEdit,
                    contentColor = contentColor,
                    disabledContentColor = disabledContentColor,
                    websiteIcons = websiteIcons,
                    cipherUnsecureUrlCheck = cipherUnsecureUrlCheck,
                    cipherUnsecureUrlAutoFix = cipherUnsecureUrlAutoFix,
                    getJustDeleteMeByUrl = getJustDeleteMeByUrl,
                    getJustGetMyDataByUrl = getJustGetMyDataByUrl,
                    executeCommand = executeCommand,
                    holder = holder,
                    id = id,
                    accountId = account.accountId(),
                    cipher = cipher,
                    copy = copy,
                )
                item
            }
            .forEach { item ->
                emit(item)
            }
    }
    if (cipher.fields.isNotEmpty()) {
        val sectionText = if (cipher.fields.size > 1) {
            translate(Res.string.custom_fields)
        } else translate(Res.string.custom_field)
        val section = VaultViewItem.Section(
            id = "custom",
            text = sectionText,
        )
        emit(section)
        // items
        cipher.fields.forEachIndexed { index, field ->
            if (field.type == DSecret.Field.Type.Boolean) {
                fun createAction(
                    value: Boolean,
                ) = FlatItemAction(
                    title = TextHolder.Res(Res.string.custom_field_toggle_boolean_value),
                    trailing = {
                        Switch(
                            checked = !value,
                            onCheckedChange = null,
                            enabled = canEdit,
                        )
                    },
                    onClick = if (canEdit) {
                        // lambda
                        {
                            val request = mapOf(
                                cipher.id to listOf(
                                    CipherFieldSwitchToggleRequest(
                                        fieldIndex = index,
                                        fieldName = field.name,
                                        value = value,
                                    ),
                                ),
                            )
                            cipherFieldSwitchToggle(request)
                                .launchIn(appScope)
                        }
                    } else {
                        null
                    },
                )

                val value = field.value?.toBooleanStrictOrNull() ?: false
                val actions = listOf(
                    createAction(!value),
                )
                val m = VaultViewItem.Switch(
                    id = "custom.$index",
                    title = field.name.orEmpty(),
                    value = value,
                    dropdown = actions,
                )
                emit(m)
                return@forEachIndexed
            }
            if (field.type == DSecret.Field.Type.Linked) {
                val t = annotate(
                    when (field.linkedId) {
                        DSecret.Field.LinkedId.Login_Username -> Res.string.field_linked_to_username
                        DSecret.Field.LinkedId.Login_Password -> Res.string.field_linked_to_password
                        DSecret.Field.LinkedId.Card_CardholderName -> Res.string.field_linked_to_card_cardholdername
                        DSecret.Field.LinkedId.Card_ExpMonth -> Res.string.field_linked_to_card_expmonth
                        DSecret.Field.LinkedId.Card_ExpYear -> Res.string.field_linked_to_card_expyear
                        DSecret.Field.LinkedId.Card_Code -> Res.string.field_linked_to_card_code
                        DSecret.Field.LinkedId.Card_Brand -> Res.string.field_linked_to_card_brand
                        DSecret.Field.LinkedId.Card_Number -> Res.string.field_linked_to_card_number
                        DSecret.Field.LinkedId.Identity_Title -> Res.string.field_linked_to_identity_title
                        DSecret.Field.LinkedId.Identity_MiddleName -> Res.string.field_linked_to_identity_middlename
                        DSecret.Field.LinkedId.Identity_Address1 -> Res.string.field_linked_to_identity_address1
                        DSecret.Field.LinkedId.Identity_Address2 -> Res.string.field_linked_to_identity_address2
                        DSecret.Field.LinkedId.Identity_Address3 -> Res.string.field_linked_to_identity_address3
                        DSecret.Field.LinkedId.Identity_City -> Res.string.field_linked_to_identity_city
                        DSecret.Field.LinkedId.Identity_State -> Res.string.field_linked_to_identity_state
                        DSecret.Field.LinkedId.Identity_PostalCode -> Res.string.field_linked_to_identity_postalcode
                        DSecret.Field.LinkedId.Identity_Country -> Res.string.field_linked_to_identity_country
                        DSecret.Field.LinkedId.Identity_Company -> Res.string.field_linked_to_identity_company
                        DSecret.Field.LinkedId.Identity_Email -> Res.string.field_linked_to_identity_email
                        DSecret.Field.LinkedId.Identity_Phone -> Res.string.field_linked_to_identity_phone
                        DSecret.Field.LinkedId.Identity_Ssn -> Res.string.field_linked_to_identity_ssn
                        DSecret.Field.LinkedId.Identity_Username -> Res.string.field_linked_to_identity_username
                        DSecret.Field.LinkedId.Identity_PassportNumber -> Res.string.field_linked_to_identity_passportnumber
                        DSecret.Field.LinkedId.Identity_LicenseNumber -> Res.string.field_linked_to_identity_licensenumber
                        DSecret.Field.LinkedId.Identity_FirstName -> Res.string.field_linked_to_identity_firstname
                        DSecret.Field.LinkedId.Identity_LastName -> Res.string.field_linked_to_identity_lastname
                        DSecret.Field.LinkedId.Identity_FullName -> Res.string.field_linked_to_identity_fullname
                        null -> Res.string.field_linked_to_unknown_field
                    },
                    field.name.orEmpty() to SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        color = contentColor,
                    ),
                )
                val m = VaultViewItem.Label(
                    id = "custom.$index",
                    text = t,
                )
                emit(m)
                return@forEachIndexed
            }

            val hidden = field.type == DSecret.Field.Type.Hidden
            val m = create(
                copy = copy,
                id = "custom.$index",
                accountId = account.id,
                title = field.name.orEmpty(),
                value = field.value.orEmpty(),
                visibility = Visibility(
                    concealed = hidden && concealFields,
                    transformUserEvent = visibilityGlobalUserTransform,
                    globalConfig = visibilityGlobalConfig,
                ),
            )
            emit(m)
        }
    }
    if (cipher.type != DSecret.Type.SecureNote && cipher.notes.isNotEmpty()) {
        val section = VaultViewItem.Section(
            id = "note",
            text = translate(Res.string.notes),
        )
        emit(section)
        val content = VaultViewItem.Note.Content.of(
            parser = markdownParser,
            markdown = markdown,
            text = cipher.notes,
        )
        val note = VaultViewItem.Note(
            id = "note.text",
            content = content,
            visibility = Visibility(
                concealed = verify != null,
                transformUserEvent = visibilityGlobalUserTransform,
                globalConfig = visibilityGlobalConfig,
            ),
        )
        emit(note)
    }
    if (cipher.attachments.isNotEmpty()) {
        val section = VaultViewItem.Section(
            id = "attachment",
            text = translate(Res.string.attachments),
        )
        emit(section)
        // items
        cipher.attachments.forEachIndexed { index, attachment ->
            when (attachment) {
                is DSecret.Attachment.Remote -> {
                    val downloadIo = kotlin.run {
                        val request = DownloadAttachmentRequest.ByLocalCipherAttachment(
                            cipher = cipher,
                            attachment = attachment,
                        )
                        downloadAttachment(listOf(request))
                    }
                    val removeIo = kotlin.run {
                        val request = RemoveAttachmentRequest.ByLocalCipherAttachment(
                            cipher = cipher,
                            attachment = attachment,
                        )
                        removeAttachment(listOf(request))
                    }

                    val actualItem = createAttachmentItem(
                        tag = DownloadInfoEntity2.AttachmentDownloadTag(
                            localCipherId = cipher.id,
                            remoteCipherId = cipher.service.remote?.id,
                            attachmentId = attachment.id,
                        ),
                        selectionHandle = selectionHandle,
                        sharingScope = sharingScope,
                        attachment = attachment,
                        launchViewCipherData = null,
                        downloadManager = downloadManager,
                        downloadIo = downloadIo,
                        removeIo = removeIo,
                        verify = verify,
                    )
                    val wrapperItem = VaultViewItem.Attachment(
                        id = actualItem.key,
                        item = actualItem,
                    )
                    emit(wrapperItem)
                }

                is DSecret.Attachment.Local -> {
                    val downloadIo = kotlin.run {
                        ioUnit()
                    }
                    val removeIo = kotlin.run {
                        ioUnit()
                    }

                    val actualItem = createAttachmentItem(
                        tag = DownloadInfoEntity2.AttachmentDownloadTag(
                            localCipherId = cipher.id,
                            remoteCipherId = cipher.service.remote?.id,
                            attachmentId = attachment.id,
                        ),
                        selectionHandle = selectionHandle,
                        sharingScope = sharingScope,
                        attachment = attachment,
                        launchViewCipherData = null,
                        downloadManager = downloadManager,
                        downloadIo = downloadIo,
                        removeIo = removeIo,
                        verify = verify,
                    )
                    val wrapperItem = VaultViewItem.Attachment(
                        id = actualItem.key,
                        item = actualItem,
                    )
                    emit(wrapperItem)
                }
            }
        }
    }

    if (cipher.tags.isNotEmpty()) {
        val sectionText = if (cipher.tags.size > 1) {
            translate(Res.string.tags)
        } else translate(Res.string.tag)
        val section = VaultViewItem.Section(
            id = "tags",
            text = sectionText,
        )
        emit(section)

        val f = VaultViewItem.Tags(
            id = "tag.0",
            tags = cipher.tags,
            onClick = { tag ->
                action {
                    val route = VaultRoute.by(
                        tag = tag,
                    )
                    val intent = NavigationIntent.NavigateToRoute(route)
                    navigate(intent)
                }
            },
        )
        emit(f)
    }

    if (folder != null) {
        val section = VaultViewItem.Section(
            id = "folder",
            text = translate(Res.string.folder),
        )
        emit(section)

        val f = VaultViewItem.Folder(
            id = "folder.0",
            nodes = folder.hierarchy
                .map {
                    VaultViewItem.Folder.FolderNode(
                        name = it.name,
                        onClick = onClick {
                            val route = VaultRoute.by(
                                folder = it.folder,
                            )
                            val intent = NavigationIntent.NavigateToRoute(route)
                            navigate(intent)
                        },
                    )
                },
            onClick = onClick {
                val route = VaultRoute.by(
                    folder = folder.folder,
                )
                val intent = NavigationIntent.NavigateToRoute(route)
                navigate(intent)
            },
        )
        emit(f)
    }

    if (collections.isNotEmpty()) {
        val section = VaultViewItem.Section(
            id = "collection",
            text =
                if (collections.size == 1) {
                    translate(Res.string.collection)
                } else {
                    translate(Res.string.collections)
                },
        )
        emit(section)
        collections.forEach { collection ->
            val f = VaultViewItem.Collection(
                id = "collection.${collection.id}",
                title = collection.name,
                onClick = onClick {
                    val route = VaultRoute.by(
                        collection = collection,
                    )
                    val intent = NavigationIntent.NavigateToRoute(route)
                    navigate(intent)
                },
            )
            emit(f)
        }
    }

    if (organization != null) {
        val section = VaultViewItem.Section(
            id = "organization",
            text = translate(Res.string.organization),
        )
        emit(section)
        val f = VaultViewItem.Organization(
            id = "organization.0",
            title = organization.name,
            onClick = {
                val intent = NavigationIntent.NavigateToRoute(
                    CollectionsRoute(
                        args = CollectionsRoute.Args(
                            accountId = organization.accountId.let(::AccountId),
                            organizationId = organization.id,
                        ),
                    ),
                )
                navigate(intent)
            },
        )
        emit(f)
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
    val createdDate = cipher.createdDate
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
                dateFormatter.formatDateTime(cipher.revisionDate),
            ),
        ),
    )
    emit(a)
    val deletedDate = cipher.deletedDate
    if (deletedDate != null) {
        val b = VaultViewItem.Label(
            id = "deleted",
            text = AnnotatedString(
                translate(
                    Res.string.vault_view_deleted_at_label,
                    dateFormatter.formatDateTime(deletedDate),
                ),
            ),
        )
        emit(b)
    }

    if (!isRelease && false) {
        val debugSpacer = VaultViewItem.Spacer(
            id = "debug.spacer",
            height = 24.dp,
        )
        emit(debugSpacer)
        val debugSection = VaultViewItem.Section(
            id = "debug.section",
            text = "Debug",
        )
        emit(debugSection)

        // IDs
        val localId = create(
            copy = copy,
            id = "debug.local_id",
            accountId = account.id,
            title = "Local ID",
            value = cipher.id,
        )
        emit(localId)
        val remoteId = create(
            copy = copy,
            id = "debug.remote_id",
            accountId = account.id,
            title = "Remote ID",
            value = cipher.service.remote?.id.orEmpty(),
        )
        emit(remoteId)
        val remoteRevDate = create(
            copy = copy,
            id = "debug.remote_rev_date",
            accountId = account.id,
            title = "Remote revision date",
            value = cipher.service.remote?.revisionDate
                ?.let { date ->
                    dateFormatter.formatDateTime(date)
                }
                .orEmpty(),
        )
        emit(remoteRevDate)
    }
}

private suspend fun RememberStateFlowScope.createUriItem(
    canEdit: Boolean,
    contentColor: Color,
    disabledContentColor: Color,
    websiteIcons: Boolean,
    cipherUnsecureUrlCheck: CipherUnsecureUrlCheck,
    cipherUnsecureUrlAutoFix: CipherUnsecureUrlAutoFix,
    getJustDeleteMeByUrl: GetJustDeleteMeByUrl,
    getJustGetMyDataByUrl: GetJustGetMyDataByUrl,
    executeCommand: ExecuteCommand,
    holder: Holder,
    id: String,
    accountId: String,
    cipher: DSecret,
    copy: CopyText,
): VaultViewItem.Uri {
    val cipherId = cipher.id
    val overrides = holder
        .overrides
        .map { data ->
            val title = data.override.name
            data.contentOrException.fold(
                ifLeft = { e ->
                    val parsedMessage = getErrorReadableMessage(
                        e,
                        translator = this,
                    )
                    val dropdown = buildContextItems {
                        this += ContextItem.Custom {
                            Column(
                                modifier = Modifier
                                    .padding(
                                        horizontal = Dimens.horizontalPadding,
                                        vertical = 8.dp,
                                    ),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Spacer(
                                    modifier = Modifier
                                        .height(12.dp),
                                )
                                Text(
                                    text = stringResource(Res.string.error_failed_format_placeholder),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Text(
                                    text = parsedMessage.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = LocalContentColor.current
                                        .combineAlpha(MediumEmphasisAlpha),
                                )
                            }
                        }
                    }
                    VaultViewItem.Uri.Override(
                        title = title,
                        text = parsedMessage.title,
                        error = true,
                        dropdown = dropdown,
                    )
                },
                ifRight = { content ->
                    val text = content.uri
                    val dropdown = createUriItemContextItems(
                        canEdit = false,
                        cipherUnsecureUrlCheck = cipherUnsecureUrlCheck,
                        cipherUnsecureUrlAutoFix = cipherUnsecureUrlAutoFix,
                        getJustDeleteMeByUrl = getJustDeleteMeByUrl,
                        getJustGetMyDataByUrl = getJustGetMyDataByUrl,
                        executeCommand = executeCommand,
                        uri = content.uri,
                        info = content.info,
                        cipherId = cipherId,
                        copy = copy,
                    )
                    VaultViewItem.Uri.Override(
                        title = title,
                        text = text,
                        error = false,
                        dropdown = dropdown,
                    )
                },
            )
        }

    val uri = holder.uri

    val matchTypeTitle = holder.uri.match
        ?.titleH()
        ?.let {
            translate(it)
        }

    val dropdown = createUriItemContextItems(
        canEdit = canEdit,
        cipherUnsecureUrlCheck = cipherUnsecureUrlCheck,
        cipherUnsecureUrlAutoFix = cipherUnsecureUrlAutoFix,
        getJustDeleteMeByUrl = getJustDeleteMeByUrl,
        getJustGetMyDataByUrl = getJustGetMyDataByUrl,
        executeCommand = executeCommand,
        uri = holder.uri.uri,
        info = holder.info,
        cipherId = cipherId,
        copy = copy,
    )

    val platformMarker = holder.info
        .firstOrNull { it is LinkInfoPlatform } as LinkInfoPlatform?
    when (platformMarker) {
        is LinkInfoPlatform.Android -> {
            val androidMarker = holder.info
                .firstOrNull { it is LinkInfoAndroid } as LinkInfoAndroid?
            when (androidMarker) {
                is LinkInfoAndroid.Installed -> {
                    return VaultViewItem.Uri(
                        id = id,
                        icon = {
                            Image(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape),
                                painter = androidMarker.icon,
                                contentDescription = null,
                            )
                        },
                        title = AnnotatedString(androidMarker.label),
                        matchTypeTitle = matchTypeTitle,
                        dropdown = dropdown,
                        overrides = overrides,
                    )
                }

                else -> {
                    return VaultViewItem.Uri(
                        id = id,
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Android,
                                contentDescription = null,
                            )
                        },
                        title = AnnotatedString(platformMarker.packageName),
                        matchTypeTitle = matchTypeTitle,
                        dropdown = dropdown,
                        overrides = overrides,
                    )
                }
            }
        }

        is LinkInfoPlatform.IOS -> {
            return VaultViewItem.Uri(
                id = id,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.PhoneIphone,
                        contentDescription = null,
                    )
                },
                title = AnnotatedString(platformMarker.packageName),
                matchTypeTitle = matchTypeTitle,
                dropdown = dropdown,
                overrides = overrides,
            )
        }

        is LinkInfoPlatform.Web -> {
            val url = platformMarker.url.toString()

            val isUnsecure = cipherUnsecureUrlCheck(holder.uri.uri) &&
                    !cipher.ignores(DWatchtowerAlertType.UNSECURE_WEBSITE)
            val faviconUrl = FaviconUrl(
                serverId = accountId,
                url = url,
            ).takeIf { websiteIcons }
            val warningTitle = translate(Res.string.uri_unsecure)
                .takeIf { isUnsecure }
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

                    val host = platformMarker.url.host
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
                warningTitle = warningTitle,
                matchTypeTitle = matchTypeTitle,
                dropdown = dropdown,
                overrides = overrides,
            )
        }

        else -> {
            val canLuanch = holder.info
                .firstNotNullOfOrNull { it as? LinkInfoLaunch.Allow }
            val canExecute = holder.info
                .firstNotNullOfOrNull { it as? LinkInfoExecute.Allow }
            return VaultViewItem.Uri(
                id = id,
                icon = {
                    if (canLuanch != null && canLuanch.apps.size == 1) {
                        val icon = canLuanch.apps.first().icon
                        if (icon != null) {
                            IconBox2(
                                main = {
                                    Image(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape),
                                        painter = icon,
                                        contentDescription = null,
                                    )
                                },
                                secondary = {
                                    Icon(
                                        imageVector = Icons.Outlined.Link,
                                        contentDescription = null,
                                    )
                                },
                            )
                        } else if (canExecute != null) {
                            Icon(
                                imageVector = Icons.Outlined.Terminal,
                                contentDescription = null,
                            )
                        } else {
                            Icon(Icons.Outlined.Link, null)
                        }
                    } else if (canExecute != null) {
                        Icon(
                            imageVector = Icons.Outlined.Terminal,
                            contentDescription = null,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Link,
                            contentDescription = null,
                        )
                    }
                },
                title = when (uri.match) {
                    DSecret.Uri.MatchType.RegularExpression -> {
                        colorizePassword(uri.uri, contentColor)
                    }

                    else -> {
                        kotlin.runCatching {
                            val url = uri.uri
                            val host = URI(url).host

                            buildAnnotatedString {
                                append(uri.uri)

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
                            }
                        }.getOrElse {
                            AnnotatedString(uri.uri)
                        }
                    }
                },
                matchTypeTitle = matchTypeTitle,
                dropdown = dropdown,
                overrides = overrides,
            )
        }
    }
}

private suspend fun RememberStateFlowScope.createUriItemContextItems(
    canEdit: Boolean,
    cipherUnsecureUrlCheck: CipherUnsecureUrlCheck,
    cipherUnsecureUrlAutoFix: CipherUnsecureUrlAutoFix,
    getJustDeleteMeByUrl: GetJustDeleteMeByUrl,
    getJustGetMyDataByUrl: GetJustGetMyDataByUrl,
    executeCommand: ExecuteCommand,
    uri: String,
    info: List<LinkInfo>,
    cipherId: String,
    copy: CopyText,
): List<ContextItem> {
    val platformMarker = info
        .firstOrNull { it is LinkInfoPlatform } as LinkInfoPlatform?
    when (platformMarker) {
        is LinkInfoPlatform.Android -> {
            val androidMarker = info
                .firstOrNull { it is LinkInfoAndroid } as LinkInfoAndroid?
            when (androidMarker) {
                is LinkInfoAndroid.Installed -> {
                    val dropdown = buildContextItems {
                        section {
                            this += copy.FlatItemAction(
                                title = Res.string.copy_package_name.wrap(),
                                value = platformMarker.packageName,
                            )
                        }
                        section {
                            this += FlatItemAction(
                                leading = {
                                    Image(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape),
                                        painter = androidMarker.icon,
                                        contentDescription = null,
                                    )
                                },
                                title = Res.string.uri_action_launch_app_title.wrap(),
                                trailing = {
                                    ChevronIcon()
                                },
                                onClick = {
                                    val intent =
                                        NavigationIntent.NavigateToApp(platformMarker.packageName)
                                    navigate(intent)
                                },
                            )
                            this += FlatItemAction(
                                icon = Icons.AutoMirrored.Outlined.Launch,
                                title = Res.string.uri_action_launch_play_store_title.wrap(),
                                trailing = {
                                    ChevronIcon()
                                },
                                onClick = {
                                    val intent =
                                        NavigationIntent.NavigateToBrowser(platformMarker.playStoreUrl)
                                    navigate(intent)
                                },
                            )
                        }
                        section {
                            this += BarcodeTypeRoute.showInBarcodeTypeActionOrNull(
                                translator = this@createUriItemContextItems,
                                data = platformMarker.playStoreUrl,
                                single = true,
                                navigate = ::navigate,
                            )
                            this += createShareAction(
                                translator = this@createUriItemContextItems,
                                text = platformMarker.playStoreUrl,
                                navigate = ::navigate,
                            )
                        }
                    }
                    return dropdown
                }

                else -> {
                    val dropdown = buildContextItems {
                        section {
                            this += copy.FlatItemAction(
                                title = Res.string.copy_package_name.wrap(),
                                value = platformMarker.packageName,
                            )
                        }
                        section {
                            this += FlatItemAction(
                                icon = Icons.AutoMirrored.Outlined.Launch,
                                title = Res.string.uri_action_launch_play_store_title.wrap(),
                                trailing = {
                                    ChevronIcon()
                                },
                                onClick = {
                                    val intent =
                                        NavigationIntent.NavigateToBrowser(platformMarker.playStoreUrl)
                                    navigate(intent)
                                },
                            )
                        }
                        section {
                            this += BarcodeTypeRoute.showInBarcodeTypeActionOrNull(
                                translator = this@createUriItemContextItems,
                                data = platformMarker.playStoreUrl,
                                single = true,
                                navigate = ::navigate,
                            )
                            this += createShareAction(
                                translator = this@createUriItemContextItems,
                                text = platformMarker.playStoreUrl,
                                navigate = ::navigate,
                            )
                        }
                    }
                    return dropdown
                }
            }
        }

        is LinkInfoPlatform.IOS -> {
            val dropdown = buildContextItems {
                section {
                    this += copy.FlatItemAction(
                        title = Res.string.copy_package_name.wrap(),
                        value = platformMarker.packageName,
                    )
                }
            }
            return dropdown
        }

        is LinkInfoPlatform.Web -> {
            val url = platformMarker.url.toString()

            val isJustDeleteMe = getJustDeleteMeByUrl(url)
                .attempt()
                .bind()
                .getOrNull()
            val isJustGetMyData = getJustGetMyDataByUrl(url)
                .attempt()
                .bind()
                .getOrNull()

            val isUnsecure = cipherUnsecureUrlCheck(uri)
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
                    if (
                        url.removeSuffix("/") !=
                        platformMarker.frontPageUrl.toString().removeSuffix("/")
                    ) {
                        val launchUrl = platformMarker.frontPageUrl.toString()
                        this += FlatItemAction(
                            icon = Icons.AutoMirrored.Outlined.Launch,
                            title = Res.string.uri_action_launch_browser_main_page_title.wrap(),
                            text = TextHolder.Value(launchUrl),
                            trailing = {
                                ChevronIcon()
                            },
                            onClick = {
                                val intent = NavigationIntent.NavigateToBrowser(launchUrl)
                                navigate(intent)
                            },
                        )
                    }
                }
                if (isUnsecure && canEdit) {
                    section {
                        this += FlatItemAction(
                            icon = Icons.Outlined.AutoAwesome,
                            title = Res.string.uri_action_autofix_unsecure_title.wrap(),
                            text = Res.string.uri_action_autofix_unsecure_text.wrap(),
                            onClick = {
                                val ff = mapOf(
                                    cipherId to setOf(uri),
                                )
                                cipherUnsecureUrlAutoFix(ff)
                                    .launchIn(appScope)
                            },
                        )
                    }
                }
                section {
                    this += BarcodeTypeRoute.showInBarcodeTypeActionOrNull(
                        translator = this@createUriItemContextItems,
                        data = uri,
                        single = true,
                        navigate = ::navigate,
                    )
                    this += createShareAction(
                        translator = this@createUriItemContextItems,
                        text = uri,
                        navigate = ::navigate,
                    )
                }
                section {
                    this += WebsiteLeakRoute.checkBreachesWebsiteActionOrNull(
                        translator = this@createUriItemContextItems,
                        host = platformMarker.url.host,
                        navigate = ::navigate,
                    )
                    if (isJustGetMyData != null) {
                        this += JustGetMyDataViewDialogRoute.actionOrNull(
                            translator = this@createUriItemContextItems,
                            justGetMyData = isJustGetMyData,
                            navigate = ::navigate,
                        )
                    }
                    if (isJustDeleteMe != null) {
                        this += JustDeleteMeServiceViewDialogRoute.actionOrNull(
                            translator = this@createUriItemContextItems,
                            justDeleteMe = isJustDeleteMe,
                            navigate = ::navigate,
                        )
                    }
                }
            }
            return dropdown
        }

        else -> {
            val canLuanch = info
                .firstNotNullOfOrNull { it as? LinkInfoLaunch.Allow }
            val canExecute = info
                .firstNotNullOfOrNull { it as? LinkInfoExecute.Allow }
            val dropdown = buildContextItems {
                section {
                    this += copy.FlatItemAction(
                        title = Res.string.copy.wrap(),
                        value = uri,
                    )
                }
                section {
                    if (canExecute != null) {
                        this += FlatItemAction(
                            icon = Icons.Outlined.Terminal,
                            title = Res.string.execute_command.wrap(),
                            trailing = {
                                ChevronIcon()
                            },
                            onClick = {
                                executeCommand(canExecute.command)
                                    .launchIn(appScope)
                            },
                        )
                    }
                    if (canLuanch != null) {
                        if (canLuanch.apps.size > 1) {
                            this += FlatItemAction(
                                icon = Icons.AutoMirrored.Outlined.Launch,
                                title = Res.string.uri_action_launch_in_smth_title.wrap(),
                                trailing = {
                                    ChevronIcon()
                                },
                                onClick = {
                                    val intent = NavigationIntent.NavigateToBrowser(uri)
                                    navigate(intent)
                                },
                            )
                        } else {
                            val icon = canLuanch.apps.first().icon
                            this += FlatItemAction(
                                leading = {
                                    if (icon != null) {
                                        Image(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape),
                                            painter = icon,
                                            contentDescription = null,
                                        )
                                    } else {
                                        Icon(Icons.AutoMirrored.Outlined.Launch, null)
                                    }
                                },
                                title = translate(
                                    Res.string.uri_action_launch_in_app_title,
                                    canLuanch.apps.first().label,
                                ).let(TextHolder::Value),
                                trailing = {
                                    ChevronIcon()
                                },
                                onClick = {
                                    val intent = NavigationIntent.NavigateToBrowser(uri)
                                    navigate(intent)
                                },
                            )
                        }
                    }
                }
                section {
                    this += LargeTypeRoute.showInLargeTypeActionOrNull(
                        translator = this@createUriItemContextItems,
                        text = uri,
                        colorize = true,
                        navigate = ::navigate,
                    )
                    this += LargeTypeRoute.showInLargeTypeActionAndLockOrNull(
                        translator = this@createUriItemContextItems,
                        text = uri,
                        colorize = true,
                        navigate = ::navigate,
                    )
                    this += BarcodeTypeRoute.showInBarcodeTypeActionOrNull(
                        translator = this@createUriItemContextItems,
                        data = uri,
                        single = true,
                        navigate = ::navigate,
                    )
                    this += createShareAction(
                        translator = this@createUriItemContextItems,
                        text = uri,
                        navigate = ::navigate,
                    )
                }
            }
            return dropdown
        }
    }
}

suspend fun RememberStateFlowScope.create(
    copy: CopyText,
    id: String,
    accountId: AccountId,
    title: String?,
    value: String,
    shortcut: KeyShortcut? = null,
    visibility: Visibility = Visibility(),
    badge: VaultViewItem.Value.Badge? = null,
    badge2: List<StateFlow<VaultViewItem.Value.Badge?>> = emptyList(),
    leading: (@Composable RowScope.() -> Unit)? = null,
    verify: ((() -> Unit) -> Unit)? = null,
    onBuildActions: (ContextItemBuilder.() -> Unit)? = null,
    maxLines: Int = 64,
    password: Boolean = false,
    username: Boolean = false,
    monospace: Boolean = false,
    colorize: Boolean = false,
    elevated: Boolean = false,
): VaultViewItem {
    val dropdown = if (!visibility.hidden) {
        buildContextItems {
            section {
                this += copy.FlatItemAction(
                    title = Res.string.copy.wrap(),
                    value = value,
                    shortcut = shortcut,
                    hidden = visibility.concealed,
                )
            }
            if (onBuildActions != null) {
                section {
                    onBuildActions()
                }
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
                    text = value,
                    navigate = ::navigate,
                )
            }
            section {
                val isEmail = REGEX_EMAIL.matches(value)
                if (isEmail) {
                    this += EmailLeakRoute.checkBreachesEmailActionOrNull(
                        translator = this@create,
                        accountId = accountId,
                        email = value,
                        navigate = ::navigate,
                    )
                } else if (username) {
                    this += EmailLeakRoute.checkBreachesUsernameActionOrNull(
                        translator = this@create,
                        accountId = accountId,
                        username = value,
                        navigate = ::navigate,
                    )
                } else if (password) {
                    this += PasswordLeakRoute.checkBreachesPasswordAction(
                        translator = this@create,
                        password = value,
                        navigate = ::navigate,
                    )
                }
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
        maxLines = maxLines,
        visibility = visibility,
        monospace = monospace,
        colorize = colorize,
        leading = leading,
        badge = badge,
        badge2 = badge2,
        dropdown = dropdown.verify(verify),
    )
}

suspend fun RememberStateFlowScope.createExpDate(
    copy: CopyText,
    id: String,
    title: String?,
    month: String?,
    year: String?,
    elevated: Boolean = false,
): VaultViewItem {
    val monthFormatted = month ?: "mm"
    val yearFormatted = year ?: "yyyy"
    // Compose the value of a date, raw value
    // doesn't contain the / symbol because it
    // usually is inserted automatically.
    val valueRaw = kotlin.run {
        val monthCopy = monthFormatted.takeLast(2).padStart(2, '0')
        val yearCopy = yearFormatted.takeLast(2)
        "$monthCopy$yearCopy"
    }
    val valueFormatted = "$monthFormatted/$yearFormatted"

    val dropdown = buildContextItems {
        section {
            this += copy.FlatItemAction(
                title = Res.string.copy.wrap(),
                value = valueRaw,
            )
            this += copy.FlatItemAction(
                title = Res.string.copy_expiration_month.wrap(),
                value = month,
                type = CopyText.Type.CARD_EXP_MONTH,
            )
            this += copy.FlatItemAction(
                title = Res.string.copy_expiration_year.wrap(),
                value = year,
                type = CopyText.Type.CARD_EXP_YEAR,
            )
        }
    }
    return VaultViewItem.Value(
        id = id,
        elevation = if (elevated) 1.dp else 0.dp,
        title = title,
        value = valueFormatted,
        monospace = false,
        colorize = false,
        leading = null,
        badge = null,
        badge2 = emptyList(),
        dropdown = dropdown,
    )
}

private suspend fun RememberStateFlowScope.create(
    copy: CopyText,
    copyShortcut: KeyShortcut?,
    id: String,
    verify: ((() -> Unit) -> Unit)? = null,
    visibility: Visibility,
    name: String,
    data: DSecret.Card,
): VaultViewItem {
    val dropdown = buildContextItems {
        section {
            this += copy.FlatItemAction(
                title = Res.string.copy_card_number.wrap(),
                value = data.number,
                shortcut = copyShortcut,
                hidden = visibility.concealed,
                type = CopyText.Type.CARD_NUMBER,
            )?.verify(verify)
            this += copy.FlatItemAction(
                title = Res.string.copy_cardholder_name.wrap(),
                value = data.cardholderName,
                type = CopyText.Type.CARD_CARDHOLDER_NAME,
            )
        }
        section {
            val cardNumber = data.number
            if (cardNumber != null) {
                val formattedCardNumber = formatCardNumber(cardNumber)
                this += LargeTypeRoute.showInLargeTypeActionOrNull(
                    translator = this@create,
                    text = formattedCardNumber,
                    split = true,
                    navigate = ::navigate,
                )?.verify(verify)
                this += LargeTypeRoute.showInLargeTypeActionAndLockOrNull(
                    translator = this@create,
                    text = formattedCardNumber,
                    split = true,
                    navigate = ::navigate,
                )?.verify(verify)
                this += BarcodeTypeRoute.showInBarcodeTypeActionOrNull(
                    translator = this@create,
                    data = cardNumber,
                    format = BarcodeImageFormat.QR_CODE,
                    navigate = ::navigate,
                )?.verify(verify)
                this += createShareAction(
                    translator = this@create,
                    text = cardNumber,
                    navigate = ::navigate,
                )?.verify(verify)
                this += createSendActionOrNull(
                    translator = this@create,
                    name = name,
                    text = cardNumber,
                    navigate = ::navigate,
                )?.verify(verify)
            }
        }
    }
    return VaultViewItem.Card(
        id = id,
        data = data,
        dropdown = dropdown,
        visibility = visibility,
        elevation = 1.dp,
    )
}

@JvmName("verifyContextItemList")
fun List<ContextItem>.verify(
    verify: ((() -> Unit) -> Unit)? = null,
): List<ContextItem> = this
    .map {
        when (it) {
            is ContextItem.Section -> it
            is ContextItem.Custom -> it
            is FlatItemAction -> it.verify(verify)
        }
    }

fun List<FlatItemAction>.verify(
    verify: ((() -> Unit) -> Unit)? = null,
) = this
    .map {
        it.verify(verify)
    }

fun FlatItemAction.verify(
    verify: ((() -> Unit) -> Unit)? = null,
) = if (verify != null) {
    val defaultOnClick = onClick
    val protectedOnClick = if (defaultOnClick != null) {
        // lambda
        {
            verify.invoke {
                defaultOnClick()
            }
        }
    } else {
        null
    }
    this.copy(
        onClick = protectedOnClick,
    )
} else {
    this
}
