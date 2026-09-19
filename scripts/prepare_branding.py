import os
import sys
from PIL import Image

def main():
    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    src_path = r"C:\Users\virsu\Downloads\ChatGPT Image 20 сент. 2026 г., 00_08_25.png"
    if not os.path.exists(src_path):
        src_path = r"C:/Users/virsu/.gemini/antigravity/brain/c1ef7fb3-55a0-4b7e-80b4-fdad621f273c/.user_uploaded/media_1789837743434.png"

    if not os.path.exists(src_path):
        print(f"[!] Source image not found at {src_path}")
        sys.exit(1)

    print(f"[*] Loading source airplane from: {src_path}")
    raw_img = Image.open(src_path).convert("RGBA")
    bbox = raw_img.getbbox()
    plane = raw_img.crop(bbox)
    pw, ph = plane.size
    print(f"[*] Cropped airplane dimensions: {pw}x{ph}")

    assets_dir = os.path.join(root_dir, "assets")
    os.makedirs(assets_dir, exist_ok=True)

    # 1. Generate master app_icon.png (1024x1024, black background)
    canvas_size = 1024
    app_icon = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 255))
    target_plane_w = int(canvas_size * 0.72)
    ratio = target_plane_w / float(pw)
    target_plane_h = int(ph * ratio)
    resized_plane = plane.resize((target_plane_w, target_plane_h), Image.LANCZOS)
    offset_x = (canvas_size - target_plane_w) // 2
    offset_y = (canvas_size - target_plane_h) // 2
    app_icon.paste(resized_plane, (offset_x, offset_y), resized_plane)
    app_icon_path = os.path.join(assets_dir, "app_icon.png")
    app_icon.save(app_icon_path, "PNG")
    print(f"[+] Saved master app icon: {app_icon_path}")

    # 2. Generate splash plane (transparent background, 512x512)
    splash_size = 512
    splash_img = Image.new("RGBA", (splash_size, splash_size), (0, 0, 0, 0))
    s_plane_w = int(splash_size * 0.85)
    s_ratio = s_plane_w / float(pw)
    s_plane_h = int(ph * s_ratio)
    s_resized = plane.resize((s_plane_w, s_plane_h), Image.LANCZOS)
    s_ox = (splash_size - s_plane_w) // 2
    s_oy = (splash_size - s_plane_h) // 2
    splash_img.paste(s_resized, (s_ox, s_oy), s_resized)
    splash_path = os.path.join(assets_dir, "colgram_plane_splash.png")
    splash_img.save(splash_path, "PNG")
    print(f"[+] Saved splash plane: {splash_path}")

    # 3. Generate mipmap densities into assets/icons
    icons_root = os.path.join(assets_dir, "icons")
    sizes = {
        "mdpi": (48, 108),
        "hdpi": (72, 162),
        "xhdpi": (96, 216),
        "xxhdpi": (144, 324),
        "xxxhdpi": (192, 432),
    }

    for density, (icon_size, fg_size) in sizes.items():
        density_dir = os.path.join(icons_root, f"mipmap-{density}")
        os.makedirs(density_dir, exist_ok=True)

        # Standard / legacy icon (black bg + centered plane)
        icon_img = Image.new("RGBA", (icon_size, icon_size), (0, 0, 0, 255))
        p_w = int(icon_size * 0.72)
        p_ratio = p_w / float(pw)
        p_h = max(1, int(ph * p_ratio))
        p_resized = plane.resize((p_w, p_h), Image.LANCZOS)
        ox = (icon_size - p_w) // 2
        oy = (icon_size - p_h) // 2
        icon_img.paste(p_resized, (ox, oy), p_resized)

        for name in ["ic_launcher.png", "ic_launcher_round.png", "ic_launcher_sa.png", "icon_2_launcher.png", "icon_2_launcher_round.png"]:
            icon_img.save(os.path.join(density_dir, name), "PNG")

        # Foreground for adaptive icon (transparent bg + centered plane in safe zone)
        fg_img = Image.new("RGBA", (fg_size, fg_size), (0, 0, 0, 0))
        fg_p_w = int(fg_size * 0.55) # Android adaptive icon safe zone is 66/108
        fg_ratio = fg_p_w / float(pw)
        fg_p_h = max(1, int(ph * fg_ratio))
        fg_resized = plane.resize((fg_p_w, fg_p_h), Image.LANCZOS)
        fg_ox = (fg_size - fg_p_w) // 2
        fg_oy = (fg_size - fg_p_h) // 2
        fg_img.paste(fg_resized, (fg_ox, fg_oy), fg_resized)

        for name in ["icon_foreground.png", "icon_foreground_sa.png", "icon_foreground_round.png"]:
            fg_img.save(os.path.join(density_dir, name), "PNG")

        print(f" [+] Generated {density} icons ({icon_size}x{icon_size} icon, {fg_size}x{fg_size} fg)")

    print("[+] All Colgram branding assets successfully generated!")

if __name__ == "__main__":
    main()
