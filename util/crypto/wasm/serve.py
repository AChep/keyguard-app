#!/usr/bin/env python3
"""Serve the Wasm smoke test locally, including CSP headers on worker scripts."""

import argparse
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class Handler(SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; "
            "worker-src 'self'; connect-src 'self'; object-src 'none'",
        )
        super().end_headers()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8766)
    args = parser.parse_args()
    handler = partial(Handler, directory=str(Path(__file__).resolve().parent.parent))
    server = ThreadingHTTPServer(("127.0.0.1", args.port), handler)
    print(f"http://127.0.0.1:{args.port}/wasm/smoke.html", flush=True)
    server.serve_forever()
