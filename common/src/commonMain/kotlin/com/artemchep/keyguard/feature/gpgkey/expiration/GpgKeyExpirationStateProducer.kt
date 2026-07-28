package com.artemchep.keyguard.feature.gpgkey.expiration

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationChange
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.datedaypicker.DateDayPickerRoute
import com.artemchep.keyguard.feature.datedaypicker.createDateDayPickerDialogIntent
import com.artemchep.keyguard.feature.gpgkey.GpgKeyExpiryPreset
import com.artemchep.keyguard.feature.gpgkey.titleResource
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
fun gpgKeyExpirationState(
    args: GpgKeyExpirationRoute.Args,
    transmitter: RouteResultTransmitter<GpgKeyExpirationChange>,
): GpgKeyExpirationState = with(localDI().direct) {
    gpgKeyExpirationState(
        args = args,
        transmitter = transmitter,
        dateFormatter = instance(),
    )
}

@Composable
fun gpgKeyExpirationState(
    args: GpgKeyExpirationRoute.Args,
    transmitter: RouteResultTransmitter<GpgKeyExpirationChange>,
    dateFormatter: DateFormatter,
): GpgKeyExpirationState = produceScreenState(
    key = "gpg_key_expiration",
    initial = GpgKeyExpirationState(),
    args = arrayOf(args, dateFormatter),
) {
    gpgKeyExpirationStateProducer(
        args = args,
        transmitter = transmitter,
        dateFormatter = dateFormatter,
    )
}

