"""Collect French paragraphs from Wikipedia (random articles) for synthetic test documents."""
import json, time, urllib.request, urllib.parse, sys, re
out = sys.argv[1]; n_req = int(sys.argv[2])
seen = set(); paras = []
for i in range(n_req):
    q = urllib.parse.urlencode(dict(action="query", format="json", generator="random", grnnamespace=0, grnlimit=20,
                                    prop="extracts", explaintext=1, exintro=1, exlimit=20))
    req = urllib.request.Request("https://fr.wikipedia.org/w/api.php?" + q, headers={"User-Agent": "carnet-test-corpus/0.1 (offline translator testing)"})
    try:
        data = json.load(urllib.request.urlopen(req, timeout=30))
    except Exception as e:
        print("err", e); time.sleep(5); continue
    for p in data.get("query", {}).get("pages", {}).values():
        if p["pageid"] in seen: continue
        seen.add(p["pageid"])
        for para in p.get("extract", "").split("\n"):
            para = para.strip()
            if len(para) > 80 and not para.startswith("=="):
                paras.append({"title": p["title"], "text": para})
    if i % 20 == 0: print(i, len(paras), flush=True)
    time.sleep(0.5)
json.dump(paras, open(out, "w"), ensure_ascii=False)
print("done", len(paras))
