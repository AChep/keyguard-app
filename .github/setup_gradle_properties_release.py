import argparse
import re
import subprocess

from datetime import datetime
from convert_tag_date import get_date_from_tag

parser = argparse.ArgumentParser("setup_gradle_properties")
parser.add_argument("tag", help="Release tag", type=str)
args = parser.parse_args()

tag = args.tag
date = get_date_from_tag(tag)
ref = subprocess.check_output(['git', 'rev-parse', '--short', 'HEAD']).decode('utf-8').strip()

text = f"""
versionDate={date}
versionRef={ref}
buildkonfig.flavor=release
"""

with open("gradle.properties", "a") as f:
    f.write(text)
