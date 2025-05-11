package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.AppWorker
import com.artemchep.keyguard.common.AppWorkerIm
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import com.artemchep.keyguard.common.usecase.KeyPairExport
import com.artemchep.keyguard.common.service.crypto.KeyPairGenerator
import com.artemchep.keyguard.common.usecase.KeyPrivateExport
import com.artemchep.keyguard.common.usecase.KeyPublicExport
import com.artemchep.keyguard.common.service.deeplink.DeeplinkService
import com.artemchep.keyguard.common.service.deeplink.impl.DeeplinkServiceImpl
import com.artemchep.keyguard.common.service.download.DownloadService
import com.artemchep.keyguard.common.service.download.DownloadServiceImpl
import com.artemchep.keyguard.common.service.execute.ExecuteCommand
import com.artemchep.keyguard.common.service.execute.impl.ExecuteCommandImpl
import com.artemchep.keyguard.common.service.export.JsonExportService
import com.artemchep.keyguard.common.service.export.impl.JsonExportServiceImpl
import com.artemchep.keyguard.common.service.extract.impl.LinkInfoExtractorExecute
import com.artemchep.keyguard.common.service.extract.impl.LinkInfoPlatformExtractor
import com.artemchep.keyguard.common.service.googleauthenticator.OtpMigrationService
import com.artemchep.keyguard.common.service.googleauthenticator.impl.OtpMigrationServiceImpl
import com.artemchep.keyguard.common.service.googleauthenticator.util.OtpMigrationParser
import com.artemchep.keyguard.common.service.gpmprivapps.PrivilegedAppsService
import com.artemchep.keyguard.common.service.gpmprivapps.impl.PrivilegedAppsServiceImpl
import com.artemchep.keyguard.common.service.id.IdRepository
import com.artemchep.keyguard.common.service.id.impl.IdRepositoryImpl
import com.artemchep.keyguard.common.service.justdeleteme.JustDeleteMeService
import com.artemchep.keyguard.common.service.justdeleteme.impl.JustDeleteMeServiceImpl
import com.artemchep.keyguard.common.service.justgetmydata.JustGetMyDataService
import com.artemchep.keyguard.common.service.justgetmydata.impl.JustGetMyDataServiceImpl
import com.artemchep.keyguard.common.service.keyboard.KeyboardShortcutsService
import com.artemchep.keyguard.common.service.keyboard.KeyboardShortcutsServiceHost
import com.artemchep.keyguard.common.service.keyboard.KeyboardShortcutsServiceImpl
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.license.LicenseService
import com.artemchep.keyguard.common.service.license.impl.LicenseServiceImpl
import com.artemchep.keyguard.common.service.localizationcontributors.LocalizationContributorsService
import com.artemchep.keyguard.common.service.localizationcontributors.impl.LocalizationContributorsServiceImpl
import com.artemchep.keyguard.common.service.logging.inmemory.InMemoryLogRepository
import com.artemchep.keyguard.common.service.logging.inmemory.InMemoryLogRepositoryImpl
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.common.service.notification.NotificationFingerprintRepository
import com.artemchep.keyguard.common.service.notification.impl.NotificationFingerprintRepositoryImpl
import com.artemchep.keyguard.common.service.passkey.PassKeyService
import com.artemchep.keyguard.common.service.passkey.impl.PassKeyServiceImpl
import com.artemchep.keyguard.common.service.placeholder.impl.CipherPlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.CommentPlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.CustomPlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.DateTimePlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.EnvironmentPlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.TextReplaceRegexPlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.TextTransformPlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.UrlPlaceholder
import com.artemchep.keyguard.common.service.relays.di.emailRelayDiModule
import com.artemchep.keyguard.common.service.review.ReviewLog
import com.artemchep.keyguard.common.service.review.impl.ReviewLogImpl
import com.artemchep.keyguard.common.service.session.VaultSessionLocker
import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.service.settings.impl.SettingsRepositoryImpl
import com.artemchep.keyguard.common.service.similarity.SimilarityService
import com.artemchep.keyguard.common.service.state.StateRepository
import com.artemchep.keyguard.common.service.state.impl.StateRepositoryImpl
import com.artemchep.keyguard.common.service.text.Base32Service
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.service.tld.impl.TldServiceImpl
import com.artemchep.keyguard.common.service.totp.TotpService
import com.artemchep.keyguard.common.service.totp.impl.TotpServiceImpl
import com.artemchep.keyguard.common.service.twofa.TwoFaService
import com.artemchep.keyguard.common.service.twofa.impl.TwoFaServiceImpl
import com.artemchep.keyguard.common.service.vault.FingerprintReadRepository
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import com.artemchep.keyguard.common.service.vault.KeyReadRepository
import com.artemchep.keyguard.common.service.vault.KeyReadWriteRepository
import com.artemchep.keyguard.common.service.vault.SessionMetadataReadRepository
import com.artemchep.keyguard.common.service.vault.SessionMetadataReadWriteRepository
import com.artemchep.keyguard.common.service.vault.SessionReadRepository
import com.artemchep.keyguard.common.service.vault.SessionReadWriteRepository
import com.artemchep.keyguard.common.service.vault.impl.FingerprintRepositoryImpl
import com.artemchep.keyguard.common.service.vault.impl.KeyRepositoryImpl
import com.artemchep.keyguard.common.service.vault.impl.SessionMetadataRepositoryImpl
import com.artemchep.keyguard.common.service.vault.impl.SessionRepositoryImpl
import com.artemchep.keyguard.common.service.wordlist.WordlistService
import com.artemchep.keyguard.common.service.wordlist.impl.WordlistServiceImpl
import com.artemchep.keyguard.common.service.zip.ZipService
import com.artemchep.keyguard.common.usecase.AuthConfirmMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.AuthGenerateMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.BiometricKeyDecryptUseCase
import com.artemchep.keyguard.common.usecase.BiometricKeyEncryptUseCase
import com.artemchep.keyguard.common.usecase.CipherUrlBroadCheck
import com.artemchep.keyguard.common.usecase.CipherUrlCheck
import com.artemchep.keyguard.common.usecase.CipherUrlDuplicateCheck
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.ConfirmAccessByPasswordUseCase
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.DeviceEncryptionKeyUseCase
import com.artemchep.keyguard.common.usecase.DeviceIdUseCase
import com.artemchep.keyguard.common.usecase.DisableBiometric
import com.artemchep.keyguard.common.usecase.DismissNotificationsByChannel
import com.artemchep.keyguard.common.usecase.EnableBiometric
import com.artemchep.keyguard.common.usecase.GenerateMasterHashUseCase
import com.artemchep.keyguard.common.usecase.GenerateMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.GenerateMasterSaltUseCase
import com.artemchep.keyguard.common.usecase.GetAllowScreenshots
import com.artemchep.keyguard.common.usecase.GetAllowTwoPanelLayoutInLandscape
import com.artemchep.keyguard.common.usecase.GetAllowTwoPanelLayoutInPortrait
import com.artemchep.keyguard.common.usecase.GetAppBuildDate
import com.artemchep.keyguard.common.usecase.GetAppBuildRef
import com.artemchep.keyguard.common.usecase.GetAppBuildType
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.GetAppVersion
import com.artemchep.keyguard.common.usecase.GetAppVersionCode
import com.artemchep.keyguard.common.usecase.GetAppVersionName
import com.artemchep.keyguard.common.usecase.GetAutofillCopyTotp
import com.artemchep.keyguard.common.usecase.GetAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.GetAutofillInlineSuggestions
import com.artemchep.keyguard.common.usecase.GetAutofillManualSelection
import com.artemchep.keyguard.common.usecase.GetAutofillRespectAutofillOff
import com.artemchep.keyguard.common.usecase.GetAutofillSaveRequest
import com.artemchep.keyguard.common.usecase.GetAutofillSaveUri
import com.artemchep.keyguard.common.usecase.GetBiometricRemainingDuration
import com.artemchep.keyguard.common.usecase.GetBiometricRequireConfirmation
import com.artemchep.keyguard.common.usecase.GetBiometricTimeout
import com.artemchep.keyguard.common.usecase.GetBiometricTimeoutVariants
import com.artemchep.keyguard.common.usecase.GetCachePremium
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCheckPasskeys
import com.artemchep.keyguard.common.usecase.GetCheckPwnedPasswords
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
import com.artemchep.keyguard.common.usecase.GetDebugPremium
import com.artemchep.keyguard.common.usecase.GetDebugScreenDelay
import com.artemchep.keyguard.common.usecase.GetFont
import com.artemchep.keyguard.common.usecase.GetFontVariants
import com.artemchep.keyguard.common.usecase.GetGravatar
import com.artemchep.keyguard.common.usecase.GetGravatarUrl
import com.artemchep.keyguard.common.usecase.GetInMemoryLogs
import com.artemchep.keyguard.common.usecase.GetInMemoryLogsEnabled
import com.artemchep.keyguard.common.usecase.GetJustDeleteMeByUrl
import com.artemchep.keyguard.common.usecase.GetJustGetMyDataByUrl
import com.artemchep.keyguard.common.usecase.GetKeepScreenOn
import com.artemchep.keyguard.common.usecase.GetLocaleVariants
import com.artemchep.keyguard.common.usecase.GetMarkdown
import com.artemchep.keyguard.common.usecase.GetNavAnimation
import com.artemchep.keyguard.common.usecase.GetNavAnimationVariants
import com.artemchep.keyguard.common.usecase.GetNavLabel
import com.artemchep.keyguard.common.usecase.GetOnboardingLastVisitInstant
import com.artemchep.keyguard.common.usecase.GetPasskeys
import com.artemchep.keyguard.common.usecase.GetPassphrase
import com.artemchep.keyguard.common.usecase.GetPassword
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.common.usecase.GetPinCode
import com.artemchep.keyguard.common.usecase.GetProducts
import com.artemchep.keyguard.common.usecase.GetScreenState
import com.artemchep.keyguard.common.usecase.GetSubscriptions
import com.artemchep.keyguard.common.usecase.GetTheme
import com.artemchep.keyguard.common.usecase.GetThemeUseAmoledDark
import com.artemchep.keyguard.common.usecase.GetThemeVariants
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.common.usecase.GetTwoFa
import com.artemchep.keyguard.common.usecase.GetUseExternalBrowser
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterReboot
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterScreenOff
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterTimeout
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterTimeoutVariants
import com.artemchep.keyguard.common.usecase.GetVaultPersist
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.GetVersionLog
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.common.usecase.GetWriteAccess
import com.artemchep.keyguard.common.usecase.MessageHub
import com.artemchep.keyguard.common.usecase.NumberFormatter
import com.artemchep.keyguard.common.usecase.PasskeyTargetCheck
import com.artemchep.keyguard.common.usecase.PutAllowScreenshots
import com.artemchep.keyguard.common.usecase.PutAllowTwoPanelLayoutInLandscape
import com.artemchep.keyguard.common.usecase.PutAllowTwoPanelLayoutInPortrait
import com.artemchep.keyguard.common.usecase.PutAppIcons
import com.artemchep.keyguard.common.usecase.PutAutofillCopyTotp
import com.artemchep.keyguard.common.usecase.PutAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.PutAutofillInlineSuggestions
import com.artemchep.keyguard.common.usecase.PutAutofillManualSelection
import com.artemchep.keyguard.common.usecase.PutAutofillRespectAutofillOff
import com.artemchep.keyguard.common.usecase.PutAutofillSaveRequest
import com.artemchep.keyguard.common.usecase.PutAutofillSaveUri
import com.artemchep.keyguard.common.usecase.PutBiometricRequireConfirmation
import com.artemchep.keyguard.common.usecase.PutBiometricTimeout
import com.artemchep.keyguard.common.usecase.PutCachePremium
import com.artemchep.keyguard.common.usecase.PutCheckPasskeys
import com.artemchep.keyguard.common.usecase.PutCheckPwnedPasswords
import com.artemchep.keyguard.common.usecase.PutCheckPwnedServices
import com.artemchep.keyguard.common.usecase.PutCheckTwoFA
import com.artemchep.keyguard.common.usecase.PutClipboardAutoClear
import com.artemchep.keyguard.common.usecase.PutClipboardAutoRefresh
import com.artemchep.keyguard.common.usecase.PutCloseToTray
import com.artemchep.keyguard.common.usecase.PutColors
import com.artemchep.keyguard.common.usecase.PutConcealFields
import com.artemchep.keyguard.common.usecase.PutDebugPremium
import com.artemchep.keyguard.common.usecase.PutDebugScreenDelay
import com.artemchep.keyguard.common.usecase.PutFont
import com.artemchep.keyguard.common.usecase.PutGravatar
import com.artemchep.keyguard.common.usecase.PutInMemoryLogsEnabled
import com.artemchep.keyguard.common.usecase.PutKeepScreenOn
import com.artemchep.keyguard.common.usecase.PutMarkdown
import com.artemchep.keyguard.common.usecase.PutNavAnimation
import com.artemchep.keyguard.common.usecase.PutNavLabel
import com.artemchep.keyguard.common.usecase.PutOnboardingLastVisitInstant
import com.artemchep.keyguard.common.usecase.PutScreenState
import com.artemchep.keyguard.common.usecase.PutTheme
import com.artemchep.keyguard.common.usecase.PutThemeUseAmoledDark
import com.artemchep.keyguard.common.usecase.PutUseExternalBrowser
import com.artemchep.keyguard.common.usecase.PutVaultLockAfterReboot
import com.artemchep.keyguard.common.usecase.PutVaultLockAfterScreenOff
import com.artemchep.keyguard.common.usecase.PutVaultLockAfterTimeout
import com.artemchep.keyguard.common.usecase.PutVaultPersist
import com.artemchep.keyguard.common.usecase.PutVaultSession
import com.artemchep.keyguard.common.usecase.PutWebsiteIcons
import com.artemchep.keyguard.common.usecase.PutWriteAccess
import com.artemchep.keyguard.common.usecase.ReadWordlistFromFile
import com.artemchep.keyguard.common.usecase.ReadWordlistFromUrl
import com.artemchep.keyguard.common.usecase.RemoveAttachment
import com.artemchep.keyguard.common.usecase.RequestAppReview
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.ShowNotification
import com.artemchep.keyguard.common.usecase.UnlockUseCase
import com.artemchep.keyguard.common.usecase.UpdateVersionLog
import com.artemchep.keyguard.common.usecase.WatchtowerSyncer
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.usecase.impl.AuthConfirmMasterKeyUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.AuthGenerateMasterKeyUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.BiometricKeyDecryptUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.BiometricKeyEncryptUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.ClearVaultSessionImpl
import com.artemchep.keyguard.common.usecase.impl.ConfirmAccessByPasswordUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.DisableBiometricImpl
import com.artemchep.keyguard.common.usecase.impl.DismissNotificationsByChannelImpl
import com.artemchep.keyguard.common.usecase.impl.EnableBiometricImpl
import com.artemchep.keyguard.common.usecase.impl.GenerateMasterHashUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.GenerateMasterKeyUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.GenerateMasterSaltUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.GetAllowScreenshotsImpl
import com.artemchep.keyguard.common.usecase.impl.GetAllowTwoPanelLayoutInLandscapeImpl
import com.artemchep.keyguard.common.usecase.impl.GetAllowTwoPanelLayoutInPortraitImpl
import com.artemchep.keyguard.common.usecase.impl.GetAppBuildTypeImpl
import com.artemchep.keyguard.common.usecase.impl.GetAppIconsImpl
import com.artemchep.keyguard.common.usecase.impl.GetAppVersionCodeImpl
import com.artemchep.keyguard.common.usecase.impl.GetAppVersionImpl
import com.artemchep.keyguard.common.usecase.impl.GetAppVersionNameImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillCopyTotpImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillDefaultMatchDetectionImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillInlineSuggestionsImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillManualSelectionImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillRespectAutofillOffImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillSaveRequestImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillSaveUriImpl
import com.artemchep.keyguard.common.usecase.impl.GetBiometricRemainingDurationImpl
import com.artemchep.keyguard.common.usecase.impl.GetBiometricRequireConfirmationImpl
import com.artemchep.keyguard.common.usecase.impl.GetBiometricTimeoutImpl
import com.artemchep.keyguard.common.usecase.impl.GetBiometricTimeoutVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetCachePremiumImpl
import com.artemchep.keyguard.common.usecase.impl.GetCanWriteImpl
import com.artemchep.keyguard.common.usecase.impl.GetCheckPasskeysImpl
import com.artemchep.keyguard.common.usecase.impl.GetCheckPwnedPasswordsImpl
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
import com.artemchep.keyguard.common.usecase.impl.GetDebugPremiumImpl
import com.artemchep.keyguard.common.usecase.impl.GetDebugScreenDelayImpl
import com.artemchep.keyguard.common.usecase.impl.GetFontImpl
import com.artemchep.keyguard.common.usecase.impl.GetFontVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetGravatarImpl
import com.artemchep.keyguard.common.usecase.impl.GetGravatarUrlImpl
import com.artemchep.keyguard.common.usecase.impl.GetInMemoryLogsEnabledImpl
import com.artemchep.keyguard.common.usecase.impl.GetInMemoryLogsImpl
import com.artemchep.keyguard.common.usecase.impl.GetJustDeleteMeByUrlImpl
import com.artemchep.keyguard.common.usecase.impl.GetJustGetMyDataByUrlImpl
import com.artemchep.keyguard.common.usecase.impl.GetKeepScreenOnImpl
import com.artemchep.keyguard.common.usecase.impl.GetLocaleVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetMarkdownImpl
import com.artemchep.keyguard.common.usecase.impl.GetNavAnimationImpl
import com.artemchep.keyguard.common.usecase.impl.GetNavAnimationVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetNavLabelImpl
import com.artemchep.keyguard.common.usecase.impl.GetOnboardingLastVisitInstantImpl
import com.artemchep.keyguard.common.usecase.impl.GetPasskeysImpl
import com.artemchep.keyguard.common.usecase.impl.GetPasswordImpl
import com.artemchep.keyguard.common.usecase.impl.GetPinCodeImpl
import com.artemchep.keyguard.common.usecase.impl.GetProductsImpl
import com.artemchep.keyguard.common.usecase.impl.GetScreenStateImpl
import com.artemchep.keyguard.common.usecase.impl.GetSubscriptionsImpl
import com.artemchep.keyguard.common.usecase.impl.GetThemeImpl
import com.artemchep.keyguard.common.usecase.impl.GetThemeUseAmoledDarkImpl
import com.artemchep.keyguard.common.usecase.impl.GetThemeVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetTotpCodeImpl
import com.artemchep.keyguard.common.usecase.impl.GetTwoFaImpl
import com.artemchep.keyguard.common.usecase.impl.GetUseExternalBrowserImpl
import com.artemchep.keyguard.common.usecase.impl.GetVaultLockAfterRebootImpl
import com.artemchep.keyguard.common.usecase.impl.GetVaultLockAfterScreenOffImpl
import com.artemchep.keyguard.common.usecase.impl.GetVaultLockAfterTimeoutImpl
import com.artemchep.keyguard.common.usecase.impl.GetVaultLockAfterTimeoutVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetVaultPersistImpl
import com.artemchep.keyguard.common.usecase.impl.GetVaultSessionImpl
import com.artemchep.keyguard.common.usecase.impl.GetVersionLogImpl
import com.artemchep.keyguard.common.usecase.impl.GetWebsiteIconsImpl
import com.artemchep.keyguard.common.usecase.impl.GetWriteAccessImpl
import com.artemchep.keyguard.common.usecase.impl.KeyPairExportImpl
import com.artemchep.keyguard.common.usecase.impl.KeyPrivateExportImpl
import com.artemchep.keyguard.common.usecase.impl.KeyPublicExportImpl
import com.artemchep.keyguard.common.usecase.impl.MessageHubImpl
import com.artemchep.keyguard.common.usecase.impl.PasskeyTargetCheckImpl
import com.artemchep.keyguard.common.usecase.impl.PutAllowScreenshotsImpl
import com.artemchep.keyguard.common.usecase.impl.PutAllowTwoPanelLayoutInLandscapeImpl
import com.artemchep.keyguard.common.usecase.impl.PutAllowTwoPanelLayoutInPortraitImpl
import com.artemchep.keyguard.common.usecase.impl.PutAppIconsImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillCopyTotpImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillDefaultMatchDetectionImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillInlineSuggestionsImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillManualSelectionImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillRespectAutofillOffImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillSaveRequestImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillSaveUriImpl
import com.artemchep.keyguard.common.usecase.impl.PutBiometricRequireConfirmationImpl
import com.artemchep.keyguard.common.usecase.impl.PutBiometricTimeoutImpl
import com.artemchep.keyguard.common.usecase.impl.PutCachePremiumImpl
import com.artemchep.keyguard.common.usecase.impl.PutCheckPasskeysImpl
import com.artemchep.keyguard.common.usecase.impl.PutCheckPwnedPasswordsImpl
import com.artemchep.keyguard.common.usecase.impl.PutCheckPwnedServicesImpl
import com.artemchep.keyguard.common.usecase.impl.PutCheckTwoFAImpl
import com.artemchep.keyguard.common.usecase.impl.PutClipboardAutoClearImpl
import com.artemchep.keyguard.common.usecase.impl.PutClipboardAutoRefreshImpl
import com.artemchep.keyguard.common.usecase.impl.PutCloseToTrayImpl
import com.artemchep.keyguard.common.usecase.impl.PutColorsImpl
import com.artemchep.keyguard.common.usecase.impl.PutConcealFieldsImpl
import com.artemchep.keyguard.common.usecase.impl.PutDebugPremiumImpl
import com.artemchep.keyguard.common.usecase.impl.PutDebugScreenDelayImpl
import com.artemchep.keyguard.common.usecase.impl.PutFontImpl
import com.artemchep.keyguard.common.usecase.impl.PutGravatarImpl
import com.artemchep.keyguard.common.usecase.impl.PutInMemoryLogsEnabledImpl
import com.artemchep.keyguard.common.usecase.impl.PutKeepScreenOnImpl
import com.artemchep.keyguard.common.usecase.impl.PutMarkdownImpl
import com.artemchep.keyguard.common.usecase.impl.PutNavAnimationImpl
import com.artemchep.keyguard.common.usecase.impl.PutNavLabelImpl
import com.artemchep.keyguard.common.usecase.impl.PutOnboardingLastVisitInstantImpl
import com.artemchep.keyguard.common.usecase.impl.PutScreenStateImpl
import com.artemchep.keyguard.common.usecase.impl.PutThemeImpl
import com.artemchep.keyguard.common.usecase.impl.PutThemeUseAmoledDarkImpl
import com.artemchep.keyguard.common.usecase.impl.PutUserExternalBrowserImpl
import com.artemchep.keyguard.common.usecase.impl.PutVaultLockAfterRebootImpl
import com.artemchep.keyguard.common.usecase.impl.PutVaultLockAfterScreenOffImpl
import com.artemchep.keyguard.common.usecase.impl.PutVaultLockAfterTimeoutImpl
import com.artemchep.keyguard.common.usecase.impl.PutVaultPersistImpl
import com.artemchep.keyguard.common.usecase.impl.PutVaultSessionImpl
import com.artemchep.keyguard.common.usecase.impl.PutWebsiteIconsImpl
import com.artemchep.keyguard.common.usecase.impl.PutWriteAccessImpl
import com.artemchep.keyguard.common.usecase.impl.ReadWordlistFromFileImpl
import com.artemchep.keyguard.common.usecase.impl.ReadWordlistFromUrlImpl
import com.artemchep.keyguard.common.usecase.impl.RemoveAttachmentImpl
import com.artemchep.keyguard.common.usecase.impl.RequestAppReviewImpl
import com.artemchep.keyguard.common.usecase.impl.ShowNotificationImpl
import com.artemchep.keyguard.common.usecase.impl.UnlockUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.UpdateVersionLogImpl
import com.artemchep.keyguard.common.usecase.impl.WatchtowerSyncerImpl
import com.artemchep.keyguard.common.usecase.impl.WindowCoroutineScopeImpl
import com.artemchep.keyguard.copy.Base32ServiceJvm
import com.artemchep.keyguard.copy.Base64ServiceJvm
import com.artemchep.keyguard.copy.DateFormatterAndroid
import com.artemchep.keyguard.copy.GetAppBuildDateImpl
import com.artemchep.keyguard.copy.GetAppBuildRefImpl
import com.artemchep.keyguard.copy.GetPasswordStrengthJvm
import com.artemchep.keyguard.copy.NumberFormatterJvm
import com.artemchep.keyguard.copy.PasswordGeneratorDiceware
import com.artemchep.keyguard.copy.SimilarityServiceJvm
import com.artemchep.keyguard.copy.ZipServiceJvm
import com.artemchep.keyguard.core.store.DatabaseDispatcher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.crypto.CipherEncryptorImpl
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.crypto.FileEncryptorImpl
import com.artemchep.keyguard.crypto.KeyPairGeneratorJvm
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.provider.bitwarden.api.BitwardenPersona
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUrlBroadCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUrlCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUrlDuplicateCheckImpl
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.CurlUserAgent
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.instance

