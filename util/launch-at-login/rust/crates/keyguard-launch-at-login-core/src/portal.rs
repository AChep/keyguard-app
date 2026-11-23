use crate::{Error, Failure, Operation, Status, Target};
use futures::StreamExt;
use std::collections::HashMap;
use std::path::Path;
#[cfg(target_os = "linux")]
use std::sync::atomic::AtomicBool;
use std::sync::atomic::{AtomicU64, Ordering};
use std::{
    fs::{self, OpenOptions},
    future::Future,
    time::Duration,
};
use zbus::{OwnedObjectPath, OwnedVariant, Variant};

#[cfg(target_os = "linux")]
const DESTINATION: &str = "org.freedesktop.portal.Desktop";

#[derive(Clone, Copy)]
struct Deadlines {
    method: Duration,
    response: Duration,
    close: Duration,
}

#[cfg(target_os = "linux")]
const DEADLINES: Deadlines = Deadlines {
    method: Duration::from_secs(10),
    response: Duration::from_secs(120),
    close: Duration::from_secs(2),
};

#[cfg(target_os = "linux")]
pub(crate) fn execute(operation: Operation, target: &Target<'_>) -> Result<Status, Failure> {
    static ASKED: AtomicBool = AtomicBool::new(false);
    if std::env::var_os("FLATPAK_ID").is_none() {
        return Err(Failure::new(
            Error::Unavailable,
            "background portal target requires Flatpak",
        ));
    }
    if operation == Operation::Settings {
        return Err(Failure::new(
            Error::Unavailable,
            "the background portal has no settings action",
        ));
    }
    let marker = crate::xdg::config_home()?
        .join("keyguard")
        .join("launch-at-login");
    let recheck = !ASKED.swap(true, Ordering::Relaxed);
    with_marker(&marker, operation, recheck, |autostart| {
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()?
            .block_on(request(autostart, target))
    })
}

/// The portal has no status query, so a marker in the sandbox's own configuration
/// records a granted request. `recheck` is true only for the first operation in a
/// process. That operation repeats the request only when it is a status read and
/// the marker exists. Every other status read makes no D-Bus call.
fn with_marker(
    marker: &Path,
    operation: Operation,
    recheck: bool,
    request: impl FnOnce(bool) -> Result<Status, Failure>,
) -> Result<Status, Failure> {
    match operation {
        Operation::Enable => match request(true) {
            Ok(status) => {
                write_marker(marker)?;
                Ok(status)
            }
            Err(failure) => {
                if failure.code == Error::Denied {
                    remove_marker(marker)?;
                }
                Err(failure)
            }
        },
        Operation::Disable => {
            let status = request(false)?;
            remove_marker(marker)?;
            Ok(status)
        }
        // `execute` rejects Settings.
        Operation::Status | Operation::Settings => {
            match fs::symlink_metadata(marker) {
                Ok(_) => {}
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                    return Ok(Status::Disabled);
                }
                Err(error) => return Err(error.into()),
            }
            if !recheck {
                return Ok(Status::Enabled);
            }
            match request(true) {
                Err(failure) if failure.code == Error::Denied => {
                    remove_marker(marker)?;
                    Ok(Status::Disabled)
                }
                // An unreachable portal keeps the recorded answer.
                _ => Ok(Status::Enabled),
            }
        }
    }
}

/// Creates the marker without following a link planted at its path: an exclusive
/// create never opens an existing entry, and a non-file entry is unlinked first.
fn write_marker(marker: &Path) -> Result<(), Failure> {
    fs::create_dir_all(marker.parent().unwrap_or(marker))?;
    let create = || OpenOptions::new().write(true).create_new(true).open(marker);
    match create() {
        Ok(_) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
            if fs::symlink_metadata(marker)?.is_file() {
                return Ok(());
            }
            remove_marker(marker)?;
            create()?;
            Ok(())
        }
        Err(error) => Err(error.into()),
    }
}

fn remove_marker(marker: &Path) -> Result<(), Failure> {
    match fs::remove_file(marker) {
        Err(error) if error.kind() != std::io::ErrorKind::NotFound => Err(error.into()),
        _ => Ok(()),
    }
}

#[cfg(target_os = "linux")]
async fn request(autostart: bool, target: &Target<'_>) -> Result<Status, Failure> {
    let connection = call(
        DEADLINES.method,
        zbus::connection::Builder::session()
            .method_timeout(DEADLINES.method)
            .build(),
    )
    .await?;
    request_on(&connection, DESTINATION, autostart, target, DEADLINES).await
}

