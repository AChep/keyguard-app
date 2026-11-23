#!/usr/bin/env python3
"""Observe installed-app startup across two real desktop logins in a disposable account.

Read-only: it never enables login items, logs out, or launches or stops the app.
Full acceptance stays BLOCKED because it does not audit process creation at login
or bind the installer to its payload.
"""
import argparse
import configparser
import ctypes
import datetime
import getpass
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import time


class Blocked(RuntimeError):
    pass


UNVERIFIED_ACCEPTANCE_GATES = [
    "OS process-creation evidence proving the enabled-login launch came from the configured registration",
    "Continuous OS process-creation evidence covering the disabled login before this observer started",
    "Platform package evidence binding the supplied artifact to the complete installed application payload",
]


def acceptance_scope(report):
    # No receipt or caller-supplied JSON can mark these gates verified.
    report["case"] = "installed-login-observations"
    report["status"] = "BLOCKED"
    report["acceptance_status"] = "BLOCKED"
    report["unverified_acceptance_gates"] = list(UNVERIFIED_ACCEPTANCE_GATES)
    report["scope"] = (
        "Process presence/absence during the observation window and operator UI/registration observations only. "
        "Does not prove launch cause, absence before observation, or artifact-to-installed-payload identity."
    )


def linux_audit_session(proc):
    try:
        value = (proc / "sessionid").read_text().strip()
        return None if value == "4294967295" else value
    except (FileNotFoundError, PermissionError, ProcessLookupError):
        return None


def command(args):
    return subprocess.check_output(args, text=True, timeout=15).strip()


