"""Renders the end-to-end results for inspection: for each test file, page images side by side —
original | translated file (as an office suite shows it) | the app's own preview (what the result
screen shows). Also writes summary.md with timings. usage: render.py <inDir> <outDir> <renderDir>"""
import json, os, subprocess, sys, shutil
import pymupdf
from PIL import Image, ImageDraw, ImageFont

src, out, ren = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(ren, exist_ok=True)
tmp = os.path.join(ren, "_pdf"); os.makedirs(tmp, exist_ok=True)
FONT = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 22)


def as_pdf(path):
    if path is None or not os.path.exists(path): return None
    ext = path.rsplit(".", 1)[-1].lower()
    if ext == "pdf": return path
    if ext in ("jpg", "jpeg", "png"): return path
    base = os.path.basename(path)
    dst = os.path.join(tmp, base.rsplit(".", 1)[0] + ".pdf")
    if not os.path.exists(dst):
        d2 = os.path.join(tmp, "x" + str(abs(hash(path))))
        os.makedirs(d2, exist_ok=True)
        subprocess.run(["soffice", "--headless", "--convert-to", "pdf", "--outdir", d2, path], capture_output=True, timeout=180)
        made = os.path.join(d2, base.rsplit(".", 1)[0] + ".pdf")
        if os.path.exists(made): shutil.move(made, dst)
    return dst if os.path.exists(dst) else None


def pages(path, n=3, width=760):
    if path is None: return []
    if path.lower().endswith((".jpg", ".jpeg", ".png")):
        im = Image.open(path).convert("RGB"); return [im.resize((width, int(im.height * width / im.width)))]
    try:
        d = pymupdf.open(path)
    except Exception:
        return []
    ims = []
    for p in d[:n]:
        pix = p.get_pixmap(matrix=pymupdf.Matrix(width / p.rect.width, width / p.rect.width))
        ims.append(Image.frombytes("RGB", (pix.width, pix.height), pix.samples))
    return ims


def strip(cols, labels, path):
    h = max((c.height for c in cols if c), default=200) + 40
    w = sum((c.width if c else 760) for c in cols) + 20 * (len(cols) - 1)
    im = Image.new("RGB", (w, h), (230, 230, 230)); dr = ImageDraw.Draw(im); x = 0
    for c, l in zip(cols, labels):
        dr.text((x + 8, 6), l, fill=(40, 40, 40), font=FONT)
        if c: im.paste(c, (x, 40))
        else: dr.text((x + 8, 80), "(none)", fill=(180, 0, 0), font=FONT)
        x += (c.width if c else 760) + 20
    im.save(path, quality=85)


rep = json.load(open(os.path.join(out, "report.json")))
lines = [f"# End-to-end on emulator ({rep.get('cores')} cores, quality {rep.get('quality')})", "",
         f"Model download: {rep.get('download_s')} s · load: {rep.get('load_s')} · 4 sentences warm: {rep.get('text_4_sentences_s')} s", "",
         "| file | seconds | stages | error |", "|---|---|---|---|"]
for r in rep["files"]:
    lines.append(f"| {r['file']} | {r.get('seconds')} | {' / '.join(r.get('stages', []))[:200]} | {r.get('error', '')[:120]} |")
    base = r["file"].rsplit(".", 1)[0]
    orig = as_pdf(os.path.join(src, r["file"]))
    trans = as_pdf(os.path.join(out, r["output"])) if r.get("output") else None
    prev = os.path.join(out, base + ".preview-out.pdf")
    prev = prev if os.path.exists(prev) else None
    po, pt, pp = pages(orig), pages(trans), pages(prev)
    for i in range(max(len(po), len(pt), 1)):
        cols = [po[i] if i < len(po) else None, pt[i] if i < len(pt) else None]
        labels = ["original", "translated file"]
        if prev: cols.append(pp[i] if i < len(pp) else None); labels.append("app preview")
        strip(cols, labels, os.path.join(ren, f"{base}_p{i + 1}.jpg"))
open(os.path.join(ren, "summary.md"), "w").write("\n".join(lines) + "\n")
shutil.rmtree(tmp, ignore_errors=True)
print("\n".join(lines))
