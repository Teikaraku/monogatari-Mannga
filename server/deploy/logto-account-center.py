"""Turns on Logto's Account API for self-service profile and security (idempotent).
Run on the server: python3 logto-account-center.py [--test-user create|delete]

Users may edit their own name, avatar, email, password and linked social accounts with their own
access token (the app's 我的 page). Username and phone stay off; custom data stays private.
--test-user creates or deletes the throwaway account used to check the native sign-in end to end;
its password is printed once and never stored.
"""
import base64
import json
import secrets
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

CORE = "http://127.0.0.1:3001"
ADMIN = "http://127.0.0.1:3002"
HOST = "auth.monogatari.top"
TEST_EMAIL = "manga-e2e-test@monogatari.invalid"


def req(method, url, body=None, token=None, form=None, basic=None, host=True):
    headers = {"Host": HOST} if host else {}
    data = None
    if token:
        headers["Authorization"] = "Bearer " + token
    if basic:
        headers["Authorization"] = "Basic " + base64.b64encode(basic.encode()).decode()
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        sys.exit(f"{method} {url} -> {error.code}: {error.read().decode()[:600]}")


secret = subprocess.check_output(
    ["docker", "exec", "monogatari-auth-db-1", "psql", "-U", "logto", "-d", "logto", "-At", "-c",
     "select secret from applications where id='m-default' and tenant_id='admin'"], text=True).strip()
token = req("POST", ADMIN + "/oidc/token", host=False, basic="m-default:" + secret,
            form={"grant_type": "client_credentials", "resource": "https://default.logto.app/api", "scope": "all"})["access_token"]
api = lambda method, path, body=None: req(method, CORE + "/api" + path, body, token)

if "--test-user" in sys.argv:
    action = sys.argv[sys.argv.index("--test-user") + 1]
    existing = api("GET", "/users?search=" + urllib.parse.quote(TEST_EMAIL)) or []
    for user in existing:
        if user.get("primaryEmail") == TEST_EMAIL:
            api("DELETE", f"/users/{user['id']}")
            print("deleted", user["id"])
    if action == "create":
        password = "E2e-" + secrets.token_urlsafe(18)
        user = api("POST", "/users", {"primaryEmail": TEST_EMAIL, "password": password, "name": "E2E Test"})
        print("created", user["id"])
        print("password", password)
    sys.exit(0)

before = api("GET", "/account-center")
api("PATCH", "/account-center", {
    "enabled": True,
    "fields": {"name": "Edit", "avatar": "Edit", "email": "Edit", "password": "Edit", "social": "Edit",
               "profile": "ReadOnly", "username": "Off", "phone": "Off", "customData": "Off", "mfa": "Off"},
})
print("account center before", json.dumps(before, ensure_ascii=False))
print("account center after", json.dumps(api("GET", "/account-center"), ensure_ascii=False))
