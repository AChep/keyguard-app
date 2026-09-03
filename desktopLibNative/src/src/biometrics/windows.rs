use super::{report_verify_result, ChallengeResult, ChallengeStatus};
use crate::ffi::BiometricsVerifyCallback;
use std::ffi::c_void;
use std::marker::PhantomData;
use std::rc::Rc;
use subtle::ConstantTimeEq;
use windows::core::{factory, Error as WindowsError, Owned, HRESULT, HSTRING, PCWSTR};
use windows::Security::Credentials::UI::{
    UserConsentVerificationResult, UserConsentVerifier, UserConsentVerifierAvailability,
};
use windows::Win32::Foundation::{
    ERROR_CANCELLED, ERROR_INVALID_SID, HANDLE, HWND, NTE_BAD_DATA, NTE_BAD_KEYSET,
    NTE_DEVICE_NOT_READY, NTE_NOT_SUPPORTED, NTE_NO_KEY, NTE_PROV_DLL_NOT_FOUND,
    NTE_USER_CANCELLED, WAIT_ABANDONED, WAIT_OBJECT_0,
};
use windows::Win32::Security::Cryptography::{
    NCryptCreatePersistedKey, NCryptDecrypt, NCryptDeleteKey, NCryptEncrypt, NCryptFinalizeKey,
    NCryptGetProperty, NCryptOpenKey, NCryptOpenStorageProvider, NCryptSetProperty,
    BCRYPT_RSA_ALGORITHM, CERT_KEY_SPEC, MS_NGC_KEY_STORAGE_PROVIDER, NCRYPT_ALLOW_DECRYPT_FLAG,
    NCRYPT_ALLOW_KEY_IMPORT_FLAG, NCRYPT_FLAGS, NCRYPT_HANDLE, NCRYPT_KEY_HANDLE,
    NCRYPT_KEY_USAGE_PROPERTY, NCRYPT_LENGTH_PROPERTY, NCRYPT_PAD_PKCS1_FLAG, NCRYPT_PROV_HANDLE,
    NCRYPT_SILENT_FLAG, NCRYPT_USE_CONTEXT_PROPERTY, NCRYPT_WINDOW_HANDLE_PROPERTY,
};
use windows::Win32::Security::{
    GetSidIdentifierAuthority, GetSidSubAuthority, GetSidSubAuthorityCount, GetTokenInformation,
    IsValidSid, TokenUser, OBJECT_SECURITY_INFORMATION, TOKEN_QUERY, TOKEN_USER,
};
use windows::Win32::System::Threading::{
    CreateMutexW, GetCurrentProcess, OpenProcessToken, ReleaseMutex, WaitForSingleObject, INFINITE,
};
use windows::Win32::System::WinRT::{
    IUserConsentVerifierInterop, RoInitialize, RoUninitialize, RO_INIT_MULTITHREADED,
};
use windows_future::IAsyncOperation;
use zeroize::Zeroizing;

const CREDENTIAL_NAME_SUFFIX: &str = "Keyguard//biometric-unlock-v1";
const CREDENTIAL_MUTEX_NAME: PCWSTR = windows::core::w!("Local\\KeyguardBiometricUnlockV1");
const NGC_CACHE_TYPE_PROPERTY: PCWSTR = windows::core::w!("NgcCacheType");
const NGC_CACHE_TYPE_PROPERTY_LEGACY: PCWSTR = windows::core::w!("NgcCacheTypeProperty");
const PIN_CACHE_IS_GESTURE_REQUIRED_PROPERTY: PCWSTR =
    windows::core::w!("PinCacheIsGestureRequired");
const RSA_KEY_SIZE_BITS: u32 = 2048;
const NGC_CACHE_TYPE_AUTH_MANDATORY: u32 = 1;
const ERROR_CANCELLED_HRESULT: HRESULT = HRESULT::from_win32(ERROR_CANCELLED.0);

/// Serializes access to the user-scoped credential across Keyguard processes
/// in the current Windows session. This keeps provisioning and rollback from
/// racing another process that uses the same persisted key name.
struct CredentialMutexGuard {
    handle: Owned<HANDLE>,
}

