#!/usr/bin/env python3

import argparse
import pathlib
import re
import os
import glob
import xml.etree.ElementTree as ET
from abc import ABC, abstractmethod
from typing import Iterable


REPORT_TYPES = ("android_lint", "ktlint", "licensee")


def glob_files(pattern: str) -> list[pathlib.Path]:
    return [
        pathlib.Path(path)
        for path in glob.glob(pattern, recursive=True)
        if os.path.isfile(path)
    ]


def _strip_ansi(text: str) -> str:
    # Matches typical terminal ANSI color escape sequences.
    return re.sub(r"\x1b\[[0-9;]*m", "", text)


class Report(ABC):

    @abstractmethod
    def load(self, path: pathlib.Path) -> None:
        """Load report data from a single file."""
        raise NotImplementedError

    @abstractmethod
    def report(self, working_dir: pathlib.Path) -> None:
        """Print the collected report data."""
        raise NotImplementedError


class AndroidLintReport(Report):

    def __init__(self) -> None:
        self._path: pathlib.Path | None = None

    def load(self, path: pathlib.Path) -> None:
        self._path = path

    def report(self, working_dir: pathlib.Path) -> None:
        if self._path is None:
            return

        try:
            tree = ET.parse(self._path)
        except (OSError, ET.ParseError) as exc:
            print(f"!! Failed to parse Android Lint report {self._path}: {exc}")
            return

        root = tree.getroot()

        # Find all 'issue' tags directly under the root.
        error_issues: list[ET.Element] = [
            issue
            for issue in root.findall("issue")
            if issue.get("severity") == "Error"
        ]
        if not error_issues:
            return

        for issue in error_issues:
            issue_id = issue.get("id", "N/A")
            message = issue.get("message", "N/A")

            location = issue.find("location")
            location_str = "Location not available"
            if location is not None:
                file_path = location.get("file", "N/A").replace(str(working_dir), "")
                line_num = location.get("line", "N/A")
                col_num = location.get("column", "N/A")
                location_str = f"{file_path}:{line_num}:{col_num}"

            print(f"- {location_str.strip()} [{issue_id}] {message}")


class KtlintReport(Report):

    def __init__(self) -> None:
        self._path: pathlib.Path | None = None

    def load(self, path: pathlib.Path) -> None:
        self._path = path

    def report(self, working_dir: pathlib.Path) -> None:
        if self._path is None:
            return

        try:
            content = self._path.read_text(encoding="utf-8", errors="replace").strip()
        except OSError as exc:
            print(f"!! Failed to read {self._path}: {exc}")
            return

        content = _strip_ansi(content)
        # ktlint output often ends with a summary block; keep only per-file lines.
        content = "\n".join([line for line in content.splitlines() if line.startswith("/")])
        # simplify the paths
        content = content.replace(str(working_dir), "")

        if content:
            print(content)

class LicenseeReport(Report):

    def __init__(self) -> None:
        self._path: pathlib.Path | None = None

    def load(self, path: pathlib.Path) -> None:
        self._path = path

    def report(self, working_dir: pathlib.Path) -> None:
        if self._path is None:
            return

        try:
            content = self._path.read_text(encoding="utf-8", errors="replace").strip()
        except OSError as exc:
            print(f"!! Failed to read {self._path}: {exc}")
            return

        coordinate_re = re.compile(r"^([\w.-]+):([\w.-]+)(?::([\w.-]+))?(?::([\w.-]+))?:([^:\s]+)$")
        prefix = " - ERROR:"
        last_coordinate: str | None = None
        for line in content.splitlines():
            if coordinate_re.match(line):
                last_coordinate = line
            elif line.startswith(prefix):
                text = line[len(prefix):].strip()
                if last_coordinate is None:
                    continue
                print(f"- {last_coordinate} {text}")


def load_globs(report_type: str, base_dir: pathlib.Path) -> list[str]:
    match_file = base_dir / f"glob_{report_type}.txt"
    if not match_file.is_file():
        raise FileNotFoundError(f"Match file not found: {match_file}")

    patterns: list[str] = []
    with match_file.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            patterns.append(line)
    return patterns


def collect_report_files(patterns: Iterable[str]) -> list[pathlib.Path]:
    matched: list[pathlib.Path] = []
    for pattern in patterns:
        matched.extend(glob_files(pattern))

    # Deduplicate while preserving order
    seen: set[pathlib.Path] = set()
    unique: list[pathlib.Path] = []
    for path in matched:
        if path not in seen:
            seen.add(path)
            unique.append(path)
    return unique


REPORTS_BY_TYPE: dict[str, type[Report]] = {
    "android_lint": AndroidLintReport,
    "ktlint": KtlintReport,
    "licensee": LicenseeReport,
}


def print_reports(files: Iterable[pathlib.Path], working_dir: pathlib.Path, report_type: str) -> None:
    report_cls = REPORTS_BY_TYPE.get(report_type)
    if report_cls is None:
        raise ValueError(f"Unsupported report type: {report_type}")

    for path in files:
        try:
            report: Report = report_cls()
            report.load(path)
            report.report(working_dir)
        except OSError as exc:
            print(f"!! Failed to read {path}: {exc}")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Collect log files for a given type and print their contents.",
    )
    parser.add_argument(
        "--type",
        required=True,
        choices=REPORT_TYPES,
        help="Type of report to collect logs for.",
    )
    args = parser.parse_args()

    working_dir = pathlib.Path(".").resolve()
    base_dir = pathlib.Path(__file__).resolve().parent
    patterns = load_globs(args.type, base_dir)
    files = collect_report_files(patterns)
    print_reports(files, working_dir, args.type)


if __name__ == "__main__":
    main()
