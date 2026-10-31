"""Account sync: token checks, isolation, last-write-wins merging, paging, quotas and erasure."""
import time

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

from manga.sync.api import create_app
from manga.sync.auth import Verifier
from manga.sync.config import SyncConfig

ISSUER = "https://auth.example.test/oidc"
AUDIENCE = "https://manga.example.test/sync"


class Keys:
    def __init__(self):
        self.private = {"k1": ec.generate_private_key(ec.SECP384R1())}
        self.fetches = 0

    def rotate(self, kid):
        self.private[kid] = ec.generate_private_key(ec.SECP384R1())

    def jwks(self):
        self.fetches += 1
        keys = []
        for kid, key in self.private.items():
            jwk = jwt.algorithms.ECAlgorithm.to_jwk(key.public_key(), as_dict=True)
            keys.append({**jwk, "kid": kid, "alg": "ES384", "use": "sig"})
        return {"keys": keys}

    def token(self, sub="user-a", *, kid="k1", aud=AUDIENCE, iss=ISSUER, scope="sync", exp=3600, **extra):
        now = int(time.time())
        claims = {"sub": sub, "aud": aud, "iss": iss, "iat": now, "exp": now + exp, "scope": scope,
                  "client_id": "native-app", **extra}
        return jwt.encode(claims, self.private[kid], algorithm="ES384", headers={"kid": kid})


@pytest.fixture
def keys():
    return Keys()


@pytest.fixture
def config(tmp_path):
    return SyncConfig(data_dir=tmp_path / "sync", issuer=ISSUER, audience=AUDIENCE, jwks_url="unused",
                      max_records=60, max_bytes=200_000, max_push=20, max_payload=2_000, page_size=5)


@pytest.fixture
def client(config, keys):
    verifier = Verifier(ISSUER, AUDIENCE, "unused", fetch=keys.jwks)
    with TestClient(create_app(config, verifier=verifier)) as client:
        yield client


def auth(token):
    return {"Authorization": f"Bearer {token}"}


def note(key, updated, text="x", deleted=False):
    item = {"kind": "progress", "key": key, "updated": updated, "deleted": deleted}
    if not deleted:
        item["data"] = {"content": text}
    return item


def push(client, token, *items):
    return client.post("/sync/v1/changes", headers=auth(token), json={"items": list(items)})


def pull(client, token, since=0, limit=5):
    return client.get("/sync/v1/changes", headers=auth(token), params={"since": since, "limit": limit}).json()


@pytest.mark.parametrize("make", [
    lambda k: None,
    lambda k: "not-a-jwt",
    lambda k: k.token(aud="https://other.example.test"),
    lambda k: k.token(iss="https://evil.example.test/oidc"),
    lambda k: k.token(exp=-120),
    lambda k: k.token(sub="m2m-client", client_id="m2m-client"),
    lambda k: k.token(sub=""),
    lambda k: k.token(sub="x" * 65),
    lambda k: jwt.encode({"sub": "user-a", "aud": AUDIENCE, "iss": ISSUER, "exp": int(time.time()) + 60,
                          "iat": int(time.time()), "scope": "sync"}, "s" * 32, algorithm="HS256",
                         headers={"kid": "k1"}),
    lambda k: jwt.encode({"sub": "user-a", "aud": AUDIENCE, "iss": ISSUER, "exp": int(time.time()) + 60,
                          "iat": int(time.time()), "scope": "sync"},
                         ec.generate_private_key(ec.SECP384R1()), algorithm="ES384", headers={"kid": "k1"}),
])
def test_requests_without_a_valid_user_token_are_refused(client, keys, make):
    token = make(keys)
    headers = {} if token is None else auth(token)
    for method, path in (("GET", "/sync/v1/status"), ("GET", "/sync/v1/changes"),
                         ("POST", "/sync/v1/changes"), ("DELETE", "/sync/v1/account")):
        response = client.request(method, path, headers=headers, json={"items": []} if method == "POST" else None)
        assert response.status_code == 401, (method, path)
        assert response.headers["www-authenticate"].startswith("Bearer")
        assert response.headers["cache-control"] == "no-store"


def test_tokens_missing_required_claims_are_refused(client, keys):
    now = int(time.time())
    full = {"sub": "user-a", "aud": AUDIENCE, "iss": ISSUER, "iat": now, "exp": now + 60, "client_id": "native-app"}
    for claim in ("exp", "iat", "sub", "aud", "iss"):
        claims = {k: v for k, v in full.items() if k != claim}
        token = jwt.encode(claims, keys.private["k1"], algorithm="ES384", headers={"kid": "k1"})
        assert client.get("/sync/v1/status", headers=auth(token)).status_code == 401, claim


