"""Independent, standard-library Cast V2 client for device smoke tests.

Example (device and PC on the same LAN):
  adb -s SERIAL reverse tcp:18089 tcp:18089
  python tools/cast_smoke_test.py 192.168.2.96

Without --url, serves generated WAV audio through the reverse port above.
This verifies a self-signed experimental receiver, not Google CA compatibility.
"""
import argparse
import io
import json
import math
import socket
import ssl
import struct
import subprocess
import threading
import time
import wave
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


PREFIX = "urn:x-cast:com.google.cast."


def varint(value):
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def integer(tag, value):
    return varint(tag * 8) + varint(value)


def blob(tag, value):
    return varint(tag * 8 + 2) + varint(len(value)) + value


def fields(data):
    position = 0

    def read_int():
        nonlocal position
        value = shift = 0
        while True:
            b = data[position]
            position += 1
            value |= (b & 127) << shift
            if b < 128:
                return value
            shift += 7
            assert shift < 64

    result = {}
    while position < len(data):
        tag = read_int()
        kind = tag & 7
        if kind == 0:
            value = read_int()
        elif kind == 2:
            size = read_int()
            value = data[position:position + size]
            assert len(value) == size
            position += size
        else:
            raise ValueError(f"Unexpected wire type {kind}")
        result[tag >> 3] = value
    return result


class Client:
    def __init__(self, host, port):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE  # The feature deliberately uses a self-signed identity.
        self.socket = context.wrap_socket(socket.create_connection((host, port), timeout=10), server_hostname=host)
        self.rid = 0

    def send(self, namespace, payload, destination="receiver-0", binary=False):
        body = integer(1, 0) + blob(2, b"sender-smoke") + blob(3, destination.encode())
        body += blob(4, (PREFIX + namespace).encode()) + integer(5, int(binary))
        body += blob(7 if binary else 6, payload if binary else json.dumps(payload).encode())
        self.socket.sendall(struct.pack(">I", len(body)) + body)

    def read(self):
        def exact(size):
            data = bytearray()
            while len(data) < size:
                chunk = self.socket.recv(size - len(data))
                if not chunk:
                    raise EOFError("Receiver closed the Cast connection")
                data.extend(chunk)
            return bytes(data)
        size = struct.unpack(">I", exact(4))[0]
        assert 0 < size <= 1024 * 1024
        return fields(exact(size))

    def request(self, namespace, payload, destination="receiver-0"):
        self.rid += 1
        self.send(namespace, dict(payload, requestId=self.rid), destination)
        while True:
            envelope = self.read()
            if 6 in envelope:
                response = json.loads(envelope[6])
                if response.get("requestId") == self.rid:
                    return response


