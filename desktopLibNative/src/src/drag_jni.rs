//! JNI glue shared by the drag-out bridges: AppKit file promises on macOS and
//! OLE virtual files on Windows.
//!
//! Pending Java exceptions are cleared after every call and never logged:
//! they may carry names or content.

use crate::drag::{chunk_length, reader_count, ReadStatus, BUFFER_SIZE};
#[cfg(windows)]
use jni::{objects::JLongArray, sys::jlong};
use jni::{
    objects::{JByteArray, JObject, JObjectArray, JString, JValue},
    refs::Global,
    signature::MethodSignature,
    strings::JNIStr,
    sys::{jboolean, jbyte, jint},
    Env, JavaVM,
};
use keyguard_ffi::{java_string_within, jbytes_mut, PanicHook};
use zeroize::Zeroizing;

/// Unit tests keep Rust's default hook so a caught assertion still reports
/// its payload and source location in CI logs.
const PANIC_HOOK: PanicHook = if cfg!(test) {
    PanicHook::Keep
} else {
    PanicHook::Redact
};

/// Wipes a JVM buffer after a read without allocating.
static ZEROS: [jbyte; BUFFER_SIZE] = [0; BUFFER_SIZE];

/// Runs `body`, or returns `None` when it panics.
pub(crate) fn contained<T>(body: impl FnOnce() -> T) -> Option<T> {
    keyguard_ffi::contained(PANIC_HOOK, body).ok()
}

/// Copies the names of an offer's files, or returns `None` when the array is
/// null, holds outside `1..=max_files` entries, or a name is null, malformed
/// or over `max_name_bytes` UTF-8 bytes.
pub(crate) fn java_names(
    environment: &mut Env<'_>,
    names: &JObjectArray<'_, JString<'_>>,
    max_files: usize,
    max_name_bytes: usize,
) -> Option<Vec<Zeroizing<String>>> {
    // Also rejects a null array, without calling into the JVM.
    let count = names.len(environment).ok()?;
    if !(1..=max_files).contains(&count) {
        return None;
    }
    let mut copied = Vec::with_capacity(count);
    for index in 0..count {
        let name = names.get_element(environment, index).ok()?;
        let copy = java_string_within(environment, &name, max_name_bytes);
        // JNI guarantees only 16 local references per native call, so each
        // element is released before the next is read.
        environment.delete_local_ref(name);
        copied.push(copy.ok()??);
    }
    Some(copied)
}

/// Copies the names and sizes of an offer's files, or returns `None` when
/// either array is null, the two differ in length, or [java_names] rejects
/// the names.
#[cfg(windows)]
pub(crate) fn java_files(
    environment: &mut Env<'_>,
    names: &JObjectArray<'_, JString<'_>>,
    sizes: &JLongArray<'_>,
    max_files: usize,
    max_name_bytes: usize,
) -> Option<(Vec<Zeroizing<String>>, Vec<jlong>)> {
    let count = sizes.len(environment).ok()?;
    if names.len(environment).ok()? != count {
        return None;
    }
    let copied = java_names(environment, names, max_files, max_name_bytes)?;
    let mut lengths = vec![0; count];
    sizes.get_region(environment, 0, &mut lengths).ok()?;
    Some((copied, lengths))
}

/// The `NativeDragCallbacks` of one offer, held until [JavaCallbacks::retire].
pub(crate) struct JavaCallbacks {
    vm: JavaVM,
    callbacks: Global<JObject<'static>>,
}

impl JavaCallbacks {
    /// Retains `callbacks`, or returns `None` when they are null or cannot
    /// be retained.
    pub(crate) fn new(environment: &mut Env<'_>, callbacks: &JObject<'_>) -> Option<Self> {
        if callbacks.is_null() {
            return None;
        }
        let vm = environment.get_java_vm().ok()?;
        let callbacks = environment.new_global_ref(callbacks).ok()?;
        Some(Self { vm, callbacks })
    }

    /// Attaches the calling thread until it exits.
    #[cfg(windows)]
    pub(crate) fn attach(&self) -> bool {
        self.vm
            .attach_current_thread(|_| Ok::<_, jni::errors::Error>(()))
            .is_ok()
    }

