#!/usr/bin/env python3
"""Local origin server for tunnel end-to-end tests.

Serves content that is only reachable from this machine, so a remote TestingBot
browser can only load it if traffic really flows back through the tunnel.

Routes:
  /            marker page (HTML, contains the marker string)
  /headers     JSON of the request headers this server received
  /slow        responds after ~2s, for timeout behaviour
  /large       N MiB of deterministic bytes (?mb=N, default 8), for buffering behaviour
  /protected   401 unless Basic auth matches e2euser:e2epass, for --auth
  /ws          WebSocket echo endpoint, for the upgrade relay
  /wstest      page whose JS opens a WebSocket and puts the echo in the DOM, for browsers

With a cert and key it serves HTTPS instead, so wss:// has somewhere to go.

    origin_server.py PORT [MARKER] [CERT KEY]
"""
import base64
import hashlib
import json
import os
import struct
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MARKER = sys.argv[2] if len(sys.argv) > 2 else "TB-MARKER"

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
PROTECTED_CREDENTIALS = "e2euser:e2epass"


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
        elif path == "/large":
            self._send_large(path)
        elif path == "/protected":
            self._send_protected()
        elif path == "/ws":
            self._websocket()
        elif path == "/wstest":
            self._send(self._ws_test_page())
        else:
            body = (
                "<!doctype html><html><head><title>Tunnel E2E Origin</title></head>"
                f'<body><h1 id="marker">{MARKER}</h1></body></html>'
            ).encode()
            self._send(body)

    def _ws_test_page(self):
        """A page a real browser can run, so the upgrade is driven by the browser's own stack.

        The result goes into the DOM because that is what the harness can read back through
        WebDriver's page source. A failure writes the reason rather than just failing to write
        the marker, so a broken relay is distinguishable from a page that never loaded.
        """
        scheme = "wss" if self.server.is_tls else "ws"
        host = self.headers.get("Host") or ("127.0.0.1:%d" % self.server.server_address[1])
        return (
            "<!doctype html><html><head><title>WebSocket E2E</title></head><body>"
            '<h1 id="marker">ws-pending</h1><script>\n'
            f'var socket = new WebSocket("{scheme}://{host}/ws");\n'
            'var done = function (text) {'
            '  document.getElementById("marker").textContent = text; };\n'
            'socket.onopen = function () { socket.send("' + MARKER + '"); };\n'
            'socket.onmessage = function (event) { done(event.data); };\n'
            'socket.onerror = function () { done("ws-error"); };\n'
            'socket.onclose = function (event) {'
            '  if (document.getElementById("marker").textContent === "ws-pending") {'
            '    done("ws-closed-" + event.code); } };\n'
            "</script></body></html>").encode()

    def _send_large(self, path):
        """A body far bigger than any single buffer, to catch truncation or stalling."""
        query = self.path.split("?", 1)[1] if "?" in self.path else ""
        megabytes = 8
        for part in query.split("&"):
            if part.startswith("mb="):
                megabytes = max(1, min(512, int(part[3:])))
        chunk = bytes(range(256)) * 4096                    # 1 MiB, deterministic
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(megabytes * len(chunk)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        for _ in range(megabytes):
            self.wfile.write(chunk)

    def _send_protected(self):
        """Demands Basic credentials, so --auth has something to satisfy."""
        header = self.headers.get("Authorization", "")
        scheme, _, token = header.partition(" ")
        supplied = ""
        if scheme.lower() == "basic":
            try:
                supplied = base64.b64decode(token).decode()
            except Exception:
                supplied = ""
        if supplied != PROTECTED_CREDENTIALS:
            self.log_message("PROTECTED-DENIED")
            body = b"unauthorized"
            self.send_response(401)
            self.send_header("WWW-Authenticate", 'Basic realm="e2e"')
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.log_message("PROTECTED-OK")
        self._send(b"<html><body>protected-ok</body></html>")

    def _websocket(self):
        """RFC 6455 handshake, then echo every text frame back.

        Hand-rolled because the standard library has no WebSocket server, and the point here
        is only to give the tunnel's upgrade relay something real to carry.
        """
        key = self.headers.get("Sec-WebSocket-Key")
        if self.headers.get("Upgrade", "").lower() != "websocket" or not key:
            self._send(b"expected a websocket upgrade", "text/plain", 400)
            return
        accept = base64.b64encode(
            hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
        self.log_message("WS-UPGRADE")
        self.wfile.write(
            ("HTTP/1.1 101 Switching Protocols\r\n"
             "Upgrade: websocket\r\n"
             "Connection: Upgrade\r\n"
             f"Sec-WebSocket-Accept: {accept}\r\n\r\n").encode())
        self.wfile.flush()
        self.close_connection = True                        # this socket is no longer HTTP
        try:
            while True:
                payload = self._read_frame()
                if payload is None:
                    return
                self.connection.sendall(self._text_frame(payload))
        except OSError:
            return

    def _read_frame(self):
        """One client frame, unmasked. None on close or end of stream."""
        head = self._read_exactly(2)
        if head is None:
            return None
        opcode = head[0] & 0x0F
        if opcode == 0x8:                                   # close
            return None
        masked = head[1] & 0x80
        length = head[1] & 0x7F
        if length == 126:
            length = struct.unpack("!H", self._read_exactly(2))[0]
        elif length == 127:
            length = struct.unpack("!Q", self._read_exactly(8))[0]
        mask = self._read_exactly(4) if masked else b"\x00\x00\x00\x00"
        data = self._read_exactly(length) or b""
        return bytes(b ^ mask[i % 4] for i, b in enumerate(data))

    def _read_exactly(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.connection.recv(n - len(buf))
            if not chunk:
                return None
            buf += chunk
        return buf

    @staticmethod
    def _text_frame(payload):
        header = bytes([0x81])
        if len(payload) < 126:
            header += bytes([len(payload)])
        elif len(payload) < (1 << 16):
            header += bytes([126]) + struct.pack("!H", len(payload))
        else:
            header += bytes([127]) + struct.pack("!Q", len(payload))
        return header + payload

    def log_message(self, fmt, *args):
        sys.stderr.write("[origin] " + (fmt % args) + "\n")


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 7777
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.is_tls = False
    if len(sys.argv) > 4:
        import ssl
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(certfile=sys.argv[3], keyfile=sys.argv[4])
        server.socket = context.wrap_socket(server.socket, server_side=True)
        server.is_tls = True
        sys.stderr.write("[origin] serving TLS on %d\n" % port)
        sys.stderr.flush()
    server.serve_forever()
