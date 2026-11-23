import os
from PIL import Image, ImageDraw, ImageOps

COLGRAM_ROOT = r"F:\brom\Colgram"
RES_DIR = os.path.join(COLGRAM_ROOT, "Telegram-Src", "TMessagesProj", "src", "main", "res")
REF_AVATAR = r"C:\Users\virsu\.gemini\antigravity\brain\bbda87bb-9114-41cc-a954-0f245a86e5a3\.user_uploaded\media_1795074644911_f13b13bf.jpg"
APP_ICON = os.path.join(COLGRAM_ROOT, "assets", "app_icon.png")

def make_circle(img: Image.Image, size: int) -> Image.Image:
    im = ImageOps.fit(img.convert("RGBA"), (size, size), method=Image.Resampling.LANCZOS)
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0, size - 1, size - 1), fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out

# 1. Generate high-res circular & square Koliti Avatar from user's exact reference image
koliti_src = Image.open(REF_AVATAR).convert("RGBA")
koliti_circle = make_circle(koliti_src, 256)
koliti_square = ImageOps.fit(koliti_src, (256, 256), method=Image.Resampling.LANCZOS)

os.makedirs(os.path.join(COLGRAM_ROOT, "assets"), exist_ok=True)
os.makedirs(os.path.join(RES_DIR, "drawable-nodpi"), exist_ok=True)

koliti_circle.save(os.path.join(COLGRAM_ROOT, "assets", "koliti_avatar.png"), "PNG", optimize=True)
koliti_circle.save(os.path.join(RES_DIR, "drawable-nodpi", "koliti_avatar.png"), "PNG", optimize=True)
koliti_square.save(os.path.join(COLGRAM_ROOT, "assets", "koliti_avatar_square.png"), "PNG", optimize=True)
print("[+] Generated koliti_avatar.png (256x256 circular) from reference image")

# 2. Generate crisp circular Colgram brand logo from app_icon.png
brand_src = Image.open(APP_ICON).convert("RGBA")
brand_circle = make_circle(brand_src, 256)
brand_circle.save(os.path.join(COLGRAM_ROOT, "assets", "colgram_brand_logo.png"), "PNG", optimize=True)
brand_circle.save(os.path.join(RES_DIR, "drawable-nodpi", "colgram_brand_logo.png"), "PNG", optimize=True)

# Also replace EVERY Telegram paper-plane raster logo across all densities
densities = {
    "drawable-mdpi": 48,
    "drawable-hdpi": 72,
    "drawable-xhdpi": 96,
    "drawable-xxhdpi": 144,
    "drawable-xxxhdpi": 192,
}
replaced = 0
for folder, base_px in densities.items():
    dpath = os.path.join(RES_DIR, folder)
    if not os.path.isdir(dpath):
        continue
    for target in ("logo_middle.webp", "logo_middle.png", "intro_tg_plane.webp", "menu_invit_telegram.webp"):
        fp = os.path.join(dpath, target)
        if os.path.isfile(fp):
            try:
                with Image.open(fp) as orig:
                    w, h = orig.size
                sz = max(24, min(w, h))
                canvas = Image.new("RGBA", (w, h), (0, 0, 0, 0))
                emblem = make_circle(brand_src, sz)
                canvas.paste(emblem, ((w - sz) // 2, (h - sz) // 2), emblem)
                fmt = "PNG" if target.endswith(".png") else "WEBP"
                if fmt == "PNG":
                    canvas.save(fp, "PNG", optimize=True)
                else:
                    canvas.save(fp, "WEBP", quality=92)
                replaced += 1
            except Exception as e:
                print(f"[!] skip {fp}: {e}")

# Replace colgram_plane_splash.png with clean Colgram circular brand logo
splash_fp = os.path.join(RES_DIR, "drawable", "colgram_plane_splash.png")
if os.path.isfile(splash_fp):
    brand_circle.save(splash_fp, "PNG", optimize=True)
    replaced += 1

print(f"[+] Replaced {replaced} Telegram logo drawables with Colgram brand logo")