impl CredentialMutexGuard {
    fn acquire() -> windows::core::Result<Self> {
        // SAFETY: The mutex name is a process-lifetime UTF-16 constant. The
        // returned handle is transferred immediately into an owning wrapper.
        let handle = unsafe { CreateMutexW(None, false, CREDENTIAL_MUTEX_NAME)? };
        // SAFETY: CreateMutexW returned a uniquely owned mutex handle, and
        // Owned releases it exactly once with CloseHandle.
        let handle = unsafe { Owned::new(handle) };
        // SAFETY: `handle` owns a live synchronization handle. Waiting with
        // INFINITE is intentional because biometric key operations must not
        // overlap across processes.
        let wait_result = unsafe { WaitForSingleObject(*handle, INFINITE) };
        if wait_result != WAIT_OBJECT_0 && wait_result != WAIT_ABANDONED {
            return Err(WindowsError::from_thread());
        }
        Ok(Self { handle })
    }
}

impl Drop for CredentialMutexGuard {
    fn drop(&mut self) {
        // SAFETY: A guard exists only after this thread acquired the mutex.
        // ReleaseMutex relinquishes ownership; Owned then closes the handle.
        let _ = unsafe { ReleaseMutex(*self.handle) };
    }
}

/// Balances RoInitialize/RoUninitialize on one thread. `Rc` makes the guard
/// `!Send` so it cannot be dropped from another thread.
struct WinRtApartment(PhantomData<Rc<()>>);

impl WinRtApartment {
    fn initialize() -> windows::core::Result<Self> {
        // SAFETY: RoInitialize initializes WinRT for only the current native
        // thread. Every successful call, including S_FALSE, must be balanced
        // by RoUninitialize on that same thread. Incompatible apartment modes
        // remain errors rather than pretending WinRT was initialized by us.
        unsafe { RoInitialize(RO_INIT_MULTITHREADED)? };
        Ok(Self(PhantomData))
    }
}

impl Drop for WinRtApartment {
    fn drop(&mut self) {
        // SAFETY: This balances the successful RoInitialize performed by
        // WinRtApartment::initialize on this same thread.
        unsafe { RoUninitialize() };
    }
}

struct ProviderHandle(Owned<NCRYPT_PROV_HANDLE>);

impl ProviderHandle {
    fn open() -> windows::core::Result<Self> {
        let mut handle = NCRYPT_PROV_HANDLE::default();
        // SAFETY: `handle` is a valid out pointer. The provider name is a
        // process-lifetime UTF-16 constant and the returned handle is
        // transferred immediately into an owning wrapper.
        unsafe {
            NCryptOpenStorageProvider(&mut handle, MS_NGC_KEY_STORAGE_PROVIDER, 0)?;
        }
        // SAFETY: NCryptOpenStorageProvider returned a uniquely owned provider
        // handle, and Owned releases it exactly once with NCryptFreeObject.
        Ok(Self(unsafe { Owned::new(handle) }))
    }

    fn raw(&self) -> NCRYPT_PROV_HANDLE {
        *self.0
    }
}

struct KeyHandle(Owned<NCRYPT_KEY_HANDLE>);

impl KeyHandle {
    fn open(
        provider: &ProviderHandle,
        credential_name: PCWSTR,
        silent: bool,
    ) -> windows::core::Result<Self> {
        let mut handle = NCRYPT_KEY_HANDLE::default();
        let flags = if silent {
            NCRYPT_SILENT_FLAG
        } else {
            NCRYPT_FLAGS(0)
        };
        // SAFETY: ProviderHandle owns a live provider, `handle` is a valid out
        // pointer, and the credential name remains live for the call. The
        // returned key is transferred to KeyHandle.
        unsafe {
            NCryptOpenKey(
                provider.raw(),
                &mut handle,
                credential_name,
                CERT_KEY_SPEC(0),
                flags,
            )?;
        }
        // SAFETY: NCryptOpenKey returned a uniquely owned key handle, and
        // Owned releases it exactly once with NCryptFreeObject.
        Ok(Self(unsafe { Owned::new(handle) }))
    }

