"""Draws docs/banner.png for the README. Needs Pillow and the Malgun Gothic fonts that ship with Windows."""
from PIL import Image, ImageDraw, ImageFont

W, H = 1600, 520
GROUND, INK, SOFT, ACCENT = "#F3F4F6", "#17191C", "#6C7480", "#2B6FF3"

img = Image.new("RGB", (W, H), GROUND)
d = ImageDraw.Draw(img)
bold = "C:/Windows/Fonts/malgunbd.ttf"
regular = "C:/Windows/Fonts/malgun.ttf"

# App mark: a rounded blue tile with a download arrow.
x, y, s = 120, 150, 220
d.rounded_rectangle((x, y, x + s, y + s), radius=52, fill=ACCENT)
cx = x + s // 2
d.rounded_rectangle((cx - 13, y + 52, cx + 13, y + 128), radius=13, fill="white")
d.polygon([(cx - 48, y + 112), (cx + 48, y + 112), (cx, y + 164)], fill="white")
d.rounded_rectangle((cx - 52, y + 174, cx + 52, y + 190), radius=8, fill="white")

d.text((400, 150), "ROM 업데이터", font=ImageFont.truetype(bold, 104), fill=INK)
d.text((406, 296), "새 빌드가 올라오면 알려 주고, 폰에서 바로 설치해요", font=ImageFont.truetype(regular, 40), fill=SOFT)

img.save("docs/banner.png", optimize=True)