/// Sends `RequestBackground` and waits for its actual Response.
async fn request_on(
    connection: &zbus::Connection,
    destination: &str,
    autostart: bool,
    target: &Target<'_>,
    deadlines: Deadlines,
) -> Result<Status, Failure> {
    static SERIAL: AtomicU64 = AtomicU64::new(0);
    let portal = call(
        deadlines.method,
        zbus::Proxy::new(
            connection,
            destination,
            "/org/freedesktop/portal/desktop",
            "org.freedesktop.portal.Background",
        ),
    )
    .await?;
    let _: u32 = call(deadlines.method, portal.get_property("version")).await?;
    let token = format!(
        "keyguard_{}_{}",
        std::process::id(),
        SERIAL.fetch_add(1, Ordering::Relaxed)
    );
    let sender = connection
        .unique_name()
        .ok_or_else(|| Failure::new(Error::System, "portal connection has no bus name"))?
        .as_str()
        .trim_start_matches(':')
        .replace('.', "_");
    let request_path = format!("/org/freedesktop/portal/desktop/request/{sender}/{token}");
    let request = request_proxy(connection, destination, &request_path, deadlines).await?;
    // Subscribe before making the request: a permission cached by the portal can
    // respond before RequestBackground returns.
    let mut responses = call(deadlines.method, request.receive_signal("Response")).await?;
    let mut options: HashMap<&str, Variant<'_>> = HashMap::new();
    options.insert("handle_token", Variant::from(token.as_str()));
    options.insert("reason", Variant::from(target.name));
    options.insert("autostart", Variant::from(autostart));
    options.insert("commandline", Variant::from(target.arguments.clone()));
    options.insert("dbus-activatable", Variant::from(false));
    let requested: Result<OwnedObjectPath, Failure> = call(
        deadlines.method,
        portal.call("RequestBackground", &("", options)),
    )
    .await;
    let handle = match requested {
        Ok(handle) => handle,
        Err(error) => {
            // The backend may have created the predicted request even if its reply was lost.
            // Cleanup is time-bounded.
            close_request(&request, deadlines.close).await;
            return Err(error);
        }
    };
    if handle.as_str() != request_path {
        // Only modern handle-token implementations are safe from response races.
        let other = request_proxy(connection, destination, handle.as_str(), deadlines).await?;
        close_request(&other, deadlines.close).await;
        return Err(Failure::new(
            Error::Unavailable,
            "portal did not honor the request handle token",
        ));
    }
    let response = bounded(deadlines.response, responses.next()).await;
    let message = match response {
        Ok(Some(message)) => message,
        _ => {
            close_request(&request, deadlines.close).await;
            return Err(Failure::new(
                Error::Cancelled,
                "background permission request timed out or closed",
            ));
        }
    };
    let (code, values): (u32, HashMap<String, OwnedVariant>) =
        message.body().deserialize().map_err(system)?;
    let result = |key: &str| values.get(key).and_then(|value| bool::try_from(value).ok());
    let granted = match code {
        0 => {
            let saved = result("autostart").ok_or_else(|| {
                Failure::new(Error::System, "portal omitted the autostart result")
            })?;
            // xdg-desktop-portal allows the request but answers `autostart=false`
            // when it fails to save the autostart file. That is no denial.
            if autostart && !saved {
                return Err(Failure::new(
                    Error::System,
                    "the desktop portal could not save the autostart entry",
                ));
            }
            saved
        }
        // xdg-desktop-portal answers "Don't allow" and a revoked permission with
        // code 1 and `background=false`, after it removed the autostart entry.
        1 if result("background") == Some(false) => false,
        1 => {
            return Err(Failure::new(
                Error::Cancelled,
                "background permission request cancelled",
            ));
        }
        _ => {
            return Err(Failure::new(
                Error::Denied,
                "background permission request denied",
            ));
        }
    };
    if autostart && !granted {
        Err(Failure::new(
            Error::Denied,
            "background autostart permission was denied",
        ))
    } else if granted {
        Ok(Status::Enabled)
    } else {
        Ok(Status::Disabled)
    }
}

async fn bounded<T>(duration: Duration, operation: impl Future<Output = T>) -> Result<T, Failure> {
    tokio::time::timeout(duration, operation)
        .await
        .map_err(|_| Failure::new(Error::Cancelled, "desktop portal operation timed out"))
}

/// A bounded D-Bus call; both a timeout and a D-Bus error become a [`Failure`].
async fn call<T>(
    duration: Duration,
    operation: impl Future<Output = Result<T, zbus::Error>>,
) -> Result<T, Failure> {
    bounded(duration, operation).await?.map_err(system)
}

async fn request_proxy<'a>(
    connection: &zbus::Connection,
    destination: &'a str,
    path: &'a str,
    deadlines: Deadlines,
) -> Result<zbus::Proxy<'a>, Failure> {
    call(
        deadlines.method,
        zbus::Proxy::new(
            connection,
            destination,
            path,
            "org.freedesktop.portal.Request",
        ),
    )
    .await
}

async fn close_request(request: &zbus::Proxy<'_>, timeout: Duration) {
    let _: Result<Result<(), zbus::Error>, Failure> =
        bounded(timeout, request.call("Close", &())).await;
}

#[cfg(test)]
#[path = "portal_tests.rs"]
mod tests;

fn system(error: zbus::Error) -> Failure {
    let code = match &error {
        zbus::Error::InterfaceNotFound | zbus::Error::Unsupported => Error::Unavailable,
        zbus::Error::FDO(error) => match error.as_ref() {
            zbus::fdo::Error::ServiceUnknown(_)
            | zbus::fdo::Error::UnknownInterface(_)
            | zbus::fdo::Error::UnknownMethod(_)
            | zbus::fdo::Error::NotSupported(_) => Error::Unavailable,
            zbus::fdo::Error::AccessDenied(_) | zbus::fdo::Error::AuthFailed(_) => Error::Denied,
            _ => Error::System,
        },
        _ => Error::System,
    };
    Failure::new(code, error.to_string())
}
