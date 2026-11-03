//! JNI entry points and the JVM callbacks of a virtual-file offer.
//!
//! Every call into the JVM happens on the offer's OLE thread, which stays
//! attached for its whole life. Pending Java exceptions are cleared after
//! every call and never logged: they may carry names or content.

use super::{file_entries, ole, Control, FileEntry, Offers, NAME_MAX_UTF8};
use crate::drag::{ReadStatus, MAX_FILES};
use crate::drag_jni::{
    call_void, clear_java_exception, contained, java_files, read_at, JavaCallbacks,
};
use jni::{
    objects::{JByteArray, JLongArray, JObject, JObjectArray, JString},
    refs::Global,
    sys::{jint, jlong},
    EnvUnowned,
};
use keyguard_ffi::with_java_env;
use std::cell::RefCell;
use std::sync::Arc;

/// The bridge ABI that `WindowsVirtualFile.kt` checks before its first start.
const ABI_VERSION: jint = 2;

/// Started offers the JVM can still revoke.
static OFFERS: Offers = Offers::new();

#[no_mangle]
pub extern "system" fn Java_com_artemchep_desktop_WindowsVirtualFileJni_abiVersion(
    _environment: EnvUnowned<'_>,
    _object: JObject<'_>,
) -> jint {
    ABI_VERSION
}

/// Starts one drag. Returns the offer's handle, or zero when native did not
/// take ownership and no callback will run.
#[no_mangle]
pub extern "system" fn Java_com_artemchep_desktop_WindowsVirtualFileJni_start(
    mut environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    names: JObjectArray<'_, JString<'_>>,
    sizes: JLongArray<'_>,
    callbacks: JObject<'_>,
) -> jlong {
    contained(|| {
        with_java_env(&mut environment, |environment| {
            let Some(files) = java_files(environment, &names, &sizes, MAX_FILES, NAME_MAX_UTF8)
                .and_then(|(names, sizes)| file_entries(&names, &sizes))
            else {
                return 0;
            };
            let Some(callbacks) = JavaCallbacks::new(environment, &callbacks) else {
                return 0;
            };
            let Some(registration) = OFFERS.register() else {
                return 0;
            };
            let handle = registration.handle;
            let seed = OfferSeed {
                java: Java {
                    callbacks: RefCell::new(Some(callbacks)),
                },
                files,
                control: Arc::clone(&registration.control),
            };
            // A failed spawn drops the global reference here, on an attached thread.
            if ole::start(seed, registration) {
                handle
            } else {
                0
            }
        })
    })
    .unwrap_or(0)
}

/// Stops an offer from serving new streams. Unknown and retired handles are
/// ignored.
#[no_mangle]
pub extern "system" fn Java_com_artemchep_desktop_WindowsVirtualFileJni_revoke(
    _environment: EnvUnowned<'_>,
    _object: JObject<'_>,
    handle: jlong,
) {
    let _ = contained(|| OFFERS.revoke(handle, ole::wake));
}

/// What the JVM caller hands to the OLE thread.
pub(super) struct OfferSeed {
    pub(super) java: Java,
    pub(super) files: Vec<FileEntry>,
    pub(super) control: Arc<Control>,
}

/// The `WindowsVirtualFileCallbacks` of one offer, held until retirement.
/// Every reader is closed before the callbacks retire.
pub(super) struct Java {
    callbacks: RefCell<Option<JavaCallbacks>>,
}

/// One `WindowsVirtualFileReader` and the bounded array it reads into.
pub(super) struct JavaReader {
    reader: Global<JObject<'static>>,
    buffer: Global<JByteArray<'static>>,
    capacity: usize,
}

impl Java {
    /// Runs `body` with the callbacks, unless they are retired or in use.
    fn with_callbacks<T>(&self, body: impl FnOnce(&JavaCallbacks) -> Option<T>) -> Option<T> {
        let callbacks = self.callbacks.try_borrow().ok()?;
        body(callbacks.as_ref()?)
    }

    /// Attaches the calling thread until it exits.
    pub(super) fn attach(&self) -> bool {
        self.with_callbacks(|java| Some(java.attach()))
            .unwrap_or(false)
    }

    /// Opens an independent reader of file `index` with a `capacity`-byte
    /// array, or `None` when the callbacks throw, return null or are already
    /// retired.
    pub(super) fn open_reader(&self, index: usize, capacity: usize) -> Option<JavaReader> {
        self.with_callbacks(|java| {
            java.with_env(|environment| {
                let reader = java.open_reader(
                    environment,
                    index,
                    &jni::jni_sig!("(I)Lcom/artemchep/desktop/WindowsVirtualFileReader;"),
                )?;
                let buffer = environment
                    .new_byte_array(capacity)
                    .and_then(|buffer| environment.new_global_ref(&buffer));
                clear_java_exception(environment);
                let global = environment.new_global_ref(&reader);
                clear_java_exception(environment);
                match (global, buffer) {
                    (Ok(reader), Ok(buffer)) if !reader.is_null() && !buffer.is_null() => {
                        Some(JavaReader {
                            reader,
                            buffer,
                            capacity,
                        })
                    }
                    _ => {
                        // Every opened reader is closed, including one native cannot keep.
                        call_void(
                            environment,
                            &reader,
                            jni::jni_str!("close"),
                            &jni::jni_sig!("()V"),
                            &[],
                        );
                        None
                    }
                }
            })
            .flatten()
        })
    }

    /// Fills `output` from `position` of a `size`-byte payload. Returns the
    /// bytes copied, which is fewer than requested at the declared end or on
    /// a fault.
    pub(super) fn read(
        &self,
        reader: &JavaReader,
        position: u64,
        size: u64,
        output: &mut [u8],
    ) -> (usize, ReadStatus) {
        self.with_callbacks(|java| {
            java.with_env(|environment| {
                read_at(
                    environment,
                    &reader.reader,
                    &reader.buffer,
                    reader.capacity,
                    position,
                    size,
                    output,
                )
            })
        })
        .unwrap_or((0, ReadStatus::Fault))
    }

    /// Closes a reader and deletes its global references while attached.
    pub(super) fn close(&self, reader: JavaReader) {
        let _ = self.with_callbacks(|java| {
            java.with_env(move |environment| {
                call_void(
                    environment,
                    &reader.reader,
                    jni::jni_str!("close"),
                    &jni::jni_sig!("()V"),
                    &[],
                );
                drop(reader);
            })
        });
    }

    /// Reports the drag-loop outcome.
    pub(super) fn ended(&self, accepted: bool) {
        let _ = self.with_callbacks(|java| {
            java.ended(accepted);
            Some(())
        });
    }

    /// Reports retirement once and releases the callbacks while attached.
    pub(super) fn retired(&self) {
        let callbacks = self
            .callbacks
            .try_borrow_mut()
            .ok()
            .and_then(|mut callbacks| callbacks.take());
        if let Some(callbacks) = callbacks {
            callbacks.retire();
        }
    }
}