    /// Runs `body` attached to the JVM. A thread that is not attached yet is
    /// detached again afterwards; one that is stays attached.
    pub(crate) fn with_env<T>(&self, body: impl FnOnce(&mut Env<'_>) -> T) -> Option<T> {
        self.vm
            .attach_current_thread_for_scope(|environment| {
                Ok::<_, jni::errors::Error>(body(environment))
            })
            .ok()
    }

    /// Reports the drag outcome to `ended(boolean)`.
    pub(crate) fn ended(&self, accepted: bool) {
        let _ = self.with_env(|environment| {
            call_void(
                environment,
                &self.callbacks,
                jni::jni_str!("ended"),
                &jni::jni_sig!("(Z)V"),
                &[JValue::Bool(accepted as jboolean)],
            )
        });
    }

    /// Reports to `written(int)` that file `index` is complete at its
    /// destination. Failures are ignored: the file is already written.
    #[cfg(target_os = "macos")]
    pub(crate) fn written(&self, index: usize) {
        let Ok(index) = jint::try_from(index) else {
            return;
        };
        let _ = self.with_env(|environment| {
            call_void(
                environment,
                &self.callbacks,
                jni::jni_str!("written"),
                &jni::jni_sig!("(I)V"),
                &[JValue::Int(index)],
            )
        });
    }

    /// Reports retirement to `retired()`, then releases the callbacks while
    /// attached, including when the call fails.
    pub(crate) fn retire(self) {
        let Self { vm, callbacks } = self;
        let _ = vm.attach_current_thread_for_scope(move |environment| {
            call_void(
                environment,
                &callbacks,
                jni::jni_str!("retired"),
                &jni::jni_sig!("()V"),
                &[],
            );
            drop(callbacks);
            Ok::<_, jni::errors::Error>(())
        });
    }

    /// Calls `openReader(int)`, whose return type `signature` names. Returns
    /// `None` when it throws or returns null. The caller closes the reader.
    pub(crate) fn open_reader<'local>(
        &self,
        environment: &mut Env<'local>,
        index: usize,
        signature: &MethodSignature<'_, '_>,
    ) -> Option<JObject<'local>> {
        let index = jint::try_from(index).ok()?;
        let reader = environment
            .call_method(
                &self.callbacks,
                jni::jni_str!("openReader"),
                signature,
                &[JValue::Int(index)],
            )
            .and_then(|value| value.l());
        // Foreign AppKit, worker and OLE threads never propagate a pending
        // Java exception. A failed open is cleared before local references
        // are dropped or this thread detaches.
        clear_java_exception(environment);
        reader.ok().filter(|reader| !reader.is_null())
    }
}

/// Calls a `void` method, then clears any exception it threw. Returns
/// whether it returned normally.
pub(crate) fn call_void(
    environment: &mut Env<'_>,
    object: &JObject<'_>,
    name: &JNIStr,
    signature: &MethodSignature<'_, '_>,
    arguments: &[JValue],
) -> bool {
    let result = environment.call_method(object, name, signature, arguments);
    clear_java_exception(environment);
    result.is_ok()
}

/// Fills `output` from `position` of a `size`-byte payload through the
/// reader's `readAt(long, byte[], int)`, at most `capacity` bytes of `buffer`
/// at a time. `capacity` is at most the buffer's length and [BUFFER_SIZE].
/// Returns the bytes copied, which is fewer than requested at the declared
/// end or on a fault.
pub(crate) fn read_at(
    environment: &mut Env<'_>,
    reader: &JObject<'_>,
    buffer: &JByteArray<'_>,
    capacity: usize,
    position: u64,
    size: u64,
    output: &mut [u8],
) -> (usize, ReadStatus) {
    let mut copied = 0;
    let mut used = 0;
    let status = loop {
        if copied == output.len() {
            break ReadStatus::Filled;
        }
        let offset = position.saturating_add(copied as u64);
        let wanted = chunk_length(offset, size, output.len() - copied, capacity);
        if wanted == 0 {
            break ReadStatus::Short;
        }
        let (Ok(java_offset), Ok(java_length)) = (i64::try_from(offset), i32::try_from(wanted))
        else {
            break ReadStatus::Fault;
        };
        used = used.max(wanted);
        let count = environment
            .call_method(
                reader,
                jni::jni_str!("readAt"),
                jni::jni_sig!("(J[BI)I"),
                &[
                    JValue::Long(java_offset),
                    JValue::Object(buffer.as_ref()),
                    JValue::Int(java_length),
                ],
            )
            .and_then(|value| value.i());
        clear_java_exception(environment);
        let Some(count) = count.ok().and_then(|count| reader_count(count, wanted)) else {
            break ReadStatus::Fault;
        };
        let target = &mut output[copied..copied + count];
        if buffer
            .get_region(environment, 0, jbytes_mut(target))
            .is_err()
        {
            clear_java_exception(environment);
            break ReadStatus::Fault;
        }
        copied += count;
    };
    // Wipe the JVM copy of every byte this read could have received.
    wipe_java_array(environment, buffer, used);
    (copied, status)
}

/// Zeroes the first `used` bytes of `array`, at most [BUFFER_SIZE].
fn wipe_java_array(environment: &mut Env<'_>, array: &JByteArray<'_>, used: usize) {
    if used > 0 {
        let _ = array.set_region(environment, 0, &ZEROS[..used]);
        clear_java_exception(environment);
    }
}

pub(crate) fn clear_java_exception(environment: &Env<'_>) {
    if environment.exception_check() {
        environment.exception_clear();
    }
}
