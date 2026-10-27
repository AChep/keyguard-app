#!/usr/bin/env python3
"""Explain a stalled build: what the Gradle, Kotlin and native build processes on this machine do.

The report is read-only. It never stops, kills or changes a process.
"""

from __future__ import annotations

import argparse
from collections.abc import Iterable, Sequence
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time


COMMAND_TIMEOUT_SECONDS = 30
# A JVM stuck in garbage collection may never reach the safepoint an attach needs.
JCMD_TIMEOUT_SECONDS = 15
TOP_PROCESS_COUNT = 10
SILENT_LOG_MINUTES = 10

GRADLE_DAEMON_MAIN = "org.gradle.launcher.daemon.bootstrap.GradleDaemon"
GRADLE_WORKER_MAIN = "worker.org.gradle.process.internal.worker.GradleWorkerMain"
KOTLIN_DAEMON_MAIN = "org.jetbrains.kotlin.daemon.KotlinCompileDaemon"
GRADLE_CLIENT_ENTRIES = (
    "gradle-wrapper.jar",
    "org.gradle.wrapper.GradleWrapperMain",
    "org.gradle.launcher.GradleMain",
)
NATIVE_TOOLS = ("cargo", "rustc", "xcodebuild", "swift-frontend")
KIND_LABELS = {
    "gradle-daemon": "Gradle daemon",
    "gradle-client": "Gradle client",
    "gradle-worker": "Gradle worker",
    "kotlin-daemon": "Kotlin daemon",
    "konan": "Kotlin/Native",
}

# Settings AGENTS.md asks agents not to override; each override splits the daemon pool.
OVERRIDE_KEYS = (
    "org.gradle.jvmargs",
    "org.gradle.daemon.registry.base",
    "kotlin.daemon.jvmargs",
    "kotlin.compiler.execution.strategy",
)

LOOPBACK_HOSTS = {"127.0.0.1", "[::1]", "[::127.0.0.1]", "localhost"}
CONNECTION = re.compile(r"^(?P<lhost>.+):(?P<lport>\d+)->(?P<rhost>.+):(?P<rport>\d+)$")
# ` ParOldGen  total 6990848K, used 6990717K [start, committed end, reserved end)`; G1 prints
# `[start, reserved end)`. The reserved end bounds how far the region can still grow.
HEAP_REGION = re.compile(
    r"^\s?(?P<name>\S.*?)\s+total (?P<total>\d+)K, used (?P<used>\d+)K(?: \[(?P<addresses>[^)\]]*))?"
)
SIZE = re.compile(r"^(?P<number>\d+(?:\.\d+)?)(?P<unit>[KMGT]?)B?[+-]?$", re.IGNORECASE)
KOTLIN_DAEMON_LOG = re.compile(r"/kotlin-daemon\.[^/]*\.log$")
# A Gradle daemon writes `<registry>/<version>/daemon-<pid>.out.log`. Its working directory
# follows the build it runs, so the log is the reliable way to find its registry.
GRADLE_DAEMON_LOG = re.compile(r"/daemon-\d+\.out\.log$")


@dataclass(frozen=True)
class Process:
    pid: int
    ppid: int
    elapsed: str
    cpu: float
    rss_bytes: int
    args: str

    @property
    def kind(self) -> str:
        return classify(self.args)


@dataclass(frozen=True)
class CommandResult:
    output: str
    error: str | None = None


def run(
    command: Sequence[str],
    timeout: float = COMMAND_TIMEOUT_SECONDS,
    env: dict[str, str] | None = None,
) -> CommandResult:
    try:
        completed = subprocess.run(
            list(command),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            env=env,
            check=False,
        )
    except FileNotFoundError:
        return CommandResult("", f"{command[0]} not found")
    except subprocess.TimeoutExpired:
        return CommandResult("", f"timed out after {timeout:g} s")
    except OSError as error:
        return CommandResult("", str(error))
    # lsof exits with 1 when some of the requested processes are gone, yet prints the rest.
    if completed.returncode != 0 and not completed.stdout.strip():
        lines = completed.stderr.strip().splitlines()
        return CommandResult("", lines[-1] if lines else f"exit code {completed.returncode}")
    return CommandResult(completed.stdout)


