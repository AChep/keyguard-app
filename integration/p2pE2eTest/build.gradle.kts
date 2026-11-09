import com.artemchep.keyguard.buildplugins.cargo.NativeLibraryPathArgumentProvider
import com.artemchep.keyguard.buildplugins.cargo.configureNativeLibraryTests
import com.artemchep.keyguard.buildplugins.testing.E2eToolchain
import com.artemchep.keyguard.buildplugins.testing.P2P_E2E_TURN_PROPERTY
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("keyguard.quality")
    id("keyguard.jvm-e2e")
    id("keyguard.native-crypto-consumer")
}

keyguardE2e {
    toolchain = E2eToolchain.RELAY
}

// Sessions run Noise, from util/crypto, over WebRTC data channels or relay
// forwarding.
configureNativeLibraryTests(":util:webrtc")

dependencies {
    p2pE2eTestImplementation(project(":integration:testProcess"))
    p2pE2eTestImplementation(project(":common"))
    p2pE2eTestImplementation(project(":feature:p2p-webrtc"))
    p2pE2eTestImplementation(libs.testcontainers)
    p2pE2eTestImplementation(libs.kotlinx.coroutines.core)
    p2pE2eTestImplementation(libs.kotlinx.coroutines.test)
}

// The devices wire linked apps as the vault session's DI does, from declarations
// internal to :common; the suite compiles as a friend of it.
val commonClasses = configurations.named("p2pE2eTestCompileClasspath").map { classpath ->
    classpath.incoming
        .artifactView {
            componentFilter { id ->
                id is ProjectComponentIdentifier && id.projectPath == ":common"
            }
        }
        .files
}
tasks.named<KotlinCompile>("compileP2pE2eTestKotlin") {
    friendPaths.from(commonClasses)
}

val relayDir = isolated.rootProject.projectDirectory.dir("server/relay")
val relayTargetDir = layout.buildDirectory.dir("relay")
val relayExecutable = if (System.getProperty("os.name").startsWith("Windows")) "keyguard-relay.exe" else "keyguard-relay"
val cargoOffline = providers.gradleProperty("keyguard.nativeCargo.cargoOffline")
    .map(String::toBooleanStrict)
    .orElse(false)

// A debug build: the tests need the relay's behavior, not its speed. The relay is its
// own Cargo workspace, so the build is up to date while its sources are unchanged.
val buildRelay = tasks.register<Exec>("buildRelay") {
    group = "build"
    description = "Builds the relay server of server/relay for the P2P E2E tests."
    inputs.files(relayDir.file("Cargo.toml"), relayDir.file("Cargo.lock"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("relayManifest")
    inputs.dir(relayDir.dir("src"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("relaySources")
    inputs.file(isolated.rootProject.projectDirectory.file("rust-toolchain.toml"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("rustToolchain")
    inputs.property("offline", cargoOffline)
    outputs.file(relayTargetDir.map { it.file("debug/$relayExecutable") })
        .withPropertyName("relayBinary")
    // The toolchain file at the repository root picks the Rust version.
    workingDir(isolated.rootProject.projectDirectory)
    executable("cargo")
    args(
        "build",
        "--manifest-path",
        relayDir.file("Cargo.toml").asFile.absolutePath,
        "--locked",
        "--target-dir",
        relayTargetDir.get().asFile.absolutePath,
    )
    if (cargoOffline.get()) args("--offline")
    // A missing Cargo fails with the setup hint of the suite first.
    dependsOn(tasks.named("verifyE2eEnvironment"))
}

tasks.named<Test>("p2pE2eTest") {
    description = "Syncs in-process linked Keyguard apps through a real relay server, over relay " +
        "forwarding and WebRTC; -P$P2P_E2E_TURN_PROPERTY=true adds TURN through coturn in Docker."
    jvmArgumentProviders.add(
        NativeLibraryPathArgumentProvider("keyguard.p2pE2e.relayBinary", files(buildRelay)),
    )
    val turn = providers.gradleProperty(P2P_E2E_TURN_PROPERTY)
        .map(String::toBooleanStrict)
        .orElse(false)
    inputs.property("turn", turn)
    systemProperty(P2P_E2E_TURN_PROPERTY, turn.get())
    // Testcontainers brings SLF4J without a provider; keep its notice off stderr.
    systemProperty("slf4j.internal.verbosity", "ERROR")
}
