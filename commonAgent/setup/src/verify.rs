//! Bounded client probes; all agent selection is explicit.
use anyhow::{Context, Result, ensure};
use std::{
    io::Read,
    path::Path,
    process::{Command, Stdio},
    sync::mpsc,
    thread,
    time::{Duration, Instant},
};

/// Runs a client without a shell, limiting runtime and captured output.
pub fn run(mut command: Command) -> Result<String> {
    let mut child = command
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .context("could not start the client; check its installation and executable path")?;
    let stdout = child.stdout.take().context("missing stdout")?;
    let stderr = child.stderr.take().context("missing stderr")?;
    let read = |stream: Box<dyn Read + Send>| {
        let (sender, receiver) = mpsc::sync_channel(1);
        thread::spawn(move || {
            let mut text = String::new();
            let result = stream
                .take(1024 * 1024 + 1)
                .read_to_string(&mut text)
                .map(|_| text);
            let _ = sender.send(result);
        });
        receiver
    };
    let output = read(Box::new(stdout));
    let error = read(Box::new(stderr));
    let deadline = Instant::now() + Duration::from_secs(15);
    let status = loop {
        if let Some(status) = child.try_wait()? {
            break status;
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            anyhow::bail!("client check timed out");
        }
        thread::sleep(Duration::from_millis(25));
    };
    let output = output
        .recv_timeout(deadline.saturating_duration_since(Instant::now()))
        .context("client stdout timed out")??;
    let error = error
        .recv_timeout(deadline.saturating_duration_since(Instant::now()))
        .context("client stderr timed out")??;
    ensure!(
        output.len() <= 1024 * 1024 && error.len() <= 1024 * 1024,
        "client output exceeds the size limit"
    );
    ensure!(status.success(), "client check failed: {}{}", output, error);
    Ok(output)
}

/// Lists the keys from the requested agent, independent of shell startup files.
pub fn ssh(socket: &str, executable: &Path) -> Result<String> {
    let mut command = Command::new(executable);
    command.arg("-L").env("SSH_AUTH_SOCK", socket);
    run(command)
}

/// Checks GnuPG against the managed home without starting another agent.
pub fn gpg(home: &Path, executable: &Path) -> Result<String> {
    let mut command = Command::new(executable);
    command
        .args(["--homedir"])
        .arg(home)
        .args(["--no-autostart", "KEYINFO --list", "/bye"]);
    let output = run(command)?;
    ensure!(
        !output.lines().any(|line| line.starts_with("ERR ")),
        "agent check failed: {output}"
    );
    ensure!(
        output.lines().any(|line| line.starts_with("S KEYINFO ")),
        "the agent responded but reported no keys; unlock Keyguard and check the GPG agent's key filter"
    );
    Ok(output)
}
