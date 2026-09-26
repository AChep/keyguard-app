#!/usr/bin/env python3
"""
Release Changelog Summarizer using Google Gemini AI.

Reads a text file containing commit messages, generates full release notes,
and derives a concise app-store summary from those notes in a second pass.

Dependencies:
    pip install google-genai
"""

import argparse
import logging
from pathlib import Path

from google import genai
from google.genai import types

# --- Configuration Constants ---
MODEL_NAME = "gemini-2.5-pro"
LOG_FORMAT = "%(asctime)s - %(levelname)s - %(message)s"
CHANGELOG_SUMMARY_LENGTH_LIMIT = 480

FULL_PROMPT_TEMPLATE = """
You write the GitHub release notes for Keyguard from raw git commit messages. The reader is a technical end user who wants to know what changed, not how it was implemented.

**Input**
A list of raw git commit messages, newest first. Most follow the `type(scope): subject #issue` convention.

**About Keyguard**
Keyguard is a multi-platform password manager for Bitwarden and KeePass (KDBX) vaults. It runs on Android, Wear OS, Linux, Windows, and macOS.
Feature areas: vault items (logins, cards, identities, notes, SSH keys, GPG keys, passkeys), Watchtower security audits, password and passphrase generator, autofill, SSH and GPG agents, multi-account and offline access.

**Steps**
1. Select. Keep commits that change what a user sees or how the app behaves: feat, improvement, fix, perf, security. Drop chore, build, deps, ci, tests, docs, merge commits, version bumps, CLA signatures, automatic localization or data updates, and refactors, unless the message states a user-visible effect.
2. Group. Merge commits that touch the same feature into one theme. A feature commit plus its follow-up fixes is one theme. When the feature is new in this release, describe only the feature and drop its fixes: the user never saw the bugs. Two unrelated fixes on the same platform are two items.
3. Rank. Order themes by user impact: new capabilities, then broad platform or integration changes, then security and reliability fixes, then smaller improvements, then minor fixes.
4. Write the notes.

**Content rules**
- Say only what the commits say. Do not add purpose, motivation, benefit, or consequence. "More detailed error message when opening a URI fails" is fine. "... to help diagnose the problem" is not, unless the commit says so.
- Do not compare with other software or describe how other apps behave.
- Do not expand acronyms or explain terms the commit does not explain. Keep GPG, KDBX, VKS, IPC, URI as written.
- Describe the change in behavior, not the code. If a fix names only internals (threads, dispatchers, transports, string resources) and states no symptom, name the affected action and nothing else: "Fixed copying on macOS." Omit it when the affected action is unclear.
- Do not include commit hashes, issue numbers, author names, or mentions of excluded work.
- Name a platform only when the change is specific to it.

**Format**
- Markdown. No title, version number, or date; the release page already has them.
- Optional one-sentence lead naming the headline change. Skip it when the release has no standout feature, and do not repeat the lead as a bullet.
- `###` headings named after a feature area or platform, sentence case, 2 to 5 of them. A heading needs at least two bullets. Standalone new features go under "New" as the first section; other single items go under "Fixes" or "Other" at the end. For a release of only a few fixes, use one bullet list with no headings.
- One bullet per change, one sentence, two at most. No nested bullets, bold, tables, emojis, or closing summary.
- State the new behavior or the fixed problem directly, in present tense: "KDBX databases with a key file set no longer require a password." Bullets may start with "Added" or "Fixed"; do not narrate other changes in past tense ("Rejected invalid responses"). Not "You can now ..." or "We have ...".
- Length scales with the release: roughly one bullet per kept commit after merging. A dozen commits should fit in about 150 words; a large release should stay under 600.
- Output only the release notes.

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
        self.client = genai.Client(api_key=api_token)
        self.model_name = model_name

    def _generate(self, prompt: str, output_name: str) -> str:
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
    parser.add_argument("--token", type=str, required=True, help="Gemini API Token")
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
    args = parser.parse_args()

    if not args.commit_file.exists():
        logger.error(f"File not found: {args.commit_file}")
        return

    commit_text = args.commit_file.read_text(encoding="utf-8")
    if not commit_text.strip():
        logger.error("Commit messages file is empty.")
        return

    summarizer = GeminiSummarizer(api_token=args.token)
    full = summarizer.generate_full_notes(commit_text)
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
