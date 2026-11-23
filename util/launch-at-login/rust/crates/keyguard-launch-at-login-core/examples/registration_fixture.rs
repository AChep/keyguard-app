//! Explicitly opted-in OS registration fixture; never shipped in the application.
use keyguard_launch_at_login_core::{Status, execute, last_error};
use serde::Deserialize;
use std::{
    env, fs,
    io::Write,
    path::{Path, PathBuf},
    process::Command,
};

const PREFIX: &str = "com.artemchep.keyguard.startuptest.";
const ATTESTATION: &str = "KEYGUARD_STARTUP_DISPOSABLE_ACCOUNT";

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Config {
    run_id: String,
    account: String,
    backend: String,
    id: String,
    #[serde(default)]
    launcher: String,
    #[serde(default)]
    arguments: Vec<String>,
    #[serde(default)]
    bundle: Option<PathBuf>,
    #[serde(default = "default_task")]
    task_id: String,
}
fn default_task() -> String {
    "KeyguardStartup".into()
}

fn main() {
    if let Err(message) = run() {
        println!(
            "{}",
            serde_json::json!({"result": "failed", "error": message})
        );
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let args: Vec<_> = env::args().skip(1).collect();
    if args.len() != 4 || args[0] != "--config" || args[2] != "--operation" {
        return Err("usage: registration_fixture --config /absolute/run/config.json --operation query|enable|disable".into());
    }
    let operation = match args[3].as_str() {
        "query" => 0,
        "enable" => 1,
        "disable" => 2,
        _ => return Err("unsupported fixture operation".into()),
    };
    let path = Path::new(&args[1]);
    if !path.is_absolute()
        || fs::symlink_metadata(path)
            .map_err(display)?
            .file_type()
            .is_symlink()
    {
        return Err("configuration must be an absolute non-symlink file".into());
    }
    let config: Config =
        serde_json::from_slice(&fs::read(path).map_err(display)?).map_err(display)?;
    let account = command("whoami", &[])?;
    let root = path.parent().ok_or("configuration has no run directory")?;
    let attestation = env::var(ATTESTATION).unwrap_or_default();
    let marker = fs::read_to_string(root.join("DISPOSABLE_ACCOUNT"))
        .map_err(|_| "missing disposable-account marker; see tests/README.md")?;
    validate_guard(&config, &account, &attestation, &marker)?;
    verify_package(&config)?;
    let request = request(&config)?;
    let ownership = root.join("registration.owned");
    let marker = format!("{}\n{}\n{}\n", config.run_id, config.backend, config.id);
    let owned = match fs::read_to_string(&ownership) {
        Ok(existing) if existing == marker => true,
        Ok(_) => return Err("run ownership marker belongs to another target".into()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => false,
        Err(error) => return Err(display(error)),
    };
    let before = invoke(0, &request)?;
    if operation == 1 && !owned {
        if before != Status::Disabled as i32 {
            return Err(format!(
                "refusing pre-existing registration (status {before})"
            ));
        }
        let mut file = fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&ownership)
            .map_err(display)?;
        file.write_all(marker.as_bytes()).map_err(display)?;
        file.sync_all().map_err(display)?;
    }
    if operation == 2 && !owned {
        return Err("refusing cleanup without this run's ownership marker".into());
    }
    let after = if operation == 0 {
        before
    } else {
        invoke(operation, &request)?
    };
    let cleaned = operation == 2 && after == Status::Disabled as i32;
    if cleaned {
        fs::remove_file(&ownership).map_err(display)?;
    }
    println!(
        "{}",
        serde_json::json!({
            "result": "observed", "operation": args[3], "backend": config.backend,
            "run_id": config.run_id, "before": before, "after": after, "cleanup_complete": cleaned,
        })
    );
    if operation == 2 && !cleaned {
        return Err(format!(
            "cleanup has not completed (status {after}); ownership marker retained"
        ));
    }
    Ok(())
}

fn validate_guard(
    config: &Config,
    account: &str,
    attestation: &str,
    marker: &str,
) -> Result<(), String> {
    if config.run_id.len() != 32
        || !config.run_id.bytes().all(|value| value.is_ascii_hexdigit())
        || config.id != fixture_id(&config.backend, &config.run_id)
    {
        return Err(
            "fixture identity must use the startuptest prefix and this run's 32-hex nonce".into(),
        );
    }
    if account.is_empty()
        || account != config.account
        || attestation != account
        || marker
            != format!(
                "disposable startup-test account\n{account}\n{}\n",
                config.run_id
            )
    {
        return Err(
            "explicit disposable-account attestation and run marker must match the current account"
                .into(),
        );
    }
    Ok(())
}

fn fixture_id(backend: &str, run_id: &str) -> String {
    match backend {
        // MSIX package names have a 50-character maximum.
        "msix" => format!("Keyguard.Test.{run_id}"),
        // Flatpak application ID components may not begin with a digit.
        "flatpak" => format!("{PREFIX}run{run_id}"),
        _ => format!("{PREFIX}{run_id}"),
    }
}

fn request(config: &Config) -> Result<String, String> {
    let mut fields = match config.backend.as_str() {
        "xdg" | "windows" | "msix" => {
            // A packaged helper registers its startup task; the launcher only satisfies the target.
            let launcher = if config.backend == "msix" {
                env::current_exe().map_err(display)?
            } else {
                PathBuf::from(&config.launcher)
            };
            if !launcher.is_absolute() {
                return Err("installed launcher must be absolute".into());
            }
            let xdg = config.backend == "xdg";
            vec![
                if xdg { "xdg" } else { "windows" }.into(),
                config.id.clone(),
                "Keyguard startup fixture".into(),
                launcher.to_str().ok_or("invalid launcher path")?.into(),
                // Only a Windows target has a startup task; the field is empty elsewhere.
                if xdg {
                    String::new()
                } else {
                    config.task_id.clone()
                },
            ]
        }
        "macos" => vec!["macos".into(), "".into(), "".into(), "".into(), "".into()],
        "flatpak" => vec![
            "flatpak".into(),
            "".into(),
            "Test startup permission for a disposable fixture".into(),
            "".into(),
            "".into(),
        ],
        _ => return Err("unsupported fixture backend".into()),
    };
    fields.extend(config.arguments.clone());
    if fields.iter().any(|field| field.contains('\0')) {
        return Err("fixture fields must not contain NUL".into());
    }
    Ok(fields.join("\0"))
}

fn verify_package(config: &Config) -> Result<(), String> {
    if config.bundle.is_some() && config.backend != "macos" {
        return Err("only a macOS fixture accepts a bundle path".into());
    }
    match config.backend.as_str() {
        "macos" => verify_macos(config),
        "msix" => verify_msix(config),
        "flatpak" if env::var("FLATPAK_ID").as_deref() == Ok(config.id.as_str()) => Ok(()),
        "flatpak" => Err(
            "run this helper inside the installed fixture Flatpak with the configured identity"
                .into(),
        ),
        "xdg" if cfg!(target_os = "linux") => Ok(()),
        "windows" if cfg!(windows) => Ok(()),
        _ => Err("fixture backend does not match this operating system".into()),
    }
}

#[cfg(target_os = "macos")]
fn verify_macos(config: &Config) -> Result<(), String> {
    let bundle = config
        .bundle
        .as_ref()
        .ok_or("macOS requires an installed signed fixture .app bundle")?
        .canonicalize()
        .map_err(display)?;
    if !env::current_exe()
        .map_err(display)?
        .canonicalize()
        .map_err(display)?
        .starts_with(&bundle)
    {
        return Err("helper must be the executable of the signed fixture bundle".into());
    }
    let info = bundle.join("Contents/Info.plist");
    let identifier = command(
        "/usr/bin/plutil",
        &[
            "-extract",
            "CFBundleIdentifier",
            "raw",
            "-o",
            "-",
            info.to_str().ok_or("invalid bundle path")?,
        ],
    )?;
    if identifier != config.id {
        return Err("fixture bundle identity does not match the run identity".into());
    }
    let path = bundle.to_str().ok_or("invalid bundle path")?;
    command("/usr/bin/codesign", &["--verify", "--strict", path])?;
    let signature = Command::new("/usr/bin/codesign")
        .args(["-dv", "--verbose=4", path])
        .output()
        .map_err(display)?;
    let description = String::from_utf8_lossy(&signature.stderr);
    if !signature.status.success()
        || description.contains("Signature=adhoc")
        || !description
            .lines()
            .any(|line| line.starts_with("TeamIdentifier=") && line != "TeamIdentifier=not set")
    {
        return Err("fixture needs a valid team signature; ad-hoc signing does not validate SMAppService packaging".into());
    }
    Ok(())
}
#[cfg(not(target_os = "macos"))]
fn verify_macos(_: &Config) -> Result<(), String> {
    Err("signed macOS fixture requires macOS".into())
}

#[cfg(windows)]
fn verify_msix(config: &Config) -> Result<(), String> {
    use windows::Win32::{
        Foundation::ERROR_INSUFFICIENT_BUFFER, Storage::Packaging::Appx::GetCurrentPackageFullName,
    };
    let mut length = 0;
    // SAFETY: A null buffer and zero capacity perform the documented size query.
    if unsafe { GetCurrentPackageFullName(&mut length, None) } != ERROR_INSUFFICIENT_BUFFER {
        return Err("helper must run as the installed fixture MSIX application".into());
    }
    let mut buffer = vec![0u16; length as usize];
    // SAFETY: The live UTF-16 buffer has the capacity returned by Windows.
    unsafe {
        GetCurrentPackageFullName(&mut length, Some(windows::core::PWSTR(buffer.as_mut_ptr())))
    }
    .ok()
    .map_err(display)?;
    let name = String::from_utf16_lossy(&buffer[..length.saturating_sub(1) as usize]);
    if !name.starts_with(&format!("{}_", config.id)) {
        return Err("installed MSIX identity does not match the fixture run".into());
    }
    Ok(())
}
#[cfg(not(windows))]
fn verify_msix(_: &Config) -> Result<(), String> {
    Err("installed MSIX fixture requires Windows".into())
}

fn invoke(operation: i32, request: &str) -> Result<i32, String> {
    let result = execute(operation, request);
    if result < 0 {
        Err(format!(
            "native error {result}: {}",
            last_error().unwrap_or_default()
        ))
    } else {
        Ok(result)
    }
}
fn command(program: &str, arguments: &[&str]) -> Result<String, String> {
    let result = Command::new(program)
        .args(arguments)
        .output()
        .map_err(display)?;
    if !result.status.success() {
        return Err(format!(
            "{program} failed: {}",
            String::from_utf8_lossy(&result.stderr)
        ));
    }
    Ok(String::from_utf8_lossy(&result.stdout).trim().into())
}
fn display(error: impl std::fmt::Display) -> String {
    error.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    fn config() -> Config {
        Config {
            run_id: "0123456789abcdef0123456789abcdef".into(),
            account: "fixture-user".into(),
            backend: "xdg".into(),
            id: format!("{PREFIX}0123456789abcdef0123456789abcdef"),
            // Absolute on every host, including Windows CI.
            launcher: env::temp_dir()
                .join("fixture launcher")
                .display()
                .to_string(),
            arguments: vec!["--launch-at-login".into(), "literal $;".into()],
            bundle: None,
            task_id: default_task(),
        }
    }
    #[test]
    fn guards_reject_production_identity_wrong_account_and_missing_attestation() {
        let mut config = config();
        let marker = format!(
            "disposable startup-test account\nfixture-user\n{}\n",
            config.run_id
        );
        assert!(validate_guard(&config, "fixture-user", "fixture-user", &marker).is_ok());
        assert!(validate_guard(&config, "fixture-user", "", &marker).is_err());
        assert!(validate_guard(&config, "developer", "developer", &marker).is_err());
        assert!(validate_guard(&config, "fixture-user", "fixture-user", "").is_err());
        config.id = "com.artemchep.keyguard".into();
        assert!(validate_guard(&config, "fixture-user", "fixture-user", &marker).is_err());
    }
    #[test]
    fn commands_keep_literal_arguments_and_reject_protocol_injection() {
        let mut config = config();
        let request_text = request(&config).unwrap();
        let fields: Vec<_> = request_text.split('\0').collect();
        assert_eq!(fields[0], "xdg");
        assert_eq!(fields[3], config.launcher);
        // The task ID field is always present, and empty outside Windows.
        assert_eq!(fields[4], "");
        assert_eq!(fields[5..], ["--launch-at-login", "literal $;"]);
        config.arguments.push("bad\0field".into());
        assert!(request(&config).is_err());
        config.arguments.clear();
        config.launcher = "relative".into();
        assert!(request(&config).is_err());
    }

    #[test]
    fn windows_and_msix_send_one_windows_target_with_its_task() {
        let mut config = config();
        config.backend = "windows".into();
        config.launcher = env::temp_dir().join("fixture.exe").display().to_string();
        let fields: Vec<_> = request(&config)
            .unwrap()
            .split('\0')
            .map(str::to_owned)
            .collect();
        assert_eq!(fields[0], "windows");
        assert_eq!(fields[4], "KeyguardStartup");
        config.backend = "msix".into();
        config.launcher.clear();
        assert!(request(&config).unwrap().starts_with("windows\0"));
    }

    #[test]
    fn macos_and_flatpak_send_the_full_header() {
        let mut config = config();
        config.arguments.clear();
        config.backend = "macos".into();
        assert_eq!(request(&config).unwrap(), "macos\0\0\0\0");
        config.backend = "flatpak".into();
        config.arguments = vec!["keyguard".into()];
        let request_text = request(&config).unwrap();
        let fields: Vec<_> = request_text.split('\0').collect();
        assert_eq!(fields.len(), 6);
        assert_eq!(fields[4], "");
        assert_eq!(fields[5], "keyguard");
    }

    #[test]
    fn package_identities_fit_msix_and_flatpak_constraints() {
        let config = config();
        let msix = fixture_id("msix", &config.run_id);
        assert!(msix.len() <= 50);
        assert!(msix.starts_with("Keyguard.Test."));
        let flatpak = fixture_id("flatpak", &config.run_id);
        assert!(
            flatpak
                .split('.')
                .all(|part| part.starts_with(|ch: char| ch.is_ascii_alphabetic()))
        );
    }
}
