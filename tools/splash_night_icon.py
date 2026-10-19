# -*- coding: utf-8 -*-
"""The dark splash's icon foreground (docs/SPEC.md §7 R98).

The launcher icon's foreground is near-black line art on transparency, which
would vanish on the dark splash's near-black ground. This writes the same art
recoloured to NearWhite, every pixel keeping its own alpha so the anti-aliased
edges survive, as `mipmap-<density>/ic_splash_foreground_night.png` beside each
`ic_launcher_foreground.png`. Only the dark splash uses it; the home-screen icon
is unchanged in both modes (the owner's choice).

  python tools/splash_night_icon.py            write the five densities
  python tools/splash_night_icon.py --check    exit 1 if any is missing or stale

Standard library only: PNG is decoded and encoded with zlib.
"""
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
DENSITIES = ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
sys.path.insert(0, str(Path(__file__).resolve().parent))
import palette_derive  # noqa: E402

INK = palette_derive.h2r(palette_derive.near_white())


def read_rgba(path):
    data = path.read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", path
    i, idat, w = 8, b"", 0
    while i < len(data):
        n = struct.unpack(">I", data[i:i + 4])[0]
        kind, body = data[i + 4:i + 8], data[i + 8:i + 8 + n]
        if kind == b"IHDR":
            w, h, depth, colour, _, _, interlace = struct.unpack(">IIBBBBB", body)
            assert (depth, colour, interlace) == (8, 6, 0), f"{path}: expected 8-bit RGBA, not interlaced"
        elif kind == b"IDAT":
            idat += body
        i += 12 + n
    raw, stride, rows, prev, p = zlib.decompress(idat), w * 4, [], bytearray(w * 4), 0
    for _ in range(h):
        f = raw[p]; line = bytearray(raw[p + 1:p + 1 + stride]); p += 1 + stride
        for x in range(stride):
            a = line[x - 4] if x >= 4 else 0; b = prev[x]; c = prev[x - 4] if x >= 4 else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + b) & 255
            elif f == 3: line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line); prev = line
    return w, h, rows


def write_rgba(path, w, h, rows):
    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)
    raw = b"".join(b"\x00" + bytes(r) for r in rows)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    path.write_bytes(png)


def recolour(rows):
    r, g, b = (round(c * 255) for c in INK)
    out = []
    for line in rows:
        new = bytearray(line)
        for x in range(0, len(line), 4):
            new[x], new[x + 1], new[x + 2] = r, g, b   # alpha (new[x + 3]) kept
        out.append(new)
    return out


def main(argv):
    stale = []
    for d in DENSITIES:
        src = RES / f"mipmap-{d}" / "ic_launcher_foreground.png"
        dst = RES / f"mipmap-{d}" / "ic_splash_foreground_night.png"
        w, h, rows = read_rgba(src)
        want = recolour(rows)
        if "--check" in argv:
            if not dst.exists() or read_rgba(dst)[2] != want:
                stale.append(str(dst.relative_to(ROOT)))
        else:
            write_rgba(dst, w, h, want)
            print(f"wrote {dst.relative_to(ROOT)} ({w}x{h})")
    if "--check" in argv:
        print("PASS: night splash icons match the launcher art" if not stale else f"FAIL: stale {stale}")
        return 1 if stale else 0
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
