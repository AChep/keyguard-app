"""Tests for the parsers and findings of the build stall report."""

from __future__ import annotations

import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from scripts.report_build_stall import (
    CommandResult,
    Machine,
    Report,
    classify,
    client_arguments,
    java_home,
    last_line,
    max_heap_flag,
    overridden_keys,
    parse_heap_info,
    parse_lsof_connections,
    parse_lsof_names,
    parse_memory_pressure,
    parse_meminfo,
    parse_ps,
    parse_size,
    parse_swapusage,
    parse_top,
    parse_vm_stat_compressed,
    peers_by_pid,
)


JBR = "/Users/me/.gradle/jdks/jbr-21/Contents/Home"
GRADLE_DAEMON = (
    f"{JBR}/bin/java --add-opens=java.base/java.lang=ALL-UNNAMED -Xmx4096m -Dfile.encoding=UTF-8"
    " -cp /Users/me/.gradle/wrapper/dists/gradle-9.7.1/lib/gradle-daemon-main-9.7.1.jar"
    " org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.7.1"
)
KOTLIN_DAEMON = (
    f"{JBR}/bin/java -cp /Users/me/.gradle/caches/kotlin-daemon-embeddable-2.4.20.jar -Xmx10g"
    " -XX:+UseParallelGC org.jetbrains.kotlin.daemon.KotlinCompileDaemon --daemon-logsPath /var/folders/T"
)
GRADLE_CLIENT = (
    "/Users/me/.sdkman/candidates/java/current/bin/java -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew"
    " -jar /work/keyguard/gradle/wrapper/gradle-wrapper.jar"
    " -Dorg.gradle.daemon.registry.base=/tmp/registry :common:desktopTest --tests Foo -Dorg.gradle.jvmargs=-Xmx8g -q"
)
PS_OUTPUT = "\n".join([
    f"34356     1 01:53:30 205.6 47456 {GRADLE_DAEMON}",
    f"78819     1 03:15:09 824.2 8774176 {KOTLIN_DAEMON}",
    f"28333 28332    45:24   0.0 40480 {GRADLE_CLIENT}",
    "51000 34356    01:00  50.0 1024 /opt/jdk/bin/java -cp x worker.org.gradle.process.internal.worker.GradleWorkerMain 'Gradle Test Executor 1'",
    "52000 34356    00:10  99.0 2048 /Users/me/.rustup/toolchains/stable/bin/rustc --crate-name foo",
    "53000     1    00:10   1.0 2048 /Users/me/.konan/dependencies/llvm/bin/clang++ -c foo.cpp",
    "not a process line",
])
TOP_OUTPUT = """Processes: 812 total, 4 running, 808 sleeping, 5120 threads
Load Avg: 89.10, 72.09, 56.12

PID    MEM   COMMAND
78819  11G   java
33996  9508M java
7690   6985M+ plugin-container
"""
LSOF_CONNECTIONS = """p28333
f12
n127.0.0.1:64517->127.0.0.1:50001
p34356
f40
n127.0.0.1:50001->127.0.0.1:64517
f41
n127.0.0.1:54063->127.0.0.1:17597
f42
n192.168.1.20:58189->104.18.39.21:443
p78819
f24
n127.0.0.1:17597->127.0.0.1:54063
p17178
f9
n[::127.0.0.1]:54419->[::127.0.0.1]:17597
p78819
f25
n[::127.0.0.1]:17597->[::127.0.0.1]:54419
"""
# The hung Kotlin daemon of 2026-10-22: the old generation filled its whole reserve.
HEAP_PARALLEL_FULL = """78819:
 PSYoungGen      total 2231296K, used 1527807K [0x00000071ab300000, 0x000000725fe80000, 0x0000007280800000)
  eden space 1527808K, 99% used [0x00000071ab300000,0x00000072086ffff8,0x0000007208700000)
 ParOldGen       total 6990848K, used 6990717K [0x0000007000800000, 0x00000071ab300000, 0x00000071ab300000)
  object space 6990848K, 99% used [0x0000007000800000,0x00000071ab2df658,0x00000071ab300000)
 Metaspace       used 151475K, committed 152192K, reserved 1245184K
"""
# Old generation at 99% of what it committed, but it can still grow into its reserve.
HEAP_PARALLEL_GROWING = """23537:
 PSYoungGen      total 2183168K, used 1214164K [0x0000007155d80000, 0x00000071ffd00000, 0x0000007200800000)
 ParOldGen       total 3714048K, used 3700000K [0x0000007000800000, 0x00000070e3300000, 0x0000007155d80000)
"""
HEAP_G1 = """34356:
 garbage-first heap   total 4194304K, used 2449708K [0x0000007000800000, 0x0000007100800000)
  region size 2048K, 38 young (77824K), 18 survivors (36864K)
 Metaspace       used 150000K, committed 151000K, reserved 1245184K
"""