def classify(args: str) -> str:
    if GRADLE_DAEMON_MAIN in args:
        return "gradle-daemon"
    if KOTLIN_DAEMON_MAIN in args:
        return "kotlin-daemon"
    if GRADLE_WORKER_MAIN in args:
        return "gradle-worker"
    if any(entry in args for entry in GRADLE_CLIENT_ENTRIES):
        return "gradle-client"
    executable = args.split(" ", 1)[0]
    if "/.konan/" in executable:
        return "konan"
    name = Path(executable).name
    return name if name in NATIVE_TOOLS else ""


def parse_size(text: str) -> int | None:
    """Parse `8g`, `4096m`, top's `9508M+` and plain byte counts, in binary units."""
    match = SIZE.match(text.strip())
    if not match:
        return None
    power = " KMGT".index(match["unit"].upper() or " ")
    return int(float(match["number"]) * 1024**power)


def human(size: int | None) -> str:
    if size is None:
        return "?"
    if size >= 1024**3:
        return f"{size / 1024**3:.1f} GB"
    return f"{size / 1024**2:.0f} MB"


def percent(part: float, whole: float) -> int:
    return round(100 * part / whole) if whole else 0


def shorten(path: str) -> str:
    home = str(Path.home())
    return "~" + path[len(home):] if path == home or path.startswith(home + "/") else path


def parse_ps(output: str) -> list[Process]:
    processes = []
    for line in output.splitlines():
        parts = line.split(None, 5)
        if len(parts) < 6:
            continue
        try:
            processes.append(Process(
                pid=int(parts[0]),
                ppid=int(parts[1]),
                elapsed=parts[2],
                cpu=float(parts[3]),
                rss_bytes=int(parts[4]) * 1024,
                args=parts[5],
            ))
        except ValueError:
            continue
    return processes


def parse_top(output: str) -> list[tuple[int, int, str]]:
    """Rows of `top -l 1 -stats pid,mem,command` as (pid, footprint bytes, command)."""
    rows = []
    in_table = False
    for line in output.splitlines():
        if not in_table:
            in_table = line.split()[:1] == ["PID"]
            continue
        parts = line.split(None, 2)
        if len(parts) < 3 or not parts[0].isdigit():
            continue
        size = parse_size(parts[1])
        if size is not None:
            rows.append((int(parts[0]), size, parts[2].strip()))
    return rows


def parse_swapusage(text: str) -> tuple[int, int] | None:
    match = re.search(r"total = ([\d.]+[KMGT])\s+used = ([\d.]+[KMGT])", text)
    if not match:
        return None
    total, used = parse_size(match[1]), parse_size(match[2])
    return (total, used) if total is not None and used is not None else None


def parse_vm_stat_compressed(text: str) -> int | None:
    page = re.search(r"page size of (\d+) bytes", text)
    pages = re.search(r"Pages occupied by compressor:\s+(\d+)", text)
    return int(page[1]) * int(pages[1]) if page and pages else None


def parse_memory_pressure(text: str) -> int | None:
    match = re.search(r"System-wide memory free percentage: (\d+)%", text)
    return int(match[1]) if match else None


def parse_meminfo(text: str) -> dict[str, int]:
    values = {}
    for line in text.splitlines():
        match = re.match(r"^(\w+):\s+(\d+) kB", line)
        if match:
            values[match[1]] = int(match[2]) * 1024
    return values


def parse_lsof_names(output: str) -> dict[int, list[str]]:
    """The `n` fields of `lsof -F` output, per process."""
    names: dict[int, list[str]] = {}
    pid = None
    for line in output.splitlines():
        if line.startswith("p") and line[1:].isdigit():
            pid = int(line[1:])
        elif line.startswith("n") and pid is not None:
            names.setdefault(pid, []).append(line[1:])
    return names


def parse_lsof_connections(output: str) -> list[tuple[int, int, int]]:
    """Loopback TCP connections as (pid, local port, remote port)."""
    connections = []
    for pid, names in parse_lsof_names(output).items():
        for name in names:
            match = CONNECTION.match(name)
            if match and match["lhost"] in LOOPBACK_HOSTS and match["rhost"] in LOOPBACK_HOSTS:
                connections.append((pid, int(match["lport"]), int(match["rport"])))
    return connections


