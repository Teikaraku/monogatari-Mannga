"""Inspect actual APK/AAR native binaries and ZIP layout; does not emulate a 16 KiB OS."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile


def inspect(archive_path: Path):
    entries = []
    with archive_path.open("rb") as raw, zipfile.ZipFile(archive_path) as archive:
        for entry in archive.infolist():
            if not entry.filename.endswith(".so"):
                continue
            data = archive.read(entry)
            if not data.startswith(b"\x7fELF") or data[4] not in (1, 2) or data[5] not in (1, 2):
                raise ValueError("Invalid ELF header: " + entry.filename)
            wide = data[4] == 2
            endian = "<" if data[5] == 1 else ">"
            offset = struct.unpack_from(endian + ("Q" if wide else "I"), data, 32 if wide else 28)[0]
            step, count = struct.unpack_from(endian + "HH", data, 54 if wide else 42)
            loads = []
            for index in range(count):
                at = offset + step * index
                if struct.unpack_from(endian + "I", data, at)[0] == 1:
                    loads.append(struct.unpack_from(endian + ("Q" if wide else "I"), data, at + (48 if wide else 28))[0])
            if not loads:
                raise ValueError("No loadable ELF segments: " + entry.filename)
            raw.seek(entry.header_offset)
            local = raw.read(30)
            if local[:4] != b"PK\x03\x04":
                raise ValueError("Invalid local ZIP header: " + entry.filename)
            filename_size, extra_size = struct.unpack_from("<HH", local, 26)
            payload_offset = entry.header_offset + 30 + filename_size + extra_size
            entries.append({"path": entry.filename, "bytes": len(data), "elfBits": 64 if wide else 32,
                            "loadAlignment": loads, "elf16KiB": all(value >= 16384 for value in loads),
                            "zipStored": entry.compress_type == zipfile.ZIP_STORED,
                            "zipPayloadOffset": payload_offset, "zip16KiB": payload_offset % 16384 == 0})
    sixty_four = [entry for entry in entries if entry["elfBits"] == 64]
    apk = archive_path.suffix.lower() == ".apk"
    invalid = [entry["path"] for entry in sixty_four if not entry["elf16KiB"] or (apk and entry["zipStored"] and not entry["zip16KiB"])]
    if not sixty_four:
        raise ValueError("No 64-bit native libraries to verify")
    return {"archive": str(archive_path.resolve()), "sha256": hashlib.sha256(archive_path.read_bytes()).hexdigest(),
            "status": "PASS structural native alignment" if not invalid else "FAIL structural native alignment",
            "invalid64BitLibraries": invalid, "libraries": entries,
            "scope": "ELF LOAD alignment and stored APK ZIP payload alignment only; not execution on a 16 KiB device."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    result = inspect(args.archive)
    if args.report:
        args.report.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: result[key] for key in ("archive", "sha256", "status", "invalid64BitLibraries", "scope")}, indent=2))
    raise SystemExit(bool(result["invalid64BitLibraries"]))