    fn create(
        provider: &ProviderHandle,
        credential_name: PCWSTR,
        window_handle: i64,
        title: &str,
    ) -> windows::core::Result<Self> {
        let mut handle = NCRYPT_KEY_HANDLE::default();
        // SAFETY: ProviderHandle owns a live provider, `handle` is a valid out
        // pointer, the credential name remains live for the call, and the
        // returned key is transferred to KeyHandle.
        unsafe {
            NCryptCreatePersistedKey(
                provider.raw(),
                &mut handle,
                BCRYPT_RSA_ALGORITHM,
                credential_name,
                CERT_KEY_SPEC(0),
                NCRYPT_FLAGS(0),
            )?;
        }

        // SAFETY: NCryptCreatePersistedKey returned a uniquely owned key
        // handle, and Owned releases it exactly once with NCryptFreeObject.
        let key = Self(unsafe { Owned::new(handle) });
        key.set_u32(NCRYPT_LENGTH_PROPERTY, RSA_KEY_SIZE_BITS)?;
        key.set_u32(NCRYPT_KEY_USAGE_PROPERTY, NCRYPT_ALLOW_DECRYPT_FLAG)?;

        // These are Microsoft Passport KSP properties. `NgcCacheType` makes
        // authentication mandatory for private-key operations. Older Windows
        // builds used the `NgcCacheTypeProperty` spelling, so support both.
        if key
            .set_u32(NGC_CACHE_TYPE_PROPERTY, NGC_CACHE_TYPE_AUTH_MANDATORY)
            .is_err()
        {
            key.set_u32(
                NGC_CACHE_TYPE_PROPERTY_LEGACY,
                NGC_CACHE_TYPE_AUTH_MANDATORY,
            )?;
        }
        key.set_prompt_context(window_handle, title)?;

        // SAFETY: `key.raw()` is a live unfinalized key created above. All
        // required properties have been configured and NCrypt retains no
        // pointers from those property calls.
        unsafe { NCryptFinalizeKey(key.raw(), NCRYPT_FLAGS(0))? };
        Ok(key)
    }

    fn raw(&self) -> NCRYPT_KEY_HANDLE {
        *self.0
    }

    fn set_u32(&self, property: PCWSTR, value: u32) -> windows::core::Result<()> {
        self.set_property(property, &value.to_ne_bytes())
    }

    fn set_property(&self, property: PCWSTR, value: &[u8]) -> windows::core::Result<()> {
        // SAFETY: The key handle is live, the property name is a valid
        // NUL-terminated UTF-16 constant, and NCryptSetProperty copies the
        // borrowed value during this call.
        unsafe {
            NCryptSetProperty(
                NCRYPT_HANDLE(self.raw().0),
                property,
                value,
                NCRYPT_FLAGS(0),
            )
        }
    }

    fn get_u32(&self, property: PCWSTR) -> windows::core::Result<u32> {
        let mut value = [0_u8; size_of::<u32>()];
        let mut result_len = 0_u32;
        // SAFETY: The key handle is live, the property name is a valid
        // NUL-terminated UTF-16 constant, and `value` is writable for its
        // complete declared length.
        unsafe {
            NCryptGetProperty(
                NCRYPT_HANDLE(self.raw().0),
                property,
                Some(&mut value),
                &mut result_len,
                OBJECT_SECURITY_INFORMATION(0),
            )?;
        }
        if result_len != size_of::<u32>() as u32 {
            return Err(WindowsError::from_hresult(NTE_BAD_DATA));
        }
        Ok(u32::from_ne_bytes(value))
    }

    fn has_expected_protection(&self) -> bool {
        let Ok(key_usage) = self.get_u32(NCRYPT_KEY_USAGE_PROPERTY) else {
            return false;
        };
        if key_usage & NCRYPT_ALLOW_DECRYPT_FLAG == 0
            || key_usage & NCRYPT_ALLOW_KEY_IMPORT_FLAG != 0
        {
            return false;
        }
        let cache_type = self
            .get_u32(NGC_CACHE_TYPE_PROPERTY)
            .or_else(|_| self.get_u32(NGC_CACHE_TYPE_PROPERTY_LEGACY));
        cache_type == Ok(NGC_CACHE_TYPE_AUTH_MANDATORY)
    }