def peers_by_pid(connections: Iterable[tuple[int, int, int]]) -> dict[int, set[int]]:
    """The processes at the other end of each process's loopback connections."""
    connections = list(connections)
    owner = {local: pid for pid, local, _ in connections}
    peers: dict[int, set[int]] = {}
    for pid, _, remote in connections:
        other = owner.get(remote)
        if other is not None and other != pid:
            peers.setdefault(pid, set()).add(other)
    return peers


@dataclass(frozen=True)
class HeapRegion:
    name: str
    committed: int
    used: int
    reserved: int | None

    @property
    def is_full(self) -> bool:
        """An old generation, or a whole G1 heap, that has grown to its reserve and has no room left."""
        if "Old" not in self.name and "heap" not in self.name:
            return False
        grown = self.reserved is None or self.committed >= 0.95 * self.reserved
        return grown and self.used >= 0.95 * self.committed


def parse_heap_info(output: str) -> list[HeapRegion]:
    """Top-level regions of `jcmd <pid> GC.heap_info`."""
    regions = []
    for line in output.splitlines():
        match = HEAP_REGION.match(line)
        if not match:
            continue
        addresses = [int(address, 16) for address in re.findall(r"0x[0-9a-fA-F]+", match["addresses"] or "")]
        regions.append(HeapRegion(
            name=match["name"].strip(),
            committed=int(match["total"]) * 1024,
            used=int(match["used"]) * 1024,
            reserved=addresses[-1] - addresses[0] if len(addresses) >= 2 else None,
        ))
    return regions


def max_heap_flag(args: str) -> str | None:
    match = re.search(r"\s-Xmx(\S+)", args)
    return match[1] if match else None


def java_home(args: str) -> str | None:
    match = re.match(r"^(.*?)/bin/java(?:\s|$)", args)
    return match[1] if match else None


def gradle_version(args: str) -> str:
    match = re.search(re.escape(GRADLE_DAEMON_MAIN) + r"\s+(\S+)", args)
    return match[1] if match else "?"


def client_arguments(args: str) -> tuple[str, list[str]]:
    """Split the arguments after the Gradle entry point into (tasks and options, -D/-P properties)."""
    for entry in GRADLE_CLIENT_ENTRIES:
        _, found, rest = args.partition(entry)
        if found:
            tokens = rest.split()
            properties = [token for token in tokens if token.startswith(("-D", "-P"))]
            return " ".join(token for token in tokens if token not in properties), properties
    return "", []


def overridden_keys(properties: Iterable[str]) -> list[str]:
    keys = [token[2:].split("=", 1)[0] for token in properties]
    return [key for key in keys if key in OVERRIDE_KEYS]


def plural(count: int, singular: str, plural_form: str) -> str:
    return f"{count} {singular if count == 1 else plural_form}"


def find_log(files: Iterable[str], pattern: re.Pattern[str]) -> Path | None:
    return next((Path(name) for name in files if pattern.search(name)), None)


def last_line(path: Path, limit: int = 8192) -> str:
    with path.open("rb") as file:
        file.seek(0, os.SEEK_END)
        file.seek(max(0, file.tell() - limit))
        lines = file.read().decode("utf-8", "replace").splitlines()
    return next((line.strip() for line in reversed(lines) if line.strip()), "")


def clip(text: str, limit: int = 160) -> str:
    return text if len(text) <= limit else text[:limit - 1] + "…"


