import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
@CName("keychainAddPassword")
public expect fun keychainAddPassword(id: String, password: String): Boolean

@OptIn(ExperimentalNativeApi::class)
@CName("keychainGetPassword")
public expect fun keychainGetPassword(id: String): String?

@OptIn(ExperimentalNativeApi::class)
@CName("keychainDeletePassword")
public expect fun keychainDeletePassword(id: String): Boolean

@OptIn(ExperimentalNativeApi::class)
@CName("keychainContainsPassword")
public expect fun keychainContainsPassword(id: String): Boolean