    fn set_prompt_context(&self, window_handle: i64, title: &str) -> windows::core::Result<()> {
        if let Some(handle) = native_window_handle(window_handle) {
            match self.set_property(NCRYPT_WINDOW_HANDLE_PROPERTY, &handle.to_ne_bytes()) {
                Err(error) if error.code() != NTE_BAD_DATA => return Err(error),
                _ => {}
            }
        }
        self.set_property(NCRYPT_USE_CONTEXT_PROPERTY, &utf16_bytes(title))
    }

    fn delete(self) -> windows::core::Result<()> {
        // SAFETY: KeyHandle uniquely owns this live persisted key. A successful
        // NCryptDeleteKey both deletes the persisted key and invalidates the
        // handle, so Drop must not run afterwards. On failure `self` is
        // dropped normally and frees the still-valid handle.
        unsafe { NCryptDeleteKey(self.raw(), 0)? };
        std::mem::forget(self);
        Ok(())
    }
}

pub(crate) fn is_supported() -> bool {
    let Ok(_apartment) = WinRtApartment::initialize() else {
        return false;
    };
    has_required_hello_capabilities().unwrap_or(false)
}

/// Requires a live [WinRtApartment] on the calling thread.
fn has_required_hello_capabilities() -> windows::core::Result<bool> {
    // Probe the exact KSP used by wrap/unwrap. KeyCredentialManager is not a
    // suitable proxy: it checks whether the user can provision a WinRT key
    // credential and imposes account requirements this NCrypt path does not.
    // Native desktop consent prompts require the Windows 11 interop API.
    // Probe the interface instead of relying on an OS version check so the
    // advertised capability matches what this process can actually invoke.
    let _interop = factory::<UserConsentVerifier, IUserConsentVerifierInterop>()?;
    let _provider = ProviderHandle::open()?;
    let availability = UserConsentVerifier::CheckAvailabilityAsync()?.join()?;
    Ok(is_consent_verifier_available(availability))
}

fn is_consent_verifier_available(availability: UserConsentVerifierAvailability) -> bool {
    matches!(
        availability,
        UserConsentVerifierAvailability::Available | UserConsentVerifierAvailability::DeviceBusy
    )
}

pub(crate) fn verify(window_handle: i64, title: &str, callback: BiometricsVerifyCallback) {
    let result = request_user_consent(window_handle, title);
    report_verify_result(callback, result.status, result.error.as_deref());
}

/// Shows the Windows Hello consent prompt for `window_handle` and reports
/// whether the user passed it. This never touches the unlock credential.
fn request_user_consent(window_handle: i64, title: &str) -> ChallengeResult {
    let _apartment = match WinRtApartment::initialize() {
        Ok(apartment) => apartment,
        Err(error) => return failure("Could not initialize Windows Hello", error),
    };
    let interop = match factory::<UserConsentVerifier, IUserConsentVerifierInterop>() {
        Ok(interop) => interop,
        Err(error) => {
            return ChallengeResult::failure(
                ChallengeStatus::Unavailable,
                describe("Could not open Windows Hello", &error),
            );
        }
    };
    let window = HWND(native_window_handle(window_handle).unwrap_or(0) as *mut c_void);
    let message = HSTRING::from(title);
    // SAFETY: The interop factory is a live COM object. The window handle is
    // either a valid top-level window owned by the JVM or null, which Windows
    // rejects with an error instead of dereferencing, and the message outlives
    // the call.
    let operation: windows::core::Result<IAsyncOperation<UserConsentVerificationResult>> =
        unsafe { interop.RequestVerificationForWindowAsync(window, &message) };
    let result = operation.and_then(|operation| operation.join());
    match result {
        Ok(result) => status_from_consent(result),
        Err(error) => failure("Windows Hello verification failed", error),
    }
}

fn status_from_consent(result: UserConsentVerificationResult) -> ChallengeResult {
    match result {
        UserConsentVerificationResult::Verified => ChallengeResult::success(Vec::new()),
        UserConsentVerificationResult::Canceled => ChallengeResult::failure(
            ChallengeStatus::UserCanceled,
            "Windows Hello verification was canceled",
        ),
        UserConsentVerificationResult::RetriesExhausted => ChallengeResult::failure(
            ChallengeStatus::SecurityDeviceLocked,
            "Windows Hello is locked after too many attempts",
        ),
        UserConsentVerificationResult::DeviceNotPresent
        | UserConsentVerificationResult::NotConfiguredForUser
        | UserConsentVerificationResult::DisabledByPolicy => ChallengeResult::failure(
            ChallengeStatus::Unavailable,
            "Windows Hello is not configured",
        ),
        UserConsentVerificationResult::DeviceBusy => {
            ChallengeResult::failure(ChallengeStatus::Unknown, "Windows Hello is busy")
        }
        _ => ChallengeResult::failure(
            ChallengeStatus::Unknown,
            "Windows Hello returned an unknown result",
        ),
    }
}

