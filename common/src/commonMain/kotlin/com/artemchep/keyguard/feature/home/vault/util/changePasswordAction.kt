package com.artemchep.keyguard.feature.home.vault.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNotifications
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Merge
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import com.artemchep.keyguard.common.io.biFlatTap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.common.model.DWatchtowerAlertType
import com.artemchep.keyguard.common.model.FolderOwnership2
import com.artemchep.keyguard.common.model.PatchWatchtowerAlertCipherRequest
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.usecase.ChangeCipherNameById
import com.artemchep.keyguard.common.usecase.ChangeCipherPasswordById
import com.artemchep.keyguard.common.usecase.CipherMerge
import com.artemchep.keyguard.common.usecase.CopyCipherById
import com.artemchep.keyguard.common.usecase.MoveCipherToFolderById
import com.artemchep.keyguard.common.usecase.PatchWatchtowerAlertCipher
import com.artemchep.keyguard.common.usecase.RePromptCipherById
import com.artemchep.keyguard.common.usecase.RemoveCipherById
import com.artemchep.keyguard.common.usecase.RestoreCipherById
import com.artemchep.keyguard.common.usecase.TrashCipherById
import com.artemchep.keyguard.common.util.StringComparatorIgnoreCase
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.folder.FolderConfirmationResult
import com.artemchep.keyguard.feature.confirmation.folder.FolderConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.organization.FolderInfo
import com.artemchep.keyguard.feature.confirmation.organization.OrganizationConfirmationResult
import com.artemchep.keyguard.feature.confirmation.organization.OrganizationConfirmationRoute
import com.artemchep.keyguard.feature.export.ExportRoute
import com.artemchep.keyguard.feature.home.vault.add.AddRoute
import com.artemchep.keyguard.feature.home.vault.add.LeAddRoute
import com.artemchep.keyguard.feature.home.vault.screen.VaultViewPasswordHistoryRoute
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.translate
import com.artemchep.keyguard.feature.send.add.SendAddRoute
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.AnimatedTotalCounterBadge
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.icons.iconSmall
import kotlinx.coroutines.delay
import kotlin.uuid.Uuid

fun RememberStateFlowScope.cipherEnableConfirmAccessAction(
    rePromptCipherById: RePromptCipherById,
    ciphers: List<DSecret>,
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    FlatItemAction(
        icon = Icons.Filled.Lock,
        title = Res.string.ciphers_action_enable_auth_reprompt_title.wrap(),
        onClick = {
            before?.invoke()

            val filteredCipherIds = ciphers
                .asSequence()
                .filter { !it.reprompt }
                .map { it.id }
                .toSet()
            rePromptCipherById(
                filteredCipherIds,
                true,
            )
                .launchIn(appScope)
        },
    )
}

fun RememberStateFlowScope.cipherDisableConfirmAccessAction(
    rePromptCipherById: RePromptCipherById,
    ciphers: List<DSecret>,
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    FlatItemAction(
        icon = Icons.Outlined.LockOpen,
        title = Res.string.ciphers_action_disable_auth_reprompt_title.wrap(),
        onClick = {
            before?.invoke()

            val filteredCipherIds = ciphers
                .asSequence()
                .filter { it.reprompt }
                .map { it.id }
                .toSet()
            rePromptCipherById(
                filteredCipherIds,
                false,
            )
                .biFlatTap(
                    ifException = {
                        ioEffect { after?.invoke(false) }
                    },
                    ifSuccess = {
                        ioEffect { after?.invoke(true) }
                    },
                )
                .launchIn(appScope)
        },
    )
}

fun RememberStateFlowScope.cipherEditAction(
    cipher: DSecret,
    behavior: AddRoute.Args.Behavior = AddRoute.Args.Behavior(),
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    val icon = icon(Icons.Outlined.Edit)
    val title = Res.string.ciphers_action_edit_title.wrap()
    FlatItemAction(
        leading = icon,
        title = title,
        onClick = {
            before?.invoke()

            val route = LeAddRoute(
                args = AddRoute.Args(
                    behavior = behavior,
                    initialValue = cipher,
                ),
            )
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
            // TODO: Where do I put 'after' callback?
        },
    )
}

