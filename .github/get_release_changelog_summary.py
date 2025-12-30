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
You are an expert Release Note Writer. Your goal is to take a raw list of git commit messages and convert them into a friendly, human-readable changelog.

**Input Context:**
The input will be a list of raw git commit messages.

**Guidelines & Constraints:**
1. **Audience:** Write for the end-user. Focus on *what* they can do or *what* is fixed.
2. **Length:** Keep it concise. 2-5 sentences max. Shorter is better.
3. **Format:** Use a single cohesive paragraph. Do not use bullet points or lists.
4. **Style:** Professional but accessible. Do not use emojis.
5. **Filtering:** Ignore administrative commits (e.g., "chore", "build", "deps", "bump version", "CI/CD", merge commits). Group related changes together. Prioritize changes marked as "auto" very low, these are either localization changes or changes related to the suggestions of the watchtower. 

**STRICT Output Rules:**
* Output **ONLY** the release note text.
* Do not include introductory text (e.g., "Here is your changelog").
* Do not include concluding text.
* Do not use markdown code blocks or quotes. Start directly with the first word of the changelog.

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
