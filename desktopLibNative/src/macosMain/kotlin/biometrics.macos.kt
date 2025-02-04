import kotlinx.cinterop.ByteVarOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.experimental.ExperimentalNativeApi
import platform.LocalAuthentication.LAContext
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
@CName("biometricsIsSupported")
public actual fun biometricsIsSupported(): Boolean {
    val context = LAContext()
    return context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, null)
}

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
@CName("biometricsVerify")
public actual fun biometricsVerify(
    title: String,
    callback: CPointer<CFunction<(Boolean, CPointer<ByteVarOf<Byte>>?) -> Unit>>,
) {
    val context = LAContext()

    val isSupported = context
        .canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, null)
    if (isSupported) {
        context.evaluatePolicy(
            policy = LAPolicyDeviceOwnerAuthenticationWithBiometrics,
            localizedReason = title,
        ) { success, error ->
            val errorMsg = if (success) {
                null
            } else {
                error?.localizedDescription
                    ?: "Authentication failed"
            }

            memScoped {
                callback.invoke(success, errorMsg?.cstr?.getPointer(this))
            }
        }
    } else {
        callback.invoke(false, null)
    }
}
