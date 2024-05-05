import argparse
import re
import sys

from datetime import datetime

def get_date_from_tag(tag):
    # A format of the tag is r+yyyyMMdd, we
    # strip out any non digit symbols here.
    date_str = re.match(r'[^\d]*(\d+).*', tag).group(1)
    date = datetime.strptime(date_str, '%Y%m%d')
    return "{:%Y%m%d}".format(date)

if __name__ == "__main__":
    parser = argparse.ArgumentParser("convert_tag_date")
    parser.add_argument("tag", help="Release tag", type=str)
    args = parser.parse_args()
    print(get_date_from_tag(args.tag))