class Machine:
    """Reads the state of the processes; every call is read-only."""

    def __init__(self) -> None:
        self.is_macos = sys.platform == "darwin"
        self.processes = parse_ps(run(["ps", "-ww", "-Ao", "pid=,ppid=,etime=,pcpu=,rss=,args="]).output)
        self.by_pid = {process.pid: process for process in self.processes}
        self.top_rows = parse_top(run(["top", "-l", "1", "-o", "mem", "-stats", "pid,mem,command"]).output) \
            if self.is_macos else []
        self.footprints = {pid: size for pid, size, _ in self.top_rows}
        connections = run(["lsof", "-nP", "-iTCP", "-sTCP:ESTABLISHED", "-Fpn"])
        self.peers_error = connections.error
        self.peers = peers_by_pid(parse_lsof_connections(connections.output))
        self.attach_env = dict(os.environ)
        if self.is_macos:
            # The attach socket lives in the target JVM's temp dir; a sandbox may point TMPDIR elsewhere.
            temp = run(["getconf", "DARWIN_USER_TEMP_DIR"]).output.strip()
            if temp:
                self.attach_env["TMPDIR"] = temp

    def of_kind(self, kind: str) -> list[Process]:
        return [process for process in self.processes if process.kind == kind]

    def peers_of_kind(self, process: Process, kind: str) -> list[int]:
        return sorted(
            pid for pid in self.peers.get(process.pid, ())
            if pid in self.by_pid and self.by_pid[pid].kind == kind
        )

    def memory(self, process: Process) -> int:
        """The physical footprint on macOS, which counts compressed and swapped pages; RSS elsewhere."""
        return self.footprints.get(process.pid, process.rss_bytes)

    def label(self, pid: int) -> str:
        process = self.by_pid.get(pid)
        if process is None:
            return f"pid {pid}"
        kind = process.kind
        name = KIND_LABELS.get(kind) or kind or Path(process.args.split(" ", 1)[0]).name
        return f"{name} {pid}"

    def open_files(self, pids: Sequence[int], cwd_only: bool) -> dict[int, list[str]]:
        if not pids:
            return {}
        if not self.is_macos:
            return {pid: linux_open_files(pid, cwd_only) for pid in pids}
        command = ["lsof", "-a", "-p", ",".join(map(str, pids)), "-Fn"]
        if cwd_only:
            command[2:2] = ["-d", "cwd"]
        return parse_lsof_names(run(command).output)

    def heap(self, process: Process) -> CommandResult:
        home = java_home(process.args)
        bundled = Path(home, "bin", "jcmd") if home else None
        jcmd = str(bundled) if bundled and bundled.exists() else shutil.which("jcmd")
        if not jcmd:
            return CommandResult("", "jcmd not found")
        return run([jcmd, str(process.pid), "GC.heap_info"], timeout=JCMD_TIMEOUT_SECONDS, env=self.attach_env)


def linux_open_files(pid: int, cwd_only: bool) -> list[str]:
    try:
        if cwd_only:
            return [os.readlink(f"/proc/{pid}/cwd")]
        fds = Path(f"/proc/{pid}/fd")
        return [os.readlink(fd) for fd in fds.iterdir()]
    except OSError:
        return []


