"""Per-user sync records in SQLite.

A record is (kind, key) -> payload, with the client's update time and a deleted flag. A write wins
when its update time is newer (ties: the deleted one, then the larger payload), so devices converge
whatever order they sync in. Every stored change takes the next sequence number for that user; a
client asks for changes since the last sequence it saw.

Manga and chapter keys are hashes of the source's own identifiers, so the same title is the same
record on every device and platform whatever characters the source uses in its ids.
"""
import re
import sqlite3
import threading
import time
from contextlib import contextmanager
from dataclasses import dataclass

import orjson

# Kinds the app syncs. Keys are short stable identifiers chosen by the app.
KINDS = {
    "library",   # a title on the bookshelf: sha256(source \n manga), hex
    "progress",  # reading progress of a chapter: sha256(source \n manga \n chapter), hex
    "category",  # a library category: sha256(title), hex
    "pref",      # one synced setting: its name, e.g. Reader.readingMode
}
KEY = re.compile(r"[A-Za-z0-9._:/|@+-]{1,200}")
SCHEMA = """
CREATE TABLE IF NOT EXISTS record(
  user TEXT NOT NULL, kind TEXT NOT NULL, key TEXT NOT NULL,
  seq INTEGER NOT NULL, updated INTEGER NOT NULL, deleted INTEGER NOT NULL, payload BLOB,
  size INTEGER NOT NULL, PRIMARY KEY(user, kind, key)) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS record_seq ON record(user, seq);
CREATE TABLE IF NOT EXISTS account(
  user TEXT PRIMARY KEY, seq INTEGER NOT NULL, records INTEGER NOT NULL, bytes INTEGER NOT NULL,
  created INTEGER NOT NULL, seen INTEGER NOT NULL, floor INTEGER NOT NULL DEFAULT 0) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS device(
  user TEXT NOT NULL, device TEXT NOT NULL, name TEXT NOT NULL, platform TEXT NOT NULL,
  created INTEGER NOT NULL, seen INTEGER NOT NULL, revoked INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(user, device)) WITHOUT ROWID;
"""
DEVICE = re.compile(r"[A-Za-z0-9-]{8,64}")
PLATFORMS = {"android", "ios", "macos", "windows", "linux", "ohos", "other"}
MAX_DEVICES = 50


class DeviceRevoked(Exception):
    pass
# floor: the newest update time among purged deletions. A record the server no longer has and that is
# not newer than the floor may be one of them, coming back from a device that missed the deletion.


class Invalid(ValueError):
    pass


class QuotaExceeded(Exception):
    pass


@dataclass
class PushResult:
    seq: int
    accepted: int
    stale: int


def _wins(new_updated, new_deleted, new_payload, old_updated, old_deleted, old_payload):
    if new_updated != old_updated:
        return new_updated > old_updated
    if new_deleted != old_deleted:
        return new_deleted > old_deleted
    return (new_payload or b"") > (old_payload or b"")


