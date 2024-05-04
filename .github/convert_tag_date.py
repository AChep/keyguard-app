import argparse
import re

from datetime import datetime

parser = argparse.ArgumentParser("convert_tag_date")
parser.add_argument("tag", help="Release tag", type=str)
args = parser.parse_args()

tag = args.tag
# A format of the tag is r+yyyyMMdd, we
# strip out any non digit symbols here.
date_str = re.sub(r'[^\d]', '', tag)
date = datetime.strptime(date_str, '%Y%m%d')

# Release date.
out = "{:%Y%m%d}".format(date)
print(out)
