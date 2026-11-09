package com.artemchep.keyguard.feature.auth.keyguard

import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.feature.auth.common.SwitchFieldModel
import com.artemchep.keyguard.feature.auth.common.TextCell
import com.artemchep.keyguard.feature.auth.common.TextFieldHandle
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.auth.common.textFieldHandle
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

internal class KeyguardNetworkField(
    val model: TextFieldModel,
    val validated: Validated<String>,
)

internal class KeyguardNetworkAdvanced(
    val state: KeyguardNetworkState,
    val relayUrl: Validated<String>,
    val iceUrl: Validated<String>,
    val accessToken: Validated<String>,
) {
    /** Returns the network settings, or `null` if any of the fields is invalid. */
    fun networkOrNull(): P2pNetwork? {
        val fields = listOf(relayUrl, iceUrl, accessToken)
        if (fields.any { it is Validated.Failure }) {
            return null
        }
        return P2pNetwork(
            relayUrl = relayUrl.model.ifEmpty { null },
            iceUrl = iceUrl.model.ifEmpty { null },
            accessToken = accessToken.model.ifEmpty { null },
            allowLan = state.allowLan.checked,
            relayStorage = state.relayStorage.checked,
        )
    }
}

/**
 * Returns the fields of the network settings, filled in with [initial]. The
 * access token is a secret, so it is never persisted.
 */
internal fun RememberStateFlowScope.keyguardNetworkFlow(
    initial: P2pNetwork = P2pNetwork(),
): Flow<KeyguardNetworkAdvanced> {
    val relayUrlFlow = textFieldHandle("relay_url", initial = initial.relayUrl.orEmpty())
        .fieldFlow { validatedServerUrl(it, KEYGUARD_RELAY_URL_SCHEMES) }
    val iceUrlFlow = textFieldHandle("ice_url", initial = initial.iceUrl.orEmpty())
        .fieldFlow { validatedServerUrl(it, KEYGUARD_ICE_URL_SCHEMES) }
    val accessTokenHandle = TextFieldHandle(
        sink = MutableStateFlow(TextCell(text = initial.accessToken.orEmpty())),
        id = "relay_token",
    )
    val accessTokenFlow = accessTokenHandle
        .fieldFlow { validatedRelayToken(it) }
    val allowLanSink = mutablePersistedFlow("allow_lan") { initial.allowLan }
    val relayStorageSink = mutablePersistedFlow("relay_storage") { initial.relayStorage }

    fun onAllowLanChange(allowLan: Boolean) {
        allowLanSink.value = allowLan
    }

    fun onRelayStorageChange(relayStorage: Boolean) {
        relayStorageSink.value = relayStorage
    }

    return combine(
        relayUrlFlow,
        iceUrlFlow,
        accessTokenFlow,
        allowLanSink,
        relayStorageSink,
    ) { relayUrl, iceUrl, accessToken, allowLan, relayStorage ->
        val state = KeyguardNetworkState(
            relayUrl = relayUrl.model,
            iceUrl = iceUrl.model,
            accessToken = accessToken.model,
            allowLan = SwitchFieldModel(
                checked = allowLan,
                onChange = ::onAllowLanChange,
            ),
            relayStorage = SwitchFieldModel(
                checked = relayStorage,
                onChange = ::onRelayStorageChange,
            ),
        )
        KeyguardNetworkAdvanced(
            state = state,
            relayUrl = relayUrl.validated,
            iceUrl = iceUrl.validated,
            accessToken = accessToken.validated,
        )
    }
}

internal fun TextFieldHandle.fieldFlow(
    validate: suspend (String) -> Validated<String>,
): Flow<KeyguardNetworkField> = sink
    .map { cell ->
        val validated = validate(cell.text)
        KeyguardNetworkField(
            model = TextFieldModel.of(
                cell = cell,
                handle = this,
                validated = validated,
            ),
            validated = validated,
        )
    }