def audio_server(video_file=None):
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as writer:
        writer.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
        # Quiet synthetic tone; enough duration to verify seeking and progress.
        writer.writeframes(b"".join(struct.pack("<h", int(300 * math.sin(i * 2 * math.pi * 440 / 16000)))
                                  for i in range(16000 * 45)))
    audio = buffer.getvalue()
    video = Path(video_file).read_bytes() if video_file else None

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            data = audio if self.path == "/tone.wav" else video if self.path == "/video.mp4" else None
            if data is None:
                self.send_error(404)
                return
            start = 0
            if self.headers.get("Range"):
                start = int(self.headers["Range"].split("=")[1].split("-")[0])
            self.send_response(206 if start else 200)
            self.send_header("Content-Type", "audio/wav" if self.path == "/tone.wav" else "video/mp4")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(len(data) - start))
            if start:
                self.send_header("Content-Range", f"bytes {start}-{len(data)-1}/{len(data)}")
            self.end_headers()
            try:
                self.wfile.write(data[start:])
            except (BrokenPipeError, ConnectionResetError):
                pass

        def log_message(self, *_):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 18089), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host")
    parser.add_argument("--port", type=int, default=8009)
    parser.add_argument("--url")
    parser.add_argument("--mime", default="audio/wav")
    parser.add_argument("--receiver-serial", help="Also test local Back/exit via this ADB device")
    parser.add_argument("--adb", default="adb", help="ADB executable for --receiver-serial")
    args = parser.parse_args()
    server = audio_server() if not args.url else None
    client = Client(args.host, args.port)
    transport = None
    try:
        nonce = bytes(range(32))
        client.send("tp.deviceauth", blob(1, integer(1, 1) + blob(2, nonce) + integer(3, 1)), binary=True)
        auth = fields(fields(client.read()[7])[2])
        assert auth[5] == nonce
        assert auth[2] == client.socket.getpeercert(binary_form=True)
        print("PASS TLS and device-auth response", flush=True)
        client.send("tp.connection", {"type": "CONNECT"})
        assert client.request("tp.heartbeat", {"type": "PING"})["type"] == "PONG"
        result = client.request("receiver", {"type": "LAUNCH", "appId": "CC1AD845"})
        transport = result["status"]["applications"][0]["transportId"]
        client.send("tp.connection", {"type": "CONNECT"}, transport)
        media = {"contentId": args.url or "http://127.0.0.1:18089/tone.wav", "contentType": args.mime,
                 "streamType": "BUFFERED", "metadata": {"title": "Cast integration test"}}
        result = client.request("media", {"type": "LOAD", "media": media, "autoplay": False, "currentTime": 2}, transport)
        mid = result["status"][0]["mediaSessionId"]

        def status():
            return client.request("media", {"type": "GET_STATUS"}, transport)["status"][0]

        def wait_for(predicate):
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline:
                snapshot = status()
                if predicate(snapshot):
                    return snapshot
                time.sleep(0.2)
            raise AssertionError(f"Timed out waiting for player state: {snapshot}")

        snapshot = wait_for(lambda s: s["playerState"] == "PAUSED")
        assert snapshot["currentTime"] >= 1.5
        print("PASS LOAD, autoplay=false, start time, PAUSED state", flush=True)
        client.request("media", {"type": "PLAY", "mediaSessionId": mid}, transport)
        wait_for(lambda s: s["playerState"] == "PLAYING" and s["currentTime"] > 3)
        client.request("media", {"type": "PAUSE", "mediaSessionId": mid}, transport)
        wait_for(lambda s: s["playerState"] == "PAUSED")
        client.request("media", {"type": "SEEK", "mediaSessionId": mid, "currentTime": 10}, transport)
        wait_for(lambda s: abs(s["currentTime"] - 10) < 0.5)
        print("PASS PLAY, progress, PAUSE and SEEK", flush=True)
        client.request("receiver", {"type": "SET_VOLUME", "volume": {"level": 0.3, "muted": True}})
        assert status()["volume"]["muted"] is True
        client.request("receiver", {"type": "SET_VOLUME", "volume": {"muted": False}})
        wait_for(lambda s: abs(s["volume"]["level"] - 0.3) < 0.01)
        print("PASS volume and mute", flush=True)
        if server:
            result = client.request("media", {"type": "LOAD", "media": media, "autoplay": "false"}, transport)
            mid = result["status"][0]["mediaSessionId"]
            wait_for(lambda s: s["playerState"] == "PLAYING" and s["currentTime"] > 0.5)
            print("PASS VLC string autoplay uses default automatic playback", flush=True)
            if args.receiver_serial:
                subprocess.run([args.adb, "-s", args.receiver_serial, "shell", "input", "keyevent", "4"], check=True)
                deadline = time.monotonic() + 15
                closed = False
                while time.monotonic() < deadline:
                    envelope = client.read()
                    if 6 not in envelope:
                        continue
                    update = json.loads(envelope[6])
                    if update.get("type") == "RECEIVER_STATUS" and not update["status"]["applications"]:
                        closed = True
                        break
                assert closed, "Local receiver exit did not announce application shutdown"
                assert not client.request("receiver", {"type": "GET_STATUS"})["status"]["applications"]
                launch = client.request("receiver", {"type": "LAUNCH", "appId": "CC1AD845"})
                transport = launch["status"]["applications"][0]["transportId"]
                client.send("tp.connection", {"type": "CONNECT"}, transport)
                result = client.request("media", {"type": "LOAD", "media": media}, transport)
                mid = result["status"][0]["mediaSessionId"]
                wait_for(lambda s: s["playerState"] == "PLAYING" and s["currentTime"] > 0.5)
                print("PASS local receiver exit notification and subsequent recast", flush=True)
            # Finish and error must be distinguished using real ExoPlayer callbacks.
            client.request("media", {"type": "SEEK", "mediaSessionId": mid, "currentTime": 44,
                                     "resumeState": "PLAYBACK_START"}, transport)
            wait_for(lambda s: s.get("idleReason") == "FINISHED")
            media["contentId"] = "http://127.0.0.1:18089/missing.wav"
            client.request("media", {"type": "LOAD", "media": media}, transport)
            wait_for(lambda s: s.get("idleReason") == "ERROR")
            print("PASS FINISHED and ERROR feedback", flush=True)
        client.request("receiver", {"type": "STOP"})
        print("PASS receiver STOP", flush=True)
    finally:
        if transport:
            try:
                client.request("receiver", {"type": "STOP"})
            except (OSError, EOFError):
                pass
        client.socket.close()
        if server:
            server.shutdown()


if __name__ == "__main__":
    main()
