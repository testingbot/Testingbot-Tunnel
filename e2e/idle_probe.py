#!/usr/bin/env python3
"""Holds a CONNECT tunnel open doing nothing, to see whether --http-idle-timeout closes it.

An idle timeout is only observable by waiting. This opens a tunnel through the local proxy,
proves it works, sits silent for a given number of seconds, and then reports whether the tunnel
is still usable.

Prints "open" or "closed" and exits 0; prints the reason and exits 1 if the tunnel could not be
established in the first place.

    idle_probe.py PROXY_HOST:PROXY_PORT ORIGIN_HOST:ORIGIN_PORT IDLE_SECONDS
"""
import socket
import sys
import time


def fail(reason):
    print(reason)
    sys.exit(1)


def read_head(sock):
    head = b""
    while b"\r\n\r\n" not in head:
        try:
            byte = sock.recv(1)
        except OSError as error:
            return head, error
        if not byte:
            return head, None
        head += byte
    return head, None


def main():
    proxy_host, proxy_port = sys.argv[1].rsplit(":", 1)
    origin = sys.argv[2]
    idle_seconds = float(sys.argv[3])

    sock = socket.create_connection((proxy_host, int(proxy_port)), timeout=30)
    sock.settimeout(30)
    sock.sendall(("CONNECT %s HTTP/1.1\r\nHost: %s\r\n\r\n" % (origin, origin)).encode())
    head, _ = read_head(sock)
    if b" 200" not in head.split(b"\r\n", 1)[0]:
        fail("CONNECT was refused: %r" % head[:200])

    # Prove the tunnel works before timing it, so "closed" later cannot mean "never opened".
    sock.sendall(("GET / HTTP/1.1\r\nHost: %s\r\n\r\n" % origin).encode())
    head, _ = read_head(sock)
    if b"200" not in head:
        fail("the tunnel did not carry a request before going idle: %r" % head[:200])

    time.sleep(idle_seconds)

    # A closed tunnel shows up either as a failed write, an empty read, or a reset. All three
    # mean the same thing here; only a complete response means it survived.
    try:
        sock.sendall(("GET / HTTP/1.1\r\nHost: %s\r\n\r\n" % origin).encode())
        sock.settimeout(10)
        head, error = read_head(sock)
        if error is not None or not head:
            print("closed")
            return
        print("open" if b"200" in head else "closed")
    except OSError:
        print("closed")


if __name__ == "__main__":
    main()
