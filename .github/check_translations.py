#!/usr/bin/env python3
"""
Android Strings Proofreader using Google Gemini AI.

This script parses Android XML string resources, compares a translated file 
against a source (English) file, and uses the Gemini API to identify 
critical and major localization errors.

Dependencies:
    pip install google-genai
"""

import argparse
import json
import logging
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Dict, List, Any, Optional

from google import genai
from google.genai import types

# --- Configuration Constants ---
MODEL_NAME = 'gemini-2.5-flash'
BATCH_SIZE = 80
RATE_LIMIT_DELAY = 1.0  # Seconds between batches
LOG_FORMAT = '%(asctime)s - %(levelname)s - %(message)s'

# Namespaces commonly used in Android strings
NS_XLIFF = "urn:oasis:names:tc:xliff:document:1.2"

PROMPT_TEMPLATE = """
You are a Senior Lead Localization Engineer. 
Review the following Android app translations from English to {target_language}.

Objective: Identify CRITICAL and MAJOR errors only. If a translation is acceptable but "ugly" or stylistic, IGNORE IT.

CORE RULES:
1. Source is Truth: Assume the Source text is correct.
2. Ignore Style: Do not report synonyms, word order preferences, or register changes unless they alter the fundamental meaning.
3. Strict XML Compliance: Android XML requires specific escaping.

CRITICAL ERRORS
1. Broken Variables: Placeholders (e.g. %1$s) are missing, altered, or reordered without indices.
2. Broken Tags: XML tags (<xliff:g>, <b>, <u>) are missing, malformed, or translated.
3. Escaping Errors: If the source uses escaped characters (e.g., \' for apostrophe), the translation MUST also use them. (e.g., Source: It\'s -> Target: C'est is a CRITICAL ERROR; it must be C\'est).
4. Duplication: The text is obviously repeated (e.g., "Save Save").

MAJOR ERRORS (Accuracy/User Trust)
1. Meaning Reversal: The text says the opposite (e.g., "Enable" -> "Disable").
2. Functional mismatch: A noun is translated as a verb or vice versa in a way that breaks the UI context.
3. Untranslated Content: Text remains in English (excluding brand names like 'Keyguard', technical acronyms, or proper nouns).
4. Hallucination: The translation invents rules or numbers not in the source.

Here is the input data:
```json
{batch_json}
```

Output Format (JSON):
Return a list of objects ONLY for items with MAJOR/CRITICAL errors.
[
  {{
    "id": "string_name",
    "source": "Original translation",
    "suggestion": "Corrected translation",
    "reason": "Detailed explanation of the issue, in English"
  }}
]
"""

logging.basicConfig(level=logging.INFO, format=LOG_FORMAT, datefmt='%H:%M:%S')
logger = logging.getLogger(__name__)


class AndroidStringParser:
    """Handles parsing of Android strings.xml files."""

    @staticmethod
    def get_inner_xml(element: ET.Element) -> str:
        """
        Extracts the inner content of an XML element, preserving child tags
        and mixed content (text + tags).
        """
        text = (element.text or "")
        for child in element:
            text += ET.tostring(child, encoding='unicode')
            text += (child.tail or "")
        return text.strip()

    @classmethod
    def parse(cls, file_path: Path) -> Dict[str, str]:
        """
        Parses an XML file and returns a dictionary of {name: content}.
        Ignores strings marked as translatable="false".
        """
        if not file_path.exists():
            logger.error(f"File not found: {file_path}")
            return {}

        try:
            # Register namespaces to prevent 'ns0:' prefixes in output
            ET.register_namespace('xliff', NS_XLIFF)
            tree = ET.parse(file_path)
            root = tree.getroot()

            strings_dict = {}
            for child in root:
                if child.tag == 'string':
                    name = child.get('name')
                    translatable = child.get('translatable')
                    
                    if name and translatable != 'false':
                        strings_dict[name] = cls.get_inner_xml(child)
            
            return strings_dict

        except ET.ParseError as e:
            logger.error(f"XML Parsing Error in {file_path}: {e}")
            return {}
        except Exception as e:
            logger.error(f"Unexpected error reading {file_path}: {e}")
            return {}


