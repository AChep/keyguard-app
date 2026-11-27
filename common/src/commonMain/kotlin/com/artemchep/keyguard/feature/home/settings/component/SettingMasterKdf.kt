package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.usecase.GetMasterKdf
import com.artemchep.keyguard.feature.changekdf.ChangeKdfRoute
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.masterkdf.memoryMibText
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.ChevronIcon
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingMasterKdfProvider(
    koinScope: Scope,
): SettingComponent = settingMasterKdfProvider(
    getMasterKdf = koinScope.get(),
    context = koinScope.get(),
)

fun settingMasterKdfProvider(
    getMasterKdf: GetMasterKdf,
    context: LeContext,
    platform: Platform = CurrentPlatform,
): SettingComponent {
    // The watch app has no key derivation screen.
    if (platform.hasWatch()) return flowOf(null)
    return getMasterKdf().map { kdf ->
        val summary = formatMasterKdfSummary(kdf, context)
        SettingIi(
            search = SettingIi.Search(
                group = "password",
                tokens = listOf(
                    "key",
                    "derivation",
                    "kdf",
                    "argon2",
                ),
            ),
        ) {
            val navigationController by rememberUpdatedState(LocalNavigationController.current)
            SettingMasterKdf(
                summary = summary,
                onClick = {
                    val intent = NavigationIntent.NavigateToRoute(
                        route = ChangeKdfRoute,
                    )
                    navigationController.queue(intent)
                },
            )
        }
    }
}

@Composable
private fun SettingMasterKdf(
    summary: String?,
    onClick: (() -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.Password,
        subIcon = Icons.Outlined.Tune,
        title = stringResource(Res.string.masterkdf_title),
        text = summary,
        trailing = {
            ChevronIcon()
        },
        onClick = onClick,
    )
}

/**
 * Describes the key derivation of the vault, for example
 * "Argon2id · 64 MiB · 3 iterations · 4 lanes"; `null` if
 * there is nothing to describe.
 */
suspend fun formatMasterKdfSummary(
    kdf: MasterKdf?,
    context: LeContext,
): String? = when (kdf) {
    is MasterKdf.Argon2id -> {
        val memory = textResource(
            Res.string.masterkdf_summary_memory,
            context,
            kdf.memoryMibText(),
        )
        val iterations = textResource(
            Res.plurals.masterkdf_summary_iterations_plural,
            context,
            kdf.iterations,
            kdf.iterations,
        )
        val parallelism = textResource(
            Res.plurals.masterkdf_summary_parallelism_plural,
            context,
            kdf.parallelism,
            kdf.parallelism,
        )
        textResource(
            Res.string.masterkdf_summary_argon2id,
            context,
            memory,
            iterations,
            parallelism,
        )
    }

    MasterKdf.Pbkdf2Legacy -> textResource(Res.string.masterkdf_summary_pbkdf2_legacy, context)
    is MasterKdf.Unsupported,
    null,
    -> null
}
