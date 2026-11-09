"""Scores OCR output against ground truth: score.py <setDir> <out-*.json>... → character/word error rate per category."""
import json, os, re, sys, unicodedata
def norm(t): return re.sub(r"\s+", " ", unicodedata.normalize("NFC", t.replace("’", "'"))).strip()
def lev(a, b):
    if len(a) < len(b): a, b = b, a
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1): cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]
set_dir = sys.argv[1]; rows = []
for f in sys.argv[2:]:
    o = json.load(open(f)); agg = {}
    for r in o["results"]:
        gt = json.load(open(os.path.join(set_dir, r["file"] + ".json")))
        ref = norm(" ".join(gt["lines"])); hyp = norm(" ".join(r["lines"]))
        c = lev(ref, hyp) / max(1, len(ref)); w = lev(ref.split(), hyp.split()) / max(1, len(ref.split()))
        for k in (gt["category"], "ALL"):
            a = agg.setdefault(k, [0, 0.0, 0.0, 0]); a[0] += 1; a[1] += c; a[2] += w; a[3] += r["ms"]
    for k, (n, c, w, ms) in sorted(agg.items()): rows.append((o["variant"], k, n, 100 * c / n, 100 * w / n, ms / n))
print("| variant | category | images | CER % | WER % | ms/image |\n|---|---|---|---|---|---|")
for v, k, n, c, w, ms in rows: print(f"| {v} | {k} | {n} | {c:.2f} | {w:.2f} | {ms:.0f} |")