class Store:
    def __init__(self, path, *, max_records, max_bytes, max_payload, clock=time.time):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        self.max_records = max_records
        self.max_bytes = max_bytes
        self.max_payload = max_payload
        self.clock = clock
        self.lock = threading.Lock()
        with self._connect() as connection:
            connection.execute("PRAGMA journal_mode=WAL")
            connection.executescript(SCHEMA)

    @contextmanager
    def _connect(self):
        connection = sqlite3.connect(self.path, timeout=30, isolation_level=None)
        try:
            connection.execute("PRAGMA busy_timeout=30000")
            connection.execute("PRAGMA synchronous=NORMAL")
            yield connection
        finally:
            connection.close()

    def _now(self):
        return int(self.clock() * 1000)

    @staticmethod
    def validate(item, max_payload):
        if not isinstance(item, dict):
            raise Invalid("Record must be an object")
        kind, key, updated = item.get("kind"), item.get("key"), item.get("updated")
        if kind not in KINDS:
            raise Invalid(f"Unknown kind: {kind!r}")
        if not isinstance(key, str) or not KEY.fullmatch(key):
            raise Invalid("Invalid key")
        if not isinstance(updated, int) or isinstance(updated, bool) or not 0 < updated < 2**53:
            raise Invalid("Invalid update time")
        deleted = item.get("deleted", False)
        if not isinstance(deleted, bool):
            raise Invalid("Invalid deleted flag")
        data = item.get("data")
        if deleted:
            payload = None
        else:
            if not isinstance(data, dict):
                raise Invalid("Record data must be an object")
            payload = orjson.dumps(data, option=orjson.OPT_SORT_KEYS)
            if len(payload) > max_payload:
                raise Invalid("Record too large")
        return kind, key, updated, int(deleted), payload

    def push(self, user, items, *, now=None):
        """Applies [items]; returns the user's sequence after the write and how many records won."""
        now = now if now is not None else self._now()
        records = [self.validate(item, self.max_payload) for item in items]
        # The same key twice in one push: only the winning version matters.
        merged = {}
        for record in records:
            kind, key, updated, deleted, payload = record
            known = merged.get((kind, key))
            if known is None or _wins(updated, deleted, payload, known[2], known[3], known[4]):
                merged[(kind, key)] = record
        with self.lock, self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                row = connection.execute("SELECT seq, records, bytes, floor FROM account WHERE user=?", (user,)).fetchone()
                seq, count, size, floor = row if row else (0, 0, 0, 0)
                accepted = stale = 0
                for (kind, key), (_, _, updated, deleted, payload) in sorted(merged.items()):
                    old = connection.execute(
                        "SELECT updated, deleted, payload, size FROM record WHERE user=? AND kind=? AND key=?",
                        (user, kind, key)).fetchone()
                    if old is None and updated <= floor:
                        stale += 1
                        continue
                    if old is not None and not _wins(updated, deleted, payload, old[0], old[1], old[2]):
                        stale += 1
                        continue
                    seq += 1
                    length = len(payload or b"") + len(key)
                    count += old is None
                    size += length - (old[3] if old else 0)
                    connection.execute(
                        "INSERT INTO record VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(user, kind, key) DO UPDATE SET "
                        "seq=excluded.seq, updated=excluded.updated, deleted=excluded.deleted, "
                        "payload=excluded.payload, size=excluded.size",
                        (user, kind, key, seq, updated, deleted, payload, length))
                    accepted += 1
                if count > self.max_records or size > self.max_bytes:
                    raise QuotaExceeded()
                connection.execute(
                    "INSERT INTO account VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(user) DO UPDATE SET "
                    "seq=excluded.seq, records=excluded.records, bytes=excluded.bytes, seen=excluded.seen",
                    (user, seq, count, size, now, now, floor))
                connection.execute("COMMIT")
            except BaseException:
                connection.execute("ROLLBACK")
                raise
        return PushResult(seq, accepted, stale)

    def pull(self, user, since, limit):
        """Changes after sequence [since], oldest first, and the sequence to ask from next."""
        with self._connect() as connection:
            row = connection.execute("SELECT seq FROM account WHERE user=?", (user,)).fetchone()
            current = row[0] if row else 0
            rows = connection.execute(
                "SELECT seq, kind, key, updated, deleted, payload FROM record WHERE user=? AND seq>? "
                "ORDER BY seq LIMIT ?", (user, since, limit)).fetchall()
        items = [{
            "kind": kind, "key": key, "updated": updated, "deleted": bool(deleted),
            **({} if deleted else {"data": orjson.loads(payload)}),
        } for _, kind, key, updated, deleted, payload in rows]
        # A cursor ahead of the account means the account was erased: the device must start over.
        reset = since > current
        cursor = rows[-1][0] if rows else (current if reset else since)
        return {"items": items, "cursor": cursor, "more": len(rows) == limit and cursor < current,
                "seq": current, "reset": reset}

    def status(self, user):
        with self._connect() as connection:
            row = connection.execute("SELECT seq, records, bytes, created FROM account WHERE user=?", (user,)).fetchone()
        seq, records, size, created = row if row else (0, 0, 0, None)
        return {"seq": seq, "records": records, "bytes": size, "created": created,
                "max_records": self.max_records, "max_bytes": self.max_bytes}

    def erase(self, user):
        """Deletes everything stored for [user]: synced records and the device list."""
        with self.lock, self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute("DELETE FROM record WHERE user=?", (user,))
            connection.execute("DELETE FROM account WHERE user=?", (user,))
            connection.execute("DELETE FROM device WHERE user=?", (user,))
            connection.execute("COMMIT")

    def touch_device(self, user, device, name, platform, *, now=None):
        """Records that [device] used the account; raises DeviceRevoked for one removed from the list.

        A device the user removed stays refused under that id, so the app on it signs out. Requests
        without a device id (older builds) are accepted and not listed.
        """
        if device is None:
            return
        if not DEVICE.fullmatch(device):
            raise Invalid("Invalid device id")
        name = (name or "").strip()[:80] or "Unknown device"
        platform = platform if platform in PLATFORMS else "other"
        now = now if now is not None else self._now()
        with self.lock, self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                row = connection.execute("SELECT revoked, seen FROM device WHERE user=? AND device=?",
                                         (user, device)).fetchone()
                if row is not None and row[0]:
                    raise DeviceRevoked()
                if row is None:
                    count = connection.execute("SELECT COUNT(*) FROM device WHERE user=? AND revoked=0",
                                               (user,)).fetchone()[0]
                    if count >= MAX_DEVICES:
                        # Forget the device that has been away longest.
                        connection.execute(
                            "DELETE FROM device WHERE user=? AND device=(SELECT device FROM device WHERE user=? "
                            "AND revoked=0 ORDER BY seen LIMIT 1)", (user, user))
                    connection.execute("INSERT INTO device VALUES (?, ?, ?, ?, ?, ?, 0)",
                                       (user, device, name, platform, now, now))
                elif now - row[1] > 60_000:
                    connection.execute("UPDATE device SET seen=?, name=?, platform=? WHERE user=? AND device=?",
                                       (now, name, platform, user, device))
                connection.execute("COMMIT")
            except BaseException:
                connection.execute("ROLLBACK")
                raise

    def devices(self, user):
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT device, name, platform, created, seen FROM device WHERE user=? AND revoked=0 ORDER BY seen DESC",
                (user,)).fetchall()
        return [{"id": d, "name": n, "platform": p, "created": c, "seen": s} for d, n, p, c, s in rows]

    def revoke_device(self, user, device):
        """Removes [device] from the list; its next request is refused. Returns whether it existed."""
        with self.lock, self._connect() as connection:
            cursor = connection.execute("UPDATE device SET revoked=1 WHERE user=? AND device=? AND revoked=0",
                                        (user, device))
            return cursor.rowcount > 0

    def purge_tombstones(self, older_than_ms):
        """Removes deletions older than the cutoff; returns how many were dropped."""
        with self.lock, self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            rows = connection.execute(
                "SELECT user, COUNT(*), SUM(size), MAX(updated) FROM record WHERE deleted=1 AND updated<? GROUP BY user",
                (older_than_ms,)).fetchall()
            connection.execute("DELETE FROM record WHERE deleted=1 AND updated<?", (older_than_ms,))
            for user, count, size, newest in rows:
                connection.execute("UPDATE account SET records=records-?, bytes=bytes-?, floor=MAX(floor, ?) WHERE user=?",
                                   (count, size, newest, user))
            connection.execute("COMMIT")
        return sum(row[1] for row in rows)
