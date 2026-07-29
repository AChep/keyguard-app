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
    testImplementation("junit:junit:${libs.versions.junit.get()}")
}

gradlePlugin {
    plugins {
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
        register("cryptoDependencyPolicy") {
            id = "keyguard.crypto-dependency-policy"
            implementationClass = "com.artemchep.keyguard.buildplugins.nativecrypto.CryptoDependencyPolicyPlugin"
        }
        register("detektCustomRules") {
            id = "keyguard.detekt-custom-rules"
            implementationClass = "com.artemchep.keyguard.buildplugins.detekt.DetektCustomRulesPlugin"
        }
    }
}
