import com.artemchep.keyguard.buildplugins.kotlin.sharedAppleMain
import com.artemchep.keyguard.buildplugins.kotlin.sharedJvmMain

plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    id("keyguard.native-crypto-consumer")
    id("keyguard.detekt-custom-rules")
}

detektCustomRules {
    kmpCompilation(targetName = "android", compilationName = "main")
}

kotlin {
    android {
        namespace = "com.artemchep.keyguard.util.s3"
    }

    sourceSets {
        sharedJvmMain(name = "jvmCommonMain").dependencies {
            implementation(project.dependencies.platform(libs.squareup.okhttp.bom))
            implementation(libs.squareup.okhttp)
        }
        sharedAppleMain()
        getByName("commonMain") {
            dependencies {
                implementation(project(":util:xml"))
                implementation(project(":util:foundation"))
                api(libs.ktor.ktor.client.core)
                implementation(libs.ktor.ktor.client.encoding)
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.io.core)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.ktor.ktor.client.mock)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        all {
            languageSettings.optIn("kotlin.ExperimentalStdlibApi")
            languageSettings.optIn("kotlin.time.ExperimentalTime")
        }
    }
}
