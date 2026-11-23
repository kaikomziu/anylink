# AnyLink のアイコンを生成する（PC版・Android版の両方）
# 使い方: python tools/make_icons.py
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent


def make(size: int, rounded: bool = True) -> Image.Image:
    s = size * 4  # 高解像度で描いて縮小（アンチエイリアス）
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    # 青→紫のグラデーション
    grad = Image.new("RGBA", (s, s))
    gd = ImageDraw.Draw(grad)
    for y in range(s):
        t = y / s
        c = (int(61 + (123 - 61) * t), int(123 + (92 - 123) * t), 255, 255)
        gd.line([(0, y), (s, y)], fill=c)
    mask = Image.new("L", (s, s), 0)
    md = ImageDraw.Draw(mask)
    if rounded:
        md.rounded_rectangle([0, 0, s - 1, s - 1], radius=int(s * 0.22), fill=255)
    else:
        md.rectangle([0, 0, s - 1, s - 1], fill=255)
    img.paste(grad, (0, 0), mask)

    # 2つの輪がつながったマーク（リンク）
    d = ImageDraw.Draw(img)
    w = int(s * 0.075)
    r = int(s * 0.19)
    cy = s // 2
    for cx in (int(s * 0.38), int(s * 0.62)):
        d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(255, 255, 255, 255), width=w)
    return img.resize((size, size), Image.LANCZOS)


def main():
    desk = ROOT / "desktop"
    (desk / "build").mkdir(exist_ok=True)
    make(512).save(desk / "build" / "icon.png")
    make(256).save(desk / "src" / "icon.png")

    res = ROOT / "android" / "app" / "src" / "main" / "res"
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for name, px in sizes.items():
        out = res / f"mipmap-{name}"
        out.mkdir(parents=True, exist_ok=True)
        make(px).save(out / "ic_launcher.png")
    print("アイコンを生成しました")


if __name__ == "__main__":
    main()
