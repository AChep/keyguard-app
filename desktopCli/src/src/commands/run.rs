//! `keyguard run`: starts a command with the variables of an environment.
//! Values only reach the command's environment; they are never printed.
use crate::{Connection, client, errors};
use keyguard_cli_protocol::cli::RunRequest;
use serde_json::Value;
use std::{ffi::OsString, path::PathBuf};
use zeroize::Zeroizing;

#[derive(clap::Args)]
pub(crate) struct Args {
    /// ID of the environment.
    #[arg(long, short = 'e', value_name = "id")]
    environment: String,
    /// ID or exact name of the account that holds the environment.
    #[arg(long, short = 'a', value_name = "account")]
    account: Option<String>,
    #[command(flatten)]
    connection: Connection,
    /// The command and its arguments, after `--`.
    #[arg(
        last = true,
        required = true,
        num_args = 1..,
        value_name = "command",
        hide = true
    )]
    command: Vec<OsString>,
}

/// The variables of an environment. Each value is wiped when dropped.
type Variables = Vec<(String, Zeroizing<String>)>;

/// Requests the variables and starts the command with them. On Unix the
/// command replaces this process; otherwise returns the exit code.
pub(crate) async fn main(args: Args) -> i32 {
    // Inside the sandbox, the command would run in the sandbox too.
    if std::env::var("container").as_deref() == Ok("flatpak") {
        eprintln!("keyguard: run is not supported inside a Flatpak sandbox.");
        return 1;
    }
    let variables = match request(
        args.connection.socket,
        args.environment,
        args.account,
        &args.command,
    )
    .await
    {
        Ok(variables) => variables,
        Err(code) => {
            eprintln!("keyguard: {}", message(&code));
            return 1;
        }
    };
    start(&args.command, variables)
}

async fn request(
    socket: Option<PathBuf>,
    environment: String,
    account: Option<String>,
    command: &[OsString],
) -> Result<Variables, String> {
    let request = RunRequest {
        environment,
        account,
        command: command
            .iter()
            .map(|argument| argument.to_string_lossy().into_owned())
            .collect(),
        working_directory: std::env::current_dir()
            .map(|path| path.to_string_lossy().into_owned())
            .unwrap_or_default(),
    };
    let mut response = client::request(socket, request).await?;
    let mut variables = Vec::new();
    if let Some(Value::Object(values)) = response.0.get_mut("variables") {
        for (name, value) in values.iter_mut() {
            if let Value::String(value) = value.take() {
                variables.push((name.clone(), Zeroizing::new(value)));
            }
        }
    }
    #[cfg(windows)]
    check_windows_limits(&variables)?;
    Ok(variables)
}

/// Windows names are case-insensitive, and one variable holds at most
/// 32,767 characters.
#[cfg(windows)]
fn check_windows_limits(variables: &Variables) -> Result<(), String> {
    let mut names = std::collections::HashSet::new();
    for (name, value) in variables {
        if !names.insert(name.to_ascii_uppercase()) {
            return Err("duplicate_variable".to_owned());
        }
        if name.encode_utf16().count() + 1 + value.encode_utf16().count() > 32_767 {
            return Err("value_too_large".to_owned());
        }
    }
    Ok(())
}

fn command_failure(command: &OsString, error: &std::io::Error) -> i32 {
    let name = command.to_string_lossy();
    if error.kind() == std::io::ErrorKind::NotFound {
        eprintln!("keyguard: command not found: {name}");
        127
    } else {
        eprintln!("keyguard: cannot run {name}: {error}");
        126
    }
}

/// Finds a bare command name as a Windows shell does. By name, Windows only
/// starts `.exe` files, but npm and similar tools install `.cmd` shims.
#[cfg(windows)]
fn program(name: &OsString, variables: &Variables) -> PathBuf {
    let path = std::path::Path::new(name);
    if path.components().count() != 1 {
        return path.into();
    }
    let extensions = std::env::var("PATHEXT").unwrap_or_else(|_| ".COM;.EXE;.BAT;.CMD".to_owned());
    let extensions: Vec<_> = extensions
        .split(';')
        .filter(|extension| !extension.is_empty())
        .collect();
    // A name that already has one of the extensions is looked up as is.
    let has_extension = path.extension().is_some_and(|extension| {
        let extension = format!(".{}", extension.to_string_lossy());
        extensions
            .iter()
            .any(|known| known.eq_ignore_ascii_case(&extension))
    });
    // The command sees the environment's PATH, when it sets one.
    let directories = variables
        .iter()
        .find(|(variable, _)| variable.eq_ignore_ascii_case("PATH"))
        .map(|(_, value)| OsString::from(value.as_str()))
        .or_else(|| std::env::var_os("PATH"))
        .unwrap_or_default();
    for directory in std::env::split_paths(&directories) {
        if directory.as_os_str().is_empty() {
            continue;
        }
        let candidates: Vec<PathBuf> = if has_extension {
            vec![directory.join(path)]
        } else {
            extensions
                .iter()
                .map(|extension| {
                    let mut file = name.clone();
                    file.push(extension);
                    directory.join(file)
                })
                .collect()
        };
        if let Some(found) = candidates.into_iter().find(|candidate| candidate.is_file()) {
            return found;
        }
    }
    path.into()
}

