"""Draws docs/banner.png for the README. Needs Pillow and the Malgun Gothic fonts that ship with Windows."""
from PIL import Image, ImageDraw, ImageFont

W, H = 1600, 520
GROUND, INK, SOFT = "#F3F4F6", "#17191C", "#6C7480"
TOP, BOTTOM, CHEVRON = (77, 139, 255), (27, 73, 214), (36, 89, 224)


def mark(size):
    """The app icon: two slots on a blue gradient. Same geometry as ic_launcher_foreground.xml (108 units, 66 visible)."""
    k = 4  # supersample
    n = size * k
    u = n / 66  # one icon unit; the visible part of the adaptive icon is the middle 66 of 108

    def p(v):
        return (v - 21) * u

    tile = Image.new("RGB", (n, n))
    px = tile.load()
    for y in range(n):
        for x in range(n):
            t = min(1, max(0, (x + y) / (2 * n)))
            px[x, y] = tuple(round(a + (b - a) * t) for a, b in zip(TOP, BOTTOM))
    back = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    ImageDraw.Draw(back).rounded_rectangle((p(36), p(34), p(64), p(62)), radius=7 * u, fill=(255, 255, 255, 97))
    tile = Image.alpha_composite(tile.convert("RGBA"), back)
    d = ImageDraw.Draw(tile)
    d.rounded_rectangle((p(45), p(45), p(74), p(74)), radius=7 * u, fill="white")
    w = round(3.4 * u)
    points = [(p(53.5), p(62.5)), (p(59.5), p(56.5)), (p(65.5), p(62.5))]
    d.line(points, fill=CHEVRON, width=w, joint="curve")
    for x, y in points:
        d.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill=CHEVRON)
    mask = Image.new("L", (n, n), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, n - 1, n - 1), radius=n * 0.24, fill=255)
    tile.putalpha(mask)
    return tile.resize((size, size), Image.LANCZOS)


img = Image.new("RGB", (W, H), GROUND)
icon = mark(220)
img.paste(icon, (120, 150), icon)
d = ImageDraw.Draw(img)
d.text((400, 150), "ROM 업데이터", font=ImageFont.truetype("C:/Windows/Fonts/malgunbd.ttf", 104), fill=INK)
d.text((406, 296), "새 빌드가 올라오면 알려 주고, 폰에서 바로 설치해요", font=ImageFont.truetype("C:/Windows/Fonts/malgun.ttf", 40), fill=SOFT)
img.save("docs/banner.png", optimize=True)
mark(512).save("docs/icon.png", optimize=True)
