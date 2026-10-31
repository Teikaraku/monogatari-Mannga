"""Profile pictures uploaded from the app.

Every upload is decoded and re-encoded to a square 512 px WebP, so only clean images are ever
served whatever was sent. Files are named by the hash of their content, which makes them immutable.
"""
import hashlib
import io
import os
import re
import threading
import time
from collections import defaultdict, deque
from pathlib import Path

from PIL import Image, ImageOps

MAX_UPLOAD = 10 * 1024 * 1024
MAX_PIXELS = 40_000_000
SIZE = 512
NAME = re.compile(r"[0-9a-f]{32}\.webp")


class BadImage(ValueError):
    pass


def encode(data: bytes) -> bytes:
    if not data:
        raise BadImage("Empty upload")
    Image.MAX_IMAGE_PIXELS = MAX_PIXELS
    try:
        with Image.open(io.BytesIO(data)) as image:
            if image.format not in {"JPEG", "PNG", "WEBP", "GIF", "HEIF", "AVIF", "BMP"}:
                raise BadImage("Unsupported image type")
            image.seek(0)
            image = ImageOps.exif_transpose(image)
            image = image.convert("RGBA") if image.mode in ("RGBA", "LA", "P") else image.convert("RGB")
            # Centre crop to a square, then scale down.
            image = ImageOps.fit(image, (SIZE, SIZE), Image.LANCZOS) if min(image.size) >= SIZE else ImageOps.fit(
                image, (min(image.size),) * 2, Image.LANCZOS)
            out = io.BytesIO()
            image.save(out, "WEBP", quality=86, method=4)
            return out.getvalue()
    except BadImage:
        raise
    except (Image.DecompressionBombError, OSError, ValueError, SyntaxError) as error:
        raise BadImage("Not a readable image") from error


def store(directory: Path, data: bytes) -> str:
    encoded = encode(data)
    name = hashlib.sha256(encoded).hexdigest()[:32] + ".webp"
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / name
    if not target.exists():
        temporary = directory / f".{name}.{os.getpid()}.{threading.get_ident()}"
        temporary.write_bytes(encoded)
        os.replace(temporary, target)
    return name


def path_for(directory: Path, name: str):
    if not NAME.fullmatch(name):
        return None
    path = directory / name
    return path if path.is_file() else None


class RateLimit:
    """At most [per_hour] uploads per user in any hour (per process; one worker serves sync)."""

    def __init__(self, per_hour, clock=time.time):
        self.per_hour = per_hour
        self.clock = clock
        self.events = defaultdict(deque)
        self.lock = threading.Lock()

    def allow(self, user):
        now = self.clock()
        with self.lock:
            events = self.events[user]
            while events and events[0] <= now - 3600:
                events.popleft()
            if len(events) >= self.per_hour:
                return False
            events.append(now)
            return True
