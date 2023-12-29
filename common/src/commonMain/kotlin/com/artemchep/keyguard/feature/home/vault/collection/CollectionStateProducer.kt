package com.artemchep.keyguard.feature.home.vault.collection

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun collectionScreenState(
    args: CollectionRoute.Args,
) = with(localDI().direct) {
    collectionScreenState(
        args = args,
        getOrganizations = instance(),
        getCollections = instance(),
    )
}

@Composable
fun collectionScreenState(
    args: CollectionRoute.Args,
    getOrganizations: GetOrganizations,
    getCollections: GetCollections,
): CollectionState = produceScreenState(
    key = "collection",
    initial = CollectionState(),
    args = arrayOf(
        args,
        getOrganizations,
        getCollections,
    ),
) {
    fun onClose() {
        navigatePopSelf()
    }

    val collectionFlow = getCollections()
        .map { collections ->
            collections
                .firstOrNull { it.id == args.collectionId }
        }
        .shareIn(screenScope, SharingStarted.Lazily, replay = 1)
    val contentFlow = combine(
        collectionFlow,
        getOrganizations(),
    ) { collection, organizations ->
        collection
            ?: return@combine null
        val organization = organizations.firstOrNull { it.id == collection.organizationId }
        val config = CollectionState.Content.Config(
            readOnly = collection.readOnly,
            hidePasswords = collection.hidePasswords,
        )
        CollectionState.Content(
            title = collection.name,
            organization = organization,
            config = config,
        )
    }
    contentFlow
        .map { content ->
            CollectionState(
                content = Loadable.Ok(content),
                onClose = ::onClose,
            )
        }
}
