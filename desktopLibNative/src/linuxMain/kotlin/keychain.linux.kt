import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
@CName("keychainAddPassword")
public actual fun keychainAddPassword(
    id: String,
    password: String,
): Boolean = false

@OptIn(ExperimentalNativeApi::class)
@CName("keychainGetPassword")
public actual fun keychainGetPassword(id: String): String? = null

@OptIn(ExperimentalNativeApi::class)
@CName("keychainDeletePassword")
public actual fun keychainDeletePassword(id: String): Boolean = false

@OptIn(ExperimentalNativeApi::class)
@CName("keychainContainsPassword")
public actual fun keychainContainsPassword(id: String): Boolean = false
