import com.artemchep.keyguard.buildplugins.testing.E2eToolchain
import com.artemchep.keyguard.buildplugins.testing.VerifyE2eEnvironmentTask
import com.artemchep.keyguard.buildplugins.testing.pythonSetupMessage

plugins {
    id("keyguard.quality")
    id("keyguard.jvm-e2e")
    id("keyguard.native-crypto-consumer")
}

keyguardE2e {
    toolchain = E2eToolchain.PYKEEPASS
}

dependencies {
    kdbxE2eTestImplementation(project(":util:kdbx"))
    kdbxE2eTestImplementation(libs.kotlinx.serialization.json)
}

val kdbxPython = providers.gradleProperty("kdbxE2ePython").orElse("python3")
val kdbxDriver = layout.projectDirectory.file("python/kdbx_e2e.py")
val requirements = layout.projectDirectory.file("requirements.txt")
val seedDirectory = layout.settingsDirectory.dir("util/kdbx/src/jvmCommonTest/resources")
val artifactsDirectory = layout.buildDirectory.dir("kdbxE2eTest/artifacts")

tasks.named<VerifyE2eEnvironmentTask>("verifyE2eEnvironment") {
    pythonExecutable.set(kdbxPython)
    pythonDriver.set(kdbxDriver)
    val requirementsFile = requirements.asFile
    pythonSetupHint.set(kdbxPython.map { python -> pythonSetupMessage(python, requirementsFile) })
}

tasks.named<Test>("kdbxE2eTest") {
    description = "Runs KDBX interoperability tests against pykeepass."
    systemProperty("keyguard.kdbxE2e.python", kdbxPython.get())
    systemProperty("keyguard.kdbxE2e.driver", kdbxDriver.asFile.absolutePath)
    systemProperty("keyguard.kdbxE2e.seedDir", seedDirectory.asFile.absolutePath)
    systemProperty("keyguard.kdbxE2e.artifactsDir", artifactsDirectory.get().asFile.absolutePath)
}
