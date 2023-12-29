package com.artemchep.keyguard.feature.home.vault.organization

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun organizationScreenState(
    args: OrganizationRoute.Args,
) = with(localDI().direct) {
    organizationScreenState(
        args = args,
        getOrganizations = instance(),
    )
}

@Composable
fun organizationScreenState(
    args: OrganizationRoute.Args,
    getOrganizations: GetOrganizations,
): OrganizationState = produceScreenState(
    key = "organization",
    initial = OrganizationState(),
    args = arrayOf(
        args,
        getOrganizations,
    ),
) {
    fun onClose() {
        navigatePopSelf()
    }

    val organizationFlow = getOrganizations()
        .map { organizations ->
            organizations
                .firstOrNull { it.id == args.organizationId }
        }
        .shareIn(screenScope, SharingStarted.Lazily, replay = 1)
    val contentFlow = organizationFlow
        .map { organization ->
            organization
                ?: return@map null
            val config = OrganizationState.Content.Config(
                selfHost = organization.selfHost,
            )
            OrganizationState.Content(
                title = organization.name,
                config = config,
            )
        }
    contentFlow
        .map { content ->
            OrganizationState(
                content = Loadable.Ok(content),
                onClose = ::onClose,
            )
        }
}
