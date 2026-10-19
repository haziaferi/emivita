# -*- coding: utf-8 -*-
"""The splash's icon foregrounds (docs/SPEC.md §7 R98, R99).

The splash shows the launcher art through a circle. On Android 12+ the system
draws it and the circle keeps the middle 72 of the icon's 108 units; below 12
androidx.core:core-splashscreen draws it itself, masks at two thirds of an icon
view that holds only the adaptive icon's inner 72 units, and so keeps the middle
48 (the 6T, Android 11, cut the clock and the capsules). The art reaches 34.8
units from the centre, so the splash uses its own copy, scaled to FIT_RADIUS.

Each copy keeps every pixel's coverage (area-averaged when scaled) and takes one
ink: NearBlack on the light splash, NearWhite on the dark one; the launcher's own
#030303 art is darker than NearBlack and black art would vanish on the dark
ground. The home-screen icon is unchanged (the owner's choice).

  python tools/splash_icon.py            write both foregrounds at five densities
  python tools/splash_icon.py --check    exit 1 if any is missing, stale or past the circle

Standard library only: PNG is decoded and encoded with zlib.
"""
import math
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
DENSITIES = ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
sys.path.insert(0, str(Path(__file__).resolve().parent))
import palette_derive  # noqa: E402

ICON_UNITS = 108
SPLASH_CIRCLE_RADIUS = 24.0   # units kept by the pre-12 splash: 2/3 of the inner 72, halved
FIT_RADIUS = 22.5             # where the scaled art's farthest pixel lands, inside that circle
OUTPUTS = {
    "ic_splash_foreground.png": palette_derive.near_black(),
    "ic_splash_foreground_night.png": palette_derive.near_white(),
}


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


def art_radius(alpha, w, h):
    """The farthest covered pixel's centre from the canvas centre, in icon units."""
    r = max((math.hypot(x + .5 - w / 2, y + .5 - h / 2) for y in range(h) for x in range(w) if alpha[y][x]), default=0)
    return r / w * ICON_UNITS


def taps(n, scale):
    """Per output index, the source pixels it covers and by how much (area average about the centre)."""
    c, out = n / 2, []
    for o in range(n):
        lo, hi = c + (o - c) / scale, c + (o + 1 - c) / scale
        out.append([(s, min(hi, s + 1) - max(lo, s)) for s in range(max(0, math.floor(lo)), min(n, math.ceil(hi)))
                    if min(hi, s + 1) > max(lo, s)])
    return out


def scaled(alpha, w, h, scale):
    tx, ty = taps(w, scale), taps(h, scale)
    across = [[sum(row[s] * k for s, k in t) for t in tx] for row in alpha]
    return [[min(255, round(sum(across[s][x] * k for s, k in t) * scale * scale)) for x in range(w)] for t in ty]


def foreground(src, ink):
    w, h, rows = read_rgba(src)
    alpha = [[line[x + 3] for x in range(0, len(line), 4)] for line in rows]
    small = scaled(alpha, w, h, FIT_RADIUS / art_radius(alpha, w, h))
    r, g, b = (round(c * 255) for c in palette_derive.h2r(ink))
    return w, h, [bytearray(v for a in line for v in (r, g, b, a)) for line in small], small


def main(argv):
    bad = []
    for d in DENSITIES:
        src = RES / f"mipmap-{d}" / "ic_launcher_foreground.png"
        for name, ink in OUTPUTS.items():
            dst = RES / f"mipmap-{d}" / name
            w, h, want, alpha = foreground(src, ink)
            reach = art_radius(alpha, w, h)
            if reach > SPLASH_CIRCLE_RADIUS:
                bad.append(f"{dst.relative_to(ROOT)} reaches {reach:.1f} units, past the {SPLASH_CIRCLE_RADIUS}")
            if "--check" in argv:
                if not dst.exists() or read_rgba(dst)[2] != want:
                    bad.append(f"{dst.relative_to(ROOT)} missing or stale")
            else:
                write_rgba(dst, w, h, want)
                print(f"wrote {dst.relative_to(ROOT)} ({w}x{h}, art to {reach:.1f} units)")
    print("PASS: splash icons match the launcher art and fit the splash circle" if not bad else "FAIL:\n  " + "\n  ".join(bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
