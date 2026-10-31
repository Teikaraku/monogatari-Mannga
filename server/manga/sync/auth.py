"""Verifies Logto access tokens (JWT, ES384) for the sync API resource."""
import json
import logging
import threading
import time
import urllib.request
from dataclasses import dataclass

import jwt

logger = logging.getLogger(__name__)
ALGORITHMS = ["ES384", "ES256", "ES512", "RS256"]
# The audience is what grants access: Logto mints tokens for the sync resource to signed-in users of
# the app only. (Custom scopes would need an RBAC role on every account, existing ones included.)


class Unauthorized(Exception):
    pass


@dataclass(frozen=True)
class Principal:
    user: str


class Verifier:
    """Checks signature, issuer, audience, expiry and scope. Keys come from the issuer's JWKS, cached
    and refetched (at most every 60 s) when a token names a key that is not known yet."""

    def __init__(self, issuer: str, audience: str, jwks_url: str, *, jwks_host: str = "", fetch=None,
                 clock=time.time, leeway: int = 30):
        self.issuer = issuer
        self.audience = audience
        self.jwks_url = jwks_url
        self.jwks_host = jwks_host
        self.fetch = fetch or self._fetch
        self.clock = clock
        self.leeway = leeway
        self.keys = {}
        self.fetched_at = None
        self.forced_at = None
        self.lock = threading.Lock()

    def _fetch(self):
        headers = {"Accept": "application/json"}
        if self.jwks_host:
            # Logto behind TRUST_PROXY_HEADER resolves its issuer from the forwarded host.
            headers.update({"X-Forwarded-Host": self.jwks_host, "X-Forwarded-Proto": "https"})
        request = urllib.request.Request(self.jwks_url, headers=headers)
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.loads(response.read())

    def _refresh(self, force=False):
        with self.lock:
            now = self.clock()
            if not force and self.fetched_at is not None and now - self.fetched_at < 3600:
                return
            # An unknown key refetches at once after a scheduled fetch, then at most once a minute.
            if force and self.forced_at is not None and now - self.forced_at < 60:
                return
            if force:
                self.forced_at = now
            try:
                document = self.fetch()
            except Exception:
                logger.exception("Could not fetch JWKS")
                if self.fetched_at is None:
                    raise Unauthorized("Key set unavailable")
                return
            keys = {}
            for item in document.get("keys", []):
                kid = item.get("kid")
                if not kid:
                    continue
                try:
                    keys[kid] = jwt.PyJWK(item).key
                except Exception:
                    logger.warning("Skipping unusable key %s", kid)
            self.keys = keys
            self.fetched_at = now

    def verify(self, token: str) -> Principal:
        if not token:
            raise Unauthorized("Missing token")
        try:
            header = jwt.get_unverified_header(token)
        except jwt.PyJWTError:
            raise Unauthorized("Malformed token")
        if header.get("alg") not in ALGORITHMS:
            raise Unauthorized("Unsupported algorithm")
        kid = header.get("kid")
        self._refresh()
        if kid not in self.keys:
            self._refresh(force=True)
        key = self.keys.get(kid)
        if key is None:
            raise Unauthorized("Unknown key")
        try:
            claims = jwt.decode(
                token, key, algorithms=[header["alg"]], audience=self.audience, issuer=self.issuer,
                leeway=self.leeway, options={"require": ["exp", "iat", "sub", "aud", "iss"]},
            )
        except jwt.ExpiredSignatureError:
            raise Unauthorized("Token expired")
        except jwt.PyJWTError as error:
            raise Unauthorized(f"Invalid token: {type(error).__name__}")
        subject = claims["sub"]
        if not isinstance(subject, str) or not subject or len(subject) > 64:
            raise Unauthorized("Invalid subject")
        # Client credentials tokens name the application, not a person.
        if claims.get("client_id") == subject:
            raise Unauthorized("Not a user token")
        return Principal(subject)
