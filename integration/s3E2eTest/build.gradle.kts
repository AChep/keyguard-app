import com.artemchep.keyguard.buildplugins.testing.E2eToolchain
import com.artemchep.keyguard.buildplugins.testing.VerifyE2eEnvironmentTask
import org.gradle.api.tasks.testing.Test

plugins {
    id("keyguard.quality")
    id("keyguard.jvm-e2e")
    id("keyguard.native-crypto-consumer")
}

dependencies {
    "s3E2eTestImplementation"(project(":util:s3"))
    "s3E2eTestImplementation"(libs.kotlinx.coroutines.test)
    "s3E2eTestImplementation"(libs.ktor.ktor.client.cio)
    "s3E2eTestImplementation"(libs.ktor.ktor.client.okhttp)
}

val verifyE2eEnvironment = tasks.register<VerifyE2eEnvironmentTask>("verifyE2eEnvironment") {
    toolchain.set(E2eToolchain.VERSITYGW)
}

tasks.named<Test>("s3E2eTest") {
    description = "Runs S3 E2E tests against a real versitygw server from PATH."
    dependsOn(verifyE2eEnvironment)
}
