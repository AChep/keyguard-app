#!/usr/bin/env python3
"""
Release Changelog Summarizer using Google Gemini AI.

Reads a text file containing commit messages and generates
a concise, user-facing changelog summary.

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

PROMPT_TEMPLATE = """
You are an expert release-note writer. Convert raw git commit messages into concise, fluent app-store release notes for Keyguard. Write natural English that sounds edited by a human, not like a categorized digest.

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

**Guidelines & Constraints:**
1. **Precision:** Summarize only the explicit changes mentioned in the commits. Do not infer features, guess "why" changes were made, or elaborate on potential benefits. If the commit is vague, keep the summary brief.
2. **Audience:** Write for a technical end-user. Focus on functional changes, UI updates, and bug fixes.
3. **Filtering:** Strictly ignore non-functional commits: "chore", "build", "deps", "version bumps", "CI/CD", and merge commits; do not mention them directly or indirectly. Treat commits marked as "auto" (localization/watchtower) as lowest priority; include them only if they represent a notable user-facing change.
4. **Prioritization:** Lead with the most notable user-facing changes. Prefer features before fixes when it reads naturally, but do not force category transitions.
5. **Style:** Use concrete verbs and plain, professional language. No emojis. No fluff, marketing adjectives (e.g., "exciting," "better"), or generic openers like "This update brings", "This release includes", or "We've improved".
6. **Flow:** Combine related changes by user impact and readability, not strictly by commit order, platform labels, or feature buckets. Name platforms and features only when needed for clarity.
7. **Format:** Prefer 2-4 sentences in a single cohesive paragraph. No lists or bullet points. You may use a colon and a semicolon sparingly.

**STRICT Output Rules:**
* Output **ONLY** the release note text.
* Do not include introductory text (e.g., "Here is your changelog").
* Do not include concluding text.
* Do not use markdown code blocks or quotes. Start directly with the first word of the changelog.
* Limit the output by 500 characters at MAX.
* Zero-Tolerance Policy for Hallucination: If the commits do not provide enough information for a specific feature, do not fill in the gaps.

**Input Commits:**
```
{commit_text}
```
"""

logging.basicConfig(level=logging.INFO, format=LOG_FORMAT, datefmt="%H:%M:%S")
logger = logging.getLogger(__name__)


class GeminiSummarizer:
    def __init__(self, api_token: str, model_name: str = MODEL_NAME):
        self.client = genai.Client(api_key=api_token)
        self.model_name = model_name

    def summarize(self, commit_text: str) -> str:
        prompt = PROMPT_TEMPLATE.format(commit_text=commit_text)
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
        return text.strip()


def main():
    parser = argparse.ArgumentParser(
        description="Generate a release changelog summary from commit messages using Gemini AI."
    )
    parser.add_argument("commit_file", type=Path, help="Path to the text file with commit messages")
    parser.add_argument("--token", type=str, required=True, help="Gemini API Token")
    parser.add_argument("--output", type=Path, help="Optional output file path; prints to stdout if omitted")
    args = parser.parse_args()

    if not args.commit_file.exists():
        logger.error(f"File not found: {args.commit_file}")
        return

    commit_text = args.commit_file.read_text(encoding="utf-8")
    if not commit_text.strip():
        logger.error("Commit messages file is empty.")
        return

    summarizer = GeminiSummarizer(api_token=args.token)
    summary = summarizer.summarize(commit_text)

    if args.output:
        args.output.write_text(summary + "\n", encoding="utf-8")
        logger.info(f"Summary saved to {args.output}")
    else:
        print(summary)


if __name__ == "__main__":
    main()