def sha256(path):
    with open(path, "rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def desktop_session():
    """A new process or terminal is not proof of a new OS login."""
    system = platform.system()
    if system == "Darwin":
        output = command(["launchctl", "print", f"gui/{os.getuid()}"])
        match = re.search(r"\basid = (\d+)", output)
        if not match or "session = Aqua" not in output:
            raise Blocked("A logged-in macOS Aqua session is required")
        boot = command(["sysctl", "-n", "kern.boottime"])
        return f"macos:{boot}:{match[1]}"
    if system == "Linux":
        session = os.environ.get("XDG_SESSION_ID")
        if not session:
            raise Blocked("Run from a graphical logind session (XDG_SESSION_ID is missing)")
        data = dict(line.split("=", 1) for line in command([
            "loginctl", "show-session", session, "-p", "Id", "-p", "TimestampMonotonic",
            "-p", "Type", "-p", "User", "-p", "Active",
        ]).splitlines())
        if data.get("Type") not in {"x11", "wayland"} or data.get("Active") != "yes":
            raise Blocked("An active X11/Wayland login session is required")
        if data.get("User") != str(os.getuid()) or not data.get("TimestampMonotonic"):
            raise Blocked("Cannot identify the current user's desktop login")
        boot = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
        return f"linux:{boot}:{data['Id']}:{data['TimestampMonotonic']}"
    if system == "Windows":
        # whoami /logonid reports the token's authentication LUID, shared by one login.
        logon = command(["whoami", "/logonid"])
        match = re.search(r"S-1-5-5-\d+-\d+", logon)
        if not match:
            raise Blocked("Cannot identify the Windows interactive login token")
        session = command(["powershell", "-NoProfile", "-Command",
                           "(Get-Process -Id $PID).SessionId"])
        if session == "0":
            raise Blocked("Windows session 0 cannot test interactive startup")
        boot = command(["powershell", "-NoProfile", "-Command",
                        "(Get-CimInstance Win32_OperatingSystem).LastBootUpTime.ToUniversalTime().ToString('o')"])
        return f"windows:{boot}:{match[0]}"
    raise Blocked(f"Unsupported platform: {system}")


def windows_argv(command_line):
    """Split a Windows command line the way CommandLineToArgvW does."""
    # The program name ends at its closing quote, or at whitespace when unquoted.
    # Backslashes are literal in it.
    if command_line.startswith('"'):
        end = command_line.find('"', 1)
        end = len(command_line) if end < 0 else end
        argv, position = [command_line[1:end]], end + 1
    else:
        end = len(re.match(r"[^ \t]*", command_line)[0])
        argv, position = [command_line[:end]], end
    current, started, quotes = [], False, 0
    while position < len(command_line):
        char = command_line[position]
        if char in " \t" and not quotes:
            if started:
                argv.append("".join(current))
                current, started = [], False
            position += 1
            continue
        started = True
        if char == "\\":
            count = len(command_line[position:]) - len(command_line[position:].lstrip("\\"))
            position += count
            if command_line[position:position + 1] == '"':
                # 2n backslashes before a quote are n backslashes and a quote that toggles
                # quoting; 2n+1 are n backslashes and a literal quote.
                current.append("\\" * (count // 2))
                if count % 2:
                    current.append('"')
                    position += 1
            else:
                current.append("\\" * count)
            continue
        if char == '"':
            # A run of quotes toggles quoting; every third quote in a run is literal.
            while command_line[position:position + 1] == '"':
                quotes += 1
                position += 1
                if quotes == 3:
                    current.append('"')
                    quotes = 0
            if quotes == 2:
                quotes = 0
            continue
        current.append(char)
        position += 1
    if started:
        argv.append("".join(current))
    return argv


def linux_argv(cmdline):
    """Split /proc/<pid>/cmdline into its NUL-terminated arguments."""
    if not cmdline:
        return []
    return [os.fsdecode(argument) for argument in cmdline.removesuffix(b"\0").split(b"\0")]


def matching_processes(launcher, identity=None):
    """Match executable identity, not process names that helpers could share."""
    target = os.path.normcase(str(Path(launcher).resolve()))
    system = platform.system()
    matches = []
    if system == "Windows":
        current_session = int(command(["powershell", "-NoProfile", "-Command",
                                       "(Get-Process -Id $PID).SessionId"]))
        rows = json.loads(command(["powershell", "-NoProfile", "-Command",
            "$sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value; "
            "ConvertTo-Json -InputObject @(Get-CimInstance Win32_Process | ForEach-Object { "
            "$owner = Invoke-CimMethod -InputObject $_ -MethodName GetOwnerSid -ErrorAction SilentlyContinue; "
            "if ($owner.Sid -eq $sid) { $_ | Select-Object ProcessId,ExecutablePath,CommandLine,SessionId } "
            "}) -Compress"]))
        for row in rows:
            if (row.get("SessionId") == current_session and row.get("ExecutablePath") and
                    os.path.normcase(str(Path(row["ExecutablePath"]).resolve())) == target):
                command_line = row.get("CommandLine") or ""
                matches.append({"pid": row["ProcessId"], "command": command_line, "argv": windows_argv(command_line),
                                "user_scope": "current-token-SID", "desktop_session": current_session,
                                "session_evidence": "Windows process SessionId"})
    elif system == "Linux":
        current_audit_session = linux_audit_session(Path("/proc/self"))
        for proc in Path("/proc").iterdir():
            if not proc.name.isdigit():
                continue
            try:
                # Directory ownership alone does not identify both real/effective
                # identities. Exclude another user's process before inspecting it.
                status = (proc / "status").read_text()
                uid_line = next(line for line in status.splitlines() if line.startswith("Uid:"))
                uids = list(map(int, uid_line.split()[1:]))
                if uids[:2] != [os.getuid(), os.geteuid()]:
                    continue
                process_audit_session = linux_audit_session(proc)
                if (current_audit_session is not None and process_audit_session is not None
                        and process_audit_session != current_audit_session):
                    continue
                executable = (proc / "exe").resolve(strict=False)
                same = str(executable) == target
                if identity and identity.get("distribution") == "linux-appimage":
                    environment = (proc / "environ").read_bytes().split(b"\0")
                    appimage = os.fsencode("APPIMAGE=" + identity["artifact"])
                    same = appimage in environment and sha256(proc / "exe") == identity["launcher_sha256"]
                elif identity and identity.get("distribution") == "linux-flatpak":
                    info = configparser.ConfigParser()
                    info.read(proc / "root/.flatpak-info")
                    same = info.get("Application", "name", fallback=None) == identity["flatpak_id"]
                    same = same and sha256(proc / "exe") == identity["launcher_sha256"]
                if same:
                    argv = linux_argv((proc / "cmdline").read_bytes())
                    matches.append({"pid": int(proc.name), "command": " ".join(argv), "argv": argv,
                                    "user_scope": "current-real-and-effective-UID",
                                    "audit_session": process_audit_session,
                                    "session_evidence": "matching kernel audit session" if current_audit_session is not None and process_audit_session is not None else "unavailable; desktop-login membership unverified"})
            except (FileNotFoundError, PermissionError, ProcessLookupError):
                continue
    else:
        libproc = ctypes.CDLL("/usr/lib/libproc.dylib")
        libproc.proc_pidpath.argtypes = [ctypes.c_int, ctypes.c_void_p, ctypes.c_uint32]
        for line in command(["ps", "-axo", "pid=,ruid=,uid=,command="]).splitlines():
            fields = line.strip().split(None, 3)
            if len(fields) != 4 or int(fields[1]) != os.getuid() or int(fields[2]) != os.geteuid():
                continue
            buffer = ctypes.create_string_buffer(4096)
            if libproc.proc_pidpath(int(fields[0]), buffer, len(buffer)) > 0:
                if os.path.normcase(str(Path(os.fsdecode(buffer.value)).resolve())) == target:
                    matches.append({"pid": int(fields[0]), "command": fields[3],
                                    "user_scope": "current-real-and-effective-UID",
                                    "session_evidence": "unavailable; process audit-login membership unverified"})
    return matches


def verify_sentinels(report):
    for entry in report["sentinels"]:
        if not Path(entry["path"]).is_file() or sha256(entry["path"]) != entry["sha256"]:
            raise AssertionError(f"User-data sentinel changed: {entry['path']}")


def advance(report, phase, session, processes, ui_observation=None,
            registration_observation=None, launch_provenance=None):
    """Validate a receipt transition before saving it; independently unit tested."""
    required = {"armed": "begin", "login-enabled": "armed", "disabled": "login-enabled", "login-disabled": "disabled"}
    if report["phase"] != required[phase]:
        raise AssertionError(f"{phase} requires {required[phase]}, found {report['phase']}")
    previous = report["events"][-1]["session"]
    if phase.startswith("login-") and session == previous:
        raise AssertionError("No new desktop login: launching an executable or rerunning this observer is not a login test")
    if phase in {"armed", "disabled"} and session != previous:
        raise AssertionError("Record registration/disable before logging out")
    expected = 1 if phase == "login-enabled" else 0
    if len(processes) != expected:
        raise AssertionError(f"Expected {expected} app process(es), observed {len(processes)}")
    if phase == "login-enabled" and report.get("platform") != "Darwin":
        # Compare whole arguments: the Windows shortcut quotes this one.
        if "--launch-at-login" not in processes[0].get("argv", [])[1:]:
            raise AssertionError("Process has no login activation argument")
    if phase == "login-enabled" and ui_observation != report["expected_window"]:
        raise AssertionError("Window behavior does not match the requested login preference")
    if phase in {"armed", "disabled"}:
        expected_registration = "enabled" if phase == "armed" else "disabled"
        if registration_observation != expected_registration:
            raise Blocked(f"Record the operator's {expected_registration} registration observation in app/OS settings")
    elif launch_provenance != "no-manual-launch":
        raise Blocked("Record the operator's no-manual-launch observation for this login; this is not OS launch-cause proof")
    event = {"phase": phase, "session": session, "processes": processes,
             "time": datetime.datetime.now(datetime.timezone.utc).isoformat()}
    if ui_observation:
        event["operator_window_observation"] = ui_observation
    event["registration_provenance"] = {"origin": "operator", "observation": registration_observation or "not-observed-in-this-phase", "independently_verified": False}
    event["launch_provenance"] = {"origin": "operator", "observation": launch_provenance or "not-observed-in-this-phase", "independently_verified": False}
    report["events"].append(event)
    report["phase"] = phase
    report["observation_status"] = "PASS" if phase == "login-disabled" else "BLOCKED"
    acceptance_scope(report)
    report["blocked_reasons"] = list(UNVERIFIED_ACCEPTANCE_GATES)
    if phase != "login-disabled":
        report["blocked_reasons"].insert(0, "Real login observations are incomplete")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=["begin", "armed", "login-enabled", "disabled", "login-disabled"])
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--artifact", type=Path, help="Exact MSI/MSIX/DMG/AppImage/Flatpak bundle under test")
    parser.add_argument("--launcher", type=Path, help="Installed process executable (use the persistent executable, not a symlink)")
    parser.add_argument("--distribution", choices=["windows-msi", "windows-msix", "macos-jvm", "macos-native", "linux-native", "linux-appimage", "linux-flatpak"])
    parser.add_argument("--flatpak-id", help="Installed Flatpak identity; required for linux-flatpak")
    parser.add_argument("--flavor", choices=["release", "beta"], default="release")
    parser.add_argument("--expected-window", choices=["hidden", "visible"], default="hidden")
    parser.add_argument("--observed-window", choices=["hidden", "visible"], help="Operator observation; never labeled automated GUI evidence")
    parser.add_argument("--observed-registration", choices=["enabled", "disabled"], help="Operator observation in app/OS settings, not an automated registration check")
    parser.add_argument("--launch-provenance", choices=["no-manual-launch", "manual-launch", "unknown"], help="Operator statement for this login, not OS process-creation proof")
    parser.add_argument("--sentinel", type=Path, action="append", default=[], help="Existing synthetic vault/unrelated-file sentinel; observer never writes it")
    parser.add_argument("--observe-seconds", type=int, default=30)
    args = parser.parse_args(argv)
    report = None
    try:
        if os.environ.get("KEYGUARD_DISPOSABLE_DESKTOP_ACCOUNT") != getpass.getuser():
            raise Blocked("Set KEYGUARD_DISPOSABLE_DESKTOP_ACCOUNT to the disposable test account name; never run in a personal vault account")
        if args.observe_seconds < 10:
            raise Blocked("Observe startup for at least ten seconds")
        session = desktop_session()
        if args.phase == "begin":
            if args.report.exists():
                raise Blocked("Refusing to overwrite an existing acceptance receipt")
            if not args.artifact or not args.launcher or not args.distribution or not args.sentinel:
                raise Blocked("begin requires artifact, installed launcher, distribution and existing synthetic sentinels")
            if args.distribution == "linux-flatpak" and not args.flatpak_id:
                raise Blocked("Flatpak observation requires --flatpak-id and the deployed launcher under its files directory")
            report = {"schema": 2, "observation_status": "BLOCKED", "phase": "begin",
                      "platform": platform.system(), "version": platform.platform(), "account": getpass.getuser(),
                      "distribution": args.distribution, "flavor": args.flavor, "flatpak_id": args.flatpak_id,
                      "desktop": os.environ.get("XDG_CURRENT_DESKTOP"), "display_protocol": os.environ.get("XDG_SESSION_TYPE"),
                      "artifact": str(args.artifact.resolve()), "artifact_sha256": sha256(args.artifact),
                      "launcher": str(args.launcher.resolve()), "launcher_sha256": sha256(args.launcher),
                      "expected_window": args.expected_window,
                      "sentinels": [{"path": str(path.resolve()), "sha256": sha256(path)} for path in args.sentinel],
                      "blocked_reasons": ["Enable login in app settings, Quit, run armed, then log out and log in"],
                      "events": [{"phase": "begin", "session": session, "processes": []}]}
            acceptance_scope(report)
            report["installation_provenance"] = {"origin": "operator", "observation": "supplied artifact and installed launcher paths", "independently_verified": False}
            if matching_processes(args.launcher, report):
                raise Blocked("Quit the app before beginning the acceptance run")
        else:
            report = json.loads(args.report.read_text())
            if report["account"] != getpass.getuser() or report["platform"] != platform.system():
                raise Blocked("Receipt belongs to a different account/platform")
            if sha256(report["artifact"]) != report["artifact_sha256"] or sha256(report["launcher"]) != report["launcher_sha256"]:
                raise AssertionError("Artifact changed: start a separate run for the new package")
            verify_sentinels(report)
            deadline = time.monotonic() + args.observe_seconds
            samples = []
            while True:
                processes = matching_processes(report["launcher"], report)
                samples.append({"elapsed": args.observe_seconds - max(0, deadline - time.monotonic()), "processes": processes})
                if len(processes) > (1 if args.phase == "login-enabled" else 0):
                    raise AssertionError("Unexpected/duplicate process during observation")
                if time.monotonic() >= deadline:
                    break
                time.sleep(1)
            advance(report, args.phase, session, processes, args.observed_window,
                    args.observed_registration, args.launch_provenance)
            report["events"][-1]["samples"] = samples
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps({"status": report["status"], "observation_status": report["observation_status"], "phase": report["phase"], "report": str(args.report)}))
        return 2
    except (Blocked, OSError, subprocess.SubprocessError, AssertionError, ValueError, KeyError) as error:
        status = "BLOCKED" if isinstance(error, (Blocked, OSError, subprocess.SubprocessError)) else "FAIL"
        # Preserve the last valid receipt for retry; diagnostics are a separate artifact.
        failure = {"schema": 2, "case": "installed-login-observations", "status": status, "phase": args.phase, "reason": str(error)}
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.with_suffix(".failure.json").write_text(json.dumps(failure, indent=2) + "\n")
        print(json.dumps(failure))
        return 2 if status == "BLOCKED" else 1


if __name__ == "__main__":
    sys.exit(main())
