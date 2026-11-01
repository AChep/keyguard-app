//! JNI ownership and exact-size destination writes for AppKit file promises.

use crate::drag::{ReadStatus, BUFFER_SIZE, MAX_FILES};
use crate::drag_jni::{
    call_void, clear_java_exception, contained, java_names, read_at, JavaCallbacks,
};
use jni::{
    objects::{JByteArray, JObject, JObjectArray, JString},
    sys::{jint, jlong},
    Env, EnvUnowned,
};
use keyguard_ffi::with_java_env;
use std::{
    ffi::{c_char, c_void, CStr, CString, OsStr},
    fs::{self, File, OpenOptions},
    io::{self, Write},
    os::unix::{ffi::OsStrExt, fs::MetadataExt, fs::OpenOptionsExt},
    path::{Path, PathBuf},
};
use zeroize::Zeroizing;

/// The bridge ABI that `MacFilePromise.kt` checks before its first start.
const ABI_VERSION: jint = 5;
const MAX_NAME_BYTES: usize = 4096;

struct PromiseContext {
    java: JavaCallbacks,
    count: usize,
}

unsafe extern "C" {
    fn kg_file_promise_start(
        window_handle: u64,
        names: *const *const c_char,
        count: usize,
        context: *mut c_void,
        write: extern "C" fn(*mut c_void, usize, *const c_char) -> bool,
        ended: extern "C" fn(*mut c_void, bool),
        retired: extern "C" fn(*mut c_void),
    ) -> i64;
    fn kg_file_promise_revoke(handle: i64);
}

#[no_mangle]
pub extern "system" fn Java_com_artemchep_desktop_MacFilePromiseJni_abiVersion(
    _environment: EnvUnowned<'_>,
    _object: JObject<'_>,
) -> jint {
    ABI_VERSION
}

/// Returns a positive offer handle, or zero when native did not take ownership.
#[no_mangle]
pub extern "system" fn Java_com_artemchep_desktop_MacFilePromiseJni_start(
    mut environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    window_handle: jlong,
    names: JObjectArray<'_, JString<'_>>,
    callbacks: JObject<'_>,
) -> jlong {
    contained(|| {
        with_java_env(&mut environment, |environment| {
            let Ok(window_handle) = u64::try_from(window_handle) else {
                return 0;
            };
            if window_handle == 0 {
                return 0;
            }
            // `kg_promise_names` in the AppKit shim is the authority on names.
            let Some(names) = java_names(environment, &names, MAX_FILES, MAX_NAME_BYTES) else {
                return 0;
            };
            let Ok(names) = names
                .iter()
                .map(|name| CString::new(name.as_bytes()))
                .collect::<Result<Vec<_>, _>>()
            else {
                return 0;
            };
            let pointers: Vec<*const c_char> = names.iter().map(|name| name.as_ptr()).collect();
            let Some(java) = JavaCallbacks::new(environment, &callbacks) else {
                return 0;
            };
            let context = Box::into_raw(Box::new(PromiseContext {
                java,
                count: names.len(),
            }));
            // SAFETY: The ObjC shim copies every name synchronously. A successful
            // start owns context until retired, and pins it across every active
            // write. Failure leaves ownership here and performs no later callback.
            let handle = unsafe {
                kg_file_promise_start(
                    window_handle,
                    pointers.as_ptr(),
                    pointers.len(),
                    context.cast(),
                    write_promise,
                    promise_ended,
                    promise_retired,
                )
            };
            if handle == 0 {
                // SAFETY: A failed start never takes ownership or retains context.
                drop(unsafe { Box::from_raw(context) });
                return 0;
            }
            handle
        })
    })
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_artemchep_desktop_MacFilePromiseJni_revoke(
    _environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    handle: jlong,
) {
    let _ = contained(|| {
        // SAFETY: The shim takes no pointers, accepts any value from any
        // thread, and ignores unknown, stale or already revoked handles.
        unsafe { kg_file_promise_revoke(handle) }
    });
}

extern "C" fn write_promise(context: *mut c_void, index: usize, path: *const c_char) -> bool {
    contained(|| {
        if context.is_null() || path.is_null() {
            return false;
        }
        // SAFETY: The shim pins context for this entire synchronous write, and
        // supplies the URL's immutable, NUL-terminated filesystem representation.
        let context = unsafe { &*context.cast::<PromiseContext>() };
        // SAFETY: The path's lifetime and NUL termination are guaranteed above.
        let path = Path::new(OsStr::from_bytes(
            unsafe { CStr::from_ptr(path) }.to_bytes(),
        ));
        let written = write_destination(context, index, path);
        if written {
            // The destination is synced and closed. A failed report must not
            // fail the write; the store's deadline then retires the offer.
            let _ = contained(|| context.java.written(index));
        }
        written
    })
    .unwrap_or(false)
}

fn write_destination(context: &PromiseContext, index: usize, path: &Path) -> bool {
    if index >= context.count {
        return false;
    }
    let Ok(mut destination) = PromiseDestination::create(path) else {
        return false;
    };
    let java = &context.java;
    let written = java.with_env(|environment| {
        let Some(reader) = java.open_reader(
            environment,
            index,
            &jni::jni_sig!("(I)Lcom/artemchep/desktop/MacFilePromiseReader;"),
        ) else {
            return false;
        };
        // The size arrives with the reader: a promised file may be prepared
        // only after the drag started.
        let size = environment
            .call_method(&reader, jni::jni_str!("getSize"), jni::jni_sig!("()J"), &[])
            .and_then(|value| value.j());
        clear_java_exception(environment);
        let array = environment.new_byte_array(BUFFER_SIZE);
        clear_java_exception(environment);
        let copied = match (&array, size.map(u64::try_from)) {
            (Ok(array), Ok(Ok(size))) => {
                copy_exact(environment, &reader, array, size, &mut destination.file)
            }
            _ => false,
        };
        // Every opened reader is closed, including after a failed read.
        let closed = call_void(
            environment,
            &reader,
            jni::jni_str!("close"),
            &jni::jni_sig!("()V"),
            &[],
        );
        copied && closed
    });
    if written != Some(true) || destination.file.sync_all().is_err() {
        return false;
    }
    destination.committed = true;
    true
}

/// Writes exactly `size` bytes of `reader` to `file`, or returns false.
/// Fewer bytes fail the write.
///
/// Reads stop at the declared size and nothing past it is probed for:
/// `PreparedContentStore`'s reader already caps its content at that same
/// size, the `size` the reader reports.
fn copy_exact(
    environment: &mut Env<'_>,
    reader: &JObject<'_>,
    array: &JByteArray<'_>,
    size: u64,
    file: &mut File,
) -> bool {
    // `read_at` wipes the JVM copy after every read.
    let mut buffer = Zeroizing::new(vec![0_u8; BUFFER_SIZE]);
    let mut written = 0_u64;
    while written < size {
        let wanted =
            usize::try_from(size - written).map_or(BUFFER_SIZE, |left| left.min(BUFFER_SIZE));
        let chunk = &mut buffer[..wanted];
        let (_, status) = read_at(
            environment,
            reader,
            array,
            BUFFER_SIZE,
            written,
            size,
            chunk,
        );
        let saved = status == ReadStatus::Filled && file.write_all(chunk).is_ok();
        chunk.fill(0);
        if !saved {
            return false;
        }
        written += wanted as u64;
    }
    true
}

extern "C" fn promise_ended(context: *mut c_void, accepted: bool) {
    let _ = contained(|| {
        // SAFETY: The shim serializes end and retirement on AppKit's main
        // thread and keeps the context alive through this callback.
        let context = unsafe { &*context.cast::<PromiseContext>() };
        context.java.ended(accepted);
    });
}

extern "C" fn promise_retired(context: *mut c_void) {
    let _ = contained(|| {
        // SAFETY: The shim transfers this allocation back exactly once, after
        // closing the offer and after the final in-flight writer has returned.
        let context = unsafe { Box::from_raw(context.cast::<PromiseContext>()) };
        context.java.retire();
    });
}

struct PromiseDestination {
    file: File,
    path: PathBuf,
    identity: (u64, u64),
    committed: bool,
}

impl PromiseDestination {
    fn create(path: &Path) -> io::Result<Self> {
        if !path.is_absolute() {
            return Err(io::ErrorKind::InvalidInput.into());
        }
        let file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC)
            .open(path)?;
        let metadata = file.metadata()?;
        Ok(Self {
            file,
            path: path.to_owned(),
            identity: (metadata.dev(), metadata.ino()),
            committed: false,
        })
    }
}

