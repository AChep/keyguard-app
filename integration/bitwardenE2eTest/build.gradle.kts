import com.artemchep.keyguard.buildplugins.cargo.NativeLibraryPathArgumentProvider
import com.artemchep.keyguard.buildplugins.testing.E2eToolchain
import com.artemchep.keyguard.buildplugins.testing.forwardSystemProperties

plugins {
    id("keyguard.quality")
    id("keyguard.jvm-e2e")
    id("keyguard.native-crypto-consumer")
}

keyguardE2e {
    toolchain = E2eToolchain.DOCKER
}

dependencies {
    bitwardenE2eTestImplementation(project(":common"))
    bitwardenE2eTestImplementation(libs.testcontainers)
    bitwardenE2eTestImplementation(libs.ktor.ktor.client.cio)
    bitwardenE2eTestImplementation(libs.ktor.ktor.client.content.negotiation)
    bitwardenE2eTestImplementation(libs.ktor.ktor.client.websockets)
    bitwardenE2eTestImplementation(libs.ktor.ktor.serialization.kotlinx)
    bitwardenE2eTestImplementation(libs.kotlinx.coroutines.core)
    bitwardenE2eTestImplementation(libs.kotlinx.serialization.json)
}

tasks.named<Test>("bitwardenE2eTest") {
    description = "Runs Bitwarden E2E tests against a real Vaultwarden server in Docker."
    forwardSystemProperties(listOf("keyguard.vaultwarden.image"))
}

val bitwardenE2eTestSourceSet = extensions.getByType<SourceSetContainer>().named("bitwardenE2eTest")
val nativeCryptoLibrary = configurations.named("nativeCryptoDesktopLibrary")

// The seeding CLI of server/bw/bw. It talks to a running server, so it needs no Docker.
tasks.register<JavaExec>("bw") {
    group = "application"
    description = "Creates and seeds Bitwarden accounts on a running server. Pass the command with --args."
    classpath = bitwardenE2eTestSourceSet.get().runtimeClasspath
    mainClass.set("com.artemchep.keyguard.integration.bitwarden.cli.BwCliKt")
    // No SLF4J provider is on the classpath; keep its notice off stderr.
    systemProperty("slf4j.internal.verbosity", "ERROR")
    jvmArgumentProviders.add(
        NativeLibraryPathArgumentProvider("keyguard.nativeCrypto.libraryPath", nativeCryptoLibrary.get()),
    )
}