class ParserTest(unittest.TestCase):
    def test_sizes_use_binary_units(self):
        self.assertEqual(parse_size("4096m"), 4096 * 1024**2)
        self.assertEqual(parse_size("10g"), 10 * 1024**3)
        self.assertEqual(parse_size("9508M+"), 9508 * 1024**2)
        self.assertEqual(parse_size("25600.00M"), 25600 * 1024**2)
        self.assertEqual(parse_size("0B"), 0)
        self.assertIsNone(parse_size("default"))

    def test_processes_are_classified_by_entry_point(self):
        kinds = {process.pid: process.kind for process in parse_ps(PS_OUTPUT)}
        self.assertEqual(kinds, {
            34356: "gradle-daemon",
            78819: "kotlin-daemon",
            28333: "gradle-client",
            51000: "gradle-worker",
            52000: "rustc",
            53000: "konan",
        })
        self.assertEqual(classify("/usr/bin/java -jar app.jar"), "")

    def test_ps_rss_is_converted_to_bytes(self):
        daemon = parse_ps(PS_OUTPUT)[1]
        self.assertEqual(daemon.rss_bytes, 8774176 * 1024)
        self.assertEqual(daemon.cpu, 824.2)
        self.assertEqual(daemon.elapsed, "03:15:09")

    def test_jvm_flags(self):
        self.assertEqual(max_heap_flag(GRADLE_DAEMON), "4096m")
        self.assertEqual(max_heap_flag(GRADLE_CLIENT), "64m")
        self.assertEqual(java_home(GRADLE_DAEMON), JBR)
        studio = "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
        self.assertEqual(java_home(f"{studio}/bin/java -Xmx2g Main"), studio)

    def test_client_arguments_split_tasks_from_properties(self):
        tasks, properties = client_arguments(GRADLE_CLIENT)
        self.assertEqual(tasks, ":common:desktopTest --tests Foo -q")
        self.assertEqual(properties, ["-Dorg.gradle.daemon.registry.base=/tmp/registry", "-Dorg.gradle.jvmargs=-Xmx8g"])
        self.assertEqual(
            overridden_keys(properties + ["-Dfile.encoding=UTF-8", "-Pkotlin.daemon.jvmargs=-Xmx6g"]),
            ["org.gradle.daemon.registry.base", "org.gradle.jvmargs", "kotlin.daemon.jvmargs"],
        )

    def test_top_rows_carry_the_footprint(self):
        self.assertEqual(parse_top(TOP_OUTPUT), [
            (78819, 11 * 1024**3, "java"),
            (33996, 9508 * 1024**2, "java"),
            (7690, 6985 * 1024**2, "plugin-container"),
        ])

    def test_macos_memory(self):
        swap = parse_swapusage("total = 33792.00M  used = 32835.31M  free = 956.69M  (encrypted)")
        self.assertEqual(swap, (33792 * 1024**2, int(32835.31 * 1024**2)))
        vm_stat = "Mach Virtual Memory Statistics: (page size of 16384 bytes)\nPages occupied by compressor:   1988160.\n"
        self.assertEqual(parse_vm_stat_compressed(vm_stat), 1988160 * 16384)
        self.assertEqual(parse_memory_pressure("System-wide memory free percentage: 39%\n"), 39)

    def test_linux_meminfo(self):
        values = parse_meminfo("MemTotal:       16384000 kB\nMemAvailable:    1024000 kB\nHugePages_Total: 0\n")
        self.assertEqual(values, {"MemTotal": 16384000 * 1024, "MemAvailable": 1024000 * 1024})

    def test_loopback_connections_resolve_to_peer_processes(self):
        connections = parse_lsof_connections(LSOF_CONNECTIONS)
        self.assertNotIn((34356, 58189, 443), connections)
        self.assertEqual(peers_by_pid(connections), {
            28333: {34356},
            34356: {28333, 78819},
            78819: {34356, 17178},
            17178: {78819},
        })

    def test_lsof_names_group_by_process(self):
        self.assertEqual(parse_lsof_names("p1\nfcwd\nn/work\np2\nfcwd\nn/tmp\n"), {1: ["/work"], 2: ["/tmp"]})

    def test_full_old_generation(self):
        regions = parse_heap_info(HEAP_PARALLEL_FULL)
        self.assertEqual([region.name for region in regions], ["PSYoungGen", "ParOldGen"])
        old = regions[1]
        self.assertEqual(old.reserved, old.committed)
        self.assertTrue(old.is_full)
        self.assertFalse(regions[0].is_full)

    def test_old_generation_with_room_to_grow_is_not_full(self):
        regions = parse_heap_info(HEAP_PARALLEL_GROWING)
        self.assertFalse(any(region.is_full for region in regions))

    def test_g1_heap(self):
        (heap,) = parse_heap_info(HEAP_G1)
        self.assertEqual(heap.name, "garbage-first heap")
        self.assertEqual(heap.reserved, 4194304 * 1024)
        self.assertFalse(heap.is_full)

    def test_last_line_skips_trailing_blank_lines(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "kotlin-daemon.log")
            path.write_text("first\n" + "x" * 10000 + "\nSEVERE: Exception\n\n")
            self.assertEqual(last_line(path), "SEVERE: Exception")
            path.write_text("")
            self.assertEqual(last_line(path), "")