pub(crate) fn delete_credential() -> bool {
    let Ok(_credential_guard) = CredentialMutexGuard::acquire() else {
        return false;
    };
    match open_existing_key() {
        Ok((key, _provider)) => key.delete().is_ok(),
        Err(error) => is_credential_not_found_error(&error),
    }
}

/// Opens the persisted unlock key without prompting the user. The provider is
/// returned alongside the key so that it outlives it; the tuple order makes
/// the key drop first.
fn open_existing_key() -> windows::core::Result<(KeyHandle, ProviderHandle)> {
    let provider = ProviderHandle::open()?;
    let credential_name = current_user_credential_name_wide()?;
    let key = KeyHandle::open(&provider, PCWSTR::from_raw(credential_name.as_ptr()), true)?;
    Ok((key, provider))
}

/// Wraps (`decrypt == false`) the secret with the Windows Hello protected key,
/// creating it when it is missing or misconfigured, or unwraps
/// (`decrypt == true`) the secret with the existing key.
pub(crate) fn transform_secret(
    window_handle: i64,
    title: &str,
    input: &[u8],
    decrypt: bool,
) -> ChallengeResult {
    let _credential_guard = match CredentialMutexGuard::acquire() {
        Ok(guard) => guard,
        Err(error) => return failure("Could not lock the Windows Hello credential", error),
    };
    match transform_secret_locked(window_handle, title, input, decrypt) {
        Ok(value) => ChallengeResult::success(value),
        Err(result) => result,
    }
}

/// Requires the caller to hold the [CredentialMutexGuard].
fn transform_secret_locked(
    window_handle: i64,
    title: &str,
    input: &[u8],
    decrypt: bool,
) -> Result<Zeroizing<Vec<u8>>, ChallengeResult> {
    let provider =
        ProviderHandle::open().map_err(|error| failure("Could not open Windows Hello", error))?;
    let credential_name = current_user_credential_name_wide()
        .map_err(|error| failure("Could not identify the Windows user", error))?;
    let OpenedKey { key, created } = open_or_create_key(
        &provider,
        PCWSTR::from_raw(credential_name.as_ptr()),
        window_handle,
        title,
        decrypt,
    )
    .map_err(|error| {
        // During unwrap, open_or_create_key only opens an existing key.
        // Keep missing-key classification scoped to that operation so
        // the same NCrypt code from decrypt/configuration remains
        // unknown.
        let status = if decrypt {
            status_from_open_error(&error)
        } else {
            status_from_error(&error)
        };
        ChallengeResult::failure(
            status,
            describe("Could not open the Windows Hello credential", &error),
        )
    })?;

    let outcome = (|| {
        // A freshly created key already carries the prompt context.
        if !created {
            key.set_prompt_context(window_handle, title)
                .map_err(|error| ("Could not configure the Windows Hello prompt", error))?;
        }
        let operation = if decrypt {
            decrypt_secret
        } else {
            encrypt_and_verify_secret
        };
        operation(&key, input).map_err(|error| ("Windows Hello verification failed", error))
    })();
    outcome.map_err(|(context, error)| transform_failure(key, created, context, error))
}

fn credential_name(user_sid: &str) -> String {
    format!("{user_sid}//{CREDENTIAL_NAME_SUFFIX}")
}

fn current_user_credential_name_wide() -> windows::core::Result<Vec<u16>> {
    let name = credential_name(&current_user_sid()?);
    Ok(name.encode_utf16().chain(std::iter::once(0)).collect())
}

