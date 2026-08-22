#!/usr/bin/env python3
"""Renders the launcher icon, Play store icon and feature graphic.

Everything is drawn at 4x and downsampled so the curves stay clean. The mark is
built once in an abstract 1000x1000 space, cropped to its own content box, then
placed into each target at the right scale -- which keeps the adaptive-icon safe
zone honest without hand-tuning coordinates per density.
"""
import os
from PIL import Image, ImageChops, ImageDraw, ImageFont

OUT = os.path.dirname(os.path.abspath(__file__))
REPO = "/home/user/starlink-anti-theft"

NAVY_DARK = (9, 15, 28, 255)
NAVY_LIGHT = (23, 40, 74, 255)
DISH_FACE = (233, 241, 255, 255)
DISH_RIM = (150, 186, 245, 255)
MAST = (137, 163, 204, 255)
AMBER = (255, 176, 32, 255)

S = 4000  # supersampled working canvas


def padlock_shapes(d, cx, cy, h, fill, expand=0.0):
    """Draws a padlock (body + shackle) centred on (cx, cy) with total height `h`.

    `expand` inflates the geometry outwards, which is how the keyline around the lock is
    produced: the same silhouette drawn oversized is subtracted from the layer underneath.
    """
    w = h * 0.78
    body_h = h * 0.55
    body_top = cy + h / 2 - body_h
    radius = h * 0.13

    shackle_r = w * 0.32
    shackle_w = h * 0.145

    # Shackle first; the body is drawn over its feet.
    d.arc(
        [(cx - shackle_r - expand, body_top - shackle_r - expand),
         (cx + shackle_r + expand, body_top + shackle_r + expand)],
        start=180, end=360, fill=fill, width=int(shackle_w + expand),
    )
    d.rounded_rectangle(
        [(cx - w / 2 - expand, body_top - expand),
         (cx + w / 2 + expand, cy + h / 2 + expand)],
        radius=radius + expand, fill=fill,
    )


def punch(layer, mask):
    """Removes `mask` from `layer`'s alpha, leaving a transparent gap."""
    layer.putalpha(ImageChops.subtract(layer.getchannel("A"), mask))


def draw_mark(colour_dish=DISH_FACE, colour_arc=AMBER, colour_mast=MAST, colour_lock=AMBER,
              with_lock=True):
    """The dish-with-alert-waves mark, with or without the padlock, cropped to its content."""
    k = S / 1000.0
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    cx, cy = 400 * k, 620 * k

    # Mast, drawn first so the dish sits on top of it.
    d.line([(cx, cy), (430 * k, 900 * k)], fill=colour_mast, width=int(46 * k))
    d.ellipse(
        [(330 * k, 872 * k), (540 * k, 936 * k)],
        fill=colour_mast,
    )

    # Dish face on its own layer so it can be rotated about its centre.
    dish = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    dd = ImageDraw.Draw(dish)
    dd.ellipse([(140 * k, 470 * k), (660 * k, 770 * k)], fill=colour_rim_or(colour_dish))
    dd.ellipse([(170 * k, 494 * k), (630 * k, 746 * k)], fill=colour_dish)
    dish = dish.rotate(35, resample=Image.BICUBIC, center=(cx, cy))
    img.alpha_composite(dish)

    # Feed arm and horn, pointing along the boresight.
    d.line([(cx, cy), (592 * k, 392 * k)], fill=colour_mast, width=int(26 * k))
    d.ellipse([(556 * k, 356 * k), (628 * k, 428 * k)], fill=colour_mast)

    # Alert waves radiating along the boresight direction.
    for radius in (330, 430, 530):
        box = [
            (cx - radius * k, cy - radius * k),
            (cx + radius * k, cy + radius * k),
        ]
        d.arc(box, start=-86, end=-24, fill=colour_arc, width=int(44 * k))

    # Padlock badge, sitting over the lower-right of the dish. Dropped for the status-bar
    # icon, where it is displayed at 24dp and the two shapes would merge into a blob.
    if not with_lock:
        return img.crop(img.getbbox())

    lock_x, lock_y, lock_h = 726 * k, 706 * k, 392 * k

    # Cut a padlock-shaped gap out of everything behind it. In the colour icons the gap shows
    # the background gradient, which reads as a keyline; in the monochrome icon it is the only
    # thing separating the lock from the dish, since every shape there is the same colour.
    gap = Image.new("L", (S, S), 0)
    padlock_shapes(ImageDraw.Draw(gap), lock_x, lock_y, lock_h, 255, expand=26 * k)
    punch(img, gap)

    lock = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    padlock_shapes(ImageDraw.Draw(lock), lock_x, lock_y, lock_h, colour_lock)

    # The keyhole is punched rather than painted, so it too works in monochrome.
    keyhole = Image.new("L", (S, S), 0)
    kd = ImageDraw.Draw(keyhole)
    key_cy = lock_y + lock_h * 0.16
    key_r = lock_h * 0.085
    kd.ellipse(
        [(lock_x - key_r, key_cy - key_r), (lock_x + key_r, key_cy + key_r)], fill=255
    )
    kd.polygon(
        [
            (lock_x - key_r * 0.55, key_cy),
            (lock_x + key_r * 0.55, key_cy),
            (lock_x + key_r * 0.95, key_cy + key_r * 2.5),
            (lock_x - key_r * 0.95, key_cy + key_r * 2.5),
        ],
        fill=255,
    )
    punch(lock, keyhole)

    img.alpha_composite(lock)
    return img.crop(img.getbbox())