impl Drop for PromiseDestination {
    fn drop(&mut self) {
        if self.committed {
            return;
        }
        // Never remove an existing destination or a replacement symlink/file.
        // The open descriptor is kept until after this check and cleanup.
        if fs::symlink_metadata(&self.path).is_ok_and(|metadata| {
            metadata.is_file() && (metadata.dev(), metadata.ino()) == self.identity
        }) {
            let _ = fs::remove_file(&self.path);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::{symlink, PermissionsExt};

    #[test]
    fn destination_is_private_and_failed_write_removes_only_owned_partial() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("attachment");
        let mut output = PromiseDestination::create(&path).unwrap();
        output.file.write_all(b"partial").unwrap();
        assert_eq!(
            fs::metadata(&path).unwrap().permissions().mode() & 0o777,
            0o600
        );
        drop(output);
        assert!(!path.exists());
    }

    #[test]
    fn destination_rejects_existing_files_and_symlinks() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("existing");
        fs::write(&path, b"keep").unwrap();
        assert!(PromiseDestination::create(&path).is_err());
        let link = directory.path().join("link");
        symlink(&path, &link).unwrap();
        assert!(PromiseDestination::create(&link).is_err());
        assert_eq!(fs::read(&path).unwrap(), b"keep");
    }

    #[test]
    fn cleanup_preserves_replaced_destination_and_commit_keeps_exact_bytes() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("attachment");
        let output = PromiseDestination::create(&path).unwrap();
        fs::remove_file(&path).unwrap();
        fs::write(&path, b"replacement").unwrap();
        drop(output);
        assert_eq!(fs::read(&path).unwrap(), b"replacement");
        let complete = directory.path().join("complete");
        let mut output = PromiseDestination::create(&complete).unwrap();
        output.file.write_all(b"complete").unwrap();
        output.committed = true;
        drop(output);
        assert_eq!(fs::read(complete).unwrap(), b"complete");
    }
}