fn current_user_sid() -> windows::core::Result<String> {
    let mut token = HANDLE::default();
    // SAFETY: GetCurrentProcess returns a process pseudo-handle, and `token`
    // is a valid out pointer. The returned token is immediately owned.
    unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token)? };
    // SAFETY: OpenProcessToken returned a uniquely owned token handle, and
    // Owned releases it exactly once with CloseHandle.
    let token = unsafe { Owned::new(token) };

    let mut required_length = 0;
    // SAFETY: A null buffer with length zero is the documented size-query
    // form. `required_length` is a valid out pointer.
    let _ = unsafe { GetTokenInformation(*token, TokenUser, None, 0, &mut required_length) };
    if required_length == 0 {
        return Err(WindowsError::from_thread());
    }

    // TOKEN_USER requires pointer alignment, so use machine words rather
    // than a byte vector for the backing allocation.
    let word_size = std::mem::size_of::<usize>();
    let word_count = (required_length as usize).div_ceil(word_size);
    let mut buffer = vec![0usize; word_count];
    // SAFETY: `buffer` is writable for at least `required_length` bytes and
    // has sufficient alignment for TOKEN_USER.
    unsafe {
        GetTokenInformation(
            *token,
            TokenUser,
            Some(buffer.as_mut_ptr().cast()),
            required_length,
            &mut required_length,
        )?;
    }

    // SAFETY: A successful TokenUser query starts with a TOKEN_USER value,
    // whose SID pointer remains valid while `buffer` is alive.
    let token_user = unsafe { &*(buffer.as_ptr().cast::<TOKEN_USER>()) };
    let sid = token_user.User.Sid;
    // SAFETY: The SID came from a successful TokenUser query.
    if !unsafe { IsValidSid(sid) }.as_bool() {
        return Err(WindowsError::from_hresult(HRESULT::from_win32(
            ERROR_INVALID_SID.0,
        )));
    }

    // SAFETY: IsValidSid succeeded, so the SID header, authority, count, and
    // all indexed sub-authorities are readable.
    let revision = unsafe { *sid.0.cast::<u8>() };
    // SAFETY: IsValidSid succeeded, so the SID identifier authority is readable.
    let authority_bytes = unsafe { (*GetSidIdentifierAuthority(sid)).Value };
    let authority = authority_bytes
        .into_iter()
        .fold(0u64, |value, byte| (value << 8) | u64::from(byte));
    // SAFETY: IsValidSid succeeded, so the SID sub-authority count is readable.
    let sub_authority_count = unsafe { *GetSidSubAuthorityCount(sid) };
    let mut value = format!("S-{revision}-{authority}");
    for index in 0..u32::from(sub_authority_count) {
        // SAFETY: IsValidSid succeeded and `index` is below the reported
        // sub-authority count, so this sub-authority is readable.
        let sub_authority = unsafe { *GetSidSubAuthority(sid, index) };
        value.push_str(&format!("-{sub_authority}"));
    }
    Ok(value)
}

struct OpenedKey {
    key: KeyHandle,
    created: bool,
}

fn open_or_create_key(
    provider: &ProviderHandle,
    credential_name: PCWSTR,
    window_handle: i64,
    title: &str,
    decrypt: bool,
) -> windows::core::Result<OpenedKey> {
    // Both wrapping and unwrapping eventually decrypt with this same handle
    // to require a Windows Hello gesture. A key opened with
    // NCRYPT_SILENT_FLAG retains that context and cannot display the prompt.
    let create_if_missing = !decrypt;
    match KeyHandle::open(provider, credential_name, false) {
        Ok(key) if key.has_expected_protection() => Ok(OpenedKey {
            key,
            created: false,
        }),
        Ok(key) if create_if_missing => {
            key.delete()?;
            let key = KeyHandle::create(provider, credential_name, window_handle, title)?;
            Ok(OpenedKey { key, created: true })
        }
        // An existing key with unexpected protection cannot unwrap the saved
        // binding, so report it through the same invalidation path as a key
        // that Windows removed.
        Ok(_) => Err(WindowsError::from_hresult(NTE_BAD_KEYSET)),
        Err(error) if create_if_missing && is_credential_not_found_error(&error) => {
            let key = KeyHandle::create(provider, credential_name, window_handle, title)?;
            Ok(OpenedKey { key, created: true })
        }
        Err(error) => Err(error),
    }
}

