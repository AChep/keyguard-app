import argparse
import re

parser = argparse.ArgumentParser("convert_tag_release_name")
parser.add_argument("tag", help="Release tag", type=str)
args = parser.parse_args()

tag = args.tag
# A format of the tag is r00000000.0
tag_formatted = re.match(r'[^\d]*([\d\.]+).*', tag).group(1)

with open("gradle/libs.versions.toml", mode="r") as file:
    toml = file.read()
    version = re.search(r'appVersionName\s*=\s*"(.*)"', toml).group(1)

# Release name
out = f"v{version}-{tag_formatted}"
print(out)
