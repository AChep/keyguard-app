import com.artemchep.keyguard.buildplugins.testing.E2eToolchain

plugins {
    id("keyguard.quality")
    id("keyguard.jvm-e2e")
}

keyguardE2e {
    toolchain = E2eToolchain.WEBDAV
}

dependencies {
    webdavE2eTestImplementation(project(":util:webdav"))
    webdavE2eTestImplementation(libs.kotlinx.coroutines.test)
    webdavE2eTestImplementation(libs.ktor.ktor.client.cio)
}

tasks.named<Test>("webdavE2eTest") {
    description = "Runs WebDAV E2E tests against a real hacdias/webdav server from PATH."
}
