package com.artemchep.keyguard.apple.vault

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.LinkInfoPlatform
import com.artemchep.keyguard.common.service.extract.LinkInfoRegistry
import com.artemchep.keyguard.common.service.extract.impl.LinkInfoExtractorExecute
import com.artemchep.keyguard.common.service.extract.impl.LinkInfoPlatformExtractor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class CipherLinkInfoTest {
    @Test
    fun nativeDetailClassifiesSavedUrisForDisplay() = runTest {
        val koin = koinApplication {
            modules(
                module {
                    single { LinkInfoPlatformExtractor() }
                    single { LinkInfoExtractorExecute() }
                },
            )
        }.koin
        val registry = LinkInfoRegistry(koin.appleCipherLinkInfoExtractors())
        val web = registry.process(DSecret.Uri(uri = "https://example.com/login"))
        assertTrue(web.any { it is LinkInfoPlatform.Web })
        val other = registry.process(
            DSecret.Uri(uri = "a saved search", match = DSecret.Uri.MatchType.RegularExpression),
        )
        assertTrue(other.any { it is LinkInfoPlatform.Other })
        val app = registry.process(DSecret.Uri(uri = "iosapp://com.example.audit"))
        assertTrue(app.any { it is LinkInfoPlatform.IOS })
    }
}
