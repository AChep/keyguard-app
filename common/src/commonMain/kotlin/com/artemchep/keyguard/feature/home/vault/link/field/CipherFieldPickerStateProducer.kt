package com.artemchep.keyguard.feature.home.vault.link.field

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.environment.EnvironmentReferenceableField
import com.artemchep.keyguard.common.service.environment.environmentPermissions
import com.artemchep.keyguard.common.service.environment.environmentReferenceLinkTargets
import com.artemchep.keyguard.common.service.environment.environmentReferenceableFields
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.common.textFieldHandle
import com.artemchep.keyguard.feature.environments.EnvironmentFieldReference
import com.artemchep.keyguard.feature.home.vault.link.CipherLinkTarget
import com.artemchep.keyguard.feature.home.vault.link.filterCipherLinkPickerTargets
import com.artemchep.keyguard.feature.home.vault.link.toCipherLinkPickerItem
import com.artemchep.keyguard.feature.home.vault.screen.toVaultItemPresentation
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.search.mapListShape
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.cipher_field_picker_duplicate
import com.artemchep.keyguard.res.field_type_boolean
import com.artemchep.keyguard.res.field_type_text
import com.artemchep.keyguard.res.field_type_text_concealed
import com.artemchep.keyguard.res.password
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import org.koin.compose.currentKoinScope

private const val SHARING_STOP_TIMEOUT_MS = 5_000L

@Composable
fun produceCipherFieldPickerState(
    args: CipherFieldPickerRoute.Args,
    transmitter: RouteResultTransmitter<CipherFieldPickerResult>,
): CipherFieldPickerState = with(currentKoinScope()) {
    produceCipherFieldPickerState(
        args = args,
        transmitter = transmitter,
        getCiphers = get(),
        getCollections = get(),
        getAppIcons = get(),
        getWebsiteIcons = get(),
    )
}

@Composable
fun produceCipherFieldPickerState(
    args: CipherFieldPickerRoute.Args,
    transmitter: RouteResultTransmitter<CipherFieldPickerResult>,
    getCiphers: GetCiphers,
    getCollections: GetCollections,
    getAppIcons: GetAppIcons,
    getWebsiteIcons: GetWebsiteIcons,
): CipherFieldPickerState = produceScreenState(
    key = "cipher_field_picker",
    initial = CipherFieldPickerState(),
    args = arrayOf(args, getCiphers, getCollections, getAppIcons, getWebsiteIcons),
) {
    cipherFieldPickerStateProducer(
        args = args,
        transmitter = transmitter,
        getCiphers = getCiphers,
        getCollections = getCollections,
        getAppIcons = getAppIcons,
        getWebsiteIcons = getWebsiteIcons,
    )
}

/** An item that can be referenced, with the fields it offers. */
private class CipherFieldPickerCandidate(
    val target: CipherLinkTarget,
    val fields: List<EnvironmentReferenceableField>,
)

private class CipherFieldPickerStrings(
    val password: String,
    val text: String,
    val concealed: String,
    val boolean: String,
    val duplicate: String,
)

