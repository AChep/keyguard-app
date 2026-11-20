package com.artemchep.keyguard.common.service.release

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AppReleaseCache
import com.artemchep.keyguard.common.model.AppUpdateDelay
import com.artemchep.keyguard.common.model.AppUpdateLevel
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.service.settings.impl.SettingsRepositoryImpl
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlin.time.Duration

/**
 * Keeps the app update settings in memory, so the tests that run on
 * virtual time do not wait for the store's dispatchers.
 */
internal class FakeAppUpdateSettings : SettingsReadWriteRepository by SettingsRepositoryImpl(
    store = JsonKeyValueStore(),
    json = Json,
    base64Service = Base64ServiceImpl(),
) {
    val level = MutableStateFlow(AppUpdateLevel.DEFAULT)

    val delay = MutableStateFlow(AppUpdateDelay.DEFAULT)

    val cache = MutableStateFlow<AppReleaseCache?>(null)

    val notifiedTag = MutableStateFlow<String?>(null)

    override fun getAppUpdateLevel() = level

    override fun setAppUpdateLevel(level: AppUpdateLevel): IO<Unit> = ioEffect {
        this.level.value = level
    }

    override fun getAppUpdateDelay() = delay

    override fun setAppUpdateDelay(duration: Duration): IO<Unit> = ioEffect {
        delay.value = duration
    }

    override fun getAppUpdateCache() = cache

    override fun setAppUpdateCache(cache: AppReleaseCache?): IO<Unit> = ioEffect {
        this.cache.value = cache
    }

    override fun getAppUpdateNotifiedTag() = notifiedTag

    override fun setAppUpdateNotifiedTag(tag: String?): IO<Unit> = ioEffect {
        notifiedTag.value = tag
    }
}
