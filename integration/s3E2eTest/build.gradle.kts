import com.artemchep.keyguard.buildplugins.testing.E2eToolchain

plugins {
    id("keyguard.quality")
    id("keyguard.jvm-e2e")
    id("keyguard.native-crypto-consumer")
}

keyguardE2e {
    toolchain = E2eToolchain.VERSITYGW
}

dependencies {
    s3E2eTestImplementation(project(":util:s3"))
    s3E2eTestImplementation(libs.kotlinx.coroutines.test)
    s3E2eTestImplementation(libs.ktor.ktor.client.cio)
    s3E2eTestImplementation(libs.ktor.ktor.client.okhttp)
}

tasks.named<Test>("s3E2eTest") {
    description = "Runs S3 E2E tests against a real versitygw server from PATH."
}