def test_a_token_for_the_novel_service_is_refused(client, keys):
    # Same issuer and user, another app's resource: the novel app's tokens never open manga data.
    token = keys.token(aud="https://novel.monogatari.top/sync")
    assert client.get("/sync/v1/status", headers=auth(token)).status_code == 401


def test_default_configuration_is_the_manga_service(monkeypatch):
    for name in ("SYNC_AUDIENCE", "SYNC_PUBLIC_BASE", "SYNC_ISSUER"):
        monkeypatch.delenv(name, raising=False)
    config = SyncConfig()
    assert config.audience == "https://manga.monogatari.top/sync"
    assert config.public_base == "https://manga.monogatari.top"
    assert config.issuer == "https://auth.monogatari.top/oidc"


def test_health_needs_no_token(client):
    assert client.get("/sync/healthz").json() == {"ok": True}


def test_rotated_keys_are_fetched_once(client, keys):
    assert client.get("/sync/v1/status", headers=auth(keys.token())).status_code == 200
    keys.rotate("k2")
    assert client.get("/sync/v1/status", headers=auth(keys.token(kid="k2"))).status_code == 200
    fetched = keys.fetches
    # Signed by a key Logto does not publish.
    rogue = keys.token(kid="k2").rsplit(".", 1)[0]
    header = jwt.utils.base64url_encode(b'{"alg":"ES384","kid":"k3-unknown","typ":"JWT"}').decode()
    forged = header + "." + rogue.split(".", 1)[1] + ".AAAA"
    assert client.get("/sync/v1/status", headers=auth(forged)).status_code == 401
    assert client.get("/sync/v1/status", headers=auth(forged)).status_code == 401
    # Unknown keys refetch at most once a minute, so a stream of bad tokens cannot hammer Logto.
    assert keys.fetches <= fetched + 1


def test_round_trip_and_users_are_isolated(client, keys):
    alice, bob = keys.token("alice"), keys.token("bob")
    result = push(client, alice, note("n1", 100, "hello"), {"kind": "pref", "key": "readTheme", "updated": 5,
                                                             "data": {"value": 3}})
    assert result.json() == {"seq": 2, "accepted": 2, "stale": 0}
    page = pull(client, alice)
    assert [(i["kind"], i["key"]) for i in page["items"]] == [("pref", "readTheme"), ("progress", "n1")]
    assert page["items"][1]["data"] == {"content": "hello"} and page["cursor"] == 2 and page["more"] is False
    assert pull(client, bob)["items"] == []
    assert client.get("/sync/v1/status", headers=auth(bob)).json()["records"] == 0


def test_last_write_wins_whatever_the_order(client, keys):
    token = keys.token()
    push(client, token, note("n1", 200, "newer"))
    assert push(client, token, note("n1", 100, "older")).json()["stale"] == 1
    assert pull(client, token)["items"][0]["data"] == {"content": "newer"}
    # Same time: deletion wins, so a delete racing an edit sticks.
    push(client, token, note("n1", 200, deleted=True))
    item = pull(client, token)["items"][0]
    assert item["deleted"] is True and "data" not in item
    # A later edit brings it back.
    push(client, token, note("n1", 300, "revived"))
    assert pull(client, token)["items"][0]["data"] == {"content": "revived"}


def test_duplicate_keys_in_one_push_keep_the_newest(client, keys):
    token = keys.token()
    result = push(client, token, note("n1", 1, "a"), note("n1", 3, "c"), note("n1", 2, "b")).json()
    assert result["accepted"] == 1
    assert pull(client, token)["items"][0]["data"] == {"content": "c"}


def test_paging_follows_the_cursor(client, keys):
    token = keys.token()
    push(client, token, *[note(f"n{i:02d}", 10 + i) for i in range(12)])
    seen, since = [], 0
    while True:
        page = pull(client, token, since)
        seen += [item["key"] for item in page["items"]]
        since = page["cursor"]
        if not page["more"]:
            break
    assert seen == [f"n{i:02d}" for i in range(12)] and since == 12
    assert pull(client, token, since)["items"] == []
    # Only what changed since the cursor comes back.
    push(client, token, note("n03", 999, "edited"))
    assert [i["key"] for i in pull(client, token, since)["items"]] == ["n03"]