class GeminiProofreader:
    """Manages interactions with the Google Gen AI SDK."""

    def __init__(self, api_token: str, model_name: str = MODEL_NAME):
        self.client = genai.Client(api_key=api_token)
        self.model_name = model_name

    def check_batch(self, batch: List[Dict[str, Any]], target_language: str) -> List[Dict[str, Any]]:
        """
        Sends a batch of strings to Gemini for proofreading.
        """
        prompt = PROMPT_TEMPLATE.format(
            target_language=target_language,
            batch_json=json.dumps(batch, indent=2, ensure_ascii=False)
        )

        try:
            response = self.client.models.generate_content(
                model=self.model_name,
                contents=prompt,
                config=types.GenerateContentConfig(
                    response_mime_type='application/json'
                )
            )

            text_response = response.text

            # Sanitize response if the model wraps JSON in markdown code blocks
            if text_response.startswith("```json"):
                text_response = text_response[7:]
            if text_response.endswith("```"):
                text_response = text_response[:-3]

            result = json.loads(text_response.strip())
            
            # Ensure result is a list
            if isinstance(result, list):
                return result
            else:
                logger.warning("API returned valid JSON but not a list.")
                return []

        except json.JSONDecodeError:
            logger.error("Failed to decode JSON response from Gemini.")
            logger.debug(f"Raw response: {text_response}")
            return []
        except Exception as e:
            logger.error(f"API Error processing batch: {e}")
            return []


def main():
    parser = argparse.ArgumentParser(
        description="Proofread Android XML translations using Gemini AI."
    )
    parser.add_argument("source_file", type=Path, help="Path to source (English) strings.xml")
    parser.add_argument("translated_file", type=Path, help="Path to translated strings.xml")
    parser.add_argument("language", type=str, help="Target language")
    parser.add_argument("--token", type=str, required=True, help="Gemini API Token")
    parser.add_argument("--output", type=Path, default=Path("proofread_report.json"), 
                        help="Output JSON file path (default: proofread_report.json)")

    args = parser.parse_args()

    # 1. Parse Files
    logger.info("Parsing source file...")
    source_strings = AndroidStringParser.parse(args.source_file)
    
    logger.info("Parsing translated file...")
    translated_strings = AndroidStringParser.parse(args.translated_file)

    if not source_strings or not translated_strings:
        logger.error("Aborting due to empty or invalid files.")
        return

    # 2. Prepare Items
    items_to_check = []
    for key, val in translated_strings.items():
        if key in source_strings:
            items_to_check.append({
                "id": key,
                "source": source_strings[key],
                "translation": val
            })

    total_items = len(items_to_check)
    logger.info(f"Found {total_items} items to proofread.")
    
    if total_items == 0:
        logger.info("No matching strings found between files.")
        return

    # 3. Initialize Gemini Client
    proofreader = GeminiProofreader(api_token=args.token)
    logger.info(f"Starting Proofread ({args.language}) using {MODEL_NAME}...")

    # 4. Process in Batches
    all_issues = []
    total_batches = (total_items + BATCH_SIZE - 1) // BATCH_SIZE

    for i in range(0, total_items, BATCH_SIZE):
        batch = items_to_check[i : i + BATCH_SIZE]
        current_batch = (i // BATCH_SIZE) + 1
        
        logger.info(f"Processing batch {current_batch}/{total_batches} ({len(batch)} items)...")
        
        issues = proofreader.check_batch(batch, args.language)
        if issues:
            all_issues.extend(issues)
        
        # Rate limiting
        time.sleep(RATE_LIMIT_DELAY)

    # 5. Report Results
    logger.info("-" * 40)
    logger.info("PROOFREADING COMPLETE")
    logger.info("-" * 40)

    if not all_issues:
        logger.info("Great news! No critical or major issues found.")
        # Create empty report file for consistency
        with open(args.output, "w", encoding='utf-8') as f:
            json.dump([], f)
    else:
        logger.warning(f"Found {len(all_issues)} issues.")
        for issue in all_issues:
            key = issue.get('id', 'unknown')
            suggestion = issue.get('suggestion', 'N/A')
            reason = issue.get('reason', 'N/A')
            original = translated_strings.get(key, "N/A")
            
            print(f"\nKEY: {key}")
            print(f"Original:   {original}")
            print(f"Suggestion: {suggestion}")
            print(f"Reason:     {reason}")

        # Save to file
        with open(args.output, "w", encoding='utf-8') as f:
            json.dump(all_issues, f, indent=2, ensure_ascii=False)
        logger.info(f"\nFull report saved to {args.output}")


if __name__ == "__main__":
    main()