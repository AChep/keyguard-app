package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.browseragent.BrowserAutofillAgentStatusService
import com.artemchep.keyguard.common.service.browseragent.impl.BrowserAutofillAgentStatusServiceImpl
import com.artemchep.keyguard.common.service.exposedaccount.ExposedAccountRepository
import com.artemchep.keyguard.common.service.exposedaccount.impl.ExposedAccountRepositoryImpl
import com.artemchep.keyguard.common.service.id.IdRepository
import com.artemchep.keyguard.common.service.id.impl.IdRepositoryImpl
import com.artemchep.keyguard.common.service.justgetmydata.JustGetMyDataService
import com.artemchep.keyguard.common.service.justgetmydata.impl.JustGetMyDataServiceImpl
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreFactory
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.common.service.logging.LogSinkRegistry
import com.artemchep.keyguard.common.service.logging.PlatformLogSinkRegistry
import com.artemchep.keyguard.common.service.logging.inmemory.InMemoryLogRepository
import com.artemchep.keyguard.common.service.logging.inmemory.InMemoryLogRepositoryImpl
import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.service.settings.impl.SettingsRepositoryImpl
import com.artemchep.keyguard.common.service.state.StateRepository
import com.artemchep.keyguard.common.service.state.impl.StateRepositoryImpl
import com.artemchep.keyguard.common.service.urlblock.impl.UrlBlockRepositoryExposed
import com.artemchep.keyguard.common.service.vault.KeyReadRepository
import com.artemchep.keyguard.common.service.vault.KeyReadWriteRepository
import com.artemchep.keyguard.common.service.vault.impl.KeyRepositoryImpl
import com.artemchep.keyguard.common.usecase.GetAllowScreenshots
import com.artemchep.keyguard.common.usecase.GetAllowScreenshotsVariants
import com.artemchep.keyguard.common.usecase.GetAllowTwoPanelLayoutInLandscape
import com.artemchep.keyguard.common.usecase.GetAllowTwoPanelLayoutInPortrait
import com.artemchep.keyguard.common.usecase.GetAppBuildType
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.GetAppVersion
import com.artemchep.keyguard.common.usecase.GetAppVersionCode
import com.artemchep.keyguard.common.usecase.GetAppVersionName
import com.artemchep.keyguard.common.usecase.GetAutofillBlockedUrisExposed
import com.artemchep.keyguard.common.usecase.GetAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.GetAutofillInlineSuggestions
import com.artemchep.keyguard.common.usecase.GetAutofillManualSelection
import com.artemchep.keyguard.common.usecase.GetAutofillPasskeysEnabled
import com.artemchep.keyguard.common.usecase.GetAutofillRespectAutofillOff
import com.artemchep.keyguard.common.usecase.GetAutofillSaveRequest
import com.artemchep.keyguard.common.usecase.GetAutofillSaveUri
import com.artemchep.keyguard.common.usecase.GetBrowserAutofillAgent
import com.artemchep.keyguard.common.usecase.GetBrowserAutofillAgentPairingCode
import com.artemchep.keyguard.common.usecase.GetBrowserAutofillAgentStatus
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCheckPasskeys
import com.artemchep.keyguard.common.usecase.GetCheckPwnedServices
import com.artemchep.keyguard.common.usecase.GetCheckTwoFA
import com.artemchep.keyguard.common.usecase.GetClipboardAutoClear
import com.artemchep.keyguard.common.usecase.GetClipboardAutoClearVariants
import com.artemchep.keyguard.common.usecase.GetClipboardAutoRefresh
import com.artemchep.keyguard.common.usecase.GetClipboardAutoRefreshVariants
import com.artemchep.keyguard.common.usecase.GetCloseToTray
import com.artemchep.keyguard.common.usecase.GetColors
import com.artemchep.keyguard.common.usecase.GetColorsVariants
import com.artemchep.keyguard.common.usecase.GetConcealFields
import com.artemchep.keyguard.common.usecase.GetDebugScreenDelay
import com.artemchep.keyguard.common.usecase.GetFont
import com.artemchep.keyguard.common.usecase.GetFontVariants
import com.artemchep.keyguard.common.usecase.GetGpgAgent
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindowVariants
import com.artemchep.keyguard.common.usecase.GetGpgAgentDisplayKeyNames
import com.artemchep.keyguard.common.usecase.GetGpgAgentFilter
import com.artemchep.keyguard.common.usecase.GetGpgAgentStatus
import com.artemchep.keyguard.common.usecase.GetGravatar
import com.artemchep.keyguard.common.usecase.GetGravatarUrl
import com.artemchep.keyguard.common.usecase.GetInMemoryLogs
import com.artemchep.keyguard.common.usecase.GetInMemoryLogsEnabled
import com.artemchep.keyguard.common.usecase.GetJustDeleteMeByUrl
import com.artemchep.keyguard.common.usecase.GetJustGetMyDataByUrl
import com.artemchep.keyguard.common.usecase.GetKeepScreenOn
import com.artemchep.keyguard.common.usecase.GetLocaleVariants
import com.artemchep.keyguard.common.usecase.GetMarkdown
import com.artemchep.keyguard.common.usecase.GetMinimizeOnCopy
import com.artemchep.keyguard.common.usecase.GetNavAnimation
import com.artemchep.keyguard.common.usecase.GetNavAnimationVariants
import com.artemchep.keyguard.common.usecase.GetNavLabel
import com.artemchep.keyguard.common.usecase.GetOnboardingLastVisitInstant
import com.artemchep.keyguard.common.usecase.GetPasskeys
import com.artemchep.keyguard.common.usecase.GetPassphrase
import com.artemchep.keyguard.common.usecase.GetPersistedNavItemsConfig
import com.artemchep.keyguard.common.usecase.GetPinCode
import com.artemchep.keyguard.common.usecase.GetProducts
import com.artemchep.keyguard.common.usecase.GetScreenState
import com.artemchep.keyguard.common.usecase.GetSshAgent
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindowVariants
import com.artemchep.keyguard.common.usecase.GetSshAgentDisplayKeyNames
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetSshAgentStatus
import com.artemchep.keyguard.common.usecase.GetSubscriptions
import com.artemchep.keyguard.common.usecase.GetTheme
import com.artemchep.keyguard.common.usecase.GetThemeExpressive
import com.artemchep.keyguard.common.usecase.GetThemeUseAmoledDark
import com.artemchep.keyguard.common.usecase.GetThemeVariants
import com.artemchep.keyguard.common.usecase.GetTwoFa
import com.artemchep.keyguard.common.usecase.GetUseExternalBrowser
import com.artemchep.keyguard.common.usecase.GetVersionLog
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.common.usecase.GetWriteAccess
import com.artemchep.keyguard.common.usecase.PutAllowScreenshots
import com.artemchep.keyguard.common.usecase.PutAllowTwoPanelLayoutInLandscape
import com.artemchep.keyguard.common.usecase.PutAllowTwoPanelLayoutInPortrait
import com.artemchep.keyguard.common.usecase.PutAppIcons
import com.artemchep.keyguard.common.usecase.PutAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.PutAutofillInlineSuggestions
import com.artemchep.keyguard.common.usecase.PutAutofillManualSelection
import com.artemchep.keyguard.common.usecase.PutAutofillPasskeysEnabled
import com.artemchep.keyguard.common.usecase.PutAutofillRespectAutofillOff
import com.artemchep.keyguard.common.usecase.PutAutofillSaveRequest
import com.artemchep.keyguard.common.usecase.PutAutofillSaveUri
import com.artemchep.keyguard.common.usecase.PutBrowserAutofillAgent
import com.artemchep.keyguard.common.usecase.PutBrowserAutofillAgentPairingCode
import com.artemchep.keyguard.common.usecase.PutCheckPasskeys
import com.artemchep.keyguard.common.usecase.PutCheckPwnedServices
import com.artemchep.keyguard.common.usecase.PutCheckTwoFA
import com.artemchep.keyguard.common.usecase.PutClipboardAutoClear
import com.artemchep.keyguard.common.usecase.PutClipboardAutoRefresh
import com.artemchep.keyguard.common.usecase.PutCloseToTray
import com.artemchep.keyguard.common.usecase.PutColors
import com.artemchep.keyguard.common.usecase.PutConcealFields
import com.artemchep.keyguard.common.usecase.PutDebugScreenDelay
import com.artemchep.keyguard.common.usecase.PutFont
import com.artemchep.keyguard.common.usecase.PutGpgAgent
import com.artemchep.keyguard.common.usecase.PutGpgAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.PutGpgAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.PutGpgAgentDisplayKeyNames
import com.artemchep.keyguard.common.usecase.PutGpgAgentFilter
import com.artemchep.keyguard.common.usecase.PutGravatar
import com.artemchep.keyguard.common.usecase.PutInMemoryLogsEnabled
import com.artemchep.keyguard.common.usecase.PutKeepScreenOn
import com.artemchep.keyguard.common.usecase.PutMarkdown
import com.artemchep.keyguard.common.usecase.PutMinimizeOnCopy
import com.artemchep.keyguard.common.usecase.PutNavAnimation
import com.artemchep.keyguard.common.usecase.PutNavItemsConfig
import com.artemchep.keyguard.common.usecase.PutNavLabel
import com.artemchep.keyguard.common.usecase.PutOnboardingLastVisitInstant
import com.artemchep.keyguard.common.usecase.PutScreenState
import com.artemchep.keyguard.common.usecase.PutSshAgent
import com.artemchep.keyguard.common.usecase.PutSshAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.PutSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.PutSshAgentDisplayKeyNames
import com.artemchep.keyguard.common.usecase.PutSshAgentFilter
import com.artemchep.keyguard.common.usecase.PutTheme
import com.artemchep.keyguard.common.usecase.PutThemeExpressive
import com.artemchep.keyguard.common.usecase.PutThemeUseAmoledDark
import com.artemchep.keyguard.common.usecase.PutUseExternalBrowser
import com.artemchep.keyguard.common.usecase.PutWebsiteIcons
import com.artemchep.keyguard.common.usecase.PutWriteAccess
import com.artemchep.keyguard.common.usecase.impl.GetAllowScreenshotsImpl
import com.artemchep.keyguard.common.usecase.impl.GetAllowScreenshotsVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetAllowTwoPanelLayoutInLandscapeImpl
import com.artemchep.keyguard.common.usecase.impl.GetAllowTwoPanelLayoutInPortraitImpl
import com.artemchep.keyguard.common.usecase.impl.GetAppBuildTypeImpl
import com.artemchep.keyguard.common.usecase.impl.GetAppIconsImpl
import com.artemchep.keyguard.common.usecase.impl.GetAppVersionCodeImpl
import com.artemchep.keyguard.common.usecase.impl.GetAppVersionImpl
import com.artemchep.keyguard.common.usecase.impl.GetAppVersionNameImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillBlockedUrisExposedImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillDefaultMatchDetectionImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillInlineSuggestionsImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillManualSelectionImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillPasskeysEnabledImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillRespectAutofillOffImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillSaveRequestImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillSaveUriImpl
import com.artemchep.keyguard.common.usecase.impl.GetBrowserAutofillAgentImpl
import com.artemchep.keyguard.common.usecase.impl.GetBrowserAutofillAgentPairingCodeImpl
import com.artemchep.keyguard.common.usecase.impl.GetBrowserAutofillAgentStatusImpl
import com.artemchep.keyguard.common.usecase.impl.GetCanWriteImpl
import com.artemchep.keyguard.common.usecase.impl.GetCheckPasskeysImpl
import com.artemchep.keyguard.common.usecase.impl.GetCheckPwnedServicesImpl
import com.artemchep.keyguard.common.usecase.impl.GetCheckTwoFAImpl
import com.artemchep.keyguard.common.usecase.impl.GetClipboardAutoClearImpl
import com.artemchep.keyguard.common.usecase.impl.GetClipboardAutoClearVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetClipboardAutoRefreshImpl
import com.artemchep.keyguard.common.usecase.impl.GetClipboardAutoRefreshVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetCloseToTrayImpl
import com.artemchep.keyguard.common.usecase.impl.GetColorsImpl
import com.artemchep.keyguard.common.usecase.impl.GetColorsVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetConcealFieldsImpl
import com.artemchep.keyguard.common.usecase.impl.GetDebugScreenDelayImpl
import com.artemchep.keyguard.common.usecase.impl.GetFontImpl
import com.artemchep.keyguard.common.usecase.impl.GetFontVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetGpgAgentApprovalCachePolicyImpl
import com.artemchep.keyguard.common.usecase.impl.GetGpgAgentApprovalWindowImpl
import com.artemchep.keyguard.common.usecase.impl.GetGpgAgentApprovalWindowVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetGpgAgentDisplayKeyNamesImpl
import com.artemchep.keyguard.common.usecase.impl.GetGpgAgentFilterImpl
import com.artemchep.keyguard.common.usecase.impl.GetGpgAgentImpl
import com.artemchep.keyguard.common.usecase.impl.GetGpgAgentStatusImpl
import com.artemchep.keyguard.common.usecase.impl.GetGravatarImpl
import com.artemchep.keyguard.common.usecase.impl.GetGravatarUrlImpl
import com.artemchep.keyguard.common.usecase.impl.GetInMemoryLogsEnabledImpl
import com.artemchep.keyguard.common.usecase.impl.GetInMemoryLogsImpl
import com.artemchep.keyguard.common.usecase.impl.GetJustDeleteMeByUrlImpl
import com.artemchep.keyguard.common.usecase.impl.GetJustGetMyDataByUrlImpl
import com.artemchep.keyguard.common.usecase.impl.GetKeepScreenOnImpl
import com.artemchep.keyguard.common.usecase.impl.GetLocaleVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetMarkdownImpl
import com.artemchep.keyguard.common.usecase.impl.GetMinimizeOnCopyImpl
import com.artemchep.keyguard.common.usecase.impl.GetNavAnimationImpl
import com.artemchep.keyguard.common.usecase.impl.GetNavAnimationVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetNavLabelImpl
import com.artemchep.keyguard.common.usecase.impl.GetOnboardingLastVisitInstantImpl
import com.artemchep.keyguard.common.usecase.impl.GetPasskeysImpl
import com.artemchep.keyguard.common.usecase.impl.GetPersistedNavItemsConfigImpl
import com.artemchep.keyguard.common.usecase.impl.GetPinCodeImpl
import com.artemchep.keyguard.common.usecase.impl.GetProductsImpl
import com.artemchep.keyguard.common.usecase.impl.GetScreenStateImpl
import com.artemchep.keyguard.common.usecase.impl.GetSshAgentApprovalCachePolicyImpl
import com.artemchep.keyguard.common.usecase.impl.GetSshAgentApprovalWindowImpl
import com.artemchep.keyguard.common.usecase.impl.GetSshAgentApprovalWindowVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetSshAgentDisplayKeyNamesImpl
import com.artemchep.keyguard.common.usecase.impl.GetSshAgentFilterImpl
import com.artemchep.keyguard.common.usecase.impl.GetSshAgentImpl
import com.artemchep.keyguard.common.usecase.impl.GetSshAgentStatusImpl
import com.artemchep.keyguard.common.usecase.impl.GetSubscriptionsImpl
import com.artemchep.keyguard.common.usecase.impl.GetThemeExpressiveImpl
import com.artemchep.keyguard.common.usecase.impl.GetThemeImpl
import com.artemchep.keyguard.common.usecase.impl.GetThemeUseAmoledDarkImpl
import com.artemchep.keyguard.common.usecase.impl.GetThemeVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetTwoFaImpl
import com.artemchep.keyguard.common.usecase.impl.GetUseExternalBrowserImpl
import com.artemchep.keyguard.common.usecase.impl.GetVersionLogImpl
import com.artemchep.keyguard.common.usecase.impl.GetWebsiteIconsImpl
import com.artemchep.keyguard.common.usecase.impl.GetWriteAccessImpl
import com.artemchep.keyguard.common.usecase.impl.PasswordGeneratorDiceware
import com.artemchep.keyguard.common.usecase.impl.PutAllowScreenshotsImpl
import com.artemchep.keyguard.common.usecase.impl.PutAllowTwoPanelLayoutInLandscapeImpl
import com.artemchep.keyguard.common.usecase.impl.PutAllowTwoPanelLayoutInPortraitImpl
import com.artemchep.keyguard.common.usecase.impl.PutAppIconsImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillDefaultMatchDetectionImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillInlineSuggestionsImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillManualSelectionImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillPasskeysEnabledImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillRespectAutofillOffImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillSaveRequestImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillSaveUriImpl
import com.artemchep.keyguard.common.usecase.impl.PutBrowserAutofillAgentImpl
import com.artemchep.keyguard.common.usecase.impl.PutBrowserAutofillAgentPairingCodeImpl
import com.artemchep.keyguard.common.usecase.impl.PutCheckPasskeysImpl
import com.artemchep.keyguard.common.usecase.impl.PutCheckPwnedServicesImpl
import com.artemchep.keyguard.common.usecase.impl.PutCheckTwoFAImpl
import com.artemchep.keyguard.common.usecase.impl.PutClipboardAutoClearImpl
import com.artemchep.keyguard.common.usecase.impl.PutClipboardAutoRefreshImpl
import com.artemchep.keyguard.common.usecase.impl.PutCloseToTrayImpl
import com.artemchep.keyguard.common.usecase.impl.PutColorsImpl
import com.artemchep.keyguard.common.usecase.impl.PutConcealFieldsImpl
import com.artemchep.keyguard.common.usecase.impl.PutDebugScreenDelayImpl
import com.artemchep.keyguard.common.usecase.impl.PutFontImpl
import com.artemchep.keyguard.common.usecase.impl.PutGpgAgentApprovalCachePolicyImpl
import com.artemchep.keyguard.common.usecase.impl.PutGpgAgentApprovalWindowImpl
import com.artemchep.keyguard.common.usecase.impl.PutGpgAgentDisplayKeyNamesImpl
import com.artemchep.keyguard.common.usecase.impl.PutGpgAgentFilterImpl
import com.artemchep.keyguard.common.usecase.impl.PutGpgAgentImpl
import com.artemchep.keyguard.common.usecase.impl.PutGravatarImpl
import com.artemchep.keyguard.common.usecase.impl.PutInMemoryLogsEnabledImpl
import com.artemchep.keyguard.common.usecase.impl.PutKeepScreenOnImpl
import com.artemchep.keyguard.common.usecase.impl.PutMarkdownImpl
import com.artemchep.keyguard.common.usecase.impl.PutMinimizeOnCopyImpl
import com.artemchep.keyguard.common.usecase.impl.PutNavAnimationImpl
import com.artemchep.keyguard.common.usecase.impl.PutNavItemsConfigImpl
import com.artemchep.keyguard.common.usecase.impl.PutNavLabelImpl
import com.artemchep.keyguard.common.usecase.impl.PutOnboardingLastVisitInstantImpl
import com.artemchep.keyguard.common.usecase.impl.PutScreenStateImpl
import com.artemchep.keyguard.common.usecase.impl.PutSshAgentApprovalCachePolicyImpl
import com.artemchep.keyguard.common.usecase.impl.PutSshAgentApprovalWindowImpl
import com.artemchep.keyguard.common.usecase.impl.PutSshAgentDisplayKeyNamesImpl
import com.artemchep.keyguard.common.usecase.impl.PutSshAgentFilterImpl
import com.artemchep.keyguard.common.usecase.impl.PutSshAgentImpl
import com.artemchep.keyguard.common.usecase.impl.PutThemeExpressiveImpl
import com.artemchep.keyguard.common.usecase.impl.PutThemeImpl
import com.artemchep.keyguard.common.usecase.impl.PutThemeUseAmoledDarkImpl
import com.artemchep.keyguard.common.usecase.impl.PutUserExternalBrowserImpl
import com.artemchep.keyguard.common.usecase.impl.PutWebsiteIconsImpl
import com.artemchep.keyguard.common.usecase.impl.PutWriteAccessImpl
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.factory
import org.koin.plugin.module.dsl.single

