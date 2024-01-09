package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.CipherRemovePasswordHistory
import com.artemchep.keyguard.common.usecase.CipherRemovePasswordHistoryById
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.feature.confirmation.createConfirmationDialogIntent
import com.artemchep.keyguard.feature.home.vault.model.VaultPasswordHistoryItem
import com.artemchep.keyguard.feature.largetype.LargeTypeRoute
import com.artemchep.keyguard.feature.navigation.state.copy
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.passwordleak.PasswordLeakRoute
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.Selection
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.selection.selectionHandle
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun vaultViewPasswordHistoryScreenState(
    itemId: String,
) = with(localDI().direct) {
    vaultViewPasswordHistoryScreenState(
        getCanWrite = instance(),
        getAccounts = instance(),
        getCiphers = instance(),
        cipherRemovePasswordHistory = instance(),
        cipherRemovePasswordHistoryById = instance(),
        clipboardService = instance(),
        dateFormatter = instance(),
        itemId = itemId,
    )
}

@Composable
fun vaultViewPasswordHistoryScreenState(
    getCanWrite: GetCanWrite,
    getAccounts: GetAccounts,
    getCiphers: GetCiphers,
    cipherRemovePasswordHistory: CipherRemovePasswordHistory,
    cipherRemovePasswordHistoryById: CipherRemovePasswordHistoryById,
    clipboardService: ClipboardService,
    dateFormatter: DateFormatter,
    itemId: String,
) = produceScreenState(
    key = "vault_password_history",
    initial = VaultViewPasswordHistoryState(),
    args = arrayOf(
        getAccounts,
        getCiphers,
        cipherRemovePasswordHistory,
        cipherRemovePasswordHistoryById,
        clipboardService,
        dateFormatter,
        itemId,
    ),
) {
    val selectionHandle = selectionHandle("selection")
    val copyFactory = copy(clipboardService)

    val secretFlow = getCiphers()
        .map { secrets ->
            secrets
                .firstOrNull { it.id == itemId }
        }
        .distinctUntilChanged()

    val itemsRawFlow = secretFlow
        .map { secretOrNull ->
            secretOrNull
                ?.login
                ?.passwordHistory
                .orEmpty()
        }
        .distinctUntilChanged()
        .shareInScreenScope()
    // Automatically de-select items
    // that do not exist.
    combine(
        itemsRawFlow,
        selectionHandle.idsFlow,
    ) { items, selectedItemIds ->
        val newSelectedItemIds = selectedItemIds
            .asSequence()
            .filter { itemId ->
                items.any { it.id == itemId }
            }
            .toSet()
        newSelectedItemIds.takeIf { it.size < selectedItemIds.size }
    }
        .filterNotNull()
        .onEach { ids -> selectionHandle.setSelection(ids) }
        .launchIn(screenScope)

    fun onDeleteByItems(
        items: List<DSecret.Login.PasswordHistory>,
    ) {
        val title = if (items.size > 1) {
            translate(Res.strings.passwordhistory_delete_many_confirmation_title)
        } else {
            translate(Res.strings.passwordhistory_delete_one_confirmation_title)
        }
        val message = items
            .joinToString(separator = "\n") { it.password }
        val intent = createConfirmationDialogIntent(
            icon = icon(Icons.Outlined.Delete),
            title = title,
            message = message,
        ) {
            val ids = items
                .map { it.id }
            cipherRemovePasswordHistoryById(
                itemId,
                ids,
            ).launchIn(appScope)
        }
        navigate(intent)
    }

    fun onDeleteAll() {
        val intent = createConfirmationDialogIntent(
            icon = icon(Icons.Outlined.Delete),
            title = translate(Res.strings.passwordhistory_clear_history_confirmation_title),
            message = translate(Res.strings.passwordhistory_clear_history_confirmation_text),
        ) {
            cipherRemovePasswordHistory(
                itemId,
            ).launchIn(appScope)
        }
        navigate(intent)
    }

    val selectionFlow = combine(
        itemsRawFlow,
        selectionHandle.idsFlow,
    ) { items, selectedItemIds ->
        val selectedItems = items
            .filter { it.id in selectedItemIds }
        items to selectedItems
    }
        .combine(getCanWrite()) { (allItems, selectedItems), canWrite ->
            if (selectedItems.isEmpty()) {
                return@combine null
            }

            val actions = buildContextItems {
                section {
                    this += FlatItemAction(
                        leading = icon(Icons.Outlined.Delete),
                        title = translate(Res.strings.remove_from_history),
                        onClick = ::onDeleteByItems
                            .partially1(selectedItems),
                    )
                }
            }
            Selection(
                count = selectedItems.size,
                actions = actions,
                onSelectAll = if (selectedItems.size < allItems.size) {
                    val allIds = allItems
                        .asSequence()
                        .mapNotNull { it.id }
                        .toSet()
                    selectionHandle::setSelection
                        .partially1(allIds)
                } else {
                    null
                },
                onClear = selectionHandle::clearSelection,
            )
        }

    val itemsFlow = itemsRawFlow
        .map { passwordHistory ->
            passwordHistory
                .sortedByDescending { it.lastUsedDate }
                .map { password ->
                    TempPasswordHistory(
                        src = password,
                        date = dateFormatter.formatDateTime(password.lastUsedDate),
                        actions = buildContextItems {
                            section {
                                this += copyFactory.FlatItemAction(
                                    title = translate(Res.strings.copy_password),
                                    value = password.password,
                                    type = CopyText.Type.PASSWORD,
                                )
                                val items = listOf(
                                    password,
                                )
                                this += FlatItemAction(
                                    leading = icon(Icons.Outlined.Delete),
                                    title = translate(Res.strings.remove_from_history),
                                    onClick = ::onDeleteByItems
                                        .partially1(items),
                                )
                            }
                            section {
                                this += LargeTypeRoute.showInLargeTypeActionOrNull(
                                    translator = this@produceScreenState,
                                    text = password.password,
                                    colorize = true,
                                    navigate = ::navigate,
                                )
                                this += LargeTypeRoute.showInLargeTypeActionAndLockOrNull(
                                    translator = this@produceScreenState,
                                    text = password.password,
                                    colorize = true,
                                    navigate = ::navigate,
                                )
                            }
                            section {
                                this += PasswordLeakRoute.checkBreachesPasswordAction(
                                    translator = this@produceScreenState,
                                    password = password.password,
                                    navigate = ::navigate,
                                )
                            }
                        },
                    )
                }
        }
        .combine(selectionHandle.idsFlow) { passwords, ids ->
            val selectionMode = ids.isNotEmpty()
            passwords
                .asSequence()
                .map { passwordWrapper ->
                    val password = passwordWrapper.src
                    val selected = password.id in ids
                    val onToggle = selectionHandle::toggleSelection
                        .partially1(password.id)
                    VaultPasswordHistoryItem.Value(
                        id = password.id,
                        title = passwordWrapper.date,
                        value = password.password,
                        dropdown = passwordWrapper.actions,
                        monospace = true,
                        selected = selected,
                        selecting = selectionMode,
                        onClick = onToggle.takeIf { selectionMode },
                        onLongClick = onToggle.takeUnless { selectionMode },
                    )
                }
                .toPersistentList()
        }

    val actionsFlow = flowOf(
        persistentListOf(
            FlatItemAction(
                icon = Icons.Outlined.Delete,
                title = translate(Res.strings.passwordhistory_clear_history_title),
                onClick = ::onDeleteAll,
            ),
        ),
    )

    combine(
        secretFlow,
        itemsFlow,
        actionsFlow,
        selectionFlow,
    ) { secretOrNull, items, actions, selection ->
        val content = when (secretOrNull) {
            null -> VaultViewPasswordHistoryState.Content.NotFound
            else -> VaultViewPasswordHistoryState.Content.Cipher(
                data = secretOrNull,
                selection = selection,
                items = items,
                actions = actions,
            )
        }
        VaultViewPasswordHistoryState(
            content = content,
        )
    }
}

private data class TempPasswordHistory(
    val src: DSecret.Login.PasswordHistory,
    val actions: List<ContextItem>,
    val date: String,
)
