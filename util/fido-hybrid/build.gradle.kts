import com.artemchep.keyguard.buildplugins.kotlin.sharedAppleMain
import com.artemchep.keyguard.buildplugins.kotlin.sharedJvmMain

plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    id("keyguard.compose-free")
    id("keyguard.crypto-dependency-check")
    id("keyguard.native-crypto-consumer")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":util:crypto"))
            api(project(":util:webauthn"))
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        sharedJvmMain(name = "jvmCommonMain").dependencies {
            implementation(project.dependencies.platform(libs.squareup.okhttp.bom))
            implementation(libs.squareup.okhttp)
        }
        sharedAppleMain()
    }
}