@pytest.mark.parametrize("item", [
    {"kind": "unknown", "key": "a", "updated": 1, "data": {}},
    # The novel app's kinds belong to its own service.
    {"kind": "book", "key": "a", "updated": 1, "data": {}},
    {"kind": "time", "key": "a", "updated": 1, "data": {}},
    {"kind": "progress", "key": "", "updated": 1, "data": {}},
    {"kind": "progress", "key": "bad key", "updated": 1, "data": {}},
    {"kind": "progress", "key": "a" * 201, "updated": 1, "data": {}},
    {"kind": "progress", "key": "a", "updated": "1", "data": {}},
    {"kind": "progress", "key": "a", "updated": True, "data": {}},
    {"kind": "progress", "key": "a", "updated": 0, "data": {}},
    {"kind": "progress", "key": "a", "updated": 1, "data": [1]},
    {"kind": "progress", "key": "a", "updated": 1, "deleted": "yes"},
    {"kind": "progress", "key": "a", "updated": 1, "data": {"content": "x" * 3000}},
    "not an object",
])
def test_invalid_records_reject_the_whole_push(client, keys, item):
    token = keys.token()
    response = push(client, token, note("ok", 1), item)
    assert response.status_code == 400 and response.json()["error"]["code"] == "invalid_record"
    assert pull(client, token)["items"] == []


@pytest.mark.parametrize("body", [b"not json", b"[]", b'{"items": 3}'])
def test_malformed_bodies(client, keys, body):
    response = client.post("/sync/v1/changes", headers={**auth(keys.token()), "Content-Type": "application/json"},
                           content=body)
    assert response.status_code == 400


def test_limits(client, keys, config):
    token = keys.token()
    assert push(client, token, *[note(f"n{i}", 1) for i in range(config.max_push + 1)]).status_code == 413
    for start in range(0, config.max_records, config.max_push):
        assert push(client, token, *[note(f"n{i}", 1) for i in range(start, start + config.max_push)]).status_code == 200
    full = push(client, token, note("one-more", 1))
    assert full.status_code == 507 and full.json()["error"]["code"] == "quota_exceeded"
    # Updating an existing record still works when the account is full.
    assert push(client, token, note("n0", 2, "edit")).status_code == 200
    status = client.get("/sync/v1/status", headers=auth(token)).json()
    assert status["records"] == config.max_records and status["max_records"] == config.max_records
    # So does the byte limit: one large record that would overflow it is refused, nothing else changes.
    big = {"kind": "pref", "key": "big", "updated": 1, "data": {"v": "x" * 1900}}
    small = SyncConfig(data_dir=config.data_dir.parent / "small", issuer=ISSUER, audience=AUDIENCE,
                       jwks_url="unused", max_bytes=3_000, max_payload=2_000)
    verifier = Verifier(ISSUER, AUDIENCE, "unused", fetch=keys.jwks)
    with TestClient(create_app(small, verifier=verifier)) as other:
        assert push(other, token, big).status_code == 200
        assert push(other, token, {**big, "key": "big2"}).status_code == 507
        assert other.get("/sync/v1/status", headers=auth(token)).json()["records"] == 1
    for args in ({"since": -1}, {"since": "x"}, {"limit": 0}, {"limit": config.page_size + 1}):
        assert client.get("/sync/v1/changes", headers=auth(token), params=args).status_code == 400


def test_erasing_the_account_tells_devices_to_start_over(client, keys):
    token = keys.token()
    push(client, token, note("n1", 1), note("n2", 2))
    assert client.delete("/sync/v1/account", headers=auth(token)).status_code == 204
    page = pull(client, token, since=2)
    assert page["reset"] is True and page["items"] == [] and page["cursor"] == 0
    assert client.get("/sync/v1/status", headers=auth(token)).json()["records"] == 0


def device_headers(token, device="device-aaaa-0001", name="Pixel 9", platform="android"):
    return {**auth(token), "X-Mo-Device": device, "X-Mo-Device-Name": name, "X-Mo-Platform": platform}


def test_devices_are_listed_and_a_removed_device_is_signed_out(client, keys):
    token = keys.token()
    phone, tablet = device_headers(token), device_headers(token, "device-bbbb-0002", "iPad", "ios")
    assert client.get("/sync/v1/status", headers=phone).status_code == 200
    assert client.get("/sync/v1/status", headers=tablet).status_code == 200
    listed = client.get("/sync/v1/devices", headers=phone).json()["items"]
    assert {(d["id"], d["name"], d["platform"], d["current"]) for d in listed} == {
        ("device-aaaa-0001", "Pixel 9", "android", True), ("device-bbbb-0002", "iPad", "ios", False)}
    # Removing yourself is signing out, not a device removal.
    assert client.delete("/sync/v1/devices/device-aaaa-0001", headers=phone).status_code == 400
    assert client.delete("/sync/v1/devices/device-bbbb-0002", headers=phone).status_code == 204
    refused = client.get("/sync/v1/changes", headers=tablet)
    assert refused.status_code == 401 and refused.json()["error"]["code"] == "device_revoked"
    assert [d["id"] for d in client.get("/sync/v1/devices", headers=phone).json()["items"]] == ["device-aaaa-0001"]
    assert client.delete("/sync/v1/devices/device-bbbb-0002", headers=phone).status_code == 404
    # Another user's devices are separate.
    other = device_headers(keys.token("bob"), "device-cccc-0003")
    assert client.get("/sync/v1/status", headers=other).status_code == 200
    assert [d["id"] for d in client.get("/sync/v1/devices", headers=other).json()["items"]] == ["device-cccc-0003"]
    assert client.delete("/sync/v1/devices/device-aaaa-0001", headers=other).status_code == 404


