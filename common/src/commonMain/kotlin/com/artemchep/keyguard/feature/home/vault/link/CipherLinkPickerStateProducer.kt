package com.artemchep.keyguard.feature.home.vault.link

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.common.textFieldHandle
import com.artemchep.keyguard.feature.home.vault.screen.toVaultItemPresentation
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun produceCipherLinkPickerState(
    args: CipherLinkPickerRoute.Args,
    transmitter: RouteResultTransmitter<CipherLinkPickerResult>,
): CipherLinkPickerState = with(localDI().direct) {
    produceCipherLinkPickerState(
        args = args,
        transmitter = transmitter,
        getCiphers = instance(),
        getAppIcons = instance(),
        getWebsiteIcons = instance(),
    )
}

@Composable
fun produceCipherLinkPickerState(
    args: CipherLinkPickerRoute.Args,
    transmitter: RouteResultTransmitter<CipherLinkPickerResult>,
    getCiphers: GetCiphers,
    getAppIcons: GetAppIcons,
    getWebsiteIcons: GetWebsiteIcons,
): CipherLinkPickerState = produceScreenState(
    key = "cipher_link_picker",
    initial = CipherLinkPickerState(),
    args = arrayOf(args, getCiphers, getAppIcons, getWebsiteIcons),
) {
    val queryHandle = textFieldHandle(
        key = "query",
        initial = "",
    )
    val candidatesFlow = createCipherLinkPickerTargetsFlow(
        ciphersFlow = getCiphers(),
        accountId = args.accountId,
        excludedCipherId = args.excludedCipherId,
        sharingScope = screenScope,
    )
    combine(
        candidatesFlow,
        queryHandle.sink,
        getAppIcons(),
        getWebsiteIcons(),
    ) { candidates, queryCell, appIcons, websiteIcons ->
        val items = filterCipherLinkPickerTargets(
            targets = candidates,
            query = queryCell.text,
        )
            .asSequence()
            .map { target ->
                target.cipher.toCipherLinkPickerItem(
                    appIcons = appIcons,
                    websiteIcons = websiteIcons,
                    onClick = {
                        transmitter(CipherLinkPickerResult.Confirm(target.link))
                        navigatePopSelf()
                    },
                )
            }
            .toList()

        CipherLinkPickerState(
            query = TextFieldModel(
                text = queryCell.text,
                textRevision = queryCell.revision,
                onChange = queryHandle::onChange,
                onSetText = queryHandle::setText,
            ),
            items = items,
            onDeny = {
                transmitter(CipherLinkPickerResult.Deny)
                navigatePopSelf()
            },
        )
    }
}

internal fun DSecret.toCipherLinkPickerItem(
    appIcons: Boolean,
    websiteIcons: Boolean,
    onClick: () -> Unit,
) = CipherLinkPickerState.Item(
    presentation = toVaultItemPresentation(
        appIcons = appIcons,
        websiteIcons = websiteIcons,
    ),
    onClick = onClick,
)

internal fun filterCipherLinkPickerTargets(
    targets: Collection<CipherLinkTarget>,
    query: String,
): List<CipherLinkTarget> {
    val normalizedQuery = query.trim()
    return targets
    .asSequence()
    .filter { target ->
        normalizedQuery.isEmpty() ||
                target.cipher.matchesCipherLinkPickerQuery(normalizedQuery)
    }
    .sortedWith(
        compareBy<CipherLinkTarget> { it.cipher.name.lowercase() }
            .thenBy { it.cipher.id },
    )
    .toList()
}

internal fun createCipherLinkPickerTargetsFlow(
    ciphersFlow: Flow<List<DSecret>>,
    accountId: String,
    excludedCipherId: String?,
    sharingScope: CoroutineScope,
): Flow<List<CipherLinkTarget>> = ciphersFlow
    .map { ciphers ->
        cipherLinkTargetsByRemoteId(
            ciphers = ciphers,
            accountId = accountId,
            excludedCipherId = excludedCipherId,
        ).values.toList()
    }
    .shareIn(
        scope = sharingScope,
        started = SharingStarted.WhileSubscribed(SHARING_STOP_TIMEOUT_MS),
        replay = 1,
    )

private fun DSecret.matchesCipherLinkPickerQuery(query: String): Boolean =
    name.contains(query, ignoreCase = true) ||
            login?.username?.contains(query, ignoreCase = true) == true ||
            uris.any { it.uri.contains(query, ignoreCase = true) }

private const val SHARING_STOP_TIMEOUT_MS = 5_000L
