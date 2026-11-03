"""Check Kotlin-generated synthetic .env fixtures against real Node/Python consumers.

Install dotenv@16.6.1 and python-dotenv==1.2.2, then run after
:feature:environments:desktopTest. NODE_PATH may select a temporary npm prefix.
"""
import io
import json
from pathlib import Path
import subprocess
from dotenv import dotenv_values

fixture = json.loads(Path("feature/environments/build/dotenv-compatibility.json").read_text())
expected = fixture["values"]
assert dotenv_values(stream=io.StringIO(fixture["text"]), interpolate=False) == expected, "Python dotenv changed a value"
result = subprocess.run(
    ["node", "-e", "process.stdout.write(JSON.stringify(require('dotenv').parse(require('fs').readFileSync(0))))"],
    input=fixture["text"], capture_output=True, text=True, check=True,
)
assert json.loads(result.stdout) == expected, "Node dotenv changed a value"
print(f"Node and Python dotenv preserve all {len(expected)} fixture values")

# Exercise real named-pipe reads, not just each parser's in-memory API.
import os
import sys
import tempfile
import threading

if os.name != "nt":
    with tempfile.TemporaryDirectory(prefix="keyguard-dotenv-") as directory:
        for consumer in ("node", "python"):
            path = Path(directory) / f"{consumer}.env"
            os.mkfifo(path, 0o600)
            def deliver():
                with path.open("w") as pipe:
                    pipe.write(fixture["text"])
            writer = threading.Thread(target=deliver, daemon=True)
            writer.start()
            command = (
                ["node", "-e", "process.stdout.write(JSON.stringify(require('dotenv').config({path:process.argv[1],quiet:true}).parsed))", str(path)]
                if consumer == "node" else
                [sys.executable, "-c", "import json,sys; from dotenv import dotenv_values; print(json.dumps(dotenv_values(sys.argv[1],interpolate=False)))", str(path)]
            )
            parsed = subprocess.run(command, capture_output=True, text=True, timeout=15, check=True)
            writer.join(timeout=1)
            assert not writer.is_alive(), f"{consumer} did not read the pipe"
            assert json.loads(parsed.stdout) == expected, f"{consumer} changed a FIFO value"
    print("Node and Python also read the same values from owner-only FIFOs")