fun RememberStateFlowScope.cipherMergeIntoAction(
    cipherMerge: CipherMerge,
    ciphers: List<DSecret>,
    behavior: AddRoute.Args.Behavior = AddRoute.Args.Behavior(),
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    val icon = icon(Icons.Outlined.Merge)
    val title = Res.string.ciphers_action_merge_title.wrap()
    FlatItemAction(
        leading = icon,
        title = title,
        onClick = {
            before?.invoke()
            cipherMergeInto(
                cipherMerge = cipherMerge,
                ciphers = ciphers,
                behavior = behavior,
            )
            // TODO: Where do I put 'after' callback?
        },
    )
}

fun RememberStateFlowScope.cipherMergeInto(
    cipherMerge: CipherMerge,
    ciphers: List<DSecret>,
    behavior: AddRoute.Args.Behavior = AddRoute.Args.Behavior(),
) = kotlin.run {
    val cipher = cipherMerge.invoke(ciphers)
    val type = ciphers.firstOrNull()?.type
    val route = LeAddRoute(
        args = AddRoute.Args(
            behavior = behavior,
            initialValue = cipher,
            ownershipRo = false,
            type = type,
            merge = AddRoute.Args.Merge(
                ciphers = ciphers,
            ),
        ),
    )
    val intent = NavigationIntent.NavigateToRoute(route)
    navigate(intent)
}

fun RememberStateFlowScope.cipherExportAction(
    ciphers: List<DSecret>,
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    val iconImageVector = Icons.Outlined.SaveAlt
    val title = Res.string.ciphers_action_export_title.wrap()
    FlatItemAction(
        icon = iconImageVector,
        title = title,
        onClick = {
            before?.invoke()

            val route = ExportRoute(
                args = ExportRoute.Args(
                    filter = DFilter.Or(
                        filters = ciphers
                            .map { cipher ->
                                DFilter.ById(
                                    id = cipher.id,
                                    what = DFilter.ById.What.CIPHER,
                                )
                            },
                    ),
                ),
            )
            val intent = NavigationIntent.NavigateToRoute(route)
            ExportRoute.navigate(
                intent = intent,
                navigate = ::navigate,
            )
        },
    )
}