@Suppress("LongMethod")
suspend fun RememberStateFlowScope.cipherFieldPickerStateProducer(
    args: CipherFieldPickerRoute.Args,
    transmitter: RouteResultTransmitter<CipherFieldPickerResult>,
    getCiphers: GetCiphers,
    getCollections: GetCollections,
    getAppIcons: GetAppIcons,
    getWebsiteIcons: GetWebsiteIcons,
): Flow<CipherFieldPickerState> {
    val strings = CipherFieldPickerStrings(
        password = translate(Res.string.password),
        text = translate(Res.string.field_type_text),
        concealed = translate(Res.string.field_type_text_concealed),
        boolean = translate(Res.string.field_type_boolean),
        duplicate = translate(Res.string.cipher_field_picker_duplicate),
    )

    // The query survives drilling into an item and back.
    val queryHandle = textFieldHandle(
        key = "query",
        initial = "",
    )
    // The canonical provider id of the item the
    // user drilled into, empty on the first step.
    val selectedSink = mutablePersistedFlow("selected") { "" }
    val onBack = {
        selectedSink.value = ""
    }
    // Going back from the fields returns to the items,
    // instead of closing the dialog.
    interceptBackPress(
        interceptorFlow = selectedSink
            .map { selected -> onBack.takeIf { selected.isNotEmpty() } },
    )

    fun onPick(cipherId: String, field: EnvironmentFieldReference, cipherName: String) {
        transmitter(CipherFieldPickerResult.Confirm(cipherId, field, cipherName))
        navigatePopSelf()
    }

    val candidatesFlow = combine(
        getCiphers(),
        getCollections(),
    ) { ciphers, collections ->
        cipherFieldPickerCandidates(
            ciphers = ciphers,
            collections = collections,
            args = args,
        )
    }
        .shareIn(
            scope = screenScope,
            started = SharingStarted.WhileSubscribed(SHARING_STOP_TIMEOUT_MS),
            replay = 1,
        )
    return combine(
        candidatesFlow,
        queryHandle.sink,
        selectedSink,
        getAppIcons(),
        getWebsiteIcons(),
    ) { candidates, queryCell, selected, appIcons, websiteIcons ->
        val selectedCandidate = candidates[selected]
        val step = if (selectedCandidate != null) {
            val cipherId = selectedCandidate.target.link.remoteCipherId
            val cipherName = selectedCandidate.target.cipher.name
            CipherFieldPickerState.Step.Fields(
                item = selectedCandidate.target.cipher.toVaultItemPresentation(
                    appIcons = appIcons,
                    websiteIcons = websiteIcons,
                ),
                fields = selectedCandidate.fields
                    .map { field ->
                        field.toPickerField(strings) {
                            onPick(cipherId, field.reference, cipherName)
                        }
                    }
                    .mapListShape(),
            )
        } else {
            val items = filterCipherLinkPickerTargets(
                targets = candidates.values.map { it.target },
                query = queryCell.text,
            ).map { target ->
                target.cipher.toCipherLinkPickerItem(
                    appIcons = appIcons,
                    websiteIcons = websiteIcons,
                    onClick = {
                        selectedSink.value = target.link.remoteCipherId
                    },
                )
            }
            CipherFieldPickerState.Step.Items(items)
        }
        CipherFieldPickerState(
            query = TextFieldModel(
                text = queryCell.text,
                textRevision = queryCell.revision,
                onChange = queryHandle::onChange,
                onSetText = queryHandle::setText,
            ),
            step = step,
            onBack = onBack.takeIf { selectedCandidate != null },
            onDeny = {
                transmitter(CipherFieldPickerResult.Deny)
                navigatePopSelf()
            },
        )
    }
}

/**
 * The items a variable can reference, keyed by their canonical provider id:
 * readable items of the account with at least one field that can be told apart.
 */
private fun cipherFieldPickerCandidates(
    ciphers: List<DSecret>,
    collections: List<DCollection>,
    args: CipherFieldPickerRoute.Args,
): Map<String, CipherFieldPickerCandidate> = environmentReferenceLinkTargets(
    ciphers = ciphers,
    accountId = args.accountId,
    environmentId = args.excludedCipherId,
)
    .filterValues { target -> environmentPermissions(target.cipher, collections).canRead }
    .mapNotNull { (cipherId, target) ->
        val fields = target.cipher.environmentReferenceableFields()
        if (fields.none { !it.ambiguous }) {
            return@mapNotNull null
        }
        cipherId to CipherFieldPickerCandidate(
            target = target,
            fields = fields,
        )
    }
    .toMap()

private fun EnvironmentReferenceableField.toPickerField(
    strings: CipherFieldPickerStrings,
    onClick: () -> Unit,
): CipherFieldPickerState.Field = when (val reference = reference) {
    EnvironmentFieldReference.Password -> CipherFieldPickerState.Field(
        id = "password",
        kind = CipherFieldPickerState.Field.Kind.Password,
        title = strings.password,
        text = strings.concealed,
        onClick = onClick,
    )

    is EnvironmentFieldReference.Custom -> CipherFieldPickerState.Field(
        id = "custom.${reference.name}",
        kind = CipherFieldPickerState.Field.Kind.Custom,
        title = reference.name,
        // A name used by more than one field can not be referenced.
        text = if (ambiguous) {
            strings.duplicate
        } else {
            when (type) {
                DSecret.Field.Type.Hidden -> strings.concealed
                DSecret.Field.Type.Boolean -> strings.boolean
                else -> strings.text
            }
        },
        onClick = onClick.takeUnless { ambiguous },
    )
}