fn encrypt_and_verify_secret(
    key: &KeyHandle,
    input: &[u8],
) -> windows::core::Result<Zeroizing<Vec<u8>>> {
    let wrapped_secret = encrypt_secret(key, input)?;
    let unwrapped_secret = decrypt_secret(key, &wrapped_secret)?;
    if !bool::from(unwrapped_secret.as_slice().ct_eq(input)) {
        return Err(WindowsError::from_hresult(NTE_BAD_DATA));
    }
    Ok(wrapped_secret)
}

fn transform_failure(
    key: KeyHandle,
    created: bool,
    context: &str,
    error: WindowsError,
) -> ChallengeResult {
    let status = status_from_error(&error);
    let mut message = describe(context, &error);
    if created {
        if let Err(cleanup_error) = key.delete() {
            message.push_str("; ");
            message.push_str(&describe(
                "could not remove the incomplete credential",
                &cleanup_error,
            ));
        }
    }
    ChallengeResult::failure(status, message)
}

fn encrypt_secret(key: &KeyHandle, input: &[u8]) -> windows::core::Result<Zeroizing<Vec<u8>>> {
    let mut output_len = 0_u32;
    // SAFETY: The key handle and input slice are live for the call. A null
    // output buffer is the documented size-query form of NCryptEncrypt.
    // Microsoft Passport KSP supports PKCS#1 padding for this RSA operation.
    unsafe {
        NCryptEncrypt(
            key.raw(),
            Some(input),
            None,
            None,
            &mut output_len,
            NCRYPT_PAD_PKCS1_FLAG,
        )?;
    }

    let mut output = Zeroizing::new(vec![0_u8; output_len as usize]);
    // SAFETY: The output buffer was sized by the immediately preceding query;
    // all buffers and the key remain valid for this synchronous call.
    unsafe {
        NCryptEncrypt(
            key.raw(),
            Some(input),
            None,
            Some(&mut output),
            &mut output_len,
            NCRYPT_PAD_PKCS1_FLAG,
        )?;
    }
    output.truncate(output_len as usize);
    Ok(output)
}

fn decrypt_secret(key: &KeyHandle, input: &[u8]) -> windows::core::Result<Zeroizing<Vec<u8>>> {
    // Microsoft Passport KSP consults this property for the next private-key
    // operation. Setting it immediately before decryption forces a fresh
    // Windows Hello gesture instead of silently accepting a cached PIN.
    key.set_u32(PIN_CACHE_IS_GESTURE_REQUIRED_PROPERTY, 1)?;

    // RSA plaintext is always shorter than its ciphertext, so this avoids a
    // private-key size-query call that could otherwise display two prompts.
    let mut output = Zeroizing::new(vec![0_u8; input.len()]);
    let mut output_len = 0_u32;
    // SAFETY: The key and input are live, and output has at least the maximum
    // possible PKCS#1 plaintext capacity for this ciphertext.
    unsafe {
        NCryptDecrypt(
            key.raw(),
            Some(input),
            None,
            Some(&mut output),
            &mut output_len,
            NCRYPT_PAD_PKCS1_FLAG,
        )?;
    }
    output.truncate(output_len as usize);
    Ok(output)
}

fn failure(context: &str, error: WindowsError) -> ChallengeResult {
    ChallengeResult::failure(status_from_error(&error), describe(context, &error))
}

fn describe(context: &str, error: &WindowsError) -> String {
    format!("{context}: {}", error.message())
}

/// A JVM window handle as a native `HWND` value, or `None` when unset.
fn native_window_handle(value: i64) -> Option<isize> {
    isize::try_from(value).ok().filter(|handle| *handle != 0)
}

fn status_from_error(error: &WindowsError) -> ChallengeStatus {
    match error.code() {
        NTE_USER_CANCELLED | ERROR_CANCELLED_HRESULT => ChallengeStatus::UserCanceled,
        NTE_DEVICE_NOT_READY | NTE_NOT_SUPPORTED | NTE_PROV_DLL_NOT_FOUND => {
            ChallengeStatus::Unavailable
        }
        _ => ChallengeStatus::Unknown,
    }
}

