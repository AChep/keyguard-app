import com.artemchep.keyguard.buildplugins.testing.E2eToolchain
import com.artemchep.keyguard.buildplugins.testing.VerifyE2eEnvironmentTask
import org.gradle.api.tasks.testing.Test

plugins {
    id("keyguard.quality")
    id("keyguard.jvm-e2e")
}

dependencies {
    "webdavE2eTestImplementation"(project(":util:webdav"))
    "webdavE2eTestImplementation"(libs.kotlinx.coroutines.test)
    "webdavE2eTestImplementation"(libs.ktor.ktor.client.cio)
}

val verifyE2eEnvironment = tasks.register<VerifyE2eEnvironmentTask>("verifyE2eEnvironment") {
    toolchain.set(E2eToolchain.WEBDAV)
}

tasks.named<Test>("webdavE2eTest") {
    description = "Runs WebDAV E2E tests against a real hacdias/webdav server from PATH."
    dependsOn(verifyE2eEnvironment)
}
