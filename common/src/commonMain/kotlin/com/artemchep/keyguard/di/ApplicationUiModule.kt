package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.service.clipboard.ClipboardEventBus
import com.artemchep.keyguard.common.service.keyboard.KeyboardShortcutsService
import com.artemchep.keyguard.common.service.keyboard.KeyboardShortcutsServiceHost
import com.artemchep.keyguard.common.service.keyboard.KeyboardShortcutsServiceImpl
import com.artemchep.keyguard.common.usecase.MessageHub
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.usecase.impl.MessageHubImpl
import com.artemchep.keyguard.common.usecase.impl.WindowCoroutineScopeImpl
import kotlinx.coroutines.GlobalScope
import org.koin.dsl.module
import org.koin.plugin.module.dsl.factory
import org.koin.plugin.module.dsl.single

internal class ApplicationUiModule {
    val module = module {
        single<KeyboardShortcutsServiceImpl>()

        single<KeyboardShortcutsService> {
            get<KeyboardShortcutsServiceImpl>()
        }

        single<KeyboardShortcutsServiceHost> {
            get<KeyboardShortcutsServiceImpl>()
        }

        factory<MessageHub> {
            get<MessageHubImpl>()
        }

        factory<ShowMessage> {
            get<MessageHubImpl>()
        }

        single<MessageHubImpl>()

        single<WindowCoroutineScope> {
            WindowCoroutineScopeImpl(
                scope = GlobalScope,
                showMessage = get(),
            )
        }

        single<ClipboardEventBus>()
    }
}