fun RememberStateFlowScope.cipherSendAction(
    ciphers: List<DSecret>,
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    data class SendItem(
        val name: TextHolder,
        val value: String,
    )

    data class SendItemTranslated(
        val name: String,
        val value: String,
    )

    fun createSendItemOrNull(
        name: TextHolder,
        value: String?,
    ): SendItem? {
        if (value.isNullOrBlank()) {
            return null
        }
        return SendItem(
            name = name,
            value = value,
        )
    }

    suspend fun SequenceScope<SendItem?>.yieldCipherLoginItems(login: DSecret.Login?) {
        if (login == null) return
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.username),
            value = login.username,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.password),
            value = login.password,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.one_time_password_authenticator_key),
            value = login.totp?.raw.orEmpty(),
        ).let { yield(it) }
    }

    suspend fun SequenceScope<SendItem?>.yieldCipherCardItems(card: DSecret.Card?) {
        if (card == null) return
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.cardholder_name),
            value = card.cardholderName,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.card_type),
            value = card.brand,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.card_number),
            value = card.number,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.card_expiry_month),
            value = card.expMonth,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.card_expiry_year),
            value = card.expYear,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.card_cvv),
            value = card.code,
        ).let { yield(it) }
    }

    suspend fun SequenceScope<SendItem?>.yieldCipherIdentityItems(identity: DSecret.Identity?) {
        if (identity == null) return
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.identity_title),
            value = identity.title,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.identity_first_name),
            value = identity.firstName,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.identity_middle_name),
            value = identity.middleName,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.identity_last_name),
            value = identity.lastName,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.address1),
            value = identity.address1,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.address2),
            value = identity.address2,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.address3),
            value = identity.address3,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.city),
            value = identity.city,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.state),
            value = identity.state,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.postal_code),
            value = identity.postalCode,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.country),
            value = identity.country,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.company),
            value = identity.company,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.email),
            value = identity.email,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.phone_number),
            value = identity.phone,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.ssn),
            value = identity.ssn,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.username),
            value = identity.username,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.passport_number),
            value = identity.passportNumber,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.license_number),
            value = identity.licenseNumber,
        ).let { yield(it) }
    }

    suspend fun SequenceScope<SendItem?>.yieldCipherSshKeyItems(sshKey: DSecret.SshKey?) {
        if (sshKey == null) return
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.private_key),
            value = sshKey.privateKey,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.public_key),
            value = sshKey.publicKey,
        ).let { yield(it) }
        createSendItemOrNull(
            name = TextHolder.Res(Res.string.fingerprint),
            value = sshKey.fingerprint,
        ).let { yield(it) }
    }

    suspend fun SequenceScope<SendItem?>.yieldCipherItems(cipher: DSecret) {
        yieldCipherLoginItems(cipher.login)
        yieldCipherCardItems(cipher.card)
        yieldCipherIdentityItems(cipher.identity)
        yieldCipherSshKeyItems(cipher.sshKey)

        // Urls
        cipher.uris.forEach { uri ->
            val item = SendItem(
                name = TextHolder.Res(Res.string.uri),
                value = uri.uri,
            )
            yield(item)
        }

        // Custom fields
        cipher.fields.forEach { field ->
            val item = createSendItemOrNull(
                name = TextHolder.Value(field.name.orEmpty()),
                value = field.value,
            )
            yield(item)
        }

        // Note
        val noteItem = createSendItemOrNull(
            name = TextHolder.Res(Res.string.notes),
            value = cipher.notes,
        )
        yield(noteItem)
    }

    val sendTitle = ciphers
        .joinToString { cipher -> cipher.name }
    FlatItemAction(
        leading = iconSmall(Icons.AutoMirrored.Outlined.Send, Icons.Outlined.Add),
        title = Res.string.text_action_send_title.wrap(),
        onClick = onClick {
            before?.invoke()

            val fields = sequence {
                // Add each of the cipher items to the
                // cipher builder.
                ciphers.forEach { cipher ->
                    yieldCipherItems(cipher)
                }
            }
                .filterNotNull()
                .toList()
                .map {
                    SendItemTranslated(
                        name = translate(it.name),
                        value = it.value,
                    )
                }
                .associateBy {
                    Uuid.random().toString()
                }
            val items = fields
                .map { (key, value) ->
                    ConfirmationRoute.Args.Item.BooleanItem(
                        key = key,
                        title = value.name,
                        text = value.value,
                        value = false,
                    )
                }

            val route = registerRouteResultReceiver(
                route = ConfirmationRoute(
                    args = ConfirmationRoute.Args(
                        icon = icon(Icons.AutoMirrored.Outlined.Send, Icons.Outlined.Add),
                        title = translate(Res.string.text_action_send_title),
                        subtitle = sendTitle,
                        items = items,
                    ),
                ),
            ) { result ->
                if (result is ConfirmationResult.Confirm) {
                    val selectedFields = fields
                        .mapNotNull { (key, value) ->
                            val checked = result.data[key] as? Boolean
                                ?: return@mapNotNull null
                            value.takeIf { checked }
                        }

                    val sendText = selectedFields
                        .flatMap { (name, value) ->
                            sequence {
                                yield("$name:")
                                yield(value)
                                yield("")
                            }
                        }
                        .joinToString(separator = System.lineSeparator())
                        .trimEnd()
                    val args = SendAddRoute.Args(
                        type = DSend.Type.Text,
                        name = sendTitle,
                        text = sendText,
                    )
                    val route = SendAddRoute(args)
                    val intent = NavigationIntent.NavigateToRoute(route)
                    navigate(intent)
                }

                val success = result is ConfirmationResult.Confirm
                after?.invoke(success)
            }
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )
}

