package com.artemchep.keyguard.common.service.vault

import com.artemchep.keyguard.common.model.testCipherFilterContext
import com.artemchep.keyguard.di.DomainSessionAccessModule
import com.artemchep.keyguard.di.asVaultSession
import org.koin.core.Koin
import org.koin.core.qualifier.named
import org.koin.dsl.ScopeDSL
import org.koin.dsl.module

/** Each fixture gets an isolated scoped graph and uses the production retirement boundary. */
internal fun testVaultSession(declarations: ScopeDSL.() -> Unit = {}): VaultSession {
    val application = Koin().apply {
        loadModules(listOf(
            module {
                scope<TestVaultScope> {
                    scoped { testCipherFilterContext() }
                    declarations()
                }
            },
        ))
    }
    return application.createScope("test-vault", named<TestVaultScope>()).asVaultSession()
}

/** Exercise the production typed adapters against the isolated session fakes. */
internal inline fun <reified T : Any> testDomainSessionAccess(): T = Koin().apply {
    loadModules(listOf(DomainSessionAccessModule().module))
}.get()

private class TestVaultScope
