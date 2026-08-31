#!/usr/bin/env python3
"""Opens a WebSocket through the tunnel's local proxy and echoes one message.

Exists because WebsocketHandler -- a whole handler in the proxy chain, rewritten during the
Jetty 12 migration -- had no end-to-end coverage at all. An upgrade is relayed as opaque bytes
once the handshake is replayed against the target, so nothing short of a real upgrade and a
real frame exercises it.

Three modes, because a WebSocket reaches the tunnel three different ways:

  proxy    absolute-URI GET with an Upgrade -- handled by WebsocketHandler
  connect  CONNECT first, then the upgrade inside the tunnel -- handled by CustomConnectHandler
           relaying bytes it does not interpret
  tls      CONNECT, then TLS, then the upgrade inside that, which is what wss:// really is

Prints the echoed payload on success and exits 0; prints the reason and exits 1 otherwise.

    ws_client.py PROXY_HOST:PROXY_PORT ORIGIN_HOST:ORIGIN_PORT MESSAGE [MODE]
"""
import base64
import hashlib
import os
import socket
import ssl
import struct
import sys

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def fail(reason):
    print(reason)
    sys.exit(1)


def read_exactly(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            fail("connection closed after %d of %d bytes" % (len(buf), n))
        buf += chunk
    return buf


def masked_text_frame(payload):
    mask = os.urandom(4)
    masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
    header = bytes([0x81])
    if len(payload) < 126:
        header += bytes([0x80 | len(payload)])
    elif len(payload) < (1 << 16):
        header += bytes([0x80 | 126]) + struct.pack("!H", len(payload))
    else:
        header += bytes([0x80 | 127]) + struct.pack("!Q", len(payload))
    return header + mask + masked


def read_frame(sock):
    head = read_exactly(sock, 2)
    length = head[1] & 0x7F
    if length == 126:
        length = struct.unpack("!H", read_exactly(sock, 2))[0]
    elif length == 127:
        length = struct.unpack("!Q", read_exactly(sock, 8))[0]
    if head[1] & 0x80:
        mask = read_exactly(sock, 4)
        data = read_exactly(sock, length)
        return bytes(b ^ mask[i % 4] for i, b in enumerate(data))
    return read_exactly(sock, length) if length else b""


def open_tunnel(proxy_host, proxy_port, origin):
    """CONNECT through the proxy and hand back the socket, positioned at the tunnel."""
    sock = socket.create_connection((proxy_host, proxy_port), timeout=30)
    sock.settimeout(30)
    sock.sendall((f"CONNECT {origin} HTTP/1.1\r\nHost: {origin}\r\n\r\n").encode())
    head = b""
    while b"\r\n\r\n" not in head:
        byte = sock.recv(1)
        if not byte:
            fail("proxy closed the connection during CONNECT: %r" % head[:200])
        head += byte
    status = head.split(b"\r\n", 1)[0].decode(errors="replace")
    if " 200" not in status:
        fail("CONNECT was refused: %r" % status)
    return sock


def main():
    proxy_host, proxy_port = sys.argv[1].rsplit(":", 1)
    origin = sys.argv[2]
    message = sys.argv[3].encode()
    mode = sys.argv[4] if len(sys.argv) > 4 else "proxy"

    if mode == "proxy":
        sock = socket.create_connection((proxy_host, int(proxy_port)), timeout=30)
        sock.settimeout(30)
        # An absolute URI, because this goes to a forward proxy rather than to the origin.
        target = f"http://{origin}/ws"
    else:
        sock = open_tunnel(proxy_host, int(proxy_port), origin)
        if mode == "tls":
            # The origin's certificate is self-signed, and checking it is not what this test
            # is about -- the tunnel relays these bytes without being able to read them either.
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            context.check_hostname = False
            context.verify_mode = ssl.CERT_NONE
            sock = context.wrap_socket(sock, server_hostname=origin.rsplit(":", 1)[0])
        # Inside the tunnel we are speaking to the origin directly, so an origin-form path.
        target = "/ws"

    key = base64.b64encode(os.urandom(16)).decode()
    request = (
        f"GET {target} HTTP/1.1\r\n"
        f"Host: {origin}\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        "Sec-WebSocket-Version: 13\r\n\r\n"
    )
    sock.sendall(request.encode())

    head = b""
    while b"\r\n\r\n" not in head:
        byte = sock.recv(1)
        if not byte:
            fail("connection closed during the upgrade: %r" % head[:200])
        head += byte

    status = head.split(b"\r\n", 1)[0].decode(errors="replace")
    if " 101" not in status:
        fail("expected 101 Switching Protocols, got %r" % status)

    expected = base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
    if expected.lower().encode() not in head.lower():
        fail("Sec-WebSocket-Accept did not match the key we sent")

    sock.sendall(masked_text_frame(message))
    echoed = read_frame(sock)
    if echoed != message:
        fail("echo mismatch: sent %r, got %r" % (message, echoed))

    print(echoed.decode(errors="replace"))
    sock.close()


if __name__ == "__main__":
    main()
