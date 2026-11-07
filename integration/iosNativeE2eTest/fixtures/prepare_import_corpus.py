#!/usr/bin/env python3
"""Copy the repository's pinned synthetic import corpus into test Files.

Checks every file against the corpus checksums before and after copying, and
refuses to overwrite existing Files documents. Writes the copied cases to
`import-corpus-fixtures-proof.json`.
"""

import argparse
import csv
import hashlib
import shutil

from lib.simulator import device_argument, files_directory
from lib import session

CORPUS = session.REPO / "common/src/desktopTest/resources/import-corpus"


def prepare(device):
    files = files_directory(device)
    checksums = {}
    for line in (CORPUS / "checksums.sha256").read_text().splitlines():
        digest, name = line.split(maxsplit=1)
        checksums[name.lstrip("*")] = digest
    with (CORPUS / "manifest.tsv").open() as source:
        cases = list(csv.DictReader(source, delimiter="\t"))
    proof = []
    for case in cases:
        source = CORPUS / case["resource"]
        data = source.read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        assert digest == checksums[case["resource"]], case["resource"]
        name = "e2e-corpus-" + case["id"] + source.suffix
        destination = files / name
        assert not destination.exists(), name
        shutil.copyfile(source, destination)
        assert hashlib.sha256(destination.read_bytes()).hexdigest() == digest
        proof.append(dict(case, file=name, bytes=len(data), sha256=digest))
    print(session.write_json(session.path("import-corpus-fixtures-proof.json"), proof))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    device_argument(parser)
    prepare(parser.parse_args().device)
