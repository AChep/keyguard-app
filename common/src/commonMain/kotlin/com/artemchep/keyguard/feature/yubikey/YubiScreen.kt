package com.artemchep.keyguard.feature.yubikey

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.grid.SimpleGridLayout
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.horizontalPaddingHalf
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import com.artemchep.keyguard.ui.util.HorizontalDivider
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.rememberInstance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YubiScreen() {
    val updatedShowMessage by run {
        val showMessage: ShowMessage by rememberInstance()
        rememberUpdatedState(showMessage)
    }
    val yubiKey = rememberYubiKey { event ->
        val msg = event.fold(
            ifLeft = { e ->
                ToastMessage(
                    title = "Failed to receive YubiKey code",
                    text = e.message,
                    type = ToastMessage.Type.ERROR,
                )
            },
            ifRight = { code ->
                ToastMessage(
                    title = "Received YubiKey code",
                    text = code,
                    type = ToastMessage.Type.SUCCESS,
                )
            },
        )
        updatedShowMessage.copy(msg)
    }

    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text("YubiKey")
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        YubiKeyManual(
            onSend = {
                // Do nothing.
            },
        )
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        HorizontalDivider()
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        SimpleGridLayout(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPaddingHalf),
        ) {
            YubiKeyUsbCard(
                state = yubiKey.usbState,
            )
            YubiKeyNfcCard(
                state = yubiKey.nfcState,
            )
        }
    }
}

@Composable
fun YubiKeyManual(
    modifier: Modifier = Modifier,
    onSend: (String) -> Unit,
) {
    Column(
        modifier = modifier,
    ) {
        val textState = remember {
            mutableStateOf("")
        }
        val fieldState = remember(textState) {
            derivedStateOf {
                TextFieldModel2(
                    state = textState,
                    onChange = textState::value::set,
                )
            }
        }

        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            text = stringResource(Res.string.addaccount2fa_yubikey_manual_text),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        FlatTextField(
            modifier = Modifier
                .padding(horizontal = 16.dp),
            label = stringResource(Res.string.verification_code),
            value = fieldState.value,
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = true,
                keyboardType = KeyboardType.Text,
            ),
        )
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        val enabled = remember(textState) {
            derivedStateOf {
                textState.value.isNotBlank()
            }
        }
        val updatedOnSend by rememberUpdatedState(onSend)
        Button(
            modifier = Modifier
                .padding(horizontal = 16.dp),
            enabled = enabled.value,
            onClick = {
                updatedOnSend(textState.value)
            },
        ) {
            Text(stringResource(Res.string.send))
        }
    }
}
