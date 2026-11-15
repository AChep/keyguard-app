"""
Shared helpers for the store listing scripts.

The listings are built from Android string resources that Crowdin
translates: `listing/common/<locale>/listing.xml` holds the texts shared
by the stores, and a template per store picks the texts it needs with
`<keyguard_text_res token="..." />` placeholders.

Only the standard library is used: the release and test jobs import this
module without installing packages.
"""

import re
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_DIR = Path(__file__).resolve().parent.parent
LISTING_COMMON_DIR = REPO_DIR / "listing" / "common"
LISTING_COMMON_FILE = "listing.xml"

LANGUAGE_BASE = "base"
# Crowdin's copy of the base texts in their own language. It holds the
# previous texts until the next sync, so the base texts are used instead.
LANGUAGE_BASE_COPY = "en-rUS"

_PLACEHOLDER = re.compile(r'<keyguard_text_res\s+token="([^"]+)"\s*/>')
_PLACEHOLDER_TAG = "keyguard_text_res"

_WHITESPACE = re.compile(r"[ \t\r\n]+")
_ESCAPE = re.compile(r"\\(u[0-9a-fA-F]{4}|.)", re.S)
_ESCAPE_CHARS = {
    "n": "\n",
    "t": "\t",
}


def _unescape_match(match):
    value = match.group(1)
    if len(value) == 5:
        return chr(int(value[1:], 16))
    return _ESCAPE_CHARS.get(value, value)


def unescape_android(text):
    """
    Reads a string the way Android does: collapses the whitespace, then
    resolves the backslash escapes. Crowdin writes the translations with
    these escapes, for example `l\\'app`.
    """
    text = _WHITESPACE.sub(" ", text).strip()
    return _ESCAPE.sub(_unescape_match, text)


def load_strings(path):
    """Loads an Android string resource file to a dictionary."""
    root = ET.parse(path).getroot()
    return {
        el.attrib["name"]: unescape_android("".join(el.itertext()))
        for el in root.iter("string")
    }


def load_locale_strings(directory, folder, filename):
    """Loads the strings of a locale, which might not have the file yet."""
    path = Path(directory) / folder / filename
    if not path.is_file():
        return {}
    return load_strings(path)


def list_locales(directory):
    """Lists the locale folders, without the base one and its copy."""
    return sorted(
        path.name
        for path in Path(directory).iterdir()
        if path.is_dir() and path.name not in (LANGUAGE_BASE, LANGUAGE_BASE_COPY)
    )


def merge(base, locale):
    """
    Returns the strings of a locale, taking the base text for each string
    that the locale does not have. Crowdin adds new strings to a locale
    only on its next sync, so a locale file can lag behind the base one.
    """
    return {key: locale.get(key) or value for key, value in base.items()}


def translated_locales(tokens, base, libraries):
    """Lists the locales that translate at least one of the strings."""
    return [
        language
        for language, library in libraries.items()
        if any(library[token] != base[token] for token in tokens)
    ]


def template_tokens(template):
    """Lists the strings that a template uses."""
    return _PLACEHOLDER.findall(template)


def render_text_template(template, strings):
    """
    Replaces the placeholders of a plain text template with the strings.
    The template owns the layout: each string is a single line.
    """

    def replace(match):
        token = match.group(1)
        if token not in strings:
            raise KeyError(f"The listing string '{token}' does not exist.")
        return strings[token]

    text = _PLACEHOLDER.sub(replace, template)
    if _PLACEHOLDER_TAG in text:
        raise ValueError("The template has a malformed placeholder.")
    return "\n".join(line.rstrip() for line in text.splitlines()).strip()