internal class ApplicationSettingsModule {
    val module = module {
        single<GetTwoFaImpl>() bind GetTwoFa::class

        single<GetPasskeysImpl>() bind GetPasskeys::class

        single<GetPinCodeImpl>() bind GetPinCode::class

        single<GetScreenStateImpl>() bind GetScreenState::class

        single<GetConcealFieldsImpl>() bind GetConcealFields::class

        single<GetKeepScreenOnImpl>() bind GetKeepScreenOn::class

        single<GetAutofillBlockedUrisExposedImpl>() bind GetAutofillBlockedUrisExposed::class

        single<GetAutofillDefaultMatchDetectionImpl>() bind GetAutofillDefaultMatchDetection::class

        single<GetAutofillInlineSuggestionsImpl>() bind GetAutofillInlineSuggestions::class

        single<GetAutofillManualSelectionImpl>() bind GetAutofillManualSelection::class

        single<GetAutofillRespectAutofillOffImpl>() bind GetAutofillRespectAutofillOff::class

        single<GetAutofillPasskeysEnabledImpl>() bind GetAutofillPasskeysEnabled::class

        single<GetAutofillSaveRequestImpl>() bind GetAutofillSaveRequest::class

        single<GetAutofillSaveUriImpl>() bind GetAutofillSaveUri::class

        single<GetCheckPwnedServicesImpl>() bind GetCheckPwnedServices::class

        single<GetCheckTwoFAImpl>() bind GetCheckTwoFA::class

        single<GetCheckPasskeysImpl>() bind GetCheckPasskeys::class

        single<PutCheckPwnedServicesImpl>() bind PutCheckPwnedServices::class

        single<PutCheckTwoFAImpl>() bind PutCheckTwoFA::class

        single<PutCheckPasskeysImpl>() bind PutCheckPasskeys::class

        single<PutKeepScreenOnImpl>() bind PutKeepScreenOn::class

        single<PutNavAnimationImpl>() bind PutNavAnimation::class

        single<PutNavLabelImpl>() bind PutNavLabel::class

        single<PutNavItemsConfigImpl>() bind PutNavItemsConfig::class

        single<PutThemeImpl>() bind PutTheme::class

        single<PutThemeUseAmoledDarkImpl>() bind PutThemeUseAmoledDark::class

        single<PutThemeExpressiveImpl>() bind PutThemeExpressive::class

        single<PutColorsImpl>() bind PutColors::class

        single<PutScreenStateImpl>() bind PutScreenState::class

        single<PutSshAgentImpl>() bind PutSshAgent::class

        single<PutSshAgentApprovalWindowImpl>() bind PutSshAgentApprovalWindow::class

        single<PutSshAgentApprovalCachePolicyImpl>() bind PutSshAgentApprovalCachePolicy::class

        single<PutSshAgentDisplayKeyNamesImpl>() bind PutSshAgentDisplayKeyNames::class

        single<PutSshAgentFilterImpl>() bind PutSshAgentFilter::class

        single<GetSshAgentImpl>() bind GetSshAgent::class

        single<GetSshAgentApprovalWindowImpl>() bind GetSshAgentApprovalWindow::class

        single<GetSshAgentApprovalCachePolicyImpl>() bind GetSshAgentApprovalCachePolicy::class

        single<GetSshAgentApprovalWindowVariantsImpl>() bind GetSshAgentApprovalWindowVariants::class

        single<GetSshAgentDisplayKeyNamesImpl>() bind GetSshAgentDisplayKeyNames::class

        single<GetSshAgentFilterImpl>() bind GetSshAgentFilter::class

        single<GetSshAgentStatusImpl>() bind GetSshAgentStatus::class

        single<GetBrowserAutofillAgentImpl>() bind GetBrowserAutofillAgent::class

        single<PutBrowserAutofillAgentImpl>() bind PutBrowserAutofillAgent::class

        single<GetBrowserAutofillAgentPairingCodeImpl>() bind GetBrowserAutofillAgentPairingCode::class

        single<PutBrowserAutofillAgentPairingCodeImpl>() bind PutBrowserAutofillAgentPairingCode::class

        single<GetBrowserAutofillAgentStatusImpl>() bind GetBrowserAutofillAgentStatus::class

        single<BrowserAutofillAgentStatusService> {
            BrowserAutofillAgentStatusServiceImpl()
        }

        single<PutGpgAgentImpl>() bind PutGpgAgent::class

        single<PutGpgAgentApprovalWindowImpl>() bind PutGpgAgentApprovalWindow::class

        single<PutGpgAgentApprovalCachePolicyImpl>() bind PutGpgAgentApprovalCachePolicy::class

        single<PutGpgAgentDisplayKeyNamesImpl>() bind PutGpgAgentDisplayKeyNames::class

        single<PutGpgAgentFilterImpl>() bind PutGpgAgentFilter::class

        single<GetGpgAgentImpl>() bind GetGpgAgent::class

        single<GetGpgAgentApprovalWindowImpl>() bind GetGpgAgentApprovalWindow::class

        single<GetGpgAgentApprovalCachePolicyImpl>() bind GetGpgAgentApprovalCachePolicy::class

        single<GetGpgAgentApprovalWindowVariantsImpl>() bind GetGpgAgentApprovalWindowVariants::class

        single<GetGpgAgentDisplayKeyNamesImpl>() bind GetGpgAgentDisplayKeyNames::class

        single<GetGpgAgentFilterImpl>() bind GetGpgAgentFilter::class

        single<GetGpgAgentStatusImpl>() bind GetGpgAgentStatus::class

        single<GetAllowScreenshotsImpl>() bind GetAllowScreenshots::class

        single<GetAllowTwoPanelLayoutInLandscapeImpl>() bind GetAllowTwoPanelLayoutInLandscape::class

        single<GetAllowTwoPanelLayoutInPortraitImpl>() bind GetAllowTwoPanelLayoutInPortrait::class

        single<PutAllowScreenshotsImpl>() bind PutAllowScreenshots::class

        single<GetUseExternalBrowserImpl>() bind GetUseExternalBrowser::class

        single<PutUserExternalBrowserImpl>() bind PutUseExternalBrowser::class

        single<GetCloseToTrayImpl>() bind GetCloseToTray::class

        single<PutCloseToTrayImpl>() bind PutCloseToTray::class

        single<GetMinimizeOnCopyImpl>() bind GetMinimizeOnCopy::class

        single<PutMinimizeOnCopyImpl>() bind PutMinimizeOnCopy::class

        single<PutAllowTwoPanelLayoutInLandscapeImpl>() bind PutAllowTwoPanelLayoutInLandscape::class

        single<PutAllowTwoPanelLayoutInPortraitImpl>() bind PutAllowTwoPanelLayoutInPortrait::class

        single<PutAutofillDefaultMatchDetectionImpl>() bind PutAutofillDefaultMatchDetection::class

        single<PutAutofillInlineSuggestionsImpl>() bind PutAutofillInlineSuggestions::class

        single<PutAutofillManualSelectionImpl>() bind PutAutofillManualSelection::class

        single<PutAutofillRespectAutofillOffImpl>() bind PutAutofillRespectAutofillOff::class

        single<PutAutofillPasskeysEnabledImpl>() bind PutAutofillPasskeysEnabled::class

        single<PutAutofillSaveRequestImpl>() bind PutAutofillSaveRequest::class

        single<PutAutofillSaveUriImpl>() bind PutAutofillSaveUri::class

        single<GetThemeVariantsImpl>() bind GetThemeVariants::class

        single<GetColorsVariantsImpl>() bind GetColorsVariants::class

        single<GetNavAnimationImpl>() bind GetNavAnimation::class

        single<GetNavLabelImpl>() bind GetNavLabel::class

        single<GetPersistedNavItemsConfigImpl>() bind GetPersistedNavItemsConfig::class

        single<GetNavAnimationVariantsImpl>() bind GetNavAnimationVariants::class

        single<GetFontImpl>() bind GetFont::class

        single<GetFontVariantsImpl>() bind GetFontVariants::class

        single<PutFontImpl>() bind PutFont::class

        single<GetLocaleVariantsImpl>() bind GetLocaleVariants::class

        single<GetThemeImpl>() bind GetTheme::class

        single<GetThemeUseAmoledDarkImpl>() bind GetThemeUseAmoledDark::class

        single<GetThemeExpressiveImpl>() bind GetThemeExpressive::class

        single<GetColorsImpl>() bind GetColors::class

        single<GetSubscriptionsImpl>() bind GetSubscriptions::class

        single<GetProductsImpl>() bind GetProducts::class

        single<GetCanWriteImpl>() bind GetCanWrite::class

        single<PutConcealFieldsImpl>() bind PutConcealFields::class

        single<PutClipboardAutoRefreshImpl>() bind PutClipboardAutoRefresh::class

        single<PutClipboardAutoClearImpl>() bind PutClipboardAutoClear::class

        single<GetClipboardAutoClearImpl>() bind GetClipboardAutoClear::class

        single<GetClipboardAutoClearVariantsImpl>() bind GetClipboardAutoClearVariants::class

        single<GetClipboardAutoRefreshImpl>() bind GetClipboardAutoRefresh::class

        single<GetClipboardAutoRefreshVariantsImpl>() bind GetClipboardAutoRefreshVariants::class

        single<GetAllowScreenshotsVariantsImpl>() bind GetAllowScreenshotsVariants::class

        single<GetWriteAccessImpl>() bind GetWriteAccess::class

        single<GetDebugScreenDelayImpl>() bind GetDebugScreenDelay::class

        single<GetAppIconsImpl>() bind GetAppIcons::class

        single<GetWebsiteIconsImpl>() bind GetWebsiteIcons::class

        single<GetMarkdownImpl>() bind GetMarkdown::class

        single<GetGravatarUrlImpl>() bind GetGravatarUrl::class

        single<GetGravatarImpl>() bind GetGravatar::class

        single<PutGravatarImpl>() bind PutGravatar::class

        single<GetInMemoryLogsEnabledImpl>() bind GetInMemoryLogsEnabled::class

        single<GetInMemoryLogsImpl>() bind GetInMemoryLogs::class

        single<PutInMemoryLogsEnabledImpl>() bind PutInMemoryLogsEnabled::class

        single<InMemoryLogRepositoryImpl>() bind InMemoryLogRepository::class

        single<LogSinkRegistry> {
            LogSinkRegistry(
                listOf(get<InMemoryLogRepository>()) + get<PlatformLogSinkRegistry>().values,
            )
        }

        single<LogRepository> {
            LogRepositoryBridge(
                logRepositoryList = get<LogSinkRegistry>().values,
            )
        }

        single<GetJustDeleteMeByUrlImpl>() bind GetJustDeleteMeByUrl::class

        single<GetJustGetMyDataByUrlImpl>() bind GetJustGetMyDataByUrl::class

        single<PutWriteAccessImpl>() bind PutWriteAccess::class

        single<PutDebugScreenDelayImpl>() bind PutDebugScreenDelay::class

        single<PutAppIconsImpl>() bind PutAppIcons::class

        single<PutWebsiteIconsImpl>() bind PutWebsiteIcons::class

        single<PutMarkdownImpl>() bind PutMarkdown::class

        single<PutOnboardingLastVisitInstantImpl>() bind PutOnboardingLastVisitInstant::class

        single<GetOnboardingLastVisitInstantImpl>() bind GetOnboardingLastVisitInstant::class

        single<GetAppVersionImpl>() bind GetAppVersion::class

        single<GetVersionLogImpl>() bind GetVersionLog::class

        single<PasswordGeneratorDiceware>() bind GetPassphrase::class

        single<JustGetMyDataServiceImpl>() bind JustGetMyDataService::class

        single<IdRepository> {
            val store = get<KeyValueStoreFactory>().get(Files.DEVICE_ID)
            IdRepositoryImpl(
                store = store,
            )
        }

        single<StateRepository> {
            val store = get<KeyValueStoreFactory>().get(Files.UI_STATE)
            StateRepositoryImpl(
                store = store,
                json = get(),
            )
        }

        single<GetAppVersionCodeImpl>() bind GetAppVersionCode::class

        single<GetAppVersionNameImpl>() bind GetAppVersionName::class

        single<GetAppBuildTypeImpl>() bind GetAppBuildType::class

        factory<KeyReadRepository> {
            get<KeyRepositoryImpl>()
        }

        factory<KeyReadWriteRepository> {
            get<KeyRepositoryImpl>()
        }

        single {
            SettingsRepositoryImpl(
                store = get<KeyValueStoreFactory>().get(Files.SETTINGS),
                json = get(),
                base64Service = get(),
            )
        }

        factory<SettingsReadRepository> {
            get<SettingsRepositoryImpl>()
        }

        factory<SettingsReadWriteRepository> {
            get<SettingsRepositoryImpl>()
        }

        single<ExposedAccountRepository> {
            ExposedAccountRepositoryImpl(
                exposedDatabaseManager = get(),
                cryptoGenerator = get(),
                dispatcher = databaseDispatcher(),
            )
        }

        single<UrlBlockRepositoryExposed> {
            UrlBlockRepositoryExposed(
                exposedDatabaseManager = get(),
                dispatcher = databaseDispatcher(),
            )
        }
    }
}
