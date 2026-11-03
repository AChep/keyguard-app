package com.artemchep.keyguard.feature.environments.mounts

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.AgentStatus
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.environment.EnvironmentMount
import com.artemchep.keyguard.common.service.environment.EnvironmentMountStatus
import com.artemchep.keyguard.common.service.cli.agent.CliAgentHost
import com.artemchep.keyguard.common.service.environment.EnvironmentMounts
import com.artemchep.keyguard.common.service.environment.EnvironmentRepository
import com.artemchep.keyguard.common.service.environment.EnvironmentSummary
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.createConfirmationDialogIntent
import com.artemchep.keyguard.feature.environments.mount.EnvironmentMountRoute
import com.artemchep.keyguard.feature.home.vault.screen.VaultViewRoute
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.search.mapListShape
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.copy
import com.artemchep.keyguard.res.environments_mount_disable
import com.artemchep.keyguard.res.environments_mount_open_environment
import com.artemchep.keyguard.res.environments_mounts_error_unavailable
import com.artemchep.keyguard.res.error_failed_unknown
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.koin.compose.currentKoinScope

@Composable
fun produceEnvironmentMountsState(
): Loadable<EnvironmentMountsState> = with(currentKoinScope()) {
    produceEnvironmentMountsState(
        environmentRepository = get(),
        environmentMounts = get(),
        cliAgentHost = get(),
        confirmationRouteFactory = get(),
    )
}

@Composable
fun produceEnvironmentMountsState(
    environmentRepository: EnvironmentRepository,
    environmentMounts: EnvironmentMounts,
    cliAgentHost: CliAgentHost,
    confirmationRouteFactory: ConfirmationRouteFactory,
): Loadable<EnvironmentMountsState> = produceScreenState(
    key = "environment_mounts",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    environmentMountsStateProducer(
        environmentRepository = environmentRepository,
        environmentMounts = environmentMounts,
        cliAgentHost = cliAgentHost,
        confirmationRouteFactory = confirmationRouteFactory,
    )
}

suspend fun RememberStateFlowScope.environmentMountsStateProducer(
    environmentRepository: EnvironmentRepository,
    environmentMounts: EnvironmentMounts,
    cliAgentHost: CliAgentHost,
    confirmationRouteFactory: ConfirmationRouteFactory,
): Flow<Loadable<EnvironmentMountsState>> {
    val agentFailedNote = SimpleNote(
        text = translate(Res.string.environments_mounts_error_unavailable),
        type = SimpleNote.Type.ERROR,
    )
    val disableEnvironmentMount = createDisableEnvironmentMount(
        environmentMounts = environmentMounts,
        confirmationRouteFactory = confirmationRouteFactory,
    )

    val copier = copier()
    return combine(
        environmentRepository.state,
        environmentMounts.state,
        cliAgentHost.status,
    ) { catalog, mountsState, agentStatus ->
        val items = mountsState.mounts
            .filter { it.enabled }
            .map { mount ->
                val environment = catalog.environments
                    .firstOrNull { it.id == mount.environmentId }
                createMountItem(
                    mount = mount,
                    environment = environment,
                    status = mountsState.statuses[mount.id] ?: EnvironmentMountStatus.Pending,
                    copier = copier,
                    onOpen = { navigate(it) },
                    onDisable = disableEnvironmentMount,
                )
            }
            .mapListShape()
            .toPersistentList()
        val agentFailed = items.isNotEmpty() &&
                agentStatus == AgentStatus.Failed
        val notes = if (agentFailed) {
            persistentListOf(agentFailedNote)
        } else {
            persistentListOf()
        }
        val primaryAction = if (catalog.environments.isNotEmpty()) {
            onClick {
                val route = EnvironmentMountRoute()
                val intent = NavigationIntent.NavigateToRoute(route)
                navigate(intent)
            }
        } else {
            null
        }
        val content = EnvironmentMountsState(
            notes = notes,
            items = items,
            primaryAction = primaryAction,
        )
        Loadable.Ok(content)
    }
}

private fun createMountItem(
    mount: EnvironmentMount,
    environment: EnvironmentSummary?,
    status: EnvironmentMountStatus,
    copier: CopyText,
    onOpen: (NavigationIntent) -> Unit,
    onDisable: (EnvironmentMount) -> Unit,
): EnvironmentMountsState.Item {
    val dropdown = createEnvironmentMountDropdown(
        mount = mount,
        copier = copier,
        onOpen = environment?.let {
            // lambda
            {
                val route = VaultViewRoute(
                    itemId = environment.id,
                    accountId = environment.accountId,
                )
                val intent = NavigationIntent.NavigateToRoute(route)
                onOpen(intent)
            }
        },
        onDisable = onDisable,
    )
    return EnvironmentMountsState.Item(
        key = mount.id,
        path = mount.path,
        environmentName = environment?.name,
        status = status,
        dropdown = dropdown,
    )
}

/**
 * Returns a callback that asks to confirm, then disables a local
 * `.env` file. A failure is reported with a toast.
 */
internal suspend fun RememberStateFlowScope.createDisableEnvironmentMount(
    environmentMounts: EnvironmentMounts,
    confirmationRouteFactory: ConfirmationRouteFactory,
): (EnvironmentMount) -> Unit {
    val disableTitle = translate(Res.string.environments_mount_disable)
    val errorText = translate(Res.string.error_failed_unknown)
    return { mount ->
        val intent = createConfirmationDialogIntent(
            confirmationRouteFactory = confirmationRouteFactory,
            icon = icon(Icons.Outlined.LinkOff),
            title = disableTitle,
            message = mount.path,
        ) {
            action {
                try {
                    environmentMounts.disable(mount.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    val msg = ToastMessage(
                        type = ToastMessage.Type.ERROR,
                        title = errorText,
                    )
                    message(msg)
                }
            }
        }
        navigate(intent)
    }
}

/**
 * The actions of a local `.env` file: copy its path, open its
 * environment unless [onOpen] is `null`, and disable it.
 */
internal fun createEnvironmentMountDropdown(
    mount: EnvironmentMount,
    copier: CopyText,
    onOpen: (() -> Unit)?,
    onDisable: (EnvironmentMount) -> Unit,
) = buildContextItems {
    section {
        this += copier.FlatItemAction(
            title = Res.string.copy.wrap(),
            value = mount.path,
        )
        if (onOpen != null) {
            this += FlatItemAction(
                id = "environment.mount.${mount.id}.open",
                icon = Icons.Outlined.Code,
                title = Res.string.environments_mount_open_environment.wrap(),
                onClick = onOpen,
            )
        }
    }
    section {
        this += FlatItemAction(
            id = "environment.mount.${mount.id}.disable",
            icon = Icons.Outlined.LinkOff,
            title = Res.string.environments_mount_disable.wrap(),
            danger = true,
            onClick = {
                onDisable(mount)
            },
        )
    }
}