def colour_rim_or(face):
    return DISH_RIM if face == DISH_FACE else face


def vertical_gradient(size, top, bottom):
    w, h = size
    grad = Image.new("RGBA", (1, h))
    for y in range(h):
        t = y / max(1, h - 1)
        grad.putpixel(
            (0, y),
            tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(4)),
        )
    return grad.resize((w, h), Image.BICUBIC)


def place(mark, canvas, fraction, offset=(0, 0)):
    """Scales `mark` so its longest side is `fraction` of the canvas, then centres it."""
    cw, ch = canvas.size
    target = int(min(cw, ch) * fraction)
    mw, mh = mark.size
    scale = target / max(mw, mh)
    resized = mark.resize((max(1, int(mw * scale)), max(1, int(mh * scale))), Image.LANCZOS)
    x = (cw - resized.size[0]) // 2 + offset[0]
    y = (ch - resized.size[1]) // 2 + offset[1]
    canvas.alpha_composite(resized, (x, y))
    return canvas


def rounded_bg(size, radius_frac=0.22):
    img = Image.new("RGBA", (size * 4, size * 4), (0, 0, 0, 0))
    grad = vertical_gradient((size * 4, size * 4), NAVY_LIGHT, NAVY_DARK)
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [(0, 0), (size * 4 - 1, size * 4 - 1)],
        radius=int(size * 4 * radius_frac),
        fill=255,
    )
    img.paste(grad, (0, 0), mask)
    return img.resize((size, size), Image.LANCZOS)


def circle_bg(size):
    img = Image.new("RGBA", (size * 4, size * 4), (0, 0, 0, 0))
    grad = vertical_gradient((size * 4, size * 4), NAVY_LIGHT, NAVY_DARK)
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).ellipse([(0, 0), (size * 4 - 1, size * 4 - 1)], fill=255)
    img.paste(grad, (0, 0), mask)
    return img.resize((size, size), Image.LANCZOS)


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "PNG")
    print(f"  {path}  {img.size[0]}x{img.size[1]}")