fn process(command: &[OsString], variables: &Variables) -> std::process::Command {
    #[cfg(windows)]
    let program = program(&command[0], variables);
    #[cfg(unix)]
    let program = &command[0];
    // The environment's values override the inherited variables.
    let mut process = std::process::Command::new(program);
    process.args(&command[1..]);
    for (name, value) in variables {
        process.env(name, value.as_str());
    }
    process
}

/// Replaces this process, so signals, the terminal, and the exit code
/// belong to the command alone.
#[cfg(unix)]
fn start(command: &[OsString], variables: Variables) -> i32 {
    use std::os::unix::process::CommandExt;
    let error = process(command, &variables).exec();
    drop(variables);
    command_failure(&command[0], &error)
}

/// Waits for the command in a job that ends it if this process is killed.
#[cfg(windows)]
fn start(command: &[OsString], variables: Variables) -> i32 {
    let mut process = process(command, &variables);
    drop(variables);
    let _job = windows::kill_on_close_job();
    windows::leave_interrupts_to_the_command();
    let child = process.spawn();
    drop(process);
    let status = match child {
        Ok(mut child) => child.wait(),
        Err(error) => return command_failure(&command[0], &error),
    };
    match status {
        Ok(status) => status.code().unwrap_or(1),
        Err(_) => 1,
    }
}

#[cfg(windows)]
mod windows {
    use std::os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle};
    use windows_sys::Win32::{
        Foundation::{FALSE, TRUE},
        System::{
            Console::{CTRL_BREAK_EVENT, CTRL_C_EVENT, SetConsoleCtrlHandler},
            JobObjects::{
                AssignProcessToJobObject, CreateJobObjectW, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
                JOBOBJECT_EXTENDED_LIMIT_INFORMATION, JobObjectExtendedLimitInformation,
                SetInformationJobObject,
            },
            Threading::GetCurrentProcess,
        },
    };

    /// Disarms the job on every normal return before closing its handle.
    /// Abrupt termination skips Drop, so Windows still ends the process tree.
    pub(super) struct Job {
        handle: OwnedHandle,
    }

    impl Drop for Job {
        fn drop(&mut self) {
            // Processes the command left running outlive it, as they would on Unix.
            set_kill_on_close(&self.handle, false);
        }
    }

    /// Puts this process, and so the command it starts, in a job that is
    /// ended when its last handle closes: when this process exits.
    pub(super) fn kill_on_close_job() -> Option<Job> {
        // SAFETY: null attributes and name create an anonymous job.
        let handle = unsafe { CreateJobObjectW(std::ptr::null(), std::ptr::null()) };
        if handle.is_null() {
            return None;
        }
        // SAFETY: CreateJobObjectW transferred this non-null owned handle.
        let job = unsafe { OwnedHandle::from_raw_handle(handle.cast()) };
        if !set_kill_on_close(&job, true) {
            return None;
        }
        // SAFETY: the job handle is live; the current-process pseudo handle
        // needs no closing.
        let assigned =
            unsafe { AssignProcessToJobObject(job.as_raw_handle().cast(), GetCurrentProcess()) };
        (assigned != 0).then_some(Job { handle: job })
    }

    fn set_kill_on_close(job: &OwnedHandle, enabled: bool) -> bool {
        // SAFETY: the structure holds only integers, for which zero is valid.
        let mut limits: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = unsafe { std::mem::zeroed() };
        if enabled {
            limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        }
        // SAFETY: the job handle is live and `limits` is a complete structure
        // of the stated size for this information class.
        unsafe {
            SetInformationJobObject(
                job.as_raw_handle().cast(),
                JobObjectExtendedLimitInformation,
                (&limits as *const JOBOBJECT_EXTENDED_LIMIT_INFORMATION).cast(),
                std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
            ) != 0
        }
    }

    unsafe extern "system" fn ignore_interrupt(event: u32) -> windows_sys::core::BOOL {
        if event == CTRL_C_EVENT || event == CTRL_BREAK_EVENT {
            TRUE
        } else {
            FALSE
        }
    }

    /// The command shares the console and handles Ctrl+C itself. A handler,
    /// unlike ignoring the signal, is not inherited by the command.
    pub(super) fn leave_interrupts_to_the_command() {
        // SAFETY: the handler is a static function that stays valid for the
        // lifetime of the process.
        unsafe {
            SetConsoleCtrlHandler(Some(ignore_interrupt), TRUE);
        }
    }

    #[cfg(test)]
    mod tests;
}

fn message(code: &str) -> String {
    match code {
        "unsupported_platform" => "This Keyguard build does not support keyguard run.",
        "not_found" => "Environment not found. Use Copy ID in Keyguard to get its ID.",
        "ambiguous_environment" => {
            "Environments in several accounts have this ID. Choose one with --account."
        }
        "unresolved_environment" => {
            "Some variables are unset or reference an item that is missing or hidden."
        }
        "unsupported_environment" => "This environment contains unsupported data.",
        "invalid_environment" => {
            "This environment exceeds the variable count or name length limit. Edit it in Keyguard."
        }
        "permission_denied" => "You don't have permission to read this environment.",
        "environment_too_large" => "The environment is too large.",
        "invalid_arguments" => "Invalid environment or command.",
        "duplicate_variable" => {
            "Two variable names differ only in case, which Windows treats as one."
        }
        "value_too_large" => "A variable is too large for Windows.",
        code => {
            return errors::message(code).map_or_else(
                || format!("Keyguard could not provide the environment ({code})."),
                str::to_owned,
            );
        }
    }
    .to_owned()
}
