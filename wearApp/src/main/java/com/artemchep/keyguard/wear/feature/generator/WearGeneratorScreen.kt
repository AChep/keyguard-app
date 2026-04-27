package com.artemchep.keyguard.wear.feature.generator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.LocalAppMode
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.relays.api.EmailRelay
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.usecase.GetPassword
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.common.usecase.KeyPairExport
import com.artemchep.keyguard.common.usecase.KeyPrivateExport
import com.artemchep.keyguard.common.usecase.KeyPublicExport
import com.artemchep.keyguard.common.usecase.NumberFormatter
import com.artemchep.keyguard.common.usecase.impl.GetCanWriteStub
import com.artemchep.keyguard.feature.generator.GeneratorRoute
import com.artemchep.keyguard.feature.generator.GeneratorState
import com.artemchep.keyguard.feature.generator.colorizePasswordOrEmpty
import com.artemchep.keyguard.feature.generator.produceGeneratorState
import com.artemchep.keyguard.feature.home.vault.model.Visibility
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.filter_header_title
import com.artemchep.keyguard.res.generator_header_title
import com.artemchep.keyguard.res.generator_key_length_title
import com.artemchep.keyguard.res.generator_regenerate_button
import com.artemchep.keyguard.wear.feature.picker.WearPickerRoute
import com.artemchep.keyguard.wear.feature.value.WearValueViewRoute
import com.artemchep.keyguard.wear.ui.WearListAction
import com.artemchep.keyguard.wear.ui.WearListCard
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import com.artemchep.keyguard.wear.ui.WearSectionHeader
import com.artemchep.keyguard.wear.ui.skeletonItems
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun WearGeneratorScreen(
    args: GeneratorRoute.Args = wearGeneratorArgs(),
) {
    val state = wearGeneratorScreenState(args = args)
    val contentState = if (state is Loadable.Ok) {
        wearGeneratorContentState(state.value)
    } else {
        null
    }
    WearScaffoldScreen(
        title = stringResource(Res.string.generator_header_title),
    ) { transformationSpec ->
        when (state) {
            Loadable.Loading -> {
                skeletonItems(
                    transformationSpec = transformationSpec,
                    count = 6,
                )
            }

            is Loadable.Ok -> {
                contentState?.let { content ->
                    WearGeneratorContent(
                        state = state.value,
                        content = content,
                        transformationSpec = transformationSpec,
                    )
                }
            }
        }
    }
}

private data class WearGeneratorContentState(
    val value: GeneratorState.Value?,
    val settings: List<WearGeneratorSetting>,
)

@Composable
private fun wearGeneratorContentState(
    state: GeneratorState,
): WearGeneratorContentState {
    val value by state.valueState.collectAsStateWithLifecycle()
    val filter by state.filterState.collectAsStateWithLifecycle()
    return WearGeneratorContentState(
        value = value,
        settings = filter.toWearGeneratorSettings(),
    )
}

private fun TransformingLazyColumnScope.WearGeneratorContent(
    state: GeneratorState,
    content: WearGeneratorContentState,
    transformationSpec: TransformationSpec,
) {
    item("generator.type") {
        val surfaceTransformation = SurfaceTransformation(transformationSpec)
        WearGeneratorTypeItem(
            modifier = Modifier
                .fillMaxWidth()
                .transformedHeight(this, transformationSpec),
            state = state,
            transformation = surfaceTransformation,
        )
    }
    item("generator.value") {
        val surfaceTransformation = SurfaceTransformation(transformationSpec)
        WearGeneratorValueItem(
            modifier = Modifier
                .fillMaxWidth()
                .transformedHeight(this, transformationSpec),
            value = content.value,
            transformation = surfaceTransformation,
        )
    }
    item("generator.refresh") {
        val surfaceTransformation = SurfaceTransformation(transformationSpec)
        OutlinedButton(
            modifier = Modifier
                .fillMaxWidth()
                .transformedHeight(this, transformationSpec),
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
            },
            onClick = {
                content.value?.onRefresh?.invoke()
            },
            enabled = content.value?.onRefresh != null,
            transformation = surfaceTransformation,
        ) {
            Text(
                text = stringResource(Res.string.generator_regenerate_button),
            )
        }
    }
    if (content.settings.isNotEmpty()) {
        item("generator.options.header") {
            val surfaceTransformation = SurfaceTransformation(transformationSpec)
            WearSectionHeader(
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, transformationSpec),
                title = stringResource(Res.string.filter_header_title),
                transformation = surfaceTransformation,
            )
        }
        content.settings.forEach { setting ->
            item("generator.setting.${setting.key}") {
                val surfaceTransformation = SurfaceTransformation(transformationSpec)
                when (setting) {
                    is WearGeneratorSetting.Length -> WearGeneratorLengthItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        item = setting,
                        transformation = surfaceTransformation,
                    )

                    is WearGeneratorSetting.Switch -> WearGeneratorSwitchItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        item = setting,
                        transformation = surfaceTransformation,
                    )
                }
            }
        }
    }
}

