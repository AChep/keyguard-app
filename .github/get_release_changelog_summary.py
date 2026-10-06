#!/usr/bin/env python3
"""
Release Changelog Summarizer using Google Gemini AI.

Reads a text file containing commit messages, enriches them with the titles of
the GitHub issues and pull requests they reference, generates full release
notes, and derives a concise app-store summary from those notes in a second pass.

Dependencies:
    pip install google-genai
"""

import argparse
import functools
import json
import logging
import os
import re
import urllib.request
from pathlib import Path

# --- Configuration Constants ---
MODEL_NAME = "gemini-3.8-flash"
LOG_FORMAT = "%(asctime)s - %(levelname)s - %(message)s"
CHANGELOG_SUMMARY_LENGTH_LIMIT = 480
GITHUB_API_URL = "https://api.github.com"
GITHUB_API_TIMEOUT_SECONDS = 10

NO_CHANGES_SENTINEL = "No user-facing changes."
SECTION_ORDER = ("New", "Improvements", "Security", "Fixes")
DATA_UPDATES_SECTION = "Improvements"
# Names of the `[AUTO] Update <name>` commits, mapped to what users know them as.
DATA_UPDATE_NAMES = {
    "localization library": "translations",
    "two-factor auth library": "the list of websites with two-factor authentication",
    "passkeys library": "the list of websites with passkeys",
    "gpm credential privileged apps json": "the list of browsers allowed to request passkeys",
    "public suffix list": "the public suffix list",
    "justdeleteme library": "account deletion links",
    "justgetmydata library": "data access request links",
}

# A line of the metcalfc/changelog-generator output: `- [hash](url) - subject`.
COMMIT_LINE_REGEX = re.compile(r"^- \[[0-9a-f]+\]\([^)]*\) - (?P<subject>.*)$")
# Only trailing references count, so "read entry #1 twice" is not an issue reference.
TRAILING_REFERENCES_REGEX = re.compile(r"(?:\s+\(?#\d+\)?)+\s*$")
MERGE_PULL_REQUEST_REGEX = re.compile(r"^Merge pull request #(?P<number>\d+) from (?P<owner>[^/\s]+)/")
DATA_UPDATE_REGEX = re.compile(r"^\[AUTO\] Update (?P<name>.+?)(?: \(#\d+\))?$")
CLA_SIGNATURE_REGEX = re.compile(r" has signed the CLA in ")
BULLET_REGEX = re.compile(r"^[*-]\s+")