fun globalModuleJvm() = DI.Module(
    name = "globalModuleJvm",
) {
    bindProvider<CoroutineDispatcher>(tag = DatabaseDispatcher) {
        Dispatchers.IO
    }
    bindSingleton<DownloadService> {
        DownloadServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<KeyboardShortcutsServiceImpl> {
        KeyboardShortcutsServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<KeyboardShortcutsService> {
        instance<KeyboardShortcutsServiceImpl>()
    }
    bindSingleton<KeyboardShortcutsServiceHost> {
        instance<KeyboardShortcutsServiceImpl>()
    }
    bindSingleton<AuthConfirmMasterKeyUseCase> {
        AuthConfirmMasterKeyUseCaseImpl(
            directDI = this,
        )
    }
    bindSingleton<AuthGenerateMasterKeyUseCase> {
        AuthGenerateMasterKeyUseCaseImpl(
            directDI = this,
        )
    }
    bindSingleton<BiometricKeyDecryptUseCase> {
        BiometricKeyDecryptUseCaseImpl(
            directDI = this,
        )
    }
    bindSingleton<BiometricKeyEncryptUseCase> {
        BiometricKeyEncryptUseCaseImpl(
            directDI = this,
        )
    }
    bindSingleton<GetBiometricRemainingDuration> {
        GetBiometricRemainingDurationImpl(
            directDI = this,
        )
    }
    bindSingleton<DisableBiometric> {
        DisableBiometricImpl(
            directDI = this,
        )
    }
    bindSingleton<EnableBiometric> {
        EnableBiometricImpl(
            directDI = this,
        )
    }
    bindSingleton<GenerateMasterHashUseCase> {
        GenerateMasterHashUseCaseImpl(
            directDI = this,
        )
    }
    bindSingleton<GenerateMasterKeyUseCase> {
        GenerateMasterKeyUseCaseImpl(
            directDI = this,
        )
    }
    bindSingleton<GenerateMasterSaltUseCase> {
        GenerateMasterSaltUseCaseImpl(
            directDI = this,
        )
    }
    bindSingleton<ConfirmAccessByPasswordUseCase> {
        ConfirmAccessByPasswordUseCaseImpl(
            directDI = this,
        )
    }
    bindSingleton<UnlockUseCase> {
        UnlockUseCaseImpl(
            directDI = this,
        )
    }
    bindSingleton<Base64Service> {
        Base64ServiceJvm(
            directDI = this,
        )
    }
    bindSingleton<Base32Service> {
        Base32ServiceJvm(
            directDI = this,
        )
    }
    bindSingleton<GetTotpCode> {
        GetTotpCodeImpl(
            directDI = this,
        )
    }
    bindSingleton<GetTwoFa> {
        GetTwoFaImpl(
            directDI = this,
        )
    }
    bindSingleton<GetPasskeys> {
        GetPasskeysImpl(
            directDI = this,
        )
    }
    bindSingleton<GetPassword> {
        GetPasswordImpl(
            directDI = this,
        )
    }
    bindSingleton<GetPinCode> {
        GetPinCodeImpl(
            directDI = this,
        )
    }
    bindSingleton<GetScreenState> {
        GetScreenStateImpl(
            directDI = this,
        )
    }
    bindSingleton<GetConcealFields> {
        GetConcealFieldsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetKeepScreenOn> {
        GetKeepScreenOnImpl(
            directDI = this,
        )
    }
    bindSingleton<GetVaultLockAfterTimeout> {
        GetVaultLockAfterTimeoutImpl(
            directDI = this,
        )
    }
    bindSingleton<GetVaultLockAfterTimeoutVariants> {
        GetVaultLockAfterTimeoutVariantsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetBiometricTimeout> {
        GetBiometricTimeoutImpl(
            directDI = this,
        )
    }
    bindSingleton<GetBiometricTimeoutVariants> {
        GetBiometricTimeoutVariantsImpl(
            directDI = this,
        )
    }
    bindSingleton<PutBiometricTimeout> {
        PutBiometricTimeoutImpl(
            directDI = this,
        )
    }
    bindSingleton<GetBiometricRequireConfirmation> {
        GetBiometricRequireConfirmationImpl(
            directDI = this,
        )
    }
    bindSingleton<PutBiometricRequireConfirmation> {
        PutBiometricRequireConfirmationImpl(
            directDI = this,
        )
    }
    bindSingleton<GetCheckPwnedPasswords> {
        GetCheckPwnedPasswordsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAutofillCopyTotp> {
        GetAutofillCopyTotpImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAutofillDefaultMatchDetection> {
        GetAutofillDefaultMatchDetectionImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAutofillInlineSuggestions> {
        GetAutofillInlineSuggestionsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAutofillManualSelection> {
        GetAutofillManualSelectionImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAutofillRespectAutofillOff> {
        GetAutofillRespectAutofillOffImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAutofillSaveRequest> {
        GetAutofillSaveRequestImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAutofillSaveUri> {
        GetAutofillSaveUriImpl(
            directDI = this,
        )
    }
    bindSingleton<GetCheckPwnedServices> {
        GetCheckPwnedServicesImpl(
            directDI = this,
        )
    }
    bindSingleton<GetCheckTwoFA> {
        GetCheckTwoFAImpl(
            directDI = this,
        )
    }
    bindSingleton<GetCheckPasskeys> {
        GetCheckPasskeysImpl(
            directDI = this,
        )
    }
    bindSingleton<PutCheckPwnedPasswords> {
        PutCheckPwnedPasswordsImpl(
            directDI = this,
        )
    }
    bindSingleton<PutCheckPwnedServices> {
        PutCheckPwnedServicesImpl(
            directDI = this,
        )
    }
    bindSingleton<PutCheckTwoFA> {
        PutCheckTwoFAImpl(
            directDI = this,
        )
    }
    bindSingleton<PutCheckPasskeys> {
        PutCheckPasskeysImpl(
            directDI = this,
        )
    }
    bindSingleton<PutKeepScreenOn> {
        PutKeepScreenOnImpl(
            directDI = this,
        )
    }
    bindSingleton<VaultSessionLocker> {
        VaultSessionLocker(
            directDI = this,
        )
    }
    bindSingleton<PutVaultSession> {
        PutVaultSessionImpl(
            directDI = this,
        )
    }
    bindSingleton<ClearVaultSession> {
        ClearVaultSessionImpl(
            directDI = this,
        )
    }
    bindSingleton<PutNavAnimation> {
        PutNavAnimationImpl(
            directDI = this,
        )
    }
    bindSingleton<PutNavLabel> {
        PutNavLabelImpl(
            directDI = this,
        )
    }
    bindSingleton<PutTheme> {
        PutThemeImpl(
            directDI = this,
        )
    }
    bindSingleton<PutThemeUseAmoledDark> {
        PutThemeUseAmoledDarkImpl(
            directDI = this,
        )
    }
    bindSingleton<PutColors> {
        PutColorsImpl(
            directDI = this,
        )
    }
    bindSingleton<PutScreenState> {
        PutScreenStateImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAllowScreenshots> {
        GetAllowScreenshotsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAllowTwoPanelLayoutInLandscape> {
        GetAllowTwoPanelLayoutInLandscapeImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAllowTwoPanelLayoutInPortrait> {
        GetAllowTwoPanelLayoutInPortraitImpl(
            directDI = this,
        )
    }
    bindSingleton<GetVaultLockAfterScreenOff> {
        GetVaultLockAfterScreenOffImpl(
            directDI = this,
        )
    }
    bindSingleton<GetVaultLockAfterReboot> {
        GetVaultLockAfterRebootImpl(
            directDI = this,
        )
    }
    bindSingleton<PasskeyTargetCheck> {
        PasskeyTargetCheckImpl(this)
    }
    bindSingleton<PutAllowScreenshots> {
        PutAllowScreenshotsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetUseExternalBrowser> {
        GetUseExternalBrowserImpl(
            directDI = this,
        )
    }
    bindSingleton<PutUseExternalBrowser> {
        PutUserExternalBrowserImpl(
            directDI = this,
        )
    }
    bindSingleton<GetCloseToTray> {
        GetCloseToTrayImpl(
            directDI = this,
        )
    }
    bindSingleton<PutCloseToTray> {
        PutCloseToTrayImpl(
            directDI = this,
        )
    }
    bindSingleton<PutAllowTwoPanelLayoutInLandscape> {
        PutAllowTwoPanelLayoutInLandscapeImpl(
            directDI = this,
        )
    }
    bindSingleton<PutAllowTwoPanelLayoutInPortrait> {
        PutAllowTwoPanelLayoutInPortraitImpl(
            directDI = this,
        )
    }
    bindSingleton<PutVaultLockAfterReboot> {
        PutVaultLockAfterRebootImpl(
            directDI = this,
        )
    }
    bindSingleton<PutVaultLockAfterScreenOff> {
        PutVaultLockAfterScreenOffImpl(
            directDI = this,
        )
    }
    bindSingleton<PutAutofillCopyTotp> {
        PutAutofillCopyTotpImpl(
            directDI = this,
        )
    }
    bindSingleton<PutAutofillDefaultMatchDetection> {
        PutAutofillDefaultMatchDetectionImpl(
            directDI = this,
        )
    }
    bindSingleton<PutAutofillInlineSuggestions> {
        PutAutofillInlineSuggestionsImpl(
            directDI = this,
        )
    }
    bindSingleton<PutAutofillManualSelection> {
        PutAutofillManualSelectionImpl(
            directDI = this,
        )
    }
    bindSingleton<PutAutofillRespectAutofillOff> {
        PutAutofillRespectAutofillOffImpl(
            directDI = this,
        )
    }
    bindSingleton<PutAutofillSaveRequest> {
        PutAutofillSaveRequestImpl(
            directDI = this,
        )
    }
    bindSingleton<PutAutofillSaveUri> {
        PutAutofillSaveUriImpl(
            directDI = this,
        )
    }
    bindSingleton<GetThemeVariants> {
        GetThemeVariantsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetColorsVariants> {
        GetColorsVariantsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetNavAnimation> {
        GetNavAnimationImpl(
            directDI = this,
        )
    }
    bindSingleton<GetNavLabel> {
        GetNavLabelImpl(
            directDI = this,
        )
    }
    bindSingleton<GetNavAnimationVariants> {
        GetNavAnimationVariantsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetFont> {
        GetFontImpl(
            directDI = this,
        )
    }
    bindSingleton<GetFontVariants> {
        GetFontVariantsImpl(
            directDI = this,
        )
    }
    bindSingleton<PutFont> {
        PutFontImpl(
            directDI = this,
        )
    }
    bindSingleton<GetLocaleVariants> {
        GetLocaleVariantsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetTheme> {
        GetThemeImpl(
            directDI = this,
        )
    }
    bindSingleton<GetThemeUseAmoledDark> {
        GetThemeUseAmoledDarkImpl(
            directDI = this,
        )
    }
    bindSingleton<GetColors> {
        GetColorsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetSubscriptions> {
        GetSubscriptionsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetProducts> {
        GetProductsImpl(
            directDI = this,
        )
    }
    bindSingleton<CipherUrlCheck> {
        CipherUrlCheckImpl(this)
    }
    bindSingleton<CipherUrlDuplicateCheck> {
        CipherUrlDuplicateCheckImpl(this)
    }
    bindSingleton<CipherUrlBroadCheck> {
        CipherUrlBroadCheckImpl(this)
    }
    bindSingleton<GetCanWrite> {
        GetCanWriteImpl(
            directDI = this,
        )
    }
    bindSingleton<GetCachePremium> {
        GetCachePremiumImpl(
            directDI = this,
        )
    }
    bindSingleton<PutCachePremium> {
        PutCachePremiumImpl(
            directDI = this,
        )
    }
    bindSingleton<PutVaultLockAfterTimeout> {
        PutVaultLockAfterTimeoutImpl(
            directDI = this,
        )
    }
    bindSingleton<PutConcealFields> {
        PutConcealFieldsImpl(
            directDI = this,
        )
    }
    bindSingleton<PutClipboardAutoRefresh> {
        PutClipboardAutoRefreshImpl(
            directDI = this,
        )
    }
    bindSingleton<PutClipboardAutoClear> {
        PutClipboardAutoClearImpl(
            directDI = this,
        )
    }
    bindSingleton<GetClipboardAutoClear> {
        GetClipboardAutoClearImpl(
            directDI = this,
        )
    }
    bindSingleton<GetClipboardAutoClearVariants> {
        GetClipboardAutoClearVariantsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetClipboardAutoRefresh> {
        GetClipboardAutoRefreshImpl(
            directDI = this,
        )
    }
    bindSingleton<GetClipboardAutoRefreshVariants> {
        GetClipboardAutoRefreshVariantsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetDebugPremium> {
        GetDebugPremiumImpl(
            directDI = this,
        )
    }
    bindSingleton<GetWriteAccess> {
        GetWriteAccessImpl(
            directDI = this,
        )
    }
    bindSingleton<GetDebugScreenDelay> {
        GetDebugScreenDelayImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAppIcons> {
        GetAppIconsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetWebsiteIcons> {
        GetWebsiteIconsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetMarkdown> {
        GetMarkdownImpl(
            directDI = this,
        )
    }
    bindSingleton<GetGravatarUrl> {
        GetGravatarUrlImpl(
            directDI = this,
        )
    }
    bindSingleton<GetGravatar> {
        GetGravatarImpl(
            directDI = this,
        )
    }
    bindSingleton<PutGravatar> {
        PutGravatarImpl(
            directDI = this,
        )
    }
    bindSingleton<GetInMemoryLogsEnabled> {
        GetInMemoryLogsEnabledImpl(
            directDI = this,
        )
    }
    bindSingleton<GetInMemoryLogs> {
        GetInMemoryLogsImpl(
            directDI = this,
        )
    }
    bindSingleton<PutInMemoryLogsEnabled> {
        PutInMemoryLogsEnabledImpl(
            directDI = this,
        )
    }
    bindSingleton<InMemoryLogRepository> {
        InMemoryLogRepositoryImpl(
            directDI = this,
        )
    }
    bindSingleton<LogRepository> {
        LogRepositoryBridge(
            directDI = this,
        )
    }
    bindSingleton<GetJustDeleteMeByUrl> {
        GetJustDeleteMeByUrlImpl(
            directDI = this,
        )
    }
    bindSingleton<GetJustGetMyDataByUrl> {
        GetJustGetMyDataByUrlImpl(
            directDI = this,
        )
    }
    bindSingleton<ReadWordlistFromFile> {
        ReadWordlistFromFileImpl(
            directDI = this,
        )
    }
    bindSingleton<ReadWordlistFromUrl> {
        ReadWordlistFromUrlImpl(
            directDI = this,
        )
    }
    bindSingleton<PutWriteAccess> {
        PutWriteAccessImpl(
            directDI = this,
        )
    }
    bindSingleton<PutDebugPremium> {
        PutDebugPremiumImpl(
            directDI = this,
        )
    }
    bindSingleton<PutDebugScreenDelay> {
        PutDebugScreenDelayImpl(
            directDI = this,
        )
    }
    bindSingleton<PutAppIcons> {
        PutAppIconsImpl(
            directDI = this,
        )
    }
    bindSingleton<PutWebsiteIcons> {
        PutWebsiteIconsImpl(
            directDI = this,
        )
    }
    bindSingleton<PutMarkdown> {
        PutMarkdownImpl(
            directDI = this,
        )
    }
    bindSingleton<UpdateVersionLog> {
        UpdateVersionLogImpl(
            directDI = this,
        )
    }
    bindSingleton<ShowNotification> {
        ShowNotificationImpl(
            directDI = this,
        )
    }
    bindSingleton<DismissNotificationsByChannel> {
        DismissNotificationsByChannelImpl(
            directDI = this,
        )
    }
    bindSingleton<PutOnboardingLastVisitInstant> {
        PutOnboardingLastVisitInstantImpl(
            directDI = this,
        )
    }
    bindSingleton<GetOnboardingLastVisitInstant> {
        GetOnboardingLastVisitInstantImpl(
            directDI = this,
        )
    }
    bindSingleton<AppWorker>(tag = AppWorker.Feature.SYNC) {
        AppWorkerIm(
            directDI = this,
        )
    }
    bindSingleton<WatchtowerSyncer>() {
        WatchtowerSyncerImpl(
            directDI = this,
        )
    }
    bindSingleton<GetVaultSession> {
        GetVaultSessionImpl(
            directDI = this,
        )
    }
    bindSingleton<GetVaultPersist> {
        GetVaultPersistImpl(
            directDI = this,
        )
    }
    bindSingleton<PutVaultPersist> {
        PutVaultPersistImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAppVersion> {
        GetAppVersionImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAppBuildDate> {
        GetAppBuildDateImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAppBuildRef> {
        GetAppBuildRefImpl(
            directDI = this,
        )
    }
    bindSingleton<GetVersionLog> {
        GetVersionLogImpl(
            directDI = this,
        )
    }
    bindProvider<MessageHub> {
        instance<MessageHubImpl>()
    }
    bindProvider<ShowMessage> {
        instance<MessageHubImpl>()
    }
    bindSingleton {
        MessageHubImpl(
            directDI = this,
        )
    }
    bindSingleton<RemoveAttachment> {
        RemoveAttachmentImpl(
            directDI = this,
        )
    }
    bindSingleton<RequestAppReview> {
        RequestAppReviewImpl(
            directDI = this,
        )
    }
    bindSingleton<WindowCoroutineScope> {
        WindowCoroutineScopeImpl(
            directDI = this,
        )
    }
    // Repositories
    bindSingleton<Json> {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            prettyPrint = false
            isLenient = true
            serializersModule = SerializersModule {
                // default
                polymorphic(BitwardenCipher.Attachment::class) {
                    subclass(BitwardenCipher.Attachment.Remote::class)
                    subclass(BitwardenCipher.Attachment.Local::class)
                    defaultDeserializer { BitwardenCipher.Attachment.Remote.serializer() }
                }
            }
        }
    }
    bindSingleton<CipherEncryptor> {
        CipherEncryptorImpl(
            directDI = this,
        )
    }
    bindSingleton<FileEncryptor> {
        FileEncryptorImpl(
            directDI = this,
        )
    }
    bindSingleton<GetPassphrase> {
        PasswordGeneratorDiceware(
            directDI = this,
        )
    }
    bindSingleton<ReviewLog> {
        ReviewLogImpl(
            directDI = this,
        )
    }
    import(emailRelayDiModule())
    bindSingleton<CipherPlaceholder.Factory> {
        CipherPlaceholder.Factory(
            directDI = this,
        )
    }
    bindSingleton<CommentPlaceholder.Factory> {
        CommentPlaceholder.Factory(
            directDI = this,
        )
    }
    bindSingleton<CustomPlaceholder.Factory> {
        CustomPlaceholder.Factory(
            directDI = this,
        )
    }
    bindSingleton<DateTimePlaceholder.Factory> {
        DateTimePlaceholder.Factory(
            directDI = this,
        )
    }
    bindSingleton<EnvironmentPlaceholder.Factory> {
        EnvironmentPlaceholder.Factory(
            directDI = this,
        )
    }
    bindSingleton<TextReplaceRegexPlaceholder.Factory> {
        TextReplaceRegexPlaceholder.Factory(
            directDI = this,
        )
    }
    bindSingleton<TextTransformPlaceholder.Factory> {
        TextTransformPlaceholder.Factory(
            directDI = this,
        )
    }
    bindSingleton<UrlPlaceholder.Factory> {
        UrlPlaceholder.Factory(
            directDI = this,
        )
    }
    bindSingleton<DeeplinkService> {
        DeeplinkServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<ExecuteCommand> {
        ExecuteCommandImpl(
            directDI = this,
        )
    }
    bindSingleton<JsonExportService> {
        JsonExportServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<KeyPairExport> {
        KeyPairExportImpl(
            directDI = this,
        )
    }
    bindSingleton<KeyPublicExport> {
        KeyPublicExportImpl(
            directDI = this,
        )
    }
    bindSingleton<KeyPrivateExport> {
        KeyPrivateExportImpl(
            directDI = this,
        )
    }
    bindSingleton<WordlistService> {
        WordlistServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<TwoFaService> {
        TwoFaServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<PassKeyService> {
        PassKeyServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<NotificationFingerprintRepository> {
        NotificationFingerprintRepositoryImpl(
            directDI = this,
        )
    }
    bindSingleton<JustDeleteMeService> {
        JustDeleteMeServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<LocalizationContributorsService> {
        LocalizationContributorsServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<OtpMigrationService> {
        OtpMigrationServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<OtpMigrationParser> {
        OtpMigrationParser(
            directDI = this,
        )
    }
    bindSingleton<PrivilegedAppsService> {
        PrivilegedAppsServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<JustGetMyDataService> {
        JustGetMyDataServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<LicenseService> {
        LicenseServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<TldService> {
        TldServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<LinkInfoPlatformExtractor> {
        LinkInfoPlatformExtractor()
    }
    bindSingleton<LinkInfoExtractorExecute> {
        LinkInfoExtractorExecute()
    }
    bindSingleton<ZipService> {
        ZipServiceJvm(
            directDI = this,
        )
    }
    bindSingleton<SimilarityService> {
        SimilarityServiceJvm(
            directDI = this,
        )
    }
    bindSingleton<IdRepository> {
        val store = instance<Files, KeyValueStore>(
            arg = Files.DEVICE_ID,
        )
        IdRepositoryImpl(
            store = store,
        )
    }
    bindSingleton<StateRepository> {
        val store = instance<Files, KeyValueStore>(
            arg = Files.UI_STATE,
        )
        StateRepositoryImpl(
            store = store,
            json = instance(),
        )
    }
    bindSingleton<DeviceIdUseCase> {
        DeviceIdUseCase(
            directDI = this,
        )
    }
    bindSingleton<DeviceEncryptionKeyUseCase> {
        DeviceEncryptionKeyUseCase(
            directDI = this,
        )
    }
    bindSingleton<GetAppVersionCode> {
        GetAppVersionCodeImpl()
    }
    bindSingleton<GetAppVersionName> {
        GetAppVersionNameImpl()
    }
    bindSingleton<GetPasswordStrength> {
        GetPasswordStrengthJvm(
            directDI = this,
        )
    }
    bindSingleton<GetAppBuildType> {
        GetAppBuildTypeImpl(this)
    }
    bindSingleton<DateFormatter> {
        DateFormatterAndroid(
            directDI = this,
        )
    }
    bindSingleton<NumberFormatter> {
        NumberFormatterJvm(
            directDI = this,
        )
    }
    bindSingleton<CryptoGenerator> {
        CryptoGeneratorJvm()
    }
    bindSingleton<KeyPairGenerator> {
        KeyPairGeneratorJvm(
            directDI = this,
        )
    }
    bindSingleton<TotpService> {
        TotpServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<HttpClient> {
        val json: Json = instance()
        val okHttpClient: OkHttpClient = instance()
        HttpClient(OkHttp) {
            install(UserAgent) {
                agent = BitwardenPersona.of(CurrentPlatform)
                    .userAgent
            }
            engine {
                preconfigured = okHttpClient
            }
            install(Logging) {
                level = if (isRelease) LogLevel.INFO else LogLevel.ALL
            }
            install(ContentNegotiation) {
                register(ContentType.Application.Json, KotlinxSerializationConverter(json))
            }
            install(WebSockets) {
                pingIntervalMillis = 20_000
            }
            install(HttpCache) {
                // In memory.
            }
            install(HttpRequestRetry) {
                maxRetries = 5
                retryIf { _, response ->
                    response.status == HttpStatusCode.TooManyRequests ||
                            response.status.value in 500..599
                }
                constantDelay(
                    respectRetryAfterHeader = true,
                )
            }
        }
    }
    bindSingleton<HttpClient>(tag = "curl") {
        val json: Json = instance()
        val okHttpClient: OkHttpClient = instance()
        HttpClient(OkHttp) {
            CurlUserAgent()
            engine {
                preconfigured = okHttpClient
            }
            install(Logging) {
                level = if (isRelease) LogLevel.INFO else LogLevel.ALL
            }
            install(ContentNegotiation) {
                register(ContentType.Application.Json, KotlinxSerializationConverter(json))
            }
            install(WebSockets) {
                pingIntervalMillis = 20_000
            }
            install(HttpCache) {
                // In memory.
            }
            install(HttpRequestRetry) {
                maxRetries = 5
                retryIf { _, response ->
                    response.status == HttpStatusCode.TooManyRequests ||
                            response.status.value in 500..599
                }
                constantDelay(
                    respectRetryAfterHeader = true,
                )
            }
        }
    }
    bindSingleton<OkHttpClient> {
        OkHttpClient
            .Builder()
            .apply {
                if (!isRelease) {
                    val logRepository: LogRepository = instance()
                    val logger = HttpLoggingInterceptor.Logger { message ->
                        logRepository.post(
                            tag = "OkHttp",
                            message = message,
                        )
                    }
                    val logging = HttpLoggingInterceptor(logger).apply {
                        level = HttpLoggingInterceptor.Level.BODY
                    }
                    addInterceptor(logging)
                }
            }
            .build()
    }
    installFingerprintRepo()
    installKeyRepo()
    installSessionRepo()
    installSessionMetadataRepo()
    installSettingsRepo()
}

private fun DI.Builder.installFingerprintRepo() {
    bindSingleton {
        FingerprintRepositoryImpl(directDI)
    }
    bindProvider<FingerprintReadRepository> {
        instance<FingerprintRepositoryImpl>()
    }
    bindProvider<FingerprintReadWriteRepository> {
        instance<FingerprintRepositoryImpl>()
    }
}

private fun DI.Builder.installKeyRepo() {
    bindSingleton {
        KeyRepositoryImpl(directDI)
    }
    bindProvider<KeyReadRepository> {
        instance<KeyRepositoryImpl>()
    }
    bindProvider<KeyReadWriteRepository> {
        instance<KeyRepositoryImpl>()
    }
}

private fun DI.Builder.installSessionRepo() {
    bindSingleton {
        SessionRepositoryImpl()
    }
    bindProvider<SessionReadRepository> {
        instance<SessionRepositoryImpl>()
    }
    bindProvider<SessionReadWriteRepository> {
        instance<SessionRepositoryImpl>()
    }
}

private fun DI.Builder.installSessionMetadataRepo() {
    bindSingleton {
        SessionMetadataRepositoryImpl(
            directDI = directDI,
        )
    }
    bindProvider<SessionMetadataReadRepository> {
        instance<SessionMetadataRepositoryImpl>()
    }
    bindProvider<SessionMetadataReadWriteRepository> {
        instance<SessionMetadataRepositoryImpl>()
    }
}

private fun DI.Builder.installSettingsRepo() {
    bindSingleton {
        SettingsRepositoryImpl(
            directDI = directDI,
        )
    }
    bindProvider<SettingsReadRepository> {
        instance<SettingsRepositoryImpl>()
    }
    bindProvider<SettingsReadWriteRepository> {
        instance<SettingsRepositoryImpl>()
    }
}
