package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.TemporaryArtifactMaintenance
import com.artemchep.keyguard.common.service.download.CacheDirProvider
import com.artemchep.keyguard.common.service.download.CacheDirProviderApple
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.staging.SpoolLimits
import com.artemchep.keyguard.common.service.staging.StagingPurpose
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.platform.AppleStorageDirectories
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.withTestAppleStorage
import com.artemchep.keyguard.test.RecordingLogRepository
import com.artemchep.keyguard.util.io.atomic.AtomicDirectoryDestination
import com.artemchep.keyguard.util.io.toKotlinxIoPath
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppleTemporaryStorageTest {
    @Test
    fun stagingSpillUsesTheInjectedDirectoryAndRejectsAnObstructedRoot() = runTest {
        withTestAppleStorage { directories ->
            val application = koinApplication {
                allowOverride(true)
                modules(ApplicationIntegrationsModule().module, ApplePlatformServicesModule().module, module {
                    single<AppleStorageDirectories> { directories }
                })
            }
            try {
                val factory = application.koin.resolveFromCompiledModule(StagingSpoolFactory::class)
                val bytes = "confidential spill".encodeToByteArray()
                factory.create(StagingPurpose.PendingUploadPlaintext, SpoolLimits(0, 1024)) {
                    IllegalArgumentException("limit")
                }.use { writer ->
                    writer.sink().write(bytes)
                    writer.seal().use { snapshot ->
                        snapshot.openSource().use { assertContentEquals(bytes, it.readByteArray()) }
                    }
                }
                SystemFileSystem.delete(directories.temporary.toKotlinxIoPath())
                // Missing directories may be recreated. A file at the configured
                // location must fail rather than spilling into another directory.
                SystemFileSystem.sink(directories.temporary.toKotlinxIoPath()).buffered().use {
                    it.writeString("not a directory")
                }
                assertFails {
                    factory.create(StagingPurpose.PendingUploadPlaintext, SpoolLimits(0, 1024)) {
                        IllegalArgumentException("limit")
                    }.use { writer ->
                        writer.sink().write(bytes)
                        writer.seal().close()
                    }
                }
            } finally {
                application.close()
            }
        }
    }

    @Test
    fun maintenanceResolvesOnlyTheInjectedDataCacheAndTemporaryRoots() = runTest {
        withTestAppleStorage { fixture ->
            val reads = mutableSetOf<String>()
            val directories = object : AppleStorageDirectories {
                override val data: AtomicDirectoryDestination
                    get() = fixture.data.also { reads += "data" }
                override val cache: LocalPath
                    get() = fixture.cache.also { reads += "cache" }
                override val temporary: LocalPath
                    get() = fixture.temporary.also { reads += "temporary" }
            }
            val logs = RecordingLogRepository()
            val application = koinApplication {
                allowOverride(true)
                modules(ApplicationIntegrationsModule().module, ApplePlatformServicesModule().module, module {
                    single<AppleStorageDirectories> { directories }
                    single<CacheDirProvider> { CacheDirProviderApple(get()) }
                    single<LogRepository> { logs }
                })
            }
            try {
                application.koin.resolveFromCompiledModule(TemporaryArtifactMaintenance::class)()
                assertTrue(reads.containsAll(setOf("data", "cache", "temporary")))
                assertFalse(logs.messages.any { "root_resolution_failed" in it })
            } finally {
                application.close()
            }
        }
    }
}

// Kotlin/Native's Koin plugin cannot read main-compilation definitions from its
// klib. These tests intentionally resolve the compiled production module at runtime.
private fun <T : Any> Koin.resolveFromCompiledModule(type: KClass<T>): T = get(clazz = type)
