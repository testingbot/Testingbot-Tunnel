#!/usr/bin/env python3
"""Local origin server for tunnel end-to-end tests.

Serves content that is only reachable from this machine, so a remote TestingBot
browser can only load it if traffic really flows back through the tunnel.

Routes:
  /            marker page (HTML, contains the marker string)
  /headers     JSON of the request headers this server received
  /slow        responds after ~2s, for timeout behaviour
"""
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MARKER = sys.argv[2] if len(sys.argv) > 2 else "TB-MARKER"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send(self, body: bytes, ctype: str = "text/html; charset=utf-8", code: int = 200):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/headers":
            payload = json.dumps(
                {k.lower(): v for k, v in self.headers.items()}, indent=2
            ).encode()
            self._send(payload, "application/json")
        elif path == "/slow":
            time.sleep(2)
            self._send(b"<html><body>slow-ok</body></html>")
        else:
            body = (
                "<!doctype html><html><head><title>Tunnel E2E Origin</title></head>"
                f'<body><h1 id="marker">{MARKER}</h1></body></html>'
            ).encode()
            self._send(body)

    def log_message(self, fmt, *args):
        sys.stderr.write("[origin] " + (fmt % args) + "\n")


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 7777
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
