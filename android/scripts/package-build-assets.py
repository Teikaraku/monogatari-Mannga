"""Package the bundled reader models and record their checksums.

Upload both archives to the `build-assets` pre-release; prepare-assets.py downloads them from there.
"""
from pathlib import Path
import hashlib
import json
import zipfile

ROOT = Path(__file__).resolve().parents[1]
assets = ROOT / "app/src/main/assets"
files = sorted([*assets.glob("upscale/*.onnx"), *assets.glob("dictionary/tessdata/*.traineddata")])
assert len(files) == 8, "Expected two upscalers and six OCR language models."
def info(path):
    return {"bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def package(base, paths, name):
    output = ROOT / "build" / "build-assets" / name
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in paths:
            archive.write(path, path.relative_to(base).as_posix())
    return {"archive": {"name": output.name,
        "url": f"https://github.com/Teikaraku/monogatari-reader/releases/download/build-assets/{output.name}", **info(output)},
        "files": [{"path": path.relative_to(base).as_posix(), **info(path)} for path in paths]}


model_sources = ROOT / "model-conversion"
originals = sorted([model_sources / "waifu2x_photo_noise0_scale2x.mlmodel",
                   *(path for path in (model_sources / "RealESRGAN_x2plus.mlpackage").rglob("*") if path.is_file())])
assert len(originals) == 4 and all(path.is_file() for path in originals)
manifest = {"version": "0.9", **package(assets, files, "monogatari-build-assets-0.9.zip"),
            "originalModels": package(model_sources, originals, "monogatari-model-sources-0.9.zip")}
(ROOT / "asset-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"archive": manifest["archive"], "originalArchive": manifest["originalModels"]["archive"], "files": len(files)}, ensure_ascii=False))
