#!/usr/bin/env python3
"""Minimal upstream HTTP proxy for exercising the tunnel's --proxy option.

Supports absolute-URI GET forwarding and CONNECT tunnelling -- enough to prove
the tunnel chains through it. Logs each request so the harness can assert the
traffic really went this way.

With a second argument of user:password it demands Basic proxy authentication and
answers 407 without it. That is what makes the credential path testable end to
end: a corporate proxy the tunnel must authenticate to before anything works,
including the SSH control connection (TB-321).

    upstream_proxy.py PORT [user:password]
"""
import base64
import select
import socket
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit


def pump(a, b):
    try:
        while True:
            r, _, _ = select.select([a, b], [], [], 30)
            if not r:
                return
            for s in r:
                data = s.recv(65536)
                if not data:
                    return
                (b if s is a else a).sendall(data)
    except OSError:
        pass


REQUIRED_CREDENTIALS = None


class Proxy(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def authorized(self):
        """True when no credentials are required, or the right ones were sent."""
        if REQUIRED_CREDENTIALS is None:
            return True
        header = self.headers.get("Proxy-Authorization", "")
        scheme, _, token = header.partition(" ")
        if scheme.lower() != "basic":
            # Log the scheme so a Negotiate attempt is visible in the harness output.
            self.log_message("AUTH-MISSING scheme=%r", scheme or "<none>")
            return False
        try:
            supplied = base64.b64decode(token).decode()
        except Exception:
            return False
        if supplied != REQUIRED_CREDENTIALS:
            self.log_message("AUTH-REJECTED")
            return False
        self.log_message("AUTH-OK")
        return True

    def demand_auth(self):
        self.send_response(407, "Proxy Authentication Required")
        self.send_header("Proxy-Authenticate", 'Basic realm="e2e"')
        self.send_header("Content-Length", "0")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True

    def log_message(self, fmt, *args):
        sys.stderr.write("[upstream] " + (fmt % args) + "\n")
        sys.stderr.flush()

    def do_CONNECT(self):
        self.log_message("CONNECT %s", self.path)
        if not self.authorized():
            self.demand_auth()
            return
        host, _, port = self.path.partition(":")
        try:
            upstream = socket.create_connection((host, int(port or 443)), timeout=15)
        except OSError as e:
            self.send_error(502, str(e))
            return
        self.send_response(200, "Connection Established")
        self.end_headers()
        self.connection.setblocking(True)
        pump(self.connection, upstream)
        upstream.close()

    def do_GET(self):
        self.log_message("GET %s", self.path)
        if not self.authorized():
            self.demand_auth()
            return
        parts = urlsplit(self.path)
        try:
            upstream = socket.create_connection(
                (parts.hostname, parts.port or 80), timeout=15)
        except OSError as e:
            self.send_error(502, str(e))
            return
        path = parts.path or "/"
        if parts.query:
            path += "?" + parts.query
        req = f"GET {path} HTTP/1.0\r\nHost: {parts.netloc}\r\nConnection: close\r\n\r\n"
        upstream.sendall(req.encode())
        self.connection.sendall(b"")
        while True:
            chunk = upstream.recv(65536)
            if not chunk:
                break
            self.connection.sendall(chunk)
        upstream.close()
        self.close_connection = True


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8891
    if len(sys.argv) > 2 and sys.argv[2]:
        REQUIRED_CREDENTIALS = sys.argv[2]
        sys.stderr.write("[upstream] requiring Basic proxy authentication\n")
        sys.stderr.flush()
    ThreadingHTTPServer(("127.0.0.1", port), Proxy).serve_forever()