class Report:
    def __init__(self, machine: Machine) -> None:
        self.machine = machine
        self.lines: list[str] = []
        self.warnings: list[str] = []

    def section(self, title: str) -> None:
        self.lines += ["", title]

    def add(self, text: str, indent: int = 2) -> None:
        self.lines.append(" " * indent + text)

    def memory(self) -> None:
        self.section("Memory")
        if self.machine.is_macos:
            swap = parse_swapusage(run(["sysctl", "-n", "vm.swapusage"]).output)
            if swap:
                total, used = swap
                self.add(f"Swap: {human(used)} of {human(total)} used")
                if total and used >= 0.8 * total:
                    self.warnings.append(
                        f"Swap is {percent(used, total)}% used. The machine is short on memory, so every build slows down."
                    )
            compressed = parse_vm_stat_compressed(run(["vm_stat"]).output)
            if compressed is not None:
                self.add(f"Compressed: {human(compressed)}")
            free = parse_memory_pressure(run(["memory_pressure"]).output)
            if free is not None:
                self.add(f"Free memory: {free}% (memory_pressure)")
        else:
            try:
                values = parse_meminfo(Path("/proc/meminfo").read_text())
            except OSError:
                values = {}
            total, available = values.get("MemTotal"), values.get("MemAvailable")
            if total and available is not None:
                self.add(f"Available: {human(available)} of {human(total)}")
                if available < 0.1 * total:
                    self.warnings.append(f"Only {human(available)} of memory is available.")
            swap_total, swap_free = values.get("SwapTotal"), values.get("SwapFree")
            if swap_total:
                swap_used = swap_total - (swap_free or 0)
                self.add(f"Swap: {human(swap_used)} of {human(swap_total)} used")
                if swap_used >= 0.8 * swap_total:
                    self.warnings.append(f"Swap is {percent(swap_used, swap_total)}% used.")

    def top_processes(self) -> None:
        self.section(f"Top {TOP_PROCESS_COUNT} processes by memory")
        machine = self.machine
        if machine.top_rows:
            rows = [(pid, size, command) for pid, size, command in machine.top_rows[:TOP_PROCESS_COUNT]]
        else:
            largest = sorted(machine.processes, key=lambda process: process.rss_bytes, reverse=True)
            rows = [
                (process.pid, process.rss_bytes, Path(process.args.split(" ", 1)[0]).name)
                for process in largest[:TOP_PROCESS_COUNT]
            ]
        for pid, size, command in rows:
            process = machine.by_pid.get(pid)
            kind = KIND_LABELS.get(process.kind, "") if process else ""
            self.add(f"{pid:>7}  {human(size):>8}  {command}" + (f"  ({kind})" if kind else ""))

    def jvms(self) -> None:
        machine = self.machine
        daemons = machine.of_kind("gradle-daemon")
        kotlin_daemons = machine.of_kind("kotlin-daemon")
        clients = machine.of_kind("gradle-client")
        jvms = daemons + kotlin_daemons
        with ThreadPoolExecutor(max_workers=8) as pool:
            heaps = dict(zip((process.pid for process in jvms), pool.map(machine.heap, jvms)))
        files = machine.open_files([process.pid for process in jvms], cwd_only=False)
        directories = machine.open_files([process.pid for process in clients], cwd_only=True)
        if machine.peers_error:
            self.warnings.append(f"Connections between processes are unknown: lsof failed ({machine.peers_error}).")

        self.section(f"Gradle daemons ({len(daemons)})")
        registry_root = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"), "daemon")
        idle = []
        heaps_by_version: dict[str, set[str]] = {}
        for daemon in daemons:
            peers = sorted(machine.peers.get(daemon.pid, ()))
            if machine.peers_error:
                state = "unknown"
            elif peers:
                state = "busy"
            else:
                state = "idle"
                idle.append(daemon)
            version, max_heap = gradle_version(daemon.args), max_heap_flag(daemon.args)
            heaps_by_version.setdefault(version, set()).add(max_heap or "default")
            self.add(
                f"{daemon.pid:>7}  Gradle {version}  -Xmx{max_heap or '?'}  up {daemon.elapsed}"
                f"  cpu {daemon.cpu:.0f}%  {human(machine.memory(daemon))}  {state}"
            )
            self.add(f"JDK: {shorten(java_home(daemon.args) or '?')}", indent=11)
            log = find_log(files.get(daemon.pid, []), GRADLE_DAEMON_LOG)
            if log:
                registry = log.parent.parent
                self.add(f"Registry: {shorten(str(registry))}", indent=11)
                if not registry.is_relative_to(registry_root):
                    self.warnings.append(
                        f"Gradle daemon {daemon.pid} uses a private registry ({shorten(str(registry))});"
                        " no other build can reuse it."
                    )
            self.heap_line("Gradle daemon", daemon, heaps[daemon.pid])
            self.log_line(log)
            if peers:
                self.add("Connected: " + ", ".join(machine.label(pid) for pid in peers), indent=11)
        if idle:
            held = sum(machine.memory(daemon) for daemon in idle)
            self.warnings.append(
                f"{plural(len(idle), 'idle Gradle daemon holds', 'idle Gradle daemons hold')} {human(held)}."
            )
        for version, max_heaps in heaps_by_version.items():
            if len(max_heaps) > 1:
                self.warnings.append(
                    f"Gradle {version} daemons run with different heaps ({', '.join(sorted(max_heaps))}),"
                    " so builds can't reuse each other's daemons."
                )

        self.section(f"Gradle clients ({len(clients)})")
        for client in clients:
            directory = next(iter(directories.get(client.pid, [])), "?")
            self.add(f"{client.pid:>7}  up {client.elapsed}  {shorten(directory)}")
            tasks, properties = client_arguments(client.args)
            self.add(clip(tasks) or "(no tasks)", indent=11)
            for token in properties:
                self.add(f"Property: {clip(token, 120)}", indent=11)
            overrides = overridden_keys(properties)
            if overrides:
                self.warnings.append(
                    f"Gradle client {client.pid} overrides {', '.join(overrides)}, which starts a daemon other builds can't reuse."
                )
            served_by = machine.peers_of_kind(client, "gradle-daemon")
            if served_by:
                self.add("Daemon: " + ", ".join(map(str, served_by)), indent=11)

        self.section(f"Kotlin daemons ({len(kotlin_daemons)})")
        for daemon in kotlin_daemons:
            self.add(
                f"{daemon.pid:>7}  -Xmx{max_heap_flag(daemon.args) or '?'}  up {daemon.elapsed}"
                f"  cpu {daemon.cpu:.0f}%  {human(machine.memory(daemon))}"
            )
            self.heap_line("Kotlin daemon", daemon, heaps[daemon.pid])
            log = find_log(files.get(daemon.pid, []), KOTLIN_DAEMON_LOG)
            silent_minutes = self.log_line(log)
            if silent_minutes is not None and silent_minutes >= SILENT_LOG_MINUTES and daemon.cpu >= 50:
                self.warnings.append(
                    f"Kotlin daemon {daemon.pid} has logged nothing for {silent_minutes:.0f} min"
                    f" while using {daemon.cpu:.0f}% CPU."
                )
            gradle_peers = machine.peers_of_kind(daemon, "gradle-daemon")
            if gradle_peers:
                self.add("Clients: Gradle daemons " + ", ".join(map(str, gradle_peers)), indent=11)
            if len(gradle_peers) > 1:
                self.warnings.append(
                    f"Kotlin daemon {daemon.pid} serves {len(gradle_peers)} Gradle daemons;"
                    " one full heap stalls all of their builds."
                )

    def heap_line(self, kind: str, process: Process, result: CommandResult) -> None:
        regions = parse_heap_info(result.output)
        if not regions:
            reason = result.error or next(
                (line.strip() for line in result.output.splitlines()[1:] if line.strip()), "no heap data"
            )
            self.add(f"Heap: unavailable ({clip(reason, 120)})", indent=11)
            return
        summary = ", ".join(
            f"{region.name} {human(region.used)} of {human(region.committed)} ({percent(region.used, region.committed)}%)"
            for region in regions
        )
        full = any(region.is_full for region in regions)
        self.add(f"Heap: {summary}" + ("  FULL" if full else ""), indent=11)
        # An idle JVM may sit on a full old generation until it next allocates; only a busy one is stuck.
        if full and process.cpu >= 50:
            self.warnings.append(
                f"{kind} {process.pid} has a full heap at {process.cpu:.0f}% CPU;"
                " it is likely stuck in garbage collection."
            )

    def log_line(self, path: Path | None) -> float | None:
        """Print the log's last line; returns the minutes since the log last changed."""
        if path is None:
            self.add("Log: not found among open files", indent=11)
            return None
        try:
            silent_minutes = (time.time() - path.stat().st_mtime) / 60
            line = last_line(path)
        except OSError as error:
            self.add(f"Log: unreadable ({error})", indent=11)
            return None
        self.add(f"Log: {shorten(str(path))}", indent=11)
        self.add(f"Last line {silent_minutes:.0f} min ago: {clip(line, 120) or '(empty)'}", indent=11)
        return silent_minutes

    def other_processes(self) -> None:
        self.section("Other build processes")
        workers = self.machine.of_kind("gradle-worker")
        self.add(f"Gradle workers: {len(workers)}, {human(sum(self.machine.memory(worker) for worker in workers))}")
        counts = ", ".join(
            f"{KIND_LABELS.get(kind, kind)}: {len(self.machine.of_kind(kind))}" for kind in (*NATIVE_TOOLS, "konan")
        )
        self.add(counts)

    def render(self) -> str:
        machine = self.machine
        memory = run(["sysctl", "-n", "hw.memsize"]).output.strip() if machine.is_macos else ""
        total = human(int(memory)) if memory.isdigit() else None
        header = (
            f"Build stall report, {time.strftime('%Y-%m-%d %H:%M:%S')}, {sys.platform},"
            f" {os.cpu_count()} cores" + (f", {total} RAM" if total else "")
        )
        warnings = [f"  - {warning}" for warning in self.warnings] or ["  None"]
        return "\n".join([header, "", "Warnings", *warnings, *self.lines]) + "\n"


def parse_arguments(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    parse_arguments(sys.argv[1:] if argv is None else argv)
    if sys.platform != "darwin" and not sys.platform.startswith("linux"):
        print("ERROR: The build stall report supports macOS and Linux.", file=sys.stderr)
        return 1
    report = Report(Machine())
    report.memory()
    report.top_processes()
    report.jvms()
    report.other_processes()
    sys.stdout.write(report.render())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
