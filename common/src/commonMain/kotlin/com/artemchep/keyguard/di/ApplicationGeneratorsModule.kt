package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.service.crypto.GpgKeyGeneratorUnsupported
import com.artemchep.keyguard.common.service.placeholder.PlaceholderFactoryRegistry
import com.artemchep.keyguard.common.service.placeholder.impl.CipherPlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.CommentPlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.CustomPlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.DateTimePlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.EnvironmentPlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.TextReplaceRegexPlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.TextTransformPlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.UrlPlaceholder
import com.artemchep.keyguard.common.service.relays.di.EmailRelayModule
import com.artemchep.keyguard.common.service.wordlist.WordlistService
import com.artemchep.keyguard.common.service.wordlist.impl.WordlistServiceImpl
import com.artemchep.keyguard.common.usecase.ConfirmAccessByPasswordUseCase
import com.artemchep.keyguard.common.usecase.GetAutofillPasswordsEnabled
import com.artemchep.keyguard.common.usecase.GetCheckPwnedPasswords
import com.artemchep.keyguard.common.usecase.GetPassword
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.common.usecase.PutAutofillPasswordsEnabled
import com.artemchep.keyguard.common.usecase.PutCheckPwnedPasswords
import com.artemchep.keyguard.common.usecase.ReadWordlistFromFile
import com.artemchep.keyguard.common.usecase.ReadWordlistFromUrl
import com.artemchep.keyguard.common.usecase.impl.ConfirmAccessByPasswordUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillPasswordsEnabledImpl
import com.artemchep.keyguard.common.usecase.impl.GetCheckPwnedPasswordsImpl
import com.artemchep.keyguard.common.usecase.impl.GetPasswordImpl
import com.artemchep.keyguard.common.usecase.impl.GetPasswordStrengthImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillPasswordsEnabledImpl
import com.artemchep.keyguard.common.usecase.impl.PutCheckPwnedPasswordsImpl
import com.artemchep.keyguard.common.usecase.impl.ReadWordlistFromFileImpl
import com.artemchep.keyguard.common.usecase.impl.ReadWordlistFromUrlImpl
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

internal class ApplicationGeneratorsModule {
    val module = module {
        includes(EmailRelayModule().module)
        single<ConfirmAccessByPasswordUseCaseImpl>() bind ConfirmAccessByPasswordUseCase::class

        single<GetPassword> {
            GetPasswordImpl(
                cryptoGenerator = get(),
                keyPairGenerator = get(),
                gpgKeyGenerator = getOrNull() ?: GpgKeyGeneratorUnsupported,
                getPassphrase = get(),
                getPinCode = get(),
            )
        }

        single<GetCheckPwnedPasswordsImpl>() bind GetCheckPwnedPasswords::class

        single<GetAutofillPasswordsEnabledImpl>() bind GetAutofillPasswordsEnabled::class

        single<PutCheckPwnedPasswordsImpl>() bind PutCheckPwnedPasswords::class

        single<PutAutofillPasswordsEnabledImpl>() bind PutAutofillPasswordsEnabled::class

        single<ReadWordlistFromFileImpl>() bind ReadWordlistFromFile::class

        single<ReadWordlistFromUrl> {
            ReadWordlistFromUrlImpl(
                httpClient = get(named("curl")),
            )
        }

        single<CipherPlaceholder.Factory>()

        single<CommentPlaceholder.Factory>()

        single<CustomPlaceholder.Factory>()

        single<DateTimePlaceholder.Factory>()

        single<EnvironmentPlaceholder.Factory>()

        single<TextReplaceRegexPlaceholder.Factory>()

        single<TextTransformPlaceholder.Factory>()

        single<UrlPlaceholder.Factory>()

        single<PlaceholderFactoryRegistry> {
            PlaceholderFactoryRegistry(
                listOf(
                    get<CipherPlaceholder.Factory>(),
                    get<CommentPlaceholder.Factory>(),
                    get<CustomPlaceholder.Factory>(),
                    get<DateTimePlaceholder.Factory>(),
                    get<EnvironmentPlaceholder.Factory>(),
                    get<TextReplaceRegexPlaceholder.Factory>(),
                    get<TextTransformPlaceholder.Factory>(),
                    get<UrlPlaceholder.Factory>(),
                ),
            )
        }

        single<WordlistServiceImpl>() bind WordlistService::class

        single<GetPasswordStrengthImpl>() bind GetPasswordStrength::class
    }
}