internal suspend fun RememberStateFlowScope.gpgKeyExpirationStateProducer(
    args: GpgKeyExpirationRoute.Args,
    transmitter: RouteResultTransmitter<GpgKeyExpirationChange>,
    dateFormatter: DateFormatter,
    now: () -> Instant = { Clock.System.now() },
    currentTimeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
): Flow<GpgKeyExpirationState> {
    val keyInfo = args.keyInfo
    val eligibleSubKeys = keyInfo.subKeys.filterNot { it.revoked }
    val defaultPreset = defaultGpgKeyExpirationPreset(keyInfo)
    val presetSink = mutablePersistedFlow("preset") {
        defaultPreset.key
    }
    val componentFingerprintsSink = mutablePersistedFlow("components") {
        defaultGpgKeyExpirationComponents(keyInfo)
    }
    val customDateSink = mutablePersistedFlow<LocalDate?>("custom_date") {
        null
    }

    suspend fun formatExpiration(expiresAt: Instant?): String = expiresAt
        ?.toLocalDateTime(currentTimeZone())
        ?.date
        ?.let(dateFormatter::formatDateMedium)
        ?: translate(Res.string.gpg_key_expiry_never)

    suspend fun formatSubKeyExpiration(expiresAt: Instant?): String = when {
        expiresAt != null -> formatExpiration(expiresAt)
        keyInfo.expiresAt != null -> translate(
            Res.string.gpg_key_expiry_same_as_primary,
            formatExpiration(keyInfo.expiresAt),
        )

        else -> translate(Res.string.gpg_key_expiry_never)
    }

    val primaryTitle = translate(Res.string.gpg_key_expiry_primary)
    val primaryText = formatExpiration(keyInfo.expiresAt)
    val componentDescriptions = buildMap {
        eligibleSubKeys.forEach { subKey ->
            val capabilities = buildList {
                if (subKey.canSign) {
                    add(translate(Res.string.gpg_key_expiry_capability_sign))
                }
                if (subKey.canEncrypt) {
                    add(translate(Res.string.gpg_key_expiry_capability_encrypt))
                }
            }.joinToString().ifEmpty { subKey.algorithm }
            put(
                subKey.fingerprint,
                translate(
                    Res.string.gpg_key_expiry_subkey_capabilities,
                    capabilities,
                    formatSubKeyExpiration(subKey.expiresAt),
                ),
            )
        }
    }
    val componentTitles = buildMap {
        eligibleSubKeys.forEach { subKey ->
            put(
                subKey.fingerprint,
                translate(
                    Res.string.gpg_key_expiry_subkey,
                    subKey.keyId.takeLast(8),
                ),
            )
        }
    }
    val presetTitles = GpgKeyExpiryPreset.entries.associateWith { preset ->
        translate(
            preset.titleResource(
                never = Res.string.gpg_key_expiry_no_explicit_expiry,
            ),
        )
    }

    fun requestCustomDate(componentFingerprints: Set<String>) {
        val currentInstant = now()
        val timeZone = currentTimeZone()
        val currentDate = currentInstant.toLocalDateTime(timeZone).date
        val selectableDates = gpgKeyExpirationSelectionDateRange(
            keyInfo = keyInfo,
            componentFingerprints = componentFingerprints,
            now = currentInstant,
            timeZone = timeZone,
        ) ?: return
        val initialDate = customDateSink.value
            ?.takeIf { it in selectableDates }
            ?: keyInfo.expiresAt
                ?.toLocalDateTime(timeZone)
                ?.date
                ?.takeIf { it in selectableDates }
            ?: currentDate
                .plus(1, DateTimeUnit.DAY)
                .plus(1, DateTimeUnit.YEAR)
                .coerceAtMost(selectableDates.endInclusive)
        val intent = createDateDayPickerDialogIntent(
            args = DateDayPickerRoute.Args(
                initialDate = initialDate,
                selectableDates = selectableDates,
            ),
        ) { date ->
            customDateSink.value = date
            presetSink.value = GpgKeyExpiryPreset.Custom.key
        }
        navigate(intent)
    }

    fun createComponents(selectedFingerprints: Set<String>) =
        buildList {
            add(
                GpgKeyExpirationState.Component(
                    key = keyInfo.fingerprint,
                    title = primaryTitle,
                    text = primaryText,
                    selected = keyInfo.fingerprint in selectedFingerprints,
                    onToggle = {
                        componentFingerprintsSink.value = componentFingerprintsSink.value.toggle(
                            keyInfo.fingerprint,
                        )
                    },
                ),
            )
            eligibleSubKeys.forEach { subKey ->
                add(
                    GpgKeyExpirationState.Component(
                        key = subKey.fingerprint,
                        title = componentTitles.getValue(subKey.fingerprint),
                        text = componentDescriptions.getValue(subKey.fingerprint),
                        selected = subKey.fingerprint in selectedFingerprints,
                        onToggle = {
                            componentFingerprintsSink.value = componentFingerprintsSink.value.toggle(
                                subKey.fingerprint,
                            )
                        },
                    ),
                )
            }
        }

    return combine(
        presetSink,
        componentFingerprintsSink,
        customDateSink,
    ) { rawPreset, componentFingerprints, customDate ->
        val preset = GpgKeyExpiryPreset.getOrDefault(
            key = rawPreset,
            default = defaultPreset,
        )
        val currentInstant = now()
        val timeZone = currentTimeZone()
        val evaluation = evaluateGpgKeyExpirationSelection(
            keyInfo = keyInfo,
            preset = preset,
            componentFingerprints = componentFingerprints,
            customDate = customDate,
            now = currentInstant,
            timeZone = timeZone,
        )
        val validationError = when (evaluation) {
            is GpgKeyExpirationEvaluation.Valid -> null
            is GpgKeyExpirationEvaluation.Invalid -> evaluation.error
        }
        val validationErrorText = when (validationError) {
            GpgKeyExpirationSelectionError.RevokedPrimary ->
                translate(Res.string.gpg_key_expiry_revoked_message)

            GpgKeyExpirationSelectionError.NoComponents ->
                translate(Res.string.gpg_key_expiry_no_components_message)

            GpgKeyExpirationSelectionError.AfterPrimary ->
                translate(Res.string.gpg_key_expiry_after_primary_message)

            GpgKeyExpirationSelectionError.InvalidExpiration ->
                translate(Res.string.gpg_key_expiry_invalid_message)

            null -> null
        }
        GpgKeyExpirationState(
            presets = GpgKeyExpiryPreset.entries.map { item ->
                createGpgKeyExpirationPresetState(
                    item = item,
                    title = presetTitles.getValue(item),
                    selected = item == preset,
                    customDate = customDate,
                    formatDate = dateFormatter::formatDateMedium,
                    onClick = {
                        if (item == GpgKeyExpiryPreset.Custom) {
                            requestCustomDate(componentFingerprintsSink.value)
                        } else {
                            presetSink.value = item.key
                        }
                    },
                )
            },
            components = createComponents(componentFingerprints),
            validationError = validationErrorText,
            onDeny = ::navigatePopSelf,
            onConfirm = if (evaluation is GpgKeyExpirationEvaluation.Valid) {
                {
                    transmitter(evaluation.change)
                    navigatePopSelf()
                }
            } else {
                null
            },
        )
    }
}

internal fun createGpgKeyExpirationPresetState(
    item: GpgKeyExpiryPreset,
    title: String,
    selected: Boolean,
    customDate: LocalDate?,
    formatDate: (LocalDate) -> String,
    onClick: () -> Unit,
): GpgKeyExpirationState.Preset = GpgKeyExpirationState.Preset(
    key = item.key,
    title = title,
    text = if (item == GpgKeyExpiryPreset.Custom && selected) {
        customDate?.let(formatDate)
    } else {
        null
    },
    selected = selected,
    onClick = onClick,
)

private fun <T> Set<T>.toggle(value: T): Set<T> =
    if (value in this) this - value else this + value
