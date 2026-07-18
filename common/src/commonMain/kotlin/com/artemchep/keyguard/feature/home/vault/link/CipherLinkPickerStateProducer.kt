package com.artemchep.keyguard.feature.home.vault.link

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.titleH
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.common.textFieldHandle
import com.artemchep.keyguard.feature.home.vault.screen.toVaultItemIcon
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.coroutines.flow.combine
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
    val typeTitles = DSecret.Type.entries
        .associateWith { type -> translate(type.titleH()) }

    combine(
        getCiphers(),
        queryHandle.sink,
        getAppIcons(),
        getWebsiteIcons(),
    ) { ciphers, queryCell, appIcons, websiteIcons ->
        val query = queryCell.text.trim()
        val items = filterCipherLinkPickerCiphers(
            ciphers = ciphers,
            accountId = args.accountId,
            excludedCipherId = args.excludedCipherId,
            query = query,
        )
            .asSequence()
            .map { cipher ->
                val link = requireNotNull(
                    cipher.service.remote?.id?.let(CipherLink::of),
                )
                CipherLinkPickerState.Item(
                    id = cipher.id,
                    title = cipher.name,
                    text = cipher.login?.username
                        ?.takeIf { it.isNotBlank() }
                        ?: typeTitles.getValue(cipher.type),
                    icon = cipher.toVaultItemIcon(
                        appIcons = appIcons,
                        websiteIcons = websiteIcons,
                    ),
                    onClick = {
                        transmitter(CipherLinkPickerResult.Confirm(link))
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

internal fun filterCipherLinkPickerCiphers(
    ciphers: List<DSecret>,
    accountId: String,
    excludedCipherId: String?,
    query: String,
): List<DSecret> = ciphers
    .asSequence()
    .filter { cipher ->
        cipher.accountId == accountId &&
                cipher.id != excludedCipherId &&
                cipher.deletedDate == null &&
                cipher.service.remote?.id?.let(CipherLink::of) != null
    }
    .filter { cipher ->
        query.isBlank() || cipher.matchesCipherLinkPickerQuery(query.trim())
    }
    .sortedWith(
        compareBy<DSecret> { it.name.lowercase() }
            .thenBy { it.id },
    )
    .toList()

private fun DSecret.matchesCipherLinkPickerQuery(query: String): Boolean =
    name.contains(query, ignoreCase = true) ||
            login?.username?.contains(query, ignoreCase = true) == true ||
            uris.any { it.uri.contains(query, ignoreCase = true) }