fn status_from_open_error(error: &WindowsError) -> ChallengeStatus {
    if is_credential_not_found_error(error) {
        ChallengeStatus::CredentialNotFound
    } else {
        status_from_error(error)
    }
}

fn is_credential_not_found_error(error: &WindowsError) -> bool {
    matches!(error.code(), NTE_NO_KEY | NTE_BAD_KEYSET)
}

/// NUL-terminated UTF-16 string as native-endian bytes, the layout of a
/// string-typed NCrypt property.
fn utf16_bytes(value: &str) -> Vec<u8> {
    value
        .encode_utf16()
        .chain(std::iter::once(0))
        .flat_map(u16::to_ne_bytes)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::{
        credential_name, is_consent_verifier_available, status_from_consent, status_from_error,
        status_from_open_error, utf16_bytes, ERROR_CANCELLED_HRESULT,
    };
    use crate::biometrics::ChallengeStatus;
    use windows::core::Error as WindowsError;
    use windows::Security::Credentials::UI::{
        UserConsentVerificationResult, UserConsentVerifierAvailability,
    };
    use windows::Win32::Foundation::{NTE_BAD_KEYSET, NTE_BAD_KEY_STATE, NTE_DEVICE_NOT_READY};

    #[test]
    fn prompt_context_is_nul_terminated_utf16() {
        assert_eq!(utf16_bytes("A"), vec![65, 0, 0, 0]);
    }

    #[test]
    fn credential_name_is_scoped_to_the_windows_user() {
        assert_eq!(
            credential_name("S-1-5-21-100-200-300-1001"),
            "S-1-5-21-100-200-300-1001//Keyguard//biometric-unlock-v1",
        );
    }

    #[test]
    fn ncrypt_operation_errors_map_conservatively() {
        assert_eq!(
            status_from_error(&WindowsError::from_hresult(ERROR_CANCELLED_HRESULT)),
            ChallengeStatus::UserCanceled,
        );
        assert_eq!(
            status_from_error(&WindowsError::from_hresult(NTE_BAD_KEYSET)),
            ChallengeStatus::Unknown,
        );
        assert_eq!(
            status_from_error(&WindowsError::from_hresult(NTE_DEVICE_NOT_READY)),
            ChallengeStatus::Unavailable,
        );
        assert_eq!(
            status_from_error(&WindowsError::from_hresult(NTE_BAD_KEY_STATE)),
            ChallengeStatus::Unknown,
        );
    }

    #[test]
    fn missing_status_is_limited_to_open_key_failures() {
        assert_eq!(
            status_from_open_error(&WindowsError::from_hresult(NTE_BAD_KEYSET)),
            ChallengeStatus::CredentialNotFound,
        );
    }

    #[test]
    fn consent_results_map_to_stable_ffi_statuses() {
        assert_eq!(
            status_from_consent(UserConsentVerificationResult::Verified).status,
            ChallengeStatus::Success,
        );
        assert_eq!(
            status_from_consent(UserConsentVerificationResult::Canceled).status,
            ChallengeStatus::UserCanceled,
        );
        assert_eq!(
            status_from_consent(UserConsentVerificationResult::RetriesExhausted).status,
            ChallengeStatus::SecurityDeviceLocked,
        );
        assert_eq!(
            status_from_consent(UserConsentVerificationResult::NotConfiguredForUser).status,
            ChallengeStatus::Unavailable,
        );
        assert_eq!(
            status_from_consent(UserConsentVerificationResult(42)).status,
            ChallengeStatus::Unknown,
        );
    }

    #[test]
    fn consent_verifier_availability_requires_a_usable_device() {
        assert!(is_consent_verifier_available(
            UserConsentVerifierAvailability::Available,
        ));
        assert!(is_consent_verifier_available(
            UserConsentVerifierAvailability::DeviceBusy,
        ));
        assert!(!is_consent_verifier_available(
            UserConsentVerifierAvailability::DeviceNotPresent,
        ));
        assert!(!is_consent_verifier_available(
            UserConsentVerifierAvailability::NotConfiguredForUser,
        ));
        assert!(!is_consent_verifier_available(
            UserConsentVerifierAvailability::DisabledByPolicy,
        ));
        assert!(!is_consent_verifier_available(
            UserConsentVerifierAvailability(42),
        ));
    }
}
