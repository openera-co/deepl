"""OCR test set with ground truth: make_set.py <outDir> <printed> <handwriting> [fontsDir]
Printed pages come from the corpus generator (paper, cream, lined, slide, blackboard, coloured; blur,
rotation). Handwriting-style pages use open-licence handwriting fonts with per-line jitter, ink colours
and notebook paper. Each image gets <name>.json with {"category", "lines"}."""
import json, os, random, sys, urllib.request
from PIL import Image, ImageDraw, ImageFont, ImageFilter
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "corpus"))
import generate as G

out, n_print, n_hand = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
fonts_dir = sys.argv[4] if len(sys.argv) > 4 else os.path.join(out, "_fonts")
os.makedirs(out, exist_ok=True); os.makedirs(fonts_dir, exist_ok=True)
HAND = {"Caveat": "ofl/caveat/Caveat%5Bwght%5D.ttf", "Kalam": "ofl/kalam/Kalam-Regular.ttf", "PatrickHand": "ofl/patrickhand/PatrickHand-Regular.ttf",
        "IndieFlower": "ofl/indieflower/IndieFlower-Regular.ttf", "ShadowsIntoLight": "ofl/shadowsintolight/ShadowsIntoLight.ttf"}
hand_fonts = []
for name, path in HAND.items():
    p = os.path.join(fonts_dir, name + ".ttf")
    if not os.path.exists(p):
        try: urllib.request.urlretrieve("https://raw.githubusercontent.com/google/fonts/main/" + path, p)
        except Exception as e: print("font", name, e); continue
    hand_fonts.append(p)

for i in range(n_print):
    p = os.path.join(out, f"print_{i:04d}.jpg")
    meta = G.gen_img(p, 9000 + i)
    json.dump({"category": meta["style"], "lines": [l["text"] for l in meta["lines"]]}, open(p + ".json", "w"), ensure_ascii=False)

for i in range(n_hand):
    r = random.Random(7000 + i)
    W, H = r.choice([(1240, 1754), (1080, 1440), (1440, 1080)])
    lined = r.random() < 0.7
    img = Image.new("RGB", (W, H), (252, 252, 248) if lined else r.choice([(250, 250, 246), (246, 240, 226)]))
    d = ImageDraw.Draw(img)
    pitch = r.randint(58, 80)
    if lined:
        for y in range(140, H, pitch): d.line([(0, y), (W, y)], fill=(165, 190, 232), width=2)
        d.line([(120, 0), (120, H)], fill=(232, 150, 150), width=2)
    font = ImageFont.truetype(r.choice(hand_fonts), int(pitch * r.uniform(0.62, 0.8)))
    ink = r.choice([(25, 40, 140), (20, 20, 25), (30, 60, 160), (120, 20, 30)])
    y = 140 - int(pitch * 0.82); lines = []
    while y < H - pitch * 2:
        para = G.rnd_par(r, short=True); line = ""
        for w in para.split():
            test = (line + " " + w).strip()
            if d.textlength(test, font=font) > W - 260:
                x = 140 + r.randint(-6, 6); d.text((x, y + r.randint(-3, 3)), line, font=font, fill=ink); lines.append(line); y += pitch; line = w
                if y > H - pitch * 2: break
            else: line = test
        if line and y <= H - pitch * 2:
            d.text((140, y), line, font=font, fill=ink); lines.append(line); y += pitch
        y += pitch if r.random() < 0.4 else 0
    if r.random() < 0.5: img = img.rotate(r.uniform(-2.5, 2.5), fillcolor=img.getpixel((5, 5)))
    if r.random() < 0.4: img = img.filter(ImageFilter.GaussianBlur(r.uniform(0.3, 0.9)))
    p = os.path.join(out, f"hand_{i:04d}.jpg"); img.save(p, quality=r.randint(75, 92))
    json.dump({"category": "handwriting", "lines": lines}, open(p + ".json", "w"), ensure_ascii=False)
print("made", n_print, "printed,", n_hand, "handwriting in", out)