class FakeMachine(Machine):
    """A machine state built from fixtures, so the report runs no commands."""

    def __init__(self, processes, peers, files, heaps):
        self.is_macos = True
        self.processes = processes
        self.by_pid = {process.pid: process for process in processes}
        self.top_rows = []
        self.footprints = {}
        self.peers = peers
        self.peers_error = None
        self.attach_env = {}
        self._files = files
        self._heaps = heaps

    def open_files(self, pids, cwd_only):
        return {pid: self._files.get(pid, []) for pid in pids}

    def heap(self, process):
        return CommandResult(self._heaps.get(process.pid, ""))


class FindingsTest(unittest.TestCase):
    def report(self) -> Report:
        idle_daemon = GRADLE_DAEMON.replace("-Xmx4096m", "-Xmx8g")
        processes = parse_ps(PS_OUTPUT) + parse_ps(
            f"33996 1 02:00:00 0.0 1024 {idle_daemon}\n"
            f"17178 1 01:00:00 1.0 1024 {GRADLE_DAEMON}\n"
        )
        home = Path.home()
        files = {
            34356: [f"{home}/.gradle/daemon/9.7.1/daemon-34356.out.log"],
            33996: ["/tmp/agent/gradle-daemon-registry/9.7.1/daemon-33996.out.log"],
        }
        peers = {
            28333: {34356},
            34356: {28333, 78819},
            17178: {78819},
            78819: {34356, 17178},
        }
        heaps = {78819: HEAP_PARALLEL_FULL, 34356: HEAP_G1}
        report = Report(FakeMachine(processes, peers, files, heaps))
        with mock.patch.dict(os.environ):
            os.environ.pop("GRADLE_USER_HOME", None)
            report.jvms()
        return report

    def test_warnings_name_each_way_builds_stall(self):
        warnings = "\n".join(self.report().warnings)
        self.assertIn("Kotlin daemon 78819 has a full heap at 824% CPU", warnings)
        self.assertIn("Kotlin daemon 78819 serves 2 Gradle daemons", warnings)
        self.assertIn("Gradle daemon 33996 uses a private registry (/tmp/agent/gradle-daemon-registry)", warnings)
        self.assertIn("1 idle Gradle daemon holds", warnings)
        self.assertIn("Gradle 9.7.1 daemons run with different heaps (4096m, 8g)", warnings)
        self.assertIn(
            "Gradle client 28333 overrides org.gradle.daemon.registry.base, org.gradle.jvmargs", warnings,
        )
        self.assertNotIn("Gradle daemon 34356 uses a private registry", warnings)

    def test_daemon_lines_show_state_and_peers(self):
        lines = "\n".join(self.report().lines)
        self.assertIn("34356  Gradle 9.7.1  -Xmx4096m", lines)
        self.assertIn("Connected: Gradle client 28333, Kotlin daemon 78819", lines)
        self.assertIn("Clients: Gradle daemons 17178, 34356", lines)
        self.assertIn("ParOldGen 6.7 GB of 6.7 GB (100%)  FULL", lines)


if __name__ == "__main__":
    unittest.main()