def main():
    mark = draw_mark()
    white = (255, 255, 255, 255)
    mono = draw_mark(colour_dish=white, colour_arc=white, colour_mast=white, colour_lock=white)

    # --- Adaptive launcher icon -------------------------------------------------
    # The foreground layer is a 108dp canvas whose outer 18dp on each side can be
    # cropped by the launcher's mask, so the mark is kept inside the middle ~60%.
    densities = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
    print("adaptive foreground:")
    for name, px in densities.items():
        fg = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        place(mark, fg, 0.58)
        save(fg, f"{REPO}/app/src/main/res/mipmap-{name}/ic_launcher_foreground.png")

    print("themed (monochrome) foreground:")
    for name, px in densities.items():
        fg = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        place(mono, fg, 0.58)
        save(fg, f"{REPO}/app/src/main/res/mipmap-{name}/ic_launcher_monochrome.png")

    # --- Legacy square / round launcher icons ----------------------------------
    legacy = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    print("legacy launcher icons:")
    for name, px in legacy.items():
        sq = rounded_bg(px)
        place(mark, sq, 0.68)
        save(sq, f"{REPO}/app/src/main/res/mipmap-{name}/ic_launcher.png")

        rd = circle_bg(px)
        place(mark, rd, 0.62)
        save(rd, f"{REPO}/app/src/main/res/mipmap-{name}/ic_launcher_round.png")

    # --- Play store icon: 512x512, no transparency, no rounded corners ----------
    print("play store assets:")
    store = Image.new("RGBA", (512, 512), (0, 0, 0, 255))
    store.alpha_composite(vertical_gradient((512, 512), NAVY_LIGHT, NAVY_DARK))
    place(mark, store, 0.66)
    save(store.convert("RGB").convert("RGBA"), f"{REPO}/store/play-icon-512.png")

    # --- Feature graphic: 1024x500 ---------------------------------------------
    fg = Image.new("RGBA", (1024, 500), (0, 0, 0, 255))
    fg.alpha_composite(vertical_gradient((1024, 500), NAVY_LIGHT, NAVY_DARK))
    place(mark, fg, 0.62, offset=(-330, 0))

    font_bold = find_font(["BigShoulders-Bold.ttf", "DejaVuSans-Bold.ttf"], 78)
    font_reg = find_font(["DejaVuSans.ttf"], 34)
    d = ImageDraw.Draw(fg)
    d.text((430, 186), "Starlink Guard", font=font_bold, fill=(240, 246, 255, 255))
    d.text((434, 274), "Anti-theft alarm for your dish", font=font_reg, fill=(150, 186, 245, 255))
    # Saved without an alpha channel: Play's feature graphic guidance disallows transparency.
    save(fg.convert("RGB"), f"{REPO}/store/feature-graphic-1024x500.png")

    # --- Status bar / notification icon: white silhouette, no padlock ------------
    # Android masks these by alpha and tints them, so only the shape matters.
    print("notification icon:")
    dish_only = draw_mark(colour_dish=white, colour_arc=white, colour_mast=white, with_lock=False)
    for name, px in {"mdpi": 24, "hdpi": 36, "xhdpi": 48, "xxhdpi": 72, "xxxhdpi": 96}.items():
        icon = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        place(dish_only, icon, 0.92)
        save(icon, f"{REPO}/app/src/main/res/drawable-{name}/ic_notification.png")

    # A large preview so the mark can be eyeballed on its own.
    prev = Image.new("RGBA", (600, 600), (0, 0, 0, 0))
    prev.alpha_composite(vertical_gradient((600, 600), NAVY_LIGHT, NAVY_DARK))
    place(mark, prev, 0.66)
    save(prev, f"{OUT}/preview.png")

    # And the status-bar shape at its real size, next to a 3x blow-up.
    probe = Image.new("RGBA", (200, 90), (60, 70, 90, 255))
    small = Image.new("RGBA", (24, 24), (0, 0, 0, 0))
    place(dish_only, small, 0.92)
    probe.alpha_composite(small, (16, 30))
    probe.alpha_composite(small.resize((72, 72), Image.NEAREST), (70, 10))
    save(probe, f"{OUT}/notification_probe.png")


def find_font(names, size):
    roots = [
        "/usr/share/fonts/truetype/dejavu",
        "/usr/share/fonts/truetype/liberation",
        "/mnt/skills/examples/canvas-design/canvas-fonts",
    ]
    for name in names:
        for root in roots:
            path = os.path.join(root, name)
            if os.path.exists(path):
                return ImageFont.truetype(path, size)
    return ImageFont.load_default()


if __name__ == "__main__":
    main()
