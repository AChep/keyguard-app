package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import arrow.core.throwIfFatal
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.service.autotype.AutotypeService
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.focus.FocusRequester2
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.Dimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingAutotypeTestProvider(
    directDI: DirectDI,
): SettingComponent {
    val supported = !isRelease && CurrentPlatform is Platform.Desktop.MacOS
    if (!supported) {
        return flowOf(null)
    }

    return settingAutotypeTestProvider(
        autotypeService = directDI.instance(),
        windowCoroutineScope = directDI.instance(),
    )
}

fun settingAutotypeTestProvider(
    autotypeService: AutotypeService,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = flowOf(
    SettingIi(
        platformClasses = listOf(
            Platform.Desktop.MacOS::class,
        ),
        search = SettingIi.Search(
            group = "ui",
            tokens = listOf(
                "autotype",
                "type",
                "keyboard",
                "test",
            ),
        ),
    ) {
        SettingAutotypeTest(
            autotypeService = autotypeService,
            windowCoroutineScope = windowCoroutineScope,
        )
    },
)

@Composable
private fun SettingAutotypeTest(
    autotypeService: AutotypeService,
    windowCoroutineScope: WindowCoroutineScope,
) {
    var inProgress by remember {
        mutableStateOf(false)
    }

    val inputState = remember {
        mutableStateOf("Hello, Keyguard!")
    }
    val outputState = remember {
        mutableStateOf("")
    }
    val outputFocusRequester = remember {
        FocusRequester2()
    }

    val inputModel = TextFieldModel2(
        state = inputState,
        text = inputState.value,
        onChange = inputState::value::set,
    )
    val outputModel = TextFieldModel2(
        state = outputState,
        text = outputState.value,
        onChange = outputState::value::set,
    )

    val updatedAutotypeService by rememberUpdatedState(autotypeService)
    FlatItemLayoutExpressive(
        shapeState = LocalSettingItemShape.current,
        leading = icon<RowScope>(Icons.Outlined.Keyboard),
        content = {
            FlatItemTextContent(
                title = {
                    Text("Autotype test")
                },
            )
        },
        footer = {
            Column(
                modifier = Modifier
                    .padding(
                        horizontal = Dimens.contentPadding,
                        vertical = 4.dp,
                    ),
            ) {
                FlatTextField(
                    label = "Input",
                    value = inputModel,
                    shapeState = ShapeState.ALL,
                )
                FlatTextField(
                    modifier = Modifier
                        .padding(top = 8.dp),
                    focusRequester = outputFocusRequester,
                    label = "Output",
                    value = outputModel,
                    shapeState = ShapeState.ALL,
                )
                Spacer(
                    modifier = Modifier
                        .height(8.dp),
                )

                Button(
                    enabled = !inProgress,
                    onClick = {
                        val payload = inputState.value
                        inProgress = true
                        outputState.value = ""
                        outputFocusRequester.requestFocus()
                        windowCoroutineScope.launch {
                            try {
                                delay(500L)
                                updatedAutotypeService.type(payload).bind()
                            } catch (e: Throwable) {
                                e.throwIfFatal()
                                e.printStackTrace()
                            } finally {
                                inProgress = false
                            }
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(
                        modifier = Modifier
                            .width(Dimens.buttonIconPadding),
                    )
                    Text(
                        text = "Run test",
                    )
                }
            }
        },
    )
}
