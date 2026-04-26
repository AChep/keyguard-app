package com.artemchep.keyguard.wear.feature.generator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
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
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.wear.feature.picker.WearPickerRoute
import com.artemchep.keyguard.wear.ui.WearScaffoldColumn
import com.artemchep.keyguard.wear.ui.WearListAction
import com.artemchep.keyguard.wear.ui.WearListCard
import com.artemchep.keyguard.wear.ui.WearSectionHeader
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun WearGeneratorScreen(
    args: GeneratorRoute.Args = wearGeneratorArgs(),
) {
    val state = wearGeneratorScreenState(args = args)
    WearScaffoldColumn(title = "Generator") {
        when (state) {
            Loadable.Loading -> {
                repeat(6) {
                    SkeletonItem()
                }
            }

            is Loadable.Ok -> {
                val generatorState = state.value
                WearGeneratorContent(
                    state = generatorState,
                )
            }
        }
    }
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

@Composable
private fun WearGeneratorTypeItem(
    state: GeneratorState,
) {
    val type by state.typeState.collectAsStateWithLifecycle()
    val navigationController = LocalNavigationController.current
    val actions = type.items
    WearListAction(
        icon = {
            Icon(
                imageVector = Icons.Outlined.Password,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
        },
        title = {
            Text(
                text = "Generator",
            )
        },
        text = {
            Text(
                text = type.title,
            )
        },
        onClick = {
            val route = WearPickerRoute(actions = actions)
            val intent = NavigationIntent.NavigateToRoute(route = route)
            navigationController.queue(intent)
        },
    )
}

@Composable
private fun WearGeneratorContent(
    state: GeneratorState,
) {
    val value by state.valueState.collectAsStateWithLifecycle()
    val filter by state.filterState.collectAsStateWithLifecycle()
    val settings = filter.toWearGeneratorSettings()

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WearGeneratorTypeItem(state)
        WearGeneratorValueItem(value)
        WearListAction(
            icon = {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
            },
            title = {
                Text(
                    text = "Copy",
                )
            },
            onClick = value?.onCopy,
        )
        WearListAction(
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
            },
            title = {
                Text(
                    text = "Refresh",
                )
            },
            onClick = value?.onRefresh,
        )
        if (settings.isNotEmpty()) {
            WearSectionHeader(
                title = "Options",
            )
            settings.forEach { setting ->
                when (setting) {
                    is WearGeneratorSetting.Length -> WearGeneratorLengthItem(setting)
                    is WearGeneratorSetting.Switch -> WearGeneratorSwitchItem(setting)
                }
            }
        }
    }
}

@Composable
private fun WearGeneratorValueItem(
    value: GeneratorState.Value?,
) {
    if (value == null) {
        // TODO: Would be nice to add a better UI here. Need to re-check how the
        //  main app handles it.
        return
    }

    WearListCard(
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
    )
}

@Composable
private fun WearGeneratorLengthItem(
    item: WearGeneratorSetting.Length,
) {
    val onChange by rememberUpdatedState(item.onChange)
    WearListCard(
        title = {
            Text(text = "Length")
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
    )
}

@Composable
private fun WearGeneratorSwitchItem(
    item: WearGeneratorSetting.Switch,
) {
    val onChange by rememberUpdatedState(item.onChange)
    SwitchButton(
        modifier = Modifier.fillMaxWidth(),
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
    )
}