fun RememberStateFlowScope.cipherCopyToAction(
    copyCipherById: CopyCipherById,
    ciphers: List<DSecret>,
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    val iconImageVector = Icons.Outlined.FileCopy
    val title = Res.string.ciphers_action_copy_title.wrap()
    FlatItemAction(
        icon = iconImageVector,
        title = title,
        onClick = onClick {
            before?.invoke()

            val ciphersHaveAttachments = ciphers.any { it.attachments.isNotEmpty() }
            val note = when {
                ciphersHaveAttachments -> {
                    val text =
                        "Copying a cipher does not copy its attachments, this feature is work in progress."
                    SimpleNote(
                        text = text,
                        type = SimpleNote.Type.INFO,
                    )
                }

                else -> null
            }
            val route = registerRouteResultReceiver(
                route = OrganizationConfirmationRoute(
                    args = OrganizationConfirmationRoute.Args(
                        decor = OrganizationConfirmationRoute.Args.Decor(
                            title = translate(title),
                            note = note,
                            icon = iconImageVector,
                        ),
                    ),
                ),
            ) { result ->
                if (result is OrganizationConfirmationResult.Confirm) {
                    val ownership = CreateRequest.Ownership2(
                        accountId = result.accountId,
                        folder = result.folderId,
                        organizationId = result.organizationId,
                        collectionIds = result.collectionsIds,
                    )
                    val cipherIdsToOwnership = ciphers
                        .associate { cipher ->
                            cipher.id to ownership
                        }
                    copyCipherById(cipherIdsToOwnership)
                        .launchIn(appScope)
                }

                val success = result is OrganizationConfirmationResult.Confirm
                after?.invoke(success)
            }
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )
}

fun RememberStateFlowScope.cipherMoveToFolderAction(
    moveCipherToFolderById: MoveCipherToFolderById,
    accountId: AccountId,
    ciphers: List<DSecret>,
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    val icon = icon(Icons.Outlined.Folder)
    val title = Res.string.ciphers_action_change_folder_title.wrap()
    FlatItemAction(
        leading = icon,
        title = title,
        onClick = {
            before?.invoke()

            val blacklistedFolderIds = ciphers
                .asSequence()
                .map { it.folderId }
                .toSet()
                // only if has one value
                .takeIf { it.size == 1 }
                .orEmpty()
            val route = registerRouteResultReceiver(
                route = FolderConfirmationRoute(
                    args = FolderConfirmationRoute.Args(
                        accountId = accountId,
                        blacklistedFolderIds = blacklistedFolderIds,
                    ),
                ),
            ) { result ->
                if (result is FolderConfirmationResult.Confirm) {
                    // Filter out the ciphers that already have the
                    // target folder. This is just to speed up the
                    // change process.
                    val cipherIds = when (val info = result.folderInfo) {
                        is FolderInfo.None,
                        is FolderInfo.Id,
                            -> {
                            val newFolderId = when (info) {
                                is FolderInfo.None -> null
                                is FolderInfo.Id -> info.id
                                else -> error("Unreachable statement!")
                            }
                            ciphers
                                .asSequence()
                                .filter { it.folderId != newFolderId }
                        }

                        is FolderInfo.New -> {
                            ciphers
                                .asSequence()
                        }
                    }
                        // Just in case we messed up before, make sure that
                        // the ciphers all belong to the target account.
                        .filter { it.accountId == accountId.id }
                        .map { it.id }
                        .toSet()
                    val destination = FolderOwnership2(
                        accountId = accountId.id,
                        folder = result.folderInfo,
                    )
                    moveCipherToFolderById(
                        cipherIds,
                        destination,
                    )
                        .launchIn(appScope)
                }

                val success = result is FolderConfirmationResult.Confirm
                after?.invoke(success)
            }
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )
}

fun RememberStateFlowScope.cipherChangeNameAction(
    changeCipherNameById: ChangeCipherNameById,
    ciphers: List<DSecret>,
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    val icon = icon(Icons.Outlined.Edit)
    val title = if (ciphers.size > 1) {
        Res.string.ciphers_action_change_names_title
    } else {
        Res.string.ciphers_action_change_name_title
    }.wrap()
    FlatItemAction(
        leading = icon,
        title = title,
        onClick = onClick {
            before?.invoke()

            val items = ciphers
                .sortedWith(StringComparatorIgnoreCase { it.name })
                .map { cipher ->
                    ConfirmationRoute.Args.Item.StringItem(
                        key = cipher.id,
                        value = cipher.name,
                        title = cipher.name,
                        type = ConfirmationRoute.Args.Item.StringItem.Type.Text,
                        canBeEmpty = false,
                    )
                }
            val route = registerRouteResultReceiver(
                route = ConfirmationRoute(
                    args = ConfirmationRoute.Args(
                        icon = icon,
                        title = translate(title),
                        items = items,
                    ),
                ),
            ) { result ->
                if (result is ConfirmationResult.Confirm) {
                    val cipherIdsToNames = result
                        .data
                        .mapValues { it.value as String }
                    changeCipherNameById(cipherIdsToNames)
                        .launchIn(appScope)
                }

                val success = result is ConfirmationResult.Confirm
                after?.invoke(success)
            }
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )
}

fun RememberStateFlowScope.cipherChangePasswordAction(
    changeCipherPasswordById: ChangeCipherPasswordById,
    ciphers: List<DSecret>,
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    val icon = icon(Icons.Outlined.Password)
    val title = kotlin.run {
        val hasPasswords = ciphers.any { !it.login?.password.isNullOrEmpty() }
        if (hasPasswords) {
            if (ciphers.size > 1) {
                Res.string.ciphers_action_change_passwords_title
            } else {
                Res.string.ciphers_action_change_password_title
            }
        } else {
            if (ciphers.size > 1) {
                Res.string.ciphers_action_add_passwords_title
            } else {
                Res.string.ciphers_action_add_password_title
            }
        }.wrap()
    }
    FlatItemAction(
        leading = icon,
        title = title,
        onClick = onClick {
            before?.invoke()

            val items = ciphers
                .filter { it.type == DSecret.Type.Login }
                .sortedWith(StringComparatorIgnoreCase { it.name })
                .map { cipher ->
                    ConfirmationRoute.Args.Item.StringItem(
                        key = cipher.id,
                        value = cipher.login?.password.orEmpty(),
                        title = cipher.name,
                        type = ConfirmationRoute.Args.Item.StringItem.Type.Password,
                        canBeEmpty = true, // so you can clear passwords
                    )
                }
            val route = registerRouteResultReceiver(
                route = ConfirmationRoute(
                    args = ConfirmationRoute.Args(
                        icon = icon,
                        title = translate(title),
                        message = translate(Res.string.generator_password_note, 16),
                        items = items,
                    ),
                ),
            ) { result ->
                if (result is ConfirmationResult.Confirm) {
                    val cipherIdsToPasswords = result
                        .data
                        .mapValues { it.value as String }
                    changeCipherPasswordById(cipherIdsToPasswords)
                        .launchIn(appScope)
                }

                val success = result is ConfirmationResult.Confirm
                after?.invoke(success)
            }
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
fun RememberStateFlowScope.cipherViewPasswordHistoryAction(
    cipher: DSecret,
    before: (() -> Unit)? = null,
) = kotlin.run {
    val title = Res.string.ciphers_action_view_password_history_title.wrap()
    FlatItemAction(
        leading = {
            BadgedBox(
                badge = {
                    val count = cipher.login?.passwordHistory?.size ?: 0
                    AnimatedTotalCounterBadge(
                        count = count,
                    )
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = null,
                )
            }
        },
        title = title,
        trailing = {
            ChevronIcon()
        },
        onClick = {
            val intent = NavigationIntent.NavigateToRoute(
                VaultViewPasswordHistoryRoute(
                    itemId = cipher.id,
                ),
            )
            navigate(intent)
        },
    )
}

fun RememberStateFlowScope.cipherTrashAction(
    trashCipherById: TrashCipherById,
    ciphers: List<DSecret>,
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    val icon = icon(Icons.Outlined.Delete)
    val title = Res.string.ciphers_action_trash_title.wrap()
    FlatItemAction(
        leading = icon,
        title = title,
        onClick = onClick {
            before?.invoke()

            val route = registerRouteResultReceiver(
                route = ConfirmationRoute(
                    args = ConfirmationRoute.Args(
                        icon = icon(Icons.Outlined.Delete),
                        title = translate(Res.string.ciphers_action_trash_confirmation_title.wrap()),
                        message = translate(Res.string.ciphers_action_trash_confirmation_text.wrap()),
                    ),
                ),
            ) { result ->
                if (result is ConfirmationResult.Confirm) {
                    val cipherIds = ciphers
                        .map { it.id }
                        .toSet()
                    trashCipherById(cipherIds)
                        .launchIn(appScope)
                }

                val success = result is ConfirmationResult.Confirm
                after?.invoke(success)
            }
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )
}

fun RememberStateFlowScope.cipherRestoreAction(
    restoreCipherById: RestoreCipherById,
    ciphers: List<DSecret>,
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    val icon = icon(Icons.Outlined.RestoreFromTrash)
    val title = Res.string.ciphers_action_restore_title.wrap()
    FlatItemAction(
        leading = icon,
        title = title,
        onClick = onClick {
            before?.invoke()

            val route = registerRouteResultReceiver(
                route = ConfirmationRoute(
                    args = ConfirmationRoute.Args(
                        icon = icon(Icons.Outlined.RestoreFromTrash),
                        title = translate(Res.string.ciphers_action_restore_confirmation_title.wrap()),
                    ),
                ),
            ) { result ->
                if (result is ConfirmationResult.Confirm) {
                    val cipherIds = ciphers
                        .map { it.id }
                        .toSet()
                    restoreCipherById(cipherIds)
                        .launchIn(appScope)
                }

                val success = result is ConfirmationResult.Confirm
                after?.invoke(success)
            }
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )
}

fun RememberStateFlowScope.cipherDeleteAction(
    removeCipherById: RemoveCipherById,
    ciphers: List<DSecret>,
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    val icon = icon(Icons.Outlined.DeleteForever)
    val title = Res.string.ciphers_action_delete_title.wrap()
    FlatItemAction(
        leading = icon,
        title = title,
        onClick = onClick {
            before?.invoke()

            val route = registerRouteResultReceiver(
                route = ConfirmationRoute(
                    args = ConfirmationRoute.Args(
                        icon = icon(Icons.Outlined.DeleteForever),
                        title = translate(Res.string.ciphers_action_delete_confirmation_title),
                    ),
                ),
            ) { result ->
                if (result is ConfirmationResult.Confirm) {
                    val cipherIds = ciphers
                        .map { it.id }
                        .toSet()
                    removeCipherById(cipherIds)
                        .launchIn(appScope)
                }

                val success = result is ConfirmationResult.Confirm
                after?.invoke(success)
            }
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )
}

fun RememberStateFlowScope.cipherWatchtowerAlerts(
    patchWatchtowerAlertCipher: PatchWatchtowerAlertCipher,
    ciphers: List<DSecret>,
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    val totalUniqueIgnoredAlerts = ciphers
        .asSequence()
        .flatMap { cipher -> cipher.ignoredAlerts.keys }
        .toSet()
        .count()
    val title = Res.string.ciphers_action_configure_watchtower_alerts_title.wrap()
    FlatItemAction(
        leading = {
            BadgedBox(
                badge = {
                    AnimatedTotalCounterBadge(
                        count = totalUniqueIgnoredAlerts,
                        predicate = { it > 0 },
                    )
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.EditNotifications,
                    contentDescription = null,
                )
            }
        },
        title = title,
        onClick = onClick {
            before?.invoke()

            val items = DWatchtowerAlertType.entries
                .filter { it.canBeDisabled }
                .map { watchtowerAlert ->
                    val checked = ciphers
                        .any { cipher ->
                            watchtowerAlert !in cipher.ignoredAlerts
                        }
                    ConfirmationRoute.Args.Item.BooleanItem(
                        key = watchtowerAlert.name,
                        title = translate(watchtowerAlert.title),
                        value = checked,
                    )
                }

            val route = registerRouteResultReceiver(
                route = ConfirmationRoute(
                    args = ConfirmationRoute.Args(
                        icon = icon(Icons.Outlined.EditNotifications),
                        title = translate(title),
                        items = items,
                    ),
                ),
            ) { result ->
                if (result is ConfirmationResult.Confirm) {
                    val patch = DWatchtowerAlertType.entries
                        .mapNotNull {
                            val checked = result.data[it.name] as? Boolean
                                ?: return@mapNotNull null
                            it to !checked
                        }
                        .toMap()
                    val request = PatchWatchtowerAlertCipherRequest(
                        patch = ciphers
                            .associate { cipher ->
                                cipher.id to patch
                            },
                    )
                    patchWatchtowerAlertCipher(request)
                        .launchIn(appScope)
                }

                val success = result is ConfirmationResult.Confirm
                after?.invoke(success)
            }
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )
}
