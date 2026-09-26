plugins {
    id("keyguard.quality")
    alias(libs.plugins.android.library)
    id("keyguard.android-library")
    id("keyguard.detekt-custom-rules")
}

detektCustomRules {
    androidVariant("debug")
}

android {
    namespace = "com.artemchep.keyguard.android.autofill"
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}
