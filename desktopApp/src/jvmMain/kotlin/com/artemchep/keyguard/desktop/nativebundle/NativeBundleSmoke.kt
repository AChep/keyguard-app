package com.artemchep.keyguard.desktop.nativebundle

import com.artemchep.jna.ensureDesktopLibAvailable
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.vault.VaultSessionFactory
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.createDesktopKoinApplication
import com.artemchep.keyguard.desktop.instance.verifyPackagedInstanceService
import com.artemchep.keyguard.di.resolve
import com.artemchep.keyguard.nativebundle.NativeBundleProbe
import com.artemchep.keyguard.nativebundle.nativeProbe
import com.artemchep.keyguard.util.io.LocalPath
import okhttp3.OkHttpClient
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineDispatcher
import org.koin.core.qualifier.named

internal const val NATIVE_PACKAGED_SMOKE_ARGUMENT = "--native-packaged-smoke"
private const val RESULT_PATH_ENV = "KEYGUARD_NATIVE_SMOKE_RESULT_PATH"
private const val RESULT_NONCE_ENV = "KEYGUARD_NATIVE_SMOKE_NONCE"
private const val HELPER_TIMEOUT_SECONDS = 5L

/** Runs before normal bootstrap, using only packaged resources and isolated temporary data. */
// This process boundary reports every smoke failure, including native linkage errors, before exiting.
@Suppress("TooGenericExceptionCaught")
internal fun runNativePackagedSmoke() {
    try {
        rejectDevelopmentLibraries()
        val resources = Path.of(checkNotNull(System.getProperty("compose.application.resources.dir")))
        val directory = Files.createTempDirectory("keyguard-native-smoke-").toRealPath()
        try {
            NativeBundleProbe.run(LocalPath(directory.toString()))
        } finally {
            directory.toFile().deleteRecursively()
        }
        nativeProbe("instance") { verifyPackagedInstanceService() }
        nativeProbe("desktopBridge") { ensureDesktopLibAvailable() }
        nativeProbe("sshHelper") { verifyHelper(resources, "keyguard-ssh-agent") }
        nativeProbe("gpgHelper") { verifyHelper(resources, "keyguard-gpg-agent") }
        nativeProbe("koin") { verifyPackagedKoinGraph() }
        val tls = nativeProbe("tls") { verifyPackagedDesktopTls() }
        val success = "native packaged smoke passed: crypto=PASS io=PASS zxcvbn=PASS " +
            "instance=PASS desktopBridge=PASS sshHelper=PASS gpgHelper=PASS koin=PASS tls=PASS tlsRuntime=$tls"
        publishResult(success)
        println(success)
    } catch (error: Throwable) {
        System.err.println("native packaged smoke failed: ${error.javaClass.simpleName}: ${error.message}")
        exitProcess(1)
    }
}

private fun rejectDevelopmentLibraries() {
    listOf("Crypto", "Io", "Zxcvbn", "Instance").forEach { module ->
        val property = "keyguard.native$module.libraryPath"
        check(System.getProperty(property).isNullOrBlank()) {
            "Packaged smoke rejects the development override $property"
        }
    }
}

private fun verifyHelper(resources: Path, name: String) {
    val suffix = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) ".exe" else ""
    val binary = resources.resolve(name + suffix)
    check(Files.isRegularFile(binary)) { "Missing packaged helper $name" }
    val process = ProcessBuilder(binary.toString(), "--version").redirectErrorStream(true).start()
    try {
        check(process.waitFor(HELPER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Packaged helper $name timed out" }
        check(process.exitValue() == 0) { "Packaged helper $name exited ${process.exitValue()}" }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(output.startsWith("$name ")) { "Unexpected packaged helper identity: $name" }
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}

/** Assembles the shrunk application graph and one vault scope without touching user data. */
private fun verifyPackagedKoinGraph() {
    val application = createDesktopKoinApplication()
    try {
        val koin = application.koin
        koin.get<CryptoGenerator>()
        koin.get<CoroutineDispatcher>(named<DatabaseDispatcher>())
        val session = koin.get<VaultSessionFactory>()
            .create(MasterKey(MasterKdfVersion.LATEST, ByteArray(32)))
        try {
            checkNotNull(session.resolve { get<WindowCoroutineScope>() })
        } finally {
            session.close()
        }
        check(!session.active.value) { "Vault scope did not retire on close" }
    } finally {
        application.close()
    }
}

private fun verifyPackagedDesktopTls(): String {
    val jdkFeature = Runtime.version().feature()
    val providerName = SSLContext.getDefault().provider.name
    val socketFactory = OkHttpClient.Builder().build().sslSocketFactory
    (socketFactory.createSocket() as SSLSocket).use { socket ->
        check("TLSv1.3" in socket.enabledProtocols)
    }
    return "OkHttp/$providerName/JDK$jdkFeature"
}

private fun publishResult(success: String) {
    val resultPath = System.getenv(RESULT_PATH_ENV)?.takeIf(String::isNotBlank) ?: return
    val nonce = checkNotNull(System.getenv(RESULT_NONCE_ENV)?.takeIf(String::isNotBlank)) {
        "$RESULT_NONCE_ENV is required when publishing smoke evidence"
    }
    Files.writeString(
        Path.of(resultPath),
        "$nonce\n$success\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
    )
}
