#!/usr/bin/env python3
"""Opens a WebSocket through the tunnel's local proxy and echoes one message.

Exists because WebsocketHandler -- a whole handler in the proxy chain, rewritten during the
Jetty 12 migration -- had no end-to-end coverage at all. An upgrade is relayed as opaque bytes
once the handshake is replayed against the target, so nothing short of a real upgrade and a
real frame exercises it.

Prints the echoed payload on success and exits 0; prints the reason and exits 1 otherwise.

    ws_client.py PROXY_HOST:PROXY_PORT ORIGIN_HOST:ORIGIN_PORT MESSAGE
"""
import base64
import hashlib
import os
import socket
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


def main():
    proxy_host, proxy_port = sys.argv[1].rsplit(":", 1)
    origin = sys.argv[2]
    message = sys.argv[3].encode()

    key = base64.b64encode(os.urandom(16)).decode()
    sock = socket.create_connection((proxy_host, int(proxy_port)), timeout=30)
    sock.settimeout(30)

    # An absolute URI, because this goes to a forward proxy rather than to the origin.
    request = (
        f"GET http://{origin}/ws HTTP/1.1\r\n"
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
            fail("proxy closed the connection during the upgrade: %r" % head[:200])
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
