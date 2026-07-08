package com.artemchep.keyguard.feature.gpgagent.keyserver.search

import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import arrow.core.Either
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.DGpgKeyserverResult
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GpgKeyserverConfig
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.SearchGpgPublicKeyRequest
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.model.toGpgAgentKeyMetadataOrNull
import com.artemchep.keyguard.common.service.gpgagent.chunkedGpgFingerprint
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverConfig
import com.artemchep.keyguard.common.usecase.SearchGpgPublicKey
import com.artemchep.keyguard.feature.home.vault.add.AddRoute
import com.artemchep.keyguard.feature.home.vault.add.LeAddRoute
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.keyboard.searchQueryShortcuts
import com.artemchep.keyguard.feature.search.search.debounceSearch
import com.artemchep.keyguard.feature.search.search.mapListShape
import com.artemchep.keyguard.feature.search.search.searchFilter
import com.artemchep.keyguard.feature.search.search.searchQueryHandle
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun produceGpgKeyserverSearchState() = with(localDI().direct) {
    produceGpgKeyserverSearchState(
        getGpgKeyserverConfig = instance(),
        searchGpgPublicKey = instance(),
    )
}

@Composable
fun produceGpgKeyserverSearchState(
    getGpgKeyserverConfig: GetGpgKeyserverConfig,
    searchGpgPublicKey: SearchGpgPublicKey,
): Loadable<GpgKeyserverSearchState> = produceScreenState(
    key = "gpg_keyserver_search",
    initial = Loadable.Loading,
    args = arrayOf(
        getGpgKeyserverConfig,
        searchGpgPublicKey,
    ),
) {
    val configFlow = getGpgKeyserverConfig()
        .distinctUntilChanged()
        .shareInScreenScope()
    val copyText = copier()

    val queryHandle = searchQueryHandle("query")
    searchQueryShortcuts(queryHandle)
    val queryFlow = searchFilter(queryHandle) { model, revision ->
        GpgKeyserverSearchState.Filter(
            revision = revision,
            query = model,
        )
    }

    val contentFlow = queryHandle.querySink
        .debounceSearch { it.text }
        .mapLatest { cell ->
            val query = cell.text.trim()
            val revision = query.hashCode()
            if (query.isEmpty()) {
                val content = GpgKeyserverSearchState.Content(
                    revision = revision,
                    items = emptyList(),
                )
                return@mapLatest Loadable.Ok(Either.Right(content))
            }

            val result = searchGpgPublicKey(
                SearchGpgPublicKeyRequest(query = query),
            )
                .attempt()
                .bind()
                .map { results ->
                    GpgKeyserverSearchState.Content(
                        revision = revision,
                        items = results
                            .mapIndexed { index, result ->
                                toItem(
                                    index = index,
                                    result = result,
                                    copyText = copyText,
                                    searchGpgPublicKey = searchGpgPublicKey,
                                )
                            }
                            .mapListShape()
                            .toImmutableList(),
                    )
                }
            Loadable.Ok(result)
        }

    combine(
        configFlow,
        queryFlow,
        contentFlow,
    ) { config, filter, content ->
        Loadable.Ok(
            GpgKeyserverSearchState(
                keyserverUrl = config.url,
                filter = queryFlow,
                content = content,
            ),
        )
    }
}

