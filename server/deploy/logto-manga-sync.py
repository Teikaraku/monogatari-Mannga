"""物语漫画 account sync in Logto (idempotent). Run on the server: python3 logto-manga-sync.py

- API resource https://manga.monogatari.top/sync: access tokens for the sync service (JWT, 1 hour).
- Native application 物语漫画: authorization code + PKCE, no secret, redirects
  app.monogatari.android://callback (Android) and app.monogatari.monogatari://callback (iOS; the bundle
  id lowercased, as URL schemes are compared),
  refresh tokens rotated, 30 days.
Prints the application id, which both apps carry as their account client id.
"""
import base64
import json
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

CORE = "http://127.0.0.1:3001"
ADMIN = "http://127.0.0.1:3002"
HOST = "auth.monogatari.top"
RESOURCE = "https://manga.monogatari.top/sync"
REDIRECTS = ["app.monogatari.android://callback", "app.monogatari.monogatari://callback"]


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


# The built-in m-default machine app holds Management API access; its secret is in Logto's database.
secret = subprocess.check_output(
    ["docker", "exec", "monogatari-auth-db-1", "psql", "-U", "logto", "-d", "logto", "-At", "-c",
     "select secret from applications where id='m-default' and tenant_id='admin'"], text=True).strip()
token = req("POST", ADMIN + "/oidc/token", host=False, basic="m-default:" + secret,
            form={"grant_type": "client_credentials", "resource": "https://default.logto.app/api", "scope": "all"})["access_token"]
api = lambda method, path, body=None: req(method, CORE + "/api" + path, body, token)

resources = api("GET", "/resources?page_size=100")
resource = next((r for r in resources if r["indicator"] == RESOURCE), None)
if resource is None:
    resource = api("POST", "/resources", {"name": "物语漫画同步", "indicator": RESOURCE, "accessTokenTtl": 3600})
else:
    api("PATCH", f"/resources/{resource['id']}", {"name": "物语漫画同步", "accessTokenTtl": 3600})
print("resource", resource["id"], RESOURCE)

apps = api("GET", "/applications?page_size=100")
app = next((a for a in apps if (a.get("customData") or {}).get("key") == "manga-app"), None)
body = {
    "name": "物语漫画",
    "description": "物语漫画 app (Android, iOS)",
    "oidcClientMetadata": {"redirectUris": REDIRECTS, "postLogoutRedirectUris": REDIRECTS},
    "customClientMetadata": {"rotateRefreshToken": True, "refreshTokenTtlInDays": 30, "alwaysIssueRefreshToken": True},
    "customData": {"key": "manga-app"},
}
if app is None:
    app = api("POST", "/applications", {**body, "type": "Native"})
else:
    api("PATCH", f"/applications/{app['id']}", body)
print("application", app["id"])
