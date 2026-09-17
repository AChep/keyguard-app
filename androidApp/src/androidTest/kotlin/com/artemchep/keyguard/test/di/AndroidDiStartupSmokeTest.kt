package com.artemchep.keyguard.test.di

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.filters.SdkSuppress
import coil3.SingletonImageLoader
import com.artemchep.keyguard.Main
import com.artemchep.keyguard.android.CredentialProviderGetRequestHandler
import com.artemchep.keyguard.common.AppWorker
import com.artemchep.keyguard.common.di.ImageLoaderFactory
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.backup.BackupRunService
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.usecase.BlockedUrlCheck
import com.artemchep.keyguard.common.usecase.GetAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.GetAutofillInlineSuggestions
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.di.keyguardKoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.Koin
import org.koin.core.qualifier.named

/** Run against a fresh or locked target app; no Activity, accounts, or test DI overrides. */
@RunWith(AndroidJUnit4::class)
@MediumTest
class AndroidDiStartupSmokeTest {
    @Test
    fun applicationStartupProvidesImagesAutofillAndBackgroundDependencies() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val application = ApplicationProvider.getApplicationContext<Main>()
            val koin = application.koin

            assertSame(koin, application.keyguardKoin())
            assertSame(koin, ContextWrapper(application).keyguardKoin())
            assertSame(application, koin.get<Context>())

            val imageLoaderFactory = koin.get<ImageLoaderFactory>()
            val imageLoader = SingletonImageLoader.get(application)
            assertSame(imageLoaderFactory.get(application), imageLoader)
            assertSame(imageLoader, SingletonImageLoader.get(application))

            // These are resolved by AutofillService before an Activity or unlocked vault exists.
            assertNotNull(koin.get<BlockedUrlCheck>())
            assertNotNull(koin.get<GetTotpCode>())
            assertNotNull(koin.get<GetAutofillDefaultMatchDetection>().invoke().first())
            assertNotNull(koin.get<GetAutofillInlineSuggestions>().invoke().first())

            assertNotNull(koin.get<AppWorker>(qualifier = named(AppWorker.Feature.SYNC)))
            assertNotNull(koin.get<BackupRunService>())
            assertVaultServicesAreScoped(koin)
        }
    }

    @Test
    fun backgroundBackupSkipsALockedVaultWithoutOpeningVaultServices() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val koin = ApplicationProvider.getApplicationContext<Main>().koin
            assertLocked(koin)

            val status = koin.get<BackupRunService>().runAutomatic()

            assertEquals("vault_locked", status.lastSkippedReason)
            assertNull(status.currentRun)
            assertVaultServicesAreScoped(koin)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 34)
    @RequiresApi(34)
    fun credentialProviderOffersUnlockForALockedVault() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val application = ApplicationProvider.getApplicationContext<Main>()
            val koin = application.koin
            assertLocked(koin)
            val request = BeginGetCredentialRequest(
                beginGetCredentialOptions = listOf(
                    BeginGetPasswordOption(
                        id = "di-startup-smoke-password",
                        allowedUserIds = emptySet(),
                        candidateQueryData = Bundle(),
                    ),
                ),
                callingAppInfo = null,
            )

            val response = koin.get<CredentialProviderGetRequestHandler>().process(request)

            assertTrue(response.credentialEntries.isEmpty())
            assertTrue(response.actions.isEmpty())
            assertNull(response.remoteEntry)
            assertEquals(1, response.authenticationActions.size)
            val unlock = response.authenticationActions.single()
            assertTrue(unlock.title.isNotBlank())
            assertEquals(application.packageName, unlock.pendingIntent.creatorPackage)
            assertTrue(unlock.pendingIntent.isActivity)
            assertVaultServicesAreScoped(koin)
        }
    }

    private suspend fun assertLocked(koin: Koin) {
        val session = koin.get<GetVaultSession>().invoke().first()
        assertTrue("This smoke test requires a fresh or locked target app", session is MasterSession.Empty)
    }

    private fun assertVaultServicesAreScoped(koin: Koin) {
        assertNull(koin.getOrNull<GetCiphers>())
        assertNull(koin.getOrNull<VaultDatabaseManager>())
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