private fun RememberStateFlowScope.toItem(
    index: Int,
    result: DGpgKeyserverResult,
    copyText: CopyText,
    searchGpgPublicKey: SearchGpgPublicKey,
): GpgKeyserverSearchState.Item.Content {
    val fingerprint = result.fingerprint.normalizeGpgFingerprint()
    val title = result.displayTitle()
    val details = listOfNotNull(
        fingerprint.chunkedGpgFingerprint(),
        result.algorithm,
        result.sourceKeyserver,
    )
        .distinct()
        .joinToString(separator = " • ")
    return GpgKeyserverSearchState.Item.Content(
        key = "gpg_keyserver.$fingerprint.$index",
        title = title,
        description = details,
        dropdown = buildContextItems {
            section {
                this += copyText.FlatItemAction(
                    title = Res.string.copy_gpg_fingerprint.wrap(),
                    value = fingerprint.takeIf { it.isNotBlank() },
                    type = CopyText.Type.FINGERPRINT,
                )
                this += FlatItemAction(
                    leading = icon(Icons.Outlined.ContentCopy),
                    title = Res.string.copy_gpg_public_key.wrap(),
                    type = FlatItemAction.Type.COPY,
                    onClick = onClick {
                        copyPublicKey(
                            result = result,
                            copyText = copyText,
                            searchGpgPublicKey = searchGpgPublicKey,
                        )
                    },
                )
            }
            section {
                this += FlatItemAction(
                    leading = icon(Icons.Outlined.Add),
                    title = Res.string.generator_create_item_with_gpg_key_title.wrap(),
                    onClick = onClick {
                        createGpgKeyItem(
                            result = result,
                            searchGpgPublicKey = searchGpgPublicKey,
                        )
                    },
                )
            }
        },
        result = result,
    )
}

private fun DGpgKeyserverResult.displayTitle(): String =
    userIds.firstOrNull()
        ?: emails.firstOrNull()
        ?: keyId
        ?: fingerprint.normalizeGpgFingerprint().chunkedGpgFingerprint()

private suspend fun RememberStateFlowScope.withResolvedPublicKey(
    result: DGpgKeyserverResult,
    searchGpgPublicKey: SearchGpgPublicKey,
    block: suspend (String) -> Unit,
) {
    try {
        val publicKeyArmored = resolvePublicKeyArmoredOrNull(
            result = result,
            searchGpgPublicKey = searchGpgPublicKey,
        )
        if (publicKeyArmored == null) {
            message(
                ToastMessage(
                    title = translate(Res.string.gpg_keyserver_search_public_key_not_found_title),
                    type = ToastMessage.Type.ERROR,
                ),
            )
            return
        }

        block(publicKeyArmored)
    } catch (e: Throwable) {
        e.throwIfFatalOrCancellation()
        message(e)
    }
}

private suspend fun RememberStateFlowScope.createGpgKeyItem(
    result: DGpgKeyserverResult,
    searchGpgPublicKey: SearchGpgPublicKey,
) = withResolvedPublicKey(
    result = result,
    searchGpgPublicKey = searchGpgPublicKey,
) { publicKeyArmored ->
    val fingerprint = result.fingerprint.normalizeGpgFingerprint()
    val title = result.displayTitle()
    val route = LeAddRoute(
        args = AddRoute.Args(
            type = DSecret.Type.GpgKey,
            name = title,
            gpgKeyValue = DSecret.GpgKey(
                publicKeyArmored = publicKeyArmored,
                fingerprint = fingerprint.takeIf { it.isNotBlank() },
                metadata = result.toGpgAgentKeyMetadataOrNull(),
            ),
        ),
    )
    val intent = NavigationIntent.NavigateToRoute(route)
    navigate(intent)
}

private suspend fun RememberStateFlowScope.copyPublicKey(
    result: DGpgKeyserverResult,
    copyText: CopyText,
    searchGpgPublicKey: SearchGpgPublicKey,
) = withResolvedPublicKey(
    result = result,
    searchGpgPublicKey = searchGpgPublicKey,
) { publicKeyArmored ->
    copyText.copy(
        text = publicKeyArmored,
        hidden = false,
        type = CopyText.Type.PUBLIC_KEY,
    )
}

private suspend fun resolvePublicKeyArmoredOrNull(
    result: DGpgKeyserverResult,
    searchGpgPublicKey: SearchGpgPublicKey,
): String? = result.publicKeyArmored
    ?.takeIf { it.isNotBlank() }
    ?: searchGpgPublicKey(
        SearchGpgPublicKeyRequest(
            query = result.fingerprint,
            mode = SearchGpgPublicKeyRequest.Mode.FINGERPRINT,
            keyserverConfig = result.sourceKeyserverConfig,
            keyserver = result.sourceKeyserver,
        ),
    )
        .bind()
        .firstOrNull { it.publicKeyArmored?.isNotBlank() == true }
        ?.publicKeyArmored
