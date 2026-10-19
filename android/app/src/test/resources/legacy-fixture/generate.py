"""Build an independent legacy ABI integration module without a WASM compiler.

Only standard WASM sections/instructions are emitted; imports/signatures are
copied from the original WasmStd/WasmAidoku/WasmJson/WasmDefaults exports.
Run with Python 3 to regenerate the checked-in main.wasm.
"""
from pathlib import Path
import json
import struct


def u(value):
    result = bytearray()
    while True:
        byte = value & 127
        value >>= 7
        result.append(byte | (128 if value else 0))
        if not value:
            return bytes(result)


def signed(value):
    result = bytearray()
    while True:
        byte = value & 127
        value >>= 7
        done = (value == 0 and not byte & 64) or (value == -1 and byte & 64)
        result.append(byte | (0 if done else 128))
        if done:
            return bytes(result)


def string(value):
    encoded = value.encode()
    return u(len(encoded)) + encoded


def vector(values):
    return u(len(values)) + b"".join(values)


def section(kind, content):
    return bytes([kind]) + u(len(content)) + content


I32, I64, F32, F64 = 127, 126, 125, 124
types = []


def signature(params, results):
    item = b"\x60" + vector([bytes([p]) for p in params]) + vector([bytes([r]) for r in results])
    if item not in types:
        types.append(item)
    return types.index(item)


imports = []
indices = {}


def imported(module, name, params, results):
    indices[module + "." + name] = len(imports)
    imports.append(string(module) + string(name) + b"\0" + u(signature(params, results)))


imported("std", "create_object", [], [I32])
imported("std", "create_array", [], [I32])
imported("std", "create_int", [I64], [I32])
imported("std", "create_string", [I32, I32], [I32])
imported("std", "object_set", [I32, I32, I32, I32], [])
imported("std", "object_get", [I32, I32, I32], [I32])
imported("std", "read_int", [I32], [I64])
imported("std", "array_append", [I32, I32], [])
imported("std", "array_get", [I32, I32], [I32])
imported("std", "array_len", [I32], [I32])
imported("json", "parse", [I32, I32], [I32])
imported("defaults", "set", [I32, I32, I32], [])
imported("aidoku", "create_manga", [I32] * 20, [I32])
imported("aidoku", "create_manga_result", [I32, I32], [I32])
imported("aidoku", "create_chapter", [I32] * 4 + [F32, F32, F64] + [I32] * 6, [I32])
imported("aidoku", "create_page", [I32] * 7, [I32])

segments = []
next_address = 1024


def literal(value):
    global next_address
    encoded = value.encode()
    address = next_address
    next_address += len(encoded) + 1
    segments.append(u(0) + b"\x41" + signed(address) + b"\x0b" + u(len(encoded)) + encoded)
    return i(address) + i(len(encoded))


def i(value):
    return b"\x41" + signed(value)


def i64(value):
    return b"\x42" + signed(value)


def f32(value):
    return b"\x43" + struct.pack("<f", value)


def f64(value):
    return b"\x44" + struct.pack("<d", value)


def get(index):
    return b"\x20" + u(index)


def put(index):
    return b"\x21" + u(index)


def call(name):
    return b"\x10" + u(indices[name])


def assert_condition(code):
    return code + b"\x45\x04\x40\x00\x0b"


functions = []
codes = []
exports = [string("memory") + b"\x02\0"]


def function(name, params, results, locals_count, code):
    functions.append(u(signature(params, results)))
    exports.append(string(name) + b"\0" + u(len(imports) + len(functions) - 1))
    declarations = b"\0" if not locals_count else u(1) + u(locals_count) + bytes([I32])
    body = declarations + code + b"\x0b"
    codes.append(u(len(body)) + body)


# initialize exercises dictionaries, arrays, defaults and native WASM math.
initialize = call("std.create_object") + put(0)
initialize += get(0) + literal("calculated") + f64(81) + b"\x9f\xb0" + call("std.create_int") + call("std.object_set")
initialize += literal("legacy.initialized") + i64(43) + call("std.create_int") + call("defaults.set")
initialize += assert_condition(get(0) + literal("calculated") + call("std.object_get") + call("std.read_int") + i64(9) + b"\x51")
function("initialize", [], [], 1, initialize)

# Search returns an actual source-created MangaPageResult, including unicode.
search = call("std.create_array") + put(2) + get(2)
for value in ["manga/key", "https://example.test/cover.jpg", "Legacy 漫画", "Writer", "Artist", "Description", "https://example.test/manga"]:
    search += literal(value)
search += i(0) + i(0) + i(0) + i(1) + i(0) + i(2) + call("aidoku.create_manga") + call("std.array_append")
search += get(2) + get(1) + i(1) + b"\x46" + call("aidoku.create_manga_result")
function("get_manga_list", [I32, I32], [I32], 1, search)
function("get_manga_details", [I32], [I32], 0, get(0))

chapter = call("std.create_array") + put(1) + get(1)
chapter += literal("chapter/key") + literal("Legacy chapter") + f32(2) + f32(7) + f64(1234.5)
chapter += literal("Scanlator") + literal("https://example.test/chapter") + literal("en") + call("aidoku.create_chapter") + call("std.array_append") + get(1)
function("get_chapter_list", [I32], [I32], 1, chapter)

page = call("std.create_array") + put(1) + get(1) + i(0) + literal("https://example.test/chapter/1.jpg") + literal("") + literal("") + call("aidoku.create_page") + call("std.array_append")
page += get(1) + i(1) + literal("") + literal("") + literal("Legacy text page") + call("aidoku.create_page") + call("std.array_append") + get(1)
function("get_page_list", [I32], [I32], 1, page)

verify = literal('{"number":17,"list":[3,5]}') + call("json.parse") + put(0)
verify += assert_condition(get(0) + literal("number") + call("std.object_get") + call("std.read_int") + i64(17) + b"\x51")
verify += get(0) + literal("list") + call("std.object_get") + put(1)
verify += assert_condition(get(1) + call("std.array_len") + i(2) + b"\x46")
verify += assert_condition(get(1) + i(1) + call("std.array_get") + call("std.read_int") + i64(5) + b"\x51")
verify += i(1)
function("verify_legacy_host", [], [I32], 2, verify)

module = b"\0asm\x01\0\0\0" + section(1, vector(types)) + section(2, vector(imports)) + section(3, vector(functions))
module += section(5, u(1) + u(0) + u(1)) + section(7, vector(exports)) + section(10, vector(codes)) + section(11, vector(segments))
Path(__file__).with_name("main.wasm").write_bytes(module)
Path(__file__).with_name("source.json").write_text(json.dumps({"info": {"id": "legacy.integration", "name": "Legacy integration fixture", "version": 1, "lang": "en", "url": "https://example.test"}, "filters": [{"name": "Title", "type": "text", "default": ""}]}))
