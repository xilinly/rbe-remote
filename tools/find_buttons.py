"""Find button bands in a phone screenshot and print candidate tap points."""
import sys
from PIL import Image

path = sys.argv[1] if len(sys.argv) > 1 else "phone_now.png"
im = Image.open(path).convert("RGB")
w, h = im.size
px = im.load()

def is_button(rgb):
    r, g, b = rgb
    return 200 <= r <= 240 and abs(r - g) < 8 and abs(g - b) < 10

rows = []
for y in range(0, h):
    count = 0
    for x in range(40, w - 40, 6):
        if is_button(px[x, y]):
            count += 1
    total = (w - 80) // 6
    rows.append(count > total * 0.55)

bands = []
start = None
for y, on in enumerate(rows):
    if on and start is None:
        start = y
    elif not on and start is not None:
        if y - start > 40:
            bands.append((start, y - 1))
        start = None
if start is not None and h - start > 40:
    bands.append((start, h - 1))

print(f"size {w}x{h}, {len(bands)} bands")
for i, (y0, y1) in enumerate(bands[:16]):
    # find leftmost and rightmost button pixels on the band's middle row
    mid = (y0 + y1) // 2
    xs = [x for x in range(0, w) if is_button(px[x, mid])]
    if not xs:
        continue
    print(f"band {i}: y {y0}-{y1} center_y={(y0 + y1) // 2} x {xs[0]}-{xs[-1]}")

