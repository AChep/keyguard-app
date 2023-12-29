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
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.home.vault.model.VaultPasswordHistoryItem
import com.artemchep.keyguard.feature.largetype.LargeTypeRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.copy
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.passwordleak.PasswordLeakRoute
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.Selection
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val copy = copy(
        clipboardService = clipboardService,
    )
    val secretFlow = getCiphers()
        .map { secrets ->
            secrets
                .firstOrNull { it.id == itemId }
        }
        .distinctUntilChanged()

    val selectionSink = mutablePersistedFlow("selection") {
        listOf<String>()
    }
    // Automatically remove selection from items
    // that do not exist anymore.
    secretFlow
        .onEach { secretOrNull ->
            val f = secretOrNull?.login?.passwordHistory.orEmpty()
            val selectedAccountIds = selectionSink.value
            val filteredSelectedAccountIds = selectedAccountIds
                .filter { id ->
                    f.any { it.id == id }
                }
            if (filteredSelectedAccountIds.size < selectedAccountIds.size) {
                selectionSink.value = filteredSelectedAccountIds
            }
        }
        .launchIn(this)

    fun clearSelection() {
        selectionSink.value = emptyList()
    }

    fun toggleSelection(entry: DSecret.Login.PasswordHistory) {
        val entryId = entry.id

        val oldAccountIds = selectionSink.value
        val newAccountIds =
            if (entryId in oldAccountIds) {
                oldAccountIds - entryId
            } else {
                oldAccountIds + entryId
            }
        selectionSink.value = newAccountIds
    }

    val selectionFlow = combine(
        selectionSink,
        getCanWrite(),
    ) { ids, canWrite ->
        if (ids.isEmpty()) {
            return@combine null
        }

        val actions = if (canWrite) {
            val removeAction = FlatItemAction(
                icon = Icons.Outlined.Delete,
                title = translate(Res.strings.remove_from_history),
                onClick = {
                    val route = registerRouteResultReceiver(
                        route = ConfirmationRoute(
                            args = ConfirmationRoute.Args(
                                icon = icon(Icons.Outlined.Delete),
                                title = "Remove ${ids.size} passwords from the history?",
                            ),
                        ),
                    ) {
                        if (it is ConfirmationResult.Confirm) {
                            cipherRemovePasswordHistoryById(
                                itemId,
                                ids,
                            ).launchIn(appScope)
                        }
                    }
                    val intent = NavigationIntent.NavigateToRoute(route)
                    navigate(intent)
                },
            )
            persistentListOf(
                removeAction,
            )
        } else {
            persistentListOf()
        }
        Selection(
            count = ids.size,
            actions = actions,
            onClear = ::clearSelection,
        )
    }

    val itemsFlow = secretFlow
        .map { secretOrNull ->
            secretOrNull
                ?.login
                ?.passwordHistory
                .orEmpty()
        }
        .distinctUntilChanged()
        .map { passwordHistory ->
            passwordHistory
                .sortedByDescending { it.lastUsedDate }
                .map { password ->
                    TempPasswordHistory(
                        src = password,
                        date = dateFormatter.formatDateTime(password.lastUsedDate),
                        actions = buildContextItems {
                            section {
                                this += copy.FlatItemAction(
                                    title = translate(Res.strings.copy_password),
                                    value = password.password,
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
        .combine(selectionSink) { passwords, ids ->
            val selectionMode = ids.isNotEmpty()
            passwords
                .asSequence()
                .map { passwordWrapper ->
                    val password = passwordWrapper.src
                    val selected = password.id in ids
                    val onToggle = ::toggleSelection.partially1(password)
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
                onClick = {
                    val route = registerRouteResultReceiver(
                        route = ConfirmationRoute(
                            args = ConfirmationRoute.Args(
                                icon = icon(Icons.Outlined.Delete),
                                title = translate(Res.strings.passwordhistory_clear_history_confirmation_title),
                                message = translate(Res.strings.passwordhistory_clear_history_confirmation_text),
                            ),
                        ),
                    ) {
                        if (it is ConfirmationResult.Confirm) {
                            cipherRemovePasswordHistory(
                                itemId,
                            ).launchIn(appScope)
                        }
                    }
                    val intent = NavigationIntent.NavigateToRoute(route)
                    navigate(intent)
                },
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
