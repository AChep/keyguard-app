#!/usr/bin/env python3
"""Collect a release's commits as JSON and render its raw Markdown changelog.

Requires a complete checkout (actions/checkout's fetch-depth: 0). The default
base is the newest published GitHub release that precedes the head commit, not
the nearest Git tag, so a rerun or a hotfix release line resolves on its own.
For any other comparison, pass --base-ref and --head-ref. The JSON records both
resolved SHAs so the comparison can be reproduced.
"""

import argparse
import json
import logging
import os
import re
import subprocess
import unicodedata
import urllib.error
import urllib.request
from pathlib import Path

GITHUB_API_URL = "https://api.github.com"
GITHUB_API_TIMEOUT_SECONDS = 10
# The newest releases considered as the base. A hotfix line cut
# from an old release needs that release to be among them.
RELEASES_PAGE_SIZE = 30
BIDI_CONTROLS = frozenset("؜‎‏‪‫‬‭‮⁦⁧⁨⁩")

logger = logging.getLogger(__name__)


def normalize_subject(subject: str) -> str:
    """Keep a subject on one line without invisible direction overrides."""
    return "".join(
        " " if unicodedata.category(char) in {"Cc", "Zl", "Zp"} or char in BIDI_CONTROLS else char
        for char in subject
    )


def github_api_get(path: str, token: str | None):
    """Returns the parsed JSON of a GitHub REST API resource; errors propagate as urllib raises them."""
    headers = {"Accept": "application/vnd.github+json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(f"{GITHUB_API_URL}{path}", headers=headers)
    with urllib.request.urlopen(request, timeout=GITHUB_API_TIMEOUT_SECONDS) as response:
        return json.load(response)


def published_release_tags(repo: str, token: str | None) -> list[str]:
    """Returns the tags of the newest published releases, newest first, without drafts and prereleases."""
    try:
        releases = github_api_get(f"/repos/{repo}/releases?per_page={RELEASES_PAGE_SIZE}", token)
    except urllib.error.HTTPError as error:
        error.close()
        raise ValueError(
            f"Could not list the published releases of {repo} (HTTP {error.code}). "
            "Check repository access or specify --base-ref."
        ) from error
    if not isinstance(releases, list):
        raise ValueError("The releases response is not a list; specify --base-ref.")
    tags = []
    for release in releases:
        if not isinstance(release, dict) or release.get("draft") or release.get("prerelease"):
            continue
        tag = release.get("tag_name")
        if isinstance(tag, str) and tag:
            tags.append(tag)
    return tags


def git(directory: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    result = subprocess.run(
        ["git", *args], cwd=directory, capture_output=True, encoding="utf-8", errors="replace",
    )
    if check and result.returncode:
        raise ValueError(f"git {args[0]} failed: {result.stderr.strip()}")
    return result


def is_ancestor(directory: Path, ancestor_sha: str, descendant_sha: str) -> bool:
    result = git(directory, "merge-base", "--is-ancestor", ancestor_sha, descendant_sha, check=False)
    if result.returncode > 1:
        raise ValueError(f"Could not check release ancestry: {result.stderr.strip()}")
    return result.returncode == 0


def resolve_base(repo: str, token: str | None, head_sha: str, directory: Path) -> tuple[str, str]:
    """Returns the ref and SHA of the newest published release that precedes the head commit."""
    tags = published_release_tags(repo, token)
    for tag in tags:
        # Resolve the API's tag explicitly, even if a branch has the same name.
        ref = f"refs/tags/{tag}"
        resolved = git(
            directory, "rev-parse", "--verify", "--quiet", "--end-of-options", f"{ref}^{{commit}}", check=False,
        )
        if resolved.returncode:
            logger.warning("Skipping release %s: its tag is missing from the checkout.", tag)
            continue
        sha = resolved.stdout.strip()
        # The head's own release, on a rerun, and newer releases do not precede it.
        if sha != head_sha and is_ancestor(directory, sha, head_sha):
            return ref, sha
    raise ValueError(
        f"None of the {len(tags)} newest published releases of {repo} precede the head commit; "
        "specify --base-ref."
    )


def collect_release_commits(
    repo: str,
    base_ref: str | None,
    head_ref: str,
    token: str | None = None,
    directory: Path = Path("."),
) -> dict:
    if not re.fullmatch(r"[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("Repository must be owner/name.")
    if not head_ref or base_ref == "":
        raise ValueError("Release refs must not be empty.")
    if git(directory, "rev-parse", "--is-shallow-repository").stdout.strip() == "true":
        raise ValueError("A complete Git history is required; use actions/checkout with fetch-depth: 0.")

    head_sha = git(directory, "rev-parse", "--verify", "--end-of-options", f"{head_ref}^{{commit}}").stdout.strip()
    if base_ref is None:
        base_ref, base_sha = resolve_base(repo, token, head_sha, directory)
    else:
        base_sha = git(directory, "rev-parse", "--verify", "--end-of-options", f"{base_ref}^{{commit}}").stdout.strip()
        if not is_ancestor(directory, base_sha, head_sha):
            raise ValueError(f"Base {base_ref} is not an ancestor of {head_ref}; specify --base-ref on this release line.")

    # Two-dot ranges contain only commits introduced by this release. The
    # ancestry check makes this equivalent to the old action's three-dot range
    # for the normal release flow, and rejects comparisons across release lines.
    output = git(
        directory, "log", "--no-show-signature", "--encoding=UTF-8",
        "--format=%H%x00%s", "-z", f"{base_sha}..{head_sha}", "--",
    ).stdout
    fields = output.split("\0")
    if fields.pop() != "" or len(fields) % 2:
        raise ValueError("Unexpected git log record format.")
    commits = [
        {"sha": fields[index], "subject": normalize_subject(fields[index + 1])}
        for index in range(0, len(fields), 2)
    ]
    return {
        "repository": repo,
        "base": {"ref": base_ref, "sha": base_sha},
        "head": {"ref": head_ref, "sha": head_sha},
        "commits": commits,
    }


def render_markdown(changelog: dict) -> str:
    """Renders the commits in the shape of the historical entries of versions.json."""
    lines = []
    for commit in changelog["commits"]:
        sha = commit["sha"]
        url = f"https://github.com/{changelog['repository']}/commit/{sha}"
        lines.append(f"- [{sha[:9]}]({url}) - {commit['subject']}")
    return "\n".join(lines or ["No Changes."]) + "\n"


def main():
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY"), help="GitHub owner/name")
    parser.add_argument(
        "--base-ref", help="Base ref; defaults to the newest published release that precedes the head",
    )
    parser.add_argument(
        "--head-ref", default=os.environ.get("GITHUB_SHA") or "HEAD",
        help="Head ref; defaults to $GITHUB_SHA or HEAD",
    )
    parser.add_argument("--output", type=Path, required=True, help="Structured JSON output")
    parser.add_argument("--markdown-output", type=Path, required=True, help="Raw Markdown changelog output")
    args = parser.parse_args()
    if not args.repo:
        parser.error("--repo is required when $GITHUB_REPOSITORY is not set")

    try:
        changelog = collect_release_commits(args.repo, args.base_ref, args.head_ref, os.environ.get("GITHUB_TOKEN"))
        args.output.write_text(json.dumps(changelog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        args.markdown_output.write_text(render_markdown(changelog), encoding="utf-8")
    except (ValueError, OSError) as error:
        parser.exit(1, f"Could not generate release commits: {error}\n")
    logger.info(
        "Collected %d commits: %s..%s",
        len(changelog["commits"]), changelog["base"]["sha"], changelog["head"]["sha"],
    )


if __name__ == "__main__":
    main()