FULL_PROMPT_TEMPLATE = """
You write the GitHub release notes for Keyguard from the commits of one release. The reader is a technical Keyguard user who wants to know what changed for them, not how it was implemented.

**About Keyguard**
Keyguard is a multi-platform password manager for Bitwarden and KeePass (KDBX) vaults. It runs on Android, Wear OS, and desktop: Windows, macOS, and Linux. Native iOS and macOS apps are experimental; commits scoped `Apple` or `iOS` are about them.
Feature areas: vault items (logins, cards, identities, notes, SSH keys, GPG keys, passkeys), Watchtower security reports, password and passphrase generator, autofill, auto-type, SSH and GPG agents, Send, attachments, backups, multi-account and offline access.

**Input**
One line per commit, newest first, mostly `type(scope): subject #issue`. When a commit references a GitHub issue or pull request, an indented line follows with the number, kind, labels, title, and, for outside contributors, the author:
- fix: Allow permissive Windows ancestor ACLs #1601
  - #1601 issue [bug]: [BUG] Critical error: Windows program fails to start post-update due to instance file access permissions
The reporter wrote the issue title: it names the symptom they saw or the feature they asked for. The commit says what was done. Describe a fix by the symptom in its issue. When the issue and the commit disagree (the issue asks for Windows, the commit is for macOS), follow the commit.

**Steps**
1. Select. Keep commits that change what a user sees or how the app behaves: feat, improvement, fix, perf, security, exp. Keep a chore or build commit only when it builds a new package format, installer, or CPU architecture, such as "Build Linux ARM64 .deb", or changes a minimum OS version; changes to checks, scripts, and CI never count. Drop everything else: tests, docs, refactors, CI and build checks, dependency and version bumps, and commits that change only internals (threads, sockets, sessions, streams, caches, error plumbing) without a symptom.
2. Group. One item per feature or problem. A feature commit and its follow-ups are one item; keep all their issue numbers. When a feature or app is new in this release, write one New item for it and drop all its other commits: users never saw the earlier state. For example, when the native iOS and macOS apps are added, every other `Apple` and `iOS` commit is dropped.
3. Classify each item. The commit type is a hint; the effect decides.
   - New: something users could not do before, including a new app, package, or CPU architecture.
   - Improvements: an existing feature works better or differently.
   - Security: protection against attackers or malicious input, or handling of secrets in memory or on disk. Only when the commit is clearly about this.
   - Fixes: something that did not work now does.

**Content rules**
- Say only what the commits and issue titles say. Do not add purpose, benefit, or consequence.
- Be literal about distribution: "Build MSIX for Microsoft Store" means an MSIX package was added, not that Keyguard is in the Microsoft Store.
- Keep acronyms and terms as written (GPG, KDBX, VKS, URI, S3). Do not expand or explain them.
- Describe behavior, not code: no class, function, library, or build-tool names.
- When a fix names only internals and neither the commit nor its issue states a symptom, name the affected feature: "macOS: Fixed copying." Omit the item when even the feature is unclear.
- When the input calls a feature experimental, say "experimental" in the bullet.
- Never mention commit hashes or dropped commits. Never write filler such as "various fixes".
- When no commit qualifies, output exactly: No user-facing changes.

**Format**
Output only Markdown in this shape:

This release adds <1-3 most important New items>.

> [!IMPORTANT]
> <only for removed features, changed defaults, raised minimum OS versions, or required user action>

### New
- <Prefix>: Added <feature> (#123).

### Improvements
- <Prefix>: <Thing> now <behavior> (#123).

### Security
- <Prefix>: <Thing> now <behavior>.

### Fixes
- <Prefix>: Fixed <symptom from the issue title> (#123).
- <Prefix>: Fixed <thing> so it <corrected behavior from the commit>.

Rules for this shape:
- Write the lead sentence only when the release has more than 15 items and at least one New item. It names only New items, never fixes or improvements. Otherwise start with the first heading.
- Write the alert only when it applies; it repeats an item that is also listed in its section.
- Use exactly these four headings, in this order. Skip empty ones. No other headings, even for one item.
- Every bullet starts with `- `, then one prefix and a colon, then one sentence ending with a period. One fact per bullet: no semicolons or lists of unrelated changes.
- Prefix: the platform when the change is specific to one: Android, Wear OS, Windows, macOS, Linux, Desktop (all desktop OSes), iOS; join two platforms with a comma: "macOS, Windows:". Otherwise one feature area from this list: Vault, Accounts, Unlock, Autofill, Auto-type, Passkeys, Watchtower, Generator, Send, Attachments, Backups, Sync, Bitwarden, KDBX, WebDAV, SSH agent, GPG, Export, UI.
- Inside a section, order by user impact, but keep bullets with the same prefix next to each other.
- Verbs: New bullets start with "Added". Improvements and Security describe the new behavior in present tense. Fixes start with "Fixed":
  - When the issue title names a symptom, write "Fixed <symptom>": "Windows: Fixed the app failing to start after an update (#1601)."
  - Otherwise restate the commit's corrected behavior as "Fixed <thing> so it <behavior>". Do not negate it yourself. "fix(Send): Keep the expiration date when editing" → "Send: Fixed editing a Send so it keeps the expiration date."
  - Never "You can now", "We have", "Improves", "Ensures".
- Put issue and pull request numbers before the final period: "(#1601)", "(#1585, #1607)". Copy them only from the item's own commits. Thank outside contributors listed as authors: "(#1621, thanks @Shimmerfly)". Items without numbers have no parentheses.
- No nested bullets, bold, tables, emojis, or closing text.

**Input commits**
```
{commit_text}
```
"""

SUMMARY_PROMPT_TEMPLATE = """
You are an expert app-store release-note editor for Keyguard. Compress the supplied full GitHub release notes into a concise app-store summary. The full notes are authoritative: preserve their facts and priorities, and do not introduce claims that are not present there.

**Prioritization:**
1. Lead with the most important new user-facing capability.
2. Prefer broad platform or integration improvements next.
3. Include a high-impact security or reliability fix only if it fits.
4. Omit minor fixes when space is limited. A major feature must displace them.

**Style and output:**
* Output only plain text: no headings, bullets, markdown, quotes, or commentary.
* Leave out issue numbers, @mentions, thanks, and the "Prefix:" labels; name a platform inside the sentence when it matters.
* Write 2–3 sentences in one paragraph using concrete verbs and professional language.
* Target 450–480 characters and never exceed 480 characters.
* Never end mid-sentence or with an ellipsis.
* Do not use generic openers, marketing adjectives, or unverified benefits.

**Full release notes:**
<full_notes>
{full_notes}
</full_notes>
"""

