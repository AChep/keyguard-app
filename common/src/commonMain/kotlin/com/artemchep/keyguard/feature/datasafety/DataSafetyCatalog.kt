package com.artemchep.keyguard.feature.datasafety

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.util.hasBrowser
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import org.jetbrains.compose.resources.StringResource

// GenerateMasterSaltUseCaseImpl generates 64 bytes, expressed here in bits.
@PublishedApi
internal const val DATA_SAFETY_VAULT_SALT_BITS = 512

@PublishedApi
internal const val DATA_SAFETY_LEARN_MORE_URL = "https://bitwarden.com/help/what-encryption-is-used/"

/** Inline resolvers support both Compose localization and the suspending native bridge. */
inline fun dataSafetyCatalog(
    text: (StringResource) -> String,
    format: (StringResource, Any) -> String,
    platform: Platform = CurrentPlatform,
    dividerHorizontalPadding: Dp = 0.dp,
): List<DataSafetyItem> {
    return dataSafetyLocalStorageItems(text, platform) +
        dataSafetyVaultItems(text) +
        dataSafetyVaultAlgorithmItems(text, format, dividerHorizontalPadding) +
        dataSafetyRemoteItems(text, platform)
}

@PublishedApi
internal inline fun dataSafetyLocalStorageItems(
    text: (StringResource) -> String,
    platform: Platform,
): List<DataSafetyItem> = listOf(
    DataSafetyItem.LargeSection(
        key = "local.section",
        text = text(Res.string.datasafety_local_section),
    ),
    DataSafetyItem.Text(
        key = "local.text",
        text = text(
            if (platform is Platform.Mobile.Android) Res.string.datasafety_local_text
            else Res.string.datasafety_local_stored_on_device_text,
        ),
    ),
    DataSafetyItem.Spacer(
        key = "local.text.spacer",
        height = 16.dp,
    ),
    DataSafetyItem.Section(
        key = "local.downloads.section",
        text = text(Res.string.datasafety_local_downloads_section),
    ),
    DataSafetyItem.Row(
        key = "local.downloads.encryption",
        title = text(Res.string.encryption),
        value = text(Res.string.none),
    ),
    DataSafetyItem.Spacer(
        key = "local.downloads.spacer",
        height = 16.dp,
    ),
    DataSafetyItem.Section(
        key = "local.settings.section",
        text = text(Res.string.datasafety_local_settings_section),
    ),
    DataSafetyItem.Row(
        key = "local.settings.encryption",
        title = text(Res.string.encryption),
        value = text(
            if (platform is Platform.Mobile.Android) Res.string.encryption_algorithm_256bit_aes
            else Res.string.none,
        ),
    ),
    DataSafetyItem.Spacer(
        key = "local.settings.encryption.spacer",
        height = 16.dp,
    ),
    DataSafetyItem.Text(
        key = "local.settings.note",
        text = text(
            if (platform is Platform.Mobile.Android) Res.string.datasafety_local_settings_note
            else Res.string.datasafety_local_settings_no_app_encryption_note,
        ),
        secondary = true,
    ),
    DataSafetyItem.Spacer(
        key = "local.settings.note.spacer",
        height = 16.dp,
    ),
)

@PublishedApi
internal inline fun dataSafetyVaultItems(
    text: (StringResource) -> String,
): List<DataSafetyItem> = listOf(
    DataSafetyItem.Section(
        key = "local.vault.section",
        text = text(Res.string.datasafety_local_vault_section),
    ),
    DataSafetyItem.Row(
        key = "local.vault.encryption",
        title = text(Res.string.encryption),
        value = text(Res.string.encryption_algorithm_256bit_aes),
    ),
    DataSafetyItem.Spacer(
        key = "local.vault.encryption.spacer",
        height = 16.dp,
    ),
    DataSafetyItem.Text(
        key = "local.vault.algorithm.intro",
        text = text(Res.string.datasafety_local_encryption_algorithm_intro),
        secondary = true,
    ),
    DataSafetyItem.Spacer(
        key = "local.vault.algorithm.intro.spacer",
        height = 16.dp,
    ),
    DataSafetyItem.Text(
        key = "local.vault.algorithm.kdf",
        text = text(Res.string.datasafety_local_kdf_versions_note),
        secondary = true,
    ),
    DataSafetyItem.Spacer(
        key = "local.vault.algorithm.kdf.spacer",
        height = 16.dp,
    ),
)

@PublishedApi
internal inline fun dataSafetyVaultAlgorithmItems(
    text: (StringResource) -> String,
    format: (StringResource, Any) -> String,
    dividerHorizontalPadding: Dp,
): List<DataSafetyItem> {
    val labelAppPassword = text(Res.string.app_password)
    val labelHash = text(Res.string.encryption_hash)
    val labelSalt = text(Res.string.encryption_salt)
    val labelKey = text(Res.string.encryption_key)
    return listOf(
        DataSafetyItem.Row(
            key = "local.vault.algorithm.salt",
            title = labelSalt,
            value = format(Res.string.encryption_random_bits_data, DATA_SAFETY_VAULT_SALT_BITS),
            secondary = true,
        ),
        DataSafetyItem.Divider(
            key = "local.vault.algorithm.salt.divider",
            verticalPadding = 8.dp,
            horizontalPadding = dividerHorizontalPadding,
        ),
        DataSafetyItem.Row(
            key = "local.vault.algorithm.hash",
            title = labelHash,
            value = "KDF($labelAppPassword, $labelSalt)",
            secondary = true,
        ),
        DataSafetyItem.Divider(
            key = "local.vault.algorithm.hash.divider",
            verticalPadding = 8.dp,
            horizontalPadding = dividerHorizontalPadding,
        ),
        DataSafetyItem.Row(
            key = "local.vault.algorithm.key",
            title = labelKey,
            value = "KDF($labelAppPassword, $labelHash)",
            secondary = true,
        ),
        DataSafetyItem.Spacer(
            key = "local.vault.algorithm.outro.spacer",
            height = 16.dp,
        ),
        DataSafetyItem.Text(
            key = "local.vault.algorithm.outro",
            text = text(Res.string.datasafety_local_encryption_algorithm_outro),
            secondary = true,
        ),
        DataSafetyItem.Divider(
            key = "local.vault.unlocking.divider",
            verticalPadding = 16.dp,
        ),
        DataSafetyItem.Text(
            key = "local.vault.unlocking",
            text = format(
                Res.string.datasafety_local_unlocking_vault,
                labelKey,
            ),
        ),
    )
}

@PublishedApi
internal inline fun dataSafetyRemoteItems(
    text: (StringResource) -> String,
    platform: Platform,
): List<DataSafetyItem> = listOfNotNull(
    DataSafetyItem.LargeSection(
        key = "remote.section",
        text = text(Res.string.datasafety_remote_section),
    ),
    DataSafetyItem.Text(
        key = "remote.text",
        text = text(Res.string.datasafety_remote_text),
    ),
    DataSafetyItem.LearnMore(
        key = "remote.learn_more",
        url = DATA_SAFETY_LEARN_MORE_URL,
    ).takeIf {
        platform.hasBrowser()
    },
)
