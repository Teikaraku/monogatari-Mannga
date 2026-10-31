"""HTTP API for account sync. Every endpoint needs a Logto access token for the sync resource.

GET    /sync/v1/status            the account's sequence and usage
GET    /sync/v1/changes?since=N   records changed after sequence N (paged; follow `more`)
POST   /sync/v1/changes           {"items": [...]} -> sequence after the write
DELETE /sync/v1/account           erases everything stored for the account
GET    /sync/v1/devices           the devices signed in to the manga app
DELETE /sync/v1/devices/{id}      signs that device out (its next request is refused)
GET    /sync/healthz              liveness, no token
"""
import asyncio
import logging
import time
from contextlib import asynccontextmanager

import orjson
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, Response
from starlette.concurrency import run_in_threadpool

from . import avatars
from .auth import Principal, Unauthorized, Verifier
from .config import SyncConfig
from .store import DeviceRevoked, Invalid, QuotaExceeded, Store

logger = logging.getLogger(__name__)
# Request bodies larger than this are refused before parsing.
MAX_BODY = 8 * 1024 * 1024
# Each install sends its random id, name and platform, which make up the account's device list.
DEVICE_HEADER = "x-mo-device"


def json_response(value, status=200):
    return Response(orjson.dumps(value), status_code=status, media_type="application/json",
                    headers={"Cache-Control": "no-store"})


def error(status, code, message):
    return json_response({"error": {"code": code, "message": message}}, status)