def test_device_headers_are_validated(client, keys):
    token = keys.token()
    assert client.get("/sync/v1/status", headers=device_headers(token, "bad id")).status_code == 400
    long_name = device_headers(token, name="x" * 500, platform="toaster")
    assert client.get("/sync/v1/status", headers=long_name).status_code == 200
    item = client.get("/sync/v1/devices", headers=long_name).json()["items"][0]
    assert len(item["name"]) == 80 and item["platform"] == "other"
    # Requests without a device id still work and are not listed.
    assert client.get("/sync/v1/status", headers=auth(token)).status_code == 200


def test_erasing_the_account_clears_devices(client, keys):
    token = keys.token()
    client.get("/sync/v1/status", headers=device_headers(token))
    client.delete("/sync/v1/account", headers=device_headers(token))
    assert client.get("/sync/v1/devices", headers=auth(token)).json()["items"] == []


def image_bytes(fmt="PNG", size=(900, 600), color=(255, 45, 85)):
    from io import BytesIO
    from PIL import Image
    out = BytesIO()
    Image.new("RGB", size, color).save(out, fmt)
    return out.getvalue()


def test_avatar_is_reencoded_square_and_served_publicly(client, keys):
    from io import BytesIO
    from PIL import Image
    token = keys.token()
    response = client.post("/sync/v1/avatar", headers={**auth(token), "Content-Type": "image/png"}, content=image_bytes())
    assert response.status_code == 200
    url = response.json()["url"]
    assert url.startswith("https://") and "/sync/avatars/" in url and url.endswith(".webp")
    served = client.get(url.split("://", 1)[1].split("/", 1)[1].join(["/", ""]))
    assert served.status_code == 200 and served.headers["content-type"] == "image/webp"
    assert "immutable" in served.headers["cache-control"]
    image = Image.open(BytesIO(served.content))
    assert image.format == "WEBP" and image.size == (512, 512)
    # Same picture, same file.
    again = client.post("/sync/v1/avatar", headers=auth(token), content=image_bytes()).json()["url"]
    assert again == url


@pytest.mark.parametrize("body", [b"", b"not an image", b"<svg xmlns='http://www.w3.org/2000/svg'/>",
                                  b"GIF89a" + b"\x00" * 10])
def test_avatar_rejects_non_images(client, keys, body):
    response = client.post("/sync/v1/avatar", headers=auth(keys.token()), content=body)
    assert response.status_code == 400 and response.json()["error"]["code"] == "bad_image"


def test_avatar_needs_a_token_and_is_rate_limited(client, keys):
    assert client.post("/sync/v1/avatar", content=image_bytes()).status_code == 401
    token = keys.token()
    codes = [client.post("/sync/v1/avatar", headers=auth(token), content=image_bytes(color=(i, 0, 0))).status_code
             for i in range(21)]
    assert codes[:20] == [200] * 20 and codes[20] == 429
    # Path tricks never reach a file: "../" collapses to a /sync/ path that needs a token,
    # an encoded slash is not an avatar name.
    assert client.get("/sync/avatars/../sync.sqlite").status_code in (401, 404)
    assert client.get("/sync/avatars/..%2Fsync.sqlite").status_code == 404
    assert client.get("/sync/avatars/notahash.webp").status_code == 404


def test_purged_deletions_do_not_come_back_from_stale_devices(client, keys):
    token = keys.token()
    store = client.app.state.store
    push(client, token, note("n1", 100, "x"))
    push(client, token, note("n1", 200, deleted=True))
    assert store.purge_tombstones(older_than_ms=1000) == 1
    assert client.get("/sync/v1/status", headers=auth(token)).json()["records"] == 0
    # A device that never saw the deletion pushes its old copy: refused.
    assert push(client, token, note("n1", 100, "x")).json()["stale"] == 1
    # A genuinely new edit is accepted.
    assert push(client, token, note("n1", 300, "new")).json()["accepted"] == 1
