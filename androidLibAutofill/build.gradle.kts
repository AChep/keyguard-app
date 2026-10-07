plugins {
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
