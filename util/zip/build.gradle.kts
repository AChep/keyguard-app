import com.artemchep.keyguard.buildplugins.kotlin.sharedAppleMain
import com.artemchep.keyguard.buildplugins.kotlin.sharedIosTest
import com.artemchep.keyguard.buildplugins.kotlin.sharedJvmMain

plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    id("keyguard.rust-apple-library")
}

kotlin {
    android {
        namespace = "com.artemchep.keyguard.util.zip"
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.kotlinx.io.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                // `runBlocking` is not in the common surface; `runTest` is.
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        sharedJvmMain().dependencies {
            implementation(libs.lingala.zip4j)
        }
        sharedAppleMain()
        named("appleMain").dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        sharedIosTest()

        all {
            languageSettings.optIn("kotlin.ExperimentalStdlibApi")
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
        }
    }
}

// Opt-in flag for `JvmCompatFixtureGenerator`; see its docs.
tasks.withType<Test>().configureEach {
    systemProperty(
        "keyguard.zip.writeFixtures",
        providers.gradleProperty("keyguard.zip.writeFixtures").getOrElse("false"),
    )
}