logging.basicConfig(level=logging.INFO, format=LOG_FORMAT, datefmt="%H:%M:%S")
logger = logging.getLogger(__name__)


@functools.cache
def describe_reference(repo: str, token: str | None, number: int) -> str | None:
    """Returns a one-line description of a GitHub issue or pull request, or None if it can't be fetched."""
    headers = {"Accept": "application/vnd.github+json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(f"{GITHUB_API_URL}/repos/{repo}/issues/{number}", headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=GITHUB_API_TIMEOUT_SECONDS) as response:
            issue = json.load(response)
    except Exception as e:
        logger.warning(f"Failed to fetch #{number}: {e}")
        return None

    kind = "pull request" if "pull_request" in issue else "issue"
    labels = [label["name"] for label in issue.get("labels", []) if label["name"] != "robot"]
    labels_text = f" [{', '.join(labels)}]" if labels else ""
    # Only outside contributors get credited.
    login = issue["user"]["login"]
    is_contributor = kind == "pull request" \
        and login.lower() != repo.split("/")[0].lower() \
        and not login.endswith("[bot]")
    author_text = f" by @{login}" if is_contributor else ""
    return f"#{number} {kind}{labels_text}{author_text}: {issue['title'].strip()}"


def prepare_commits(raw: str, repo: str, token: str | None) -> tuple[str, list[str]]:
    """
    Converts the raw changelog into the LLM input: drops the noise and appends
    the titles of referenced issues and pull requests. Returns the input and the
    names of the automatic data updates, which are listed by the script itself.
    """
    owner = repo.split("/")[0].lower()
    lines = []
    data_updates = []
    for raw_line in raw.splitlines():
        match = COMMIT_LINE_REGEX.match(raw_line)
        if not match:
            if raw_line.strip():
                lines.append(raw_line)
            continue

        subject = match["subject"].strip()
        data_update = DATA_UPDATE_REGEX.match(subject)
        if data_update:
            data_updates.append(data_update["name"])
            continue
        merge = MERGE_PULL_REQUEST_REGEX.match(subject)
        # Merges of our own branches duplicate the merged commits.
        if merge and merge["owner"].lower() == owner:
            continue
        if CLA_SIGNATURE_REGEX.search(subject):
            continue

        lines.append(f"- {subject}")
        numbers = []
        trailing_references = TRAILING_REFERENCES_REGEX.search(subject)
        if trailing_references:
            numbers += re.findall(r"#(\d+)", trailing_references.group())
        if merge:
            numbers.append(merge["number"])
        for number in dict.fromkeys(numbers):
            description = describe_reference(repo, token, int(number))
            if description:
                lines.append(f"  - {description}")
    return "\n".join(lines), list(dict.fromkeys(data_updates))


def format_data_updates(names: list[str]) -> str | None:
    lowercase_names = {name.lower() for name in names}
    items = [text for name, text in DATA_UPDATE_NAMES.items() if name in lowercase_names]
    items += [name for name in names if name.lower() not in DATA_UPDATE_NAMES]
    if not items:
        return None
    if len(items) == 1:
        listing = items[0]
    elif len(items) == 2:
        listing = f"{items[0]} and {items[1]}"
    else:
        listing = f"{', '.join(items[:-1])}, and {items[-1]}"
    return f"- Data: Updated {listing}."


def finalize_full_notes(text: str, data_updates: list[str]) -> str:
    """Normalizes the bullets, puts the sections in the canonical order, and appends the data updates."""
    if text.strip() == NO_CHANGES_SENTINEL:
        text = ""

    preamble = []
    sections: dict[str, list[str]] = {}
    current = preamble
    for line in text.strip().splitlines():
        if line.startswith("### "):
            current = sections.setdefault(line.removeprefix("### ").strip(), [])
        else:
            current.append(BULLET_REGEX.sub("- ", line))

    data_bullet = format_data_updates(data_updates)
    if data_bullet:
        sections.setdefault(DATA_UPDATES_SECTION, []).append(data_bullet)

    unknown_headings = [heading for heading in sections if heading not in SECTION_ORDER]
    if unknown_headings:
        logger.warning(f"Unexpected release notes headings: {unknown_headings}")

    blocks = ["\n".join(preamble).strip()]
    for heading in [*SECTION_ORDER, *unknown_headings]:
        lines = [line for line in sections.get(heading, []) if line.strip()]
        if lines:
            blocks.append("\n".join([f"### {heading}", *lines]))
    return "\n\n".join(block for block in blocks if block) or NO_CHANGES_SENTINEL


def validate_changelog_summary(text: str) -> str:
    text = text.strip()
    if len(text) > CHANGELOG_SUMMARY_LENGTH_LIMIT:
        raise ValueError(
            "Generated app-store summary exceeds "
            f"{CHANGELOG_SUMMARY_LENGTH_LIMIT} characters."
        )
    if text.endswith(("…", "...")):
        raise ValueError("Generated app-store summary ends with an ellipsis.")
    if not text.endswith((".", "!", "?")):
        raise ValueError("Generated app-store summary does not end with a complete sentence.")
    return text


class GeminiSummarizer:
    def __init__(self, api_token: str, model_name: str = MODEL_NAME):
        # Imported lazily so that --print-prompt works without the dependency.
        from google import genai

        self.client = genai.Client(api_key=api_token)
        self.model_name = model_name

    def _generate(self, prompt: str, output_name: str) -> str:
        from google.genai import types

        response = self.client.models.generate_content(
            model=self.model_name,
            contents=prompt,
            config=types.GenerateContentConfig(
                response_mime_type="text/plain"
            ),
        )
        text = response.text or ""
        if text.startswith("```"):
            text = text.split("\n", 1)[1]
        if text.endswith("```"):
            text = text.rsplit("\n", 1)[0]
        text = text.strip()
        if not text:
            raise RuntimeError(f"Gemini returned empty {output_name}.")
        return text

    def generate_full_notes(self, commit_text: str) -> str:
        prompt = FULL_PROMPT_TEMPLATE.format(commit_text=commit_text)
        return self._generate(prompt, output_name="full release notes")

    def summarize_full_notes(self, full_notes: str) -> str:
        prompt = SUMMARY_PROMPT_TEMPLATE.format(full_notes=full_notes)
        return self._generate(prompt, output_name="app-store summary")


def main():
    parser = argparse.ArgumentParser(
        description="Generate compressed and full release notes from commit messages using Gemini AI."
    )
    parser.add_argument("commit_file", type=Path, help="Path to the text file with commit messages")
    parser.add_argument("--token", type=str, help="Gemini API Token")
    parser.add_argument(
        "--repo",
        type=str,
        default=os.environ.get("GITHUB_REPOSITORY"),
        help="GitHub repository (owner/name) to look up referenced issues in; defaults to $GITHUB_REPOSITORY. "
             "Set $GITHUB_TOKEN to authenticate the lookups.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        help="Optional output file path for the compressed summary; prints it to stdout if omitted",
    )
    parser.add_argument(
        "--full-output",
        type=Path,
        help="Optional output file path for the full Markdown release notes",
    )
    parser.add_argument(
        "--print-prompt",
        action="store_true",
        help="Print the full release notes prompt and exit without calling Gemini",
    )
    args = parser.parse_args()
    if not args.repo:
        parser.error("--repo is required when $GITHUB_REPOSITORY is not set")
    if not args.token and not args.print_prompt:
        parser.error("--token is required")

    if not args.commit_file.exists():
        logger.error(f"File not found: {args.commit_file}")
        return

    commit_text = args.commit_file.read_text(encoding="utf-8")
    if not commit_text.strip():
        logger.error("Commit messages file is empty.")
        return

    commit_text, data_updates = prepare_commits(
        commit_text,
        repo=args.repo,
        token=os.environ.get("GITHUB_TOKEN"),
    )
    logger.info(f"Prepared commits:\n{commit_text}")
    logger.info(f"Data updates: {data_updates}")
    if args.print_prompt:
        print(FULL_PROMPT_TEMPLATE.format(commit_text=commit_text))
        return

    summarizer = GeminiSummarizer(api_token=args.token)
    full = summarizer.generate_full_notes(commit_text)
    full = finalize_full_notes(full, data_updates)
    summary = summarizer.summarize_full_notes(full)
    summary = validate_changelog_summary(summary)

    if args.output:
        args.output.write_text(summary + "\n", encoding="utf-8")
        logger.info(f"Summary saved to {args.output}")
    else:
        print(summary)

    if args.full_output:
        args.full_output.write_text(full + "\n", encoding="utf-8")
        logger.info(f"Full release notes saved to {args.full_output}")


if __name__ == "__main__":
    main()
