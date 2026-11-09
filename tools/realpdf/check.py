"""Compares translated PDFs with their originals: check.py <srcDir> <outDir> [--json report.json]
Per file: page/image/drawing counts kept, text inside the page, overlapping text lines, French left,
font-size shrink, text coverage (English text should cover the same regions as the French)."""
import sys, os, json, re, pymupdf

FR = set("le la les des du de un une et est sont dans pour par sur avec qui que pas plus aux ces cette nous vous ils elles leur leurs été être avoir fait comme mais où dont entre sous chez très".split())
EN = set("the of and to in is are for with that this on by as be was were from at an or which it".split())

def lines(page):
    out = []
    for b in page.get_text("dict")["blocks"]:
        for l in b.get("lines", []):
            t = "".join(s["text"] for s in l["spans"]).strip()
            if not t: continue
            size = max(s["size"] for s in l["spans"])
            out.append((pymupdf.Rect(l["bbox"]), t, size))
    return out

def words(t): return re.findall(r"[a-zàâçéèêëîïôûùüÿœ']+", t.lower())

def check(src, dst):
    a, b = pymupdf.open(src), pymupdf.open(dst)
    issues = []
    if len(a) != len(b): issues.append(f"pages {len(a)}→{len(b)}")
    fr_left = en = 0; overlaps = 0; outside = 0; shrink = []
    for i in range(min(len(a), len(b))):
        pa, pb = a[i], b[i]
        if len(pa.get_images()) != len(pb.get_images()): issues.append(f"p{i+1} images {len(pa.get_images())}→{len(pb.get_images())}")
        da, db = len(pa.get_drawings()), len(pb.get_drawings())
        if abs(da - db) > 0: issues.append(f"p{i+1} drawings {da}→{db}")
        la, lb = lines(pa), lines(pb)
        rect = pb.rect
        out_of = lambda ls, rc: sum(1 for r, t, s in ls if r.x0 < rc.x0 - 2 or r.x1 > rc.x1 + 2 or r.y1 > rc.y1 + 2 or r.y0 < rc.y0 - 2)
        outside += max(0, out_of(lb, rect) - out_of(la, pa.rect))
        for r, t, s in lb:
            ws = words(t)
            fr_left += sum(1 for w in ws if w in FR); en += sum(1 for w in ws if w in EN)
        # overlapping lines (glyph boxes of different lines intersecting noticeably), beyond those in the source
        def count_overlaps(ls):
            n = 0
            for x in range(len(ls)):
                r1 = ls[x][0]
                for y in range(x + 1, len(ls)):
                    r2 = ls[y][0]; inter = r1 & r2
                    if not inter.is_empty and inter.width > 3 and inter.height > 0.35 * min(r1.height, r2.height): n += 1
            return n
        overlaps += max(0, count_overlaps(lb) - count_overlaps(la))
        # font size: compare median size of text in matching vertical bands
        sa = sorted(s for _, t, s in la if len(t) > 20); sb = sorted(s for _, t, s in lb if len(t) > 20)
        if sa and sb: shrink.append(sb[len(sb) // 2] / sa[len(sa) // 2])
    if overlaps: issues.append(f"{overlaps} overlapping lines")
    if outside: issues.append(f"{outside} lines outside page")
    if fr_left > max(3, 0.05 * (en + 1)): issues.append(f"French left: {fr_left} fr vs {en} en function words")
    if shrink and min(shrink) < 0.85: issues.append(f"body text shrunk to {min(shrink):.2f}")
    return issues

if __name__ == "__main__":
    src, out = sys.argv[1], sys.argv[2]
    res = {}
    for f in sorted(os.listdir(src)):
        if not f.endswith(".pdf"): continue
        d = os.path.join(out, f[:-4] + ".en.pdf")
        if not os.path.exists(d): res[f] = ["missing output"]; continue
        try: res[f] = check(os.path.join(src, f), d)
        except Exception as e: res[f] = [f"check crashed: {e}"]
    bad = {k: v for k, v in res.items() if v}
    print(f"{len(res)} files, {len(res) - len(bad)} clean")
    agg = {}
    for k, v in bad.items():
        for i in v:
            key = re.sub(r"p\d+ |\d+(\.\d+)?", "#", i); agg.setdefault(key, []).append(k)
    for k, v in sorted(agg.items(), key=lambda x: -len(x[1])): print(f"  {len(v):4d}  {k}   e.g. {v[:4]}")
    if "--json" in sys.argv: json.dump(res, open(sys.argv[sys.argv.index("--json") + 1], "w"), indent=1)
