"""Prepare the release's bundled reader models using only Python's standard library."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def matches(path, item):
    return path.is_file() and path.stat().st_size == item["bytes"] and sha256(path) == item["sha256"]


def prepare(manifest, assets, from_file=None, check=False):
    files = manifest["files"]
    for item in files:
        relative = Path(item["path"])
        if relative.is_absolute() or ".." in relative.parts or "\\" in item["path"]:
            raise ValueError("模型清单中出现无效路径。")
    missing = [item for item in files if not matches(assets / item["path"], item)]
    if not missing:
        print(f"模型文件完整，共 {len(files)} 个，无需下载。")
        return
    if check:
        raise ValueError("缺失或不完整的模型文件：" + ", ".join(item["path"] for item in missing))
    cache = ROOT / ".asset-cache"
    cache.mkdir(exist_ok=True)
    bundle = manifest["archive"]
    archive = from_file.resolve() if from_file else cache / bundle["name"]
    if not from_file and not matches(archive, bundle):
        print("正在下载阅读器模型，首次运行可能需要几分钟……", flush=True)
        request = urllib.request.Request(bundle["url"], headers={"User-Agent": "Monogatari-build-assets/0.9"})
        with tempfile.NamedTemporaryFile(dir=cache, delete=False) as temporary:
            pending = Path(temporary.name)
            try:
                with urllib.request.urlopen(request, timeout=60) as response:
                    received = 0
                    while chunk := response.read(1024 * 1024):
                        received += len(chunk)
                        if received > bundle["bytes"]:
                            raise ValueError("下载文件大小与清单不符。")
                        temporary.write(chunk)
                temporary.close()
                if not matches(pending, bundle):
                    raise ValueError("模型压缩包校验失败，请检查网络并重试。")
                os.replace(pending, archive)
            finally:
                pending.unlink(missing_ok=True)
    if not matches(archive, bundle):
        raise ValueError("模型压缩包不完整，或不是本版本的文件。")
    with tempfile.TemporaryDirectory(dir=cache) as temporary:
        staging = Path(temporary)
        with zipfile.ZipFile(archive) as compressed:
            expected = {item["path"] for item in files}
            names = compressed.namelist()
            if set(names) != expected or len(names) != len(expected):
                raise ValueError("模型压缩包中的文件清单不符。")
            for item in files:
                entry = compressed.getinfo(item["path"])
                if entry.file_size != item["bytes"] or (entry.external_attr >> 16) & 0o170000 == 0o120000:
                    raise ValueError("模型压缩包中的文件类型或大小不符。")
                target = staging / item["path"]
                target.parent.mkdir(parents=True, exist_ok=True)
                with compressed.open(entry) as source, target.open("wb") as output:
                    shutil.copyfileobj(source, output)
                if not matches(target, item):
                    raise ValueError("模型文件校验失败：" + item["path"])
        # Validate every file before replacing any installed model.
        for item in missing:
            target = assets / item["path"]
            target.parent.mkdir(parents=True, exist_ok=True)
            if not target.resolve().is_relative_to(assets.resolve()):
                raise ValueError("模型目标路径超出了资源目录。")
            os.replace(staging / item["path"], target)
    print(f"模型准备完成，共 {len(files)} 个。现在可以在 Android Studio 中构建应用。")


def main():
    parser = argparse.ArgumentParser(description="下载并检查物语阅读器需要的模型文件。")
    parser.add_argument("--from-file", type=Path, help="使用已下载的阅读器模型压缩包。")
    parser.add_argument("--check", action="store_true", help="仅检查现有文件，不下载或修改。")
    parser.add_argument("--with-original-models", action="store_true", help="同时准备模型转换工具需要的原版模型。")
    args = parser.parse_args()
    manifest = json.loads((ROOT / "asset-manifest.json").read_text(encoding="utf-8"))
    prepare(manifest, ROOT / "app/src/main/assets", args.from_file, args.check)
    if args.with_original_models:
        prepare(manifest["originalModels"], ROOT / "model-conversion", check=args.check)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        raise SystemExit(str(error))
