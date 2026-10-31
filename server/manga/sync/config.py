import os
from dataclasses import dataclass, field
from pathlib import Path


def _env(name, default):
    return os.getenv(name, default)


@dataclass(frozen=True)
class SyncConfig:
    data_dir: Path = field(default_factory=lambda: Path(_env("SYNC_DATA_DIR", "/sync")))
    # The Logto tenant that signs access tokens, and the API resource the app asks tokens for.
    issuer: str = field(default_factory=lambda: _env("SYNC_ISSUER", "https://auth.monogatari.top/oidc"))
    audience: str = field(default_factory=lambda: _env("SYNC_AUDIENCE", "https://manga.monogatari.top/sync"))
    # Fetched from the issuer; the server reaches Logto directly instead of through the public name.
    jwks_url: str = field(default_factory=lambda: _env("SYNC_JWKS_URL", "https://auth.monogatari.top/oidc/jwks"))
    jwks_host: str = field(default_factory=lambda: _env("SYNC_JWKS_HOST", ""))
    # Per user: records kept (including deletions) and bytes of payload.
    max_records: int = field(default_factory=lambda: int(_env("SYNC_MAX_RECORDS", "200000")))
    max_bytes: int = field(default_factory=lambda: int(_env("SYNC_MAX_BYTES", str(64 * 1024 * 1024))))
    # Per request.
    max_push: int = field(default_factory=lambda: int(_env("SYNC_MAX_PUSH", "2000")))
    max_payload: int = field(default_factory=lambda: int(_env("SYNC_MAX_PAYLOAD", str(64 * 1024))))
    page_size: int = field(default_factory=lambda: int(_env("SYNC_PAGE_SIZE", "1000")))
    # Deletions are kept this long so every device learns about them, then purged.
    tombstone_days: int = field(default_factory=lambda: int(_env("SYNC_TOMBSTONE_DAYS", "180")))

    # Where avatar URLs point; the service also serves them under /sync/avatars/.
    public_base: str = field(default_factory=lambda: _env("SYNC_PUBLIC_BASE", "https://manga.monogatari.top").rstrip("/"))

    @property
    def database(self) -> Path:
        return self.data_dir / "sync.sqlite"

    @property
    def avatar_dir(self) -> Path:
        return self.data_dir / "avatars"
