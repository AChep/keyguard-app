plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
}

group = "com.artemchep.keyguard.buildplugins"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get().toInt()))
}

dependencies {
    implementation("com.android.tools.build:gradle:${libs.versions.androidPlugin.get()}")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    compileOnly("dev.detekt:detekt-gradle-plugin:${libs.versions.detekt.get()}")
    compileOnly("org.jlleitschuh.gradle:ktlint-gradle:${libs.versions.ktlintPlugin.get()}")
    compileOnly("app.cash.licensee:licensee-gradle-plugin:${libs.versions.licenseCheckPlugin.get()}")
    testImplementation(gradleTestKit())
    testImplementation("junit:junit:${libs.versions.junit.get()}")
}

tasks.withType<Test>().configureEach {
    systemProperty("keyguard.test.gradleUserHome", gradle.gradleUserHomeDir.absolutePath)
    systemProperty("keyguard.test.offline", gradle.startParameter.isOffline.toString())
}

gradlePlugin {
    plugins {
        register("jvmE2e") {
            id = "keyguard.jvm-e2e"
            implementationClass = "com.artemchep.keyguard.buildplugins.testing.JvmE2eConventionPlugin"
        }
        register("kotlinMultiplatform") {
            id = "keyguard.kotlin-multiplatform"
            implementationClass = "com.artemchep.keyguard.buildplugins.kotlin.KotlinMultiplatformConventionPlugin"
        }
        register("kotlinMultiplatformLibrary") {
            id = "keyguard.kotlin-multiplatform-library"
            implementationClass = "com.artemchep.keyguard.buildplugins.kotlin.KotlinMultiplatformLibraryConventionPlugin"
        }
        register("androidApplication") {
            id = "keyguard.android-application"
            implementationClass = "com.artemchep.keyguard.buildplugins.android.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "keyguard.android-library"
            implementationClass = "com.artemchep.keyguard.buildplugins.android.AndroidLibraryConventionPlugin"
        }
        register("androidTest") {
            id = "keyguard.android-test"
            implementationClass = "com.artemchep.keyguard.buildplugins.android.AndroidTestConventionPlugin"
        }
        register("ktlintConvention") {
            id = "keyguard.ktlint"
            implementationClass = "com.artemchep.keyguard.buildplugins.quality.KtlintConventionPlugin"
        }
        register("qualityConvention") {
            id = "keyguard.quality"
            implementationClass = "com.artemchep.keyguard.buildplugins.quality.QualityConventionPlugin"
        }
        register("licensePolicy") {
            id = "keyguard.license-policy"
            implementationClass = "com.artemchep.keyguard.buildplugins.quality.LicensePolicyPlugin"
        }
        register("cryptoDependencyCheck") {
            id = "keyguard.crypto-dependency-check"
            implementationClass = "com.artemchep.keyguard.buildplugins.nativecrypto.CryptoDependencyCheckPlugin"
        }
        register("resourcesCommon") {
            id = "keyguard.resources-common"
            implementationClass = "com.artemchep.keyguard.buildplugins.resources.ResourcesCommonPlugin"
        }
        register("cargoCommon") {
            id = "keyguard.cargo-common"
            implementationClass = "com.artemchep.keyguard.buildplugins.cargo.CargoCommonPlugin"
        }
        register("rustMultiplatformLibrary") {
            id = "keyguard.rust-multiplatform-library"
            implementationClass =
                "com.artemchep.keyguard.buildplugins.cargo.RustMultiplatformLibraryPlugin"
        }
        register("rustAppleLibrary") {
            id = "keyguard.rust-apple-library"
            implementationClass =
                "com.artemchep.keyguard.buildplugins.cargo.RustAppleLibraryPlugin"
        }
        register("androidSshAgent") {
            id = "keyguard.android-ssh-agent"
            implementationClass = "com.artemchep.keyguard.buildplugins.androidssh.AndroidSshAgentPlugin"
        }
        register("nativeCryptoConsumer") {
            id = "keyguard.native-crypto-consumer"
            implementationClass = "com.artemchep.keyguard.buildplugins.nativecrypto.NativeCryptoConsumerPlugin"
        }
        register("nativeIoConsumer") {
            id = "keyguard.native-io-consumer"
            implementationClass = "com.artemchep.keyguard.buildplugins.nativeio.NativeIoConsumerPlugin"
        }
        register("nativeZxcvbnConsumer") {
            id = "keyguard.native-zxcvbn-consumer"
            implementationClass =
                "com.artemchep.keyguard.buildplugins.nativezxcvbn.NativeZxcvbnConsumerPlugin"
        }
        register("cryptoDependencyPolicy") {
            id = "keyguard.crypto-dependency-policy"
            implementationClass = "com.artemchep.keyguard.buildplugins.nativecrypto.CryptoDependencyPolicyPlugin"
        }
        register("composeFree") {
            id = "keyguard.compose-free"
            implementationClass = "com.artemchep.keyguard.buildplugins.architecture.ComposeFreePlugin"
        }
        register("wearDependencyCheck") {
            id = "keyguard.wear-dependency-check"
            implementationClass =
                "com.artemchep.keyguard.buildplugins.optionalfeatures.WearDependencyCheckPlugin"
        }
        register("detektCustomRules") {
            id = "keyguard.detekt-custom-rules"
            implementationClass = "com.artemchep.keyguard.buildplugins.detekt.DetektCustomRulesPlugin"
        }
    }
}