def create_app(config: SyncConfig | None = None, *, verifier: Verifier | None = None, clock=time.time) -> FastAPI:
    config = config or SyncConfig()
    store = Store(config.database, max_records=config.max_records, max_bytes=config.max_bytes,
                  max_payload=config.max_payload, clock=clock)
    verifier = verifier or Verifier(config.issuer, config.audience, config.jwks_url, jwks_host=config.jwks_host)

    async def purge_forever():
        while True:
            try:
                cutoff = int((clock() - config.tombstone_days * 86400) * 1000)
                dropped = await run_in_threadpool(store.purge_tombstones, cutoff)
                if dropped:
                    logger.info("Purged %d old deletions", dropped)
            except Exception:
                logger.exception("Tombstone purge failed")
            await asyncio.sleep(6 * 3600)

    @asynccontextmanager
    async def lifespan(_):
        task = asyncio.create_task(purge_forever())
        try:
            yield
        finally:
            task.cancel()

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.state.store = store
    app.state.verifier = verifier
    avatar_limit = avatars.RateLimit(per_hour=20, clock=clock)

    @app.exception_handler(HTTPException)
    async def http_error(_, exc):
        return error(exc.status_code, "not_found" if exc.status_code == 404 else "bad_request", str(exc.detail))

    async def principal(request: Request) -> Principal:
        header = request.headers.get("authorization", "")
        scheme, _, token = header.partition(" ")
        if scheme.lower() != "bearer":
            raise Unauthorized("Missing token")
        return await run_in_threadpool(verifier.verify, token.strip())

    @app.middleware("http")
    async def guard(request: Request, call_next):
        path = request.url.path
        # Avatars are public images, like any profile picture.
        if path == "/sync/healthz" or path.startswith("/sync/avatars/") or not path.startswith("/sync/"):
            return await call_next(request)
        try:
            request.state.principal = await principal(request)
        except Unauthorized as exc:
            response = error(401, "unauthorized", str(exc))
            response.headers["WWW-Authenticate"] = 'Bearer error="invalid_token"'
            return response
        try:
            await run_in_threadpool(
                store.touch_device, request.state.principal.user, request.headers.get(DEVICE_HEADER),
                request.headers.get("x-mo-device-name"), request.headers.get("x-mo-platform"))
        except DeviceRevoked:
            # Removed from the device list on another device: this one signs out.
            return error(401, "device_revoked", "This device was signed out")
        except Invalid as exc:
            return error(400, "bad_request", str(exc))
        return await call_next(request)

    @app.get("/sync/healthz")
    async def healthz():
        return json_response({"ok": True})

    @app.get("/sync/v1/status")
    async def status(request: Request):
        return json_response(await run_in_threadpool(store.status, request.state.principal.user))

    @app.get("/sync/v1/changes")
    async def changes(request: Request):
        try:
            since = int(request.query_params.get("since", "0"))
            limit = int(request.query_params.get("limit", str(config.page_size)))
        except ValueError:
            return error(400, "bad_request", "since and limit must be integers")
        if since < 0 or not 1 <= limit <= config.page_size:
            return error(400, "bad_request", "since must be >= 0 and limit within the page size")
        result = await run_in_threadpool(store.pull, request.state.principal.user, since, limit)
        return json_response(result)

    @app.post("/sync/v1/changes")
    async def push(request: Request):
        declared = request.headers.get("content-length")
        if declared is not None and declared.isdigit() and int(declared) > MAX_BODY:
            return error(413, "too_large", "Request body too large")
        body = await request.body()
        if len(body) > MAX_BODY:
            return error(413, "too_large", "Request body too large")
        try:
            document = orjson.loads(body)
        except orjson.JSONDecodeError:
            return error(400, "bad_request", "Body must be JSON")
        items = document.get("items") if isinstance(document, dict) else None
        if not isinstance(items, list):
            return error(400, "bad_request", "items must be a list")
        if len(items) > config.max_push:
            return error(413, "too_large", f"At most {config.max_push} records per request")
        try:
            result = await run_in_threadpool(store.push, request.state.principal.user, items)
        except Invalid as exc:
            return error(400, "invalid_record", str(exc))
        except QuotaExceeded:
            return error(507, "quota_exceeded", "Account storage is full")
        return json_response({"seq": result.seq, "accepted": result.accepted, "stale": result.stale})

    @app.delete("/sync/v1/account")
    async def erase(request: Request):
        await run_in_threadpool(store.erase, request.state.principal.user)
        return Response(status_code=204, headers={"Cache-Control": "no-store"})

    @app.post("/sync/v1/avatar")
    async def upload_avatar(request: Request):
        declared = request.headers.get("content-length")
        if declared is not None and declared.isdigit() and int(declared) > avatars.MAX_UPLOAD:
            return error(413, "too_large", "Image too large")
        body = await request.body()
        if len(body) > avatars.MAX_UPLOAD:
            return error(413, "too_large", "Image too large")
        if not await run_in_threadpool(avatar_limit.allow, request.state.principal.user):
            return error(429, "too_many_requests", "Too many uploads, try again later")
        try:
            name = await run_in_threadpool(avatars.store, config.avatar_dir, body)
        except avatars.BadImage as exc:
            return error(400, "bad_image", str(exc))
        return json_response({"url": f"{config.public_base}/sync/avatars/{name}"})

    @app.get("/sync/avatars/{name}")
    async def avatar(name: str):
        path = avatars.path_for(config.avatar_dir, name)
        if path is None:
            return error(404, "not_found", "No such avatar")
        return FileResponse(path, media_type="image/webp",
                            headers={"Cache-Control": "public, max-age=31536000, immutable",
                                     "X-Content-Type-Options": "nosniff"})

    @app.get("/sync/v1/devices")
    async def devices(request: Request):
        current = request.headers.get(DEVICE_HEADER)
        items = await run_in_threadpool(store.devices, request.state.principal.user)
        return json_response({"items": [{**item, "current": item["id"] == current} for item in items]})

    @app.delete("/sync/v1/devices/{device}")
    async def revoke(request: Request, device: str):
        if device == request.headers.get(DEVICE_HEADER):
            return error(400, "bad_request", "Sign out instead of removing the current device")
        if not await run_in_threadpool(store.revoke_device, request.state.principal.user, device):
            return error(404, "not_found", "No such device")
        return Response(status_code=204, headers={"Cache-Control": "no-store"})

    return app


def __getattr__(name):
    # uvicorn manga.sync.api:app builds the app on first use, so importing this module stays cheap.
    if name == "app":
        globals()["app"] = create_app()
        return globals()["app"]
    raise AttributeError(name)