@Composable
private fun WearGeneratorTypeItem(
    modifier: Modifier = Modifier,
    state: GeneratorState,
    transformation: SurfaceTransformation? = null,
) {
    val type by state.typeState.collectAsStateWithLifecycle()
    val navigationController = LocalNavigationController.current
    val actions = type.items
    WearListAction(
        modifier = modifier,
        icon = {
            Icon(
                imageVector = Icons.Outlined.Password,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
        },
        title = {
            Text(
                text = type.title,
            )
        },
        onClick = {
            val route = WearPickerRoute(actions = actions)
            val intent = NavigationIntent.NavigateToRoute(route = route)
            navigationController.queue(intent)
        },
        transformation = transformation,
    )
}

@Composable
private fun WearGeneratorValueItem(
    modifier: Modifier = Modifier,
    value: GeneratorState.Value?,
    transformation: SurfaceTransformation? = null,
) {
    if (value == null) {
        // TODO: Would be nice to add a better UI here. Need to re-check how the
        //  main app handles it.
        return
    }

    val generatorTitle = stringResource(Res.string.generator_header_title)
    val navigationController by rememberUpdatedState(LocalNavigationController.current)
    WearListCard(
        modifier = modifier,
        title = value.title
            ?.let { title ->
                // composable
                {
                    Text(text = title)
                }
            },
        text = {
            Text(
                modifier = Modifier
                    .fillMaxWidth(),
                text = colorizePasswordOrEmpty(value.password),
                textAlign = TextAlign.Start,
                style = MaterialTheme.typography.displayMedium,
            )
        },
        onClick = {
            val route = WearValueViewRoute(
                title = generatorTitle,
                value = value.password,
                visibility = Visibility(),
                monospace = false,
                colorize = true,
                actions = listOf(),
            )
            navigationController.queue(
                NavigationIntent.NavigateToRoute(route),
            )
        },
        transformation = transformation,
    )
}

@Composable
private fun WearGeneratorLengthItem(
    modifier: Modifier = Modifier,
    item: WearGeneratorSetting.Length,
    transformation: SurfaceTransformation? = null,
) {
    val onChange by rememberUpdatedState(item.onChange)
    WearListCard(
        modifier = modifier,
        title = {
            Text(
                text = stringResource(Res.string.generator_key_length_title),
            )
        },
        text = {
            Text(text = item.value.toString())
        },
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    enabled = onChange != null && item.value > item.min,
                    onClick = {
                        val value = (item.value - 1).coerceAtLeast(item.min)
                        onChange?.invoke(value)
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Remove,
                        contentDescription = null,
                    )
                }
                IconButton(
                    enabled = onChange != null && item.value < item.max,
                    onClick = {
                        val value = (item.value + 1).coerceAtMost(item.max)
                        onChange?.invoke(value)
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                    )
                }
            }
        },
        transformation = transformation,
    )
}

@Composable
private fun WearGeneratorSwitchItem(
    modifier: Modifier = Modifier,
    item: WearGeneratorSetting.Switch,
    transformation: SurfaceTransformation? = null,
) {
    val onChange by rememberUpdatedState(item.onChange)
    SwitchButton(
        modifier = modifier,
        checked = item.checked,
        enabled = onChange != null,
        onCheckedChange = { newValue ->
            onChange?.invoke(newValue)
        },
        label = {
            Text(
                text = item.title,
            )
        },
        secondaryLabel = item.text?.let { text ->
            {
                Text(
                    text = text,
                )
            }
        },
        transformation = transformation,
    )
}

@Composable
private fun wearGeneratorScreenState(
    args: GeneratorRoute.Args,
): Loadable<GeneratorState> = with(localDI().direct) {
    val config = wearGeneratorConfig()
    produceGeneratorState(
        mode = LocalAppMode.current,
        args = args.copy(
            password = config.args.password,
            username = config.args.username,
            sshKey = config.args.sshKey,
        ),
        key = "wear_generator",
        addGeneratorHistory = null,
        getPassword = instance<GetPassword>(),
        getPasswordStrength = instance<GetPasswordStrength>(),
        getProfiles = null,
        getEmailRelays = null,
        getWordlists = null,
        getWordlistPrimitive = null,
        cryptoGenerator = instance<CryptoGenerator>(),
        keyPairExport = instance<KeyPairExport>(),
        publicKeyExport = instance<KeyPublicExport>(),
        privateKeyExport = instance<KeyPrivateExport>(),
        numberFormatter = instance<NumberFormatter>(),
        getCanWrite = GetCanWriteStub(),
        tldService = instance<TldService>(),
        clipboardService = instance<ClipboardService>(),
        emailRelays = emptyList<EmailRelay>(),
    )
}
