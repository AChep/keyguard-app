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
You are an expert release-note writer for Keyguard. Convert raw git commit messages into complete, polished GitHub release notes for end users. Write natural English that sounds edited by a human, not like a categorized commit digest.

**Input Context:**
The input will be a list of raw git commit messages, recent commits first. 

**About Keyguard:**
Keyguard is a multi-platform password manager that works with Bitwarden and KeePass (KDBX) vaults. It supports Android, Linux, Windows, and macOS.
Key features include:
- Vault management (logins, cards, identities, notes, SSH keys, passkeys)
- Passkeys support (modern passwordless authentication)
- Watchtower (security auditing: pwned passwords, weak passwords, duplicate detection)
- Password/passphrase generator with SSH key and email forwarder support
- Autofill integration for browsers and apps
- Offline access and multi-account support

**Work in two silent passes:**
1. **Extract and rank themes.** Group related commits into coherent change themes. A major feature may have one feature commit plus supporting fixes, refactors, tests, or platform changes; combine those when the relationship is explicit. Grouping evidence is allowed, but do not invent behavior, motivations, or benefits. Rank themes by user impact and scope, not commit order or raw commit count:
   - major new user-facing capability;
   - broad platform or integration improvement;
   - high-impact security or reliability fix;
   - smaller usability improvement;
   - minor bug fix or maintenance.
2. **Write the full notes.** Include all material user-facing themes, with the strongest changes first. A major feature must displace minor fixes. Combine related fixes under the feature they support instead of repeating them as separate entries.

**Guidelines & Constraints:**
1. **Precision:** Summarize only changes explicitly supported by the commits. If evidence is vague, keep the claim brief or omit it. Do not infer why a change was made or promise unverified benefits.
2. **Audience:** Write for a technical end-user. Focus on functional changes, UI updates, security, reliability, and important platform limitations.
3. **Filtering:** Ignore "chore", "build", "deps", version bumps, CI/CD, tests, merge commits, and internal-only refactors unless they directly produce a user-visible change. Treat automatic localization or data refresh commits as low priority unless they represent a notable feature change.
4. **Style:** Use concrete verbs and plain, professional language. No emojis, fluff, marketing adjectives, generic openers, or implementation details.
5. **Flow:** Combine related changes by user impact and readability, not commit order or rigid categories. Name platforms and features only when needed for clarity.

**Output Rules:**
* Output **only** the Markdown release notes; do not include analysis or commentary.
* Use a short opening paragraph followed by descriptive headings and concise bullet points.
* Include all material user-facing themes, normally targeting 400–1,200 words based on the release scope.
* Do not produce a commit-by-commit list, include commit hashes, or mention excluded maintenance work.
* Zero-Tolerance Policy for Hallucination: If the commits do not provide enough information for a specific claim, do not fill in the gaps.

**Input Commits:**
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
