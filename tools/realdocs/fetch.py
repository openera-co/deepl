"""Downloads openly licensed real French documents for testing (not committed to the repo).
fetch.py <outDir> [halPdfs] [govFilesPerFormat]
- HAL (hal.science): open-access French articles, theses, lecture notes, reports (PDF; LaTeX/Word made)
- data.gouv.fr: public-sector documents under Licence Ouverte (PDF, DOC/DOCX, XLS/XLSX, ODT, ODS, PPTX)"""
import json, os, sys, random, urllib.request, urllib.parse, hashlib
OUT = sys.argv[1]; NHAL = int(sys.argv[2]) if len(sys.argv) > 2 else 300; NGOV = int(sys.argv[3]) if len(sys.argv) > 3 else 100
UA = {"User-Agent": "carnet-translation-tests/1.0 (document layout testing)"}
MAX = 15 * 1024 * 1024
def get(url, timeout=60):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout)
def save(url, path):
    if os.path.exists(path): return True
    try:
        with get(url) as r:
            data = r.read(MAX + 1)
        if len(data) > MAX or len(data) < 2000: return False
        if path.endswith(".pdf") and not data.startswith(b"%PDF"): return False
        if path.endswith((".docx", ".xlsx", ".pptx", ".odt", ".ods", ".odp")) and not data.startswith(b"PK"): return False
        open(path, "wb").write(data); return True
    except Exception as e:
        return False

def hal():
    d = os.path.join(OUT, "hal"); os.makedirs(d, exist_ok=True)
    rnd = random.Random(7); got = len(os.listdir(d)); start = 0
    types = "(ART OR COMM OR THESE OR LECTURE OR REPORT OR COUV OR OTHER OR MEM OR HDR)"
    while got < NHAL and start < 20000:
        q = urllib.parse.urlencode({"q": "*:*", "fq": [f"language_s:fr", "submitType_s:file", f"docType_s:{types}"], "fl": "halId_s,fileMain_s,docType_s",
                                    "rows": 100, "start": rnd.randrange(0, 400000), "wt": "json"}, doseq=True)
        docs = json.load(get("https://api.archives-ouvertes.fr/search/?" + q))["response"]["docs"]
        for doc in docs:
            if got >= NHAL: break
            url = doc.get("fileMain_s")
            if not url: continue
            if save(url, os.path.join(d, f"{doc['docType_s'].lower()}_{doc['halId_s']}.pdf")): got += 1
        start += 100
        print("hal", got, flush=True)

def gov():
    d = os.path.join(OUT, "gov"); os.makedirs(d, exist_ok=True)
    want = {"pdf": NGOV, "docx": NGOV, "doc": NGOV // 2, "xlsx": NGOV, "xls": NGOV // 2, "odt": NGOV // 2, "ods": NGOV // 2, "pptx": NGOV // 2, "odp": NGOV // 4}
    have = {k: sum(1 for f in os.listdir(d) if f.endswith("." + k)) for k in want}
    topics = ["éducation", "école", "collège", "lycée", "université", "rapport", "cours", "formation", "santé", "budget", "environnement",
              "transport", "culture", "agriculture", "énergie", "statistiques", "enquête", "programme", "guide", "règlement", "compte rendu", "délibération"]
    for t in topics:
        for page in range(1, 6):
            try: data = json.load(get("https://www.data.gouv.fr/api/1/datasets/?" + urllib.parse.urlencode({"q": t, "page_size": 50, "page": page})))
            except Exception: break
            for ds in data.get("data", []):
                for r in ds.get("resources", []):
                    fmt = (r.get("format") or "").lower().strip(".")
                    if fmt not in want or have[fmt] >= want[fmt]: continue
                    url = r.get("url") or ""
                    if not url.startswith("http"): continue
                    name = hashlib.sha1(url.encode()).hexdigest()[:12] + "." + fmt
                    if save(url, os.path.join(d, name)): have[fmt] += 1
            print("gov", t, page, have, flush=True)
            if all(have[k] >= want[k] for k in want): return

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    which = os.environ.get("WHICH", "hal,gov")
    if "gov" in which: gov()
    if "hal" in which: hal()
