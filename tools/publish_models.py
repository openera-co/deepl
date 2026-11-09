"""Writes models.json (the app's download catalog) for a folder of exported models and stages the
release assets (named <id>__<file>) in <modelsDir>/upload.
publish_models.py <modelsDir> <releaseBaseUrl>"""
import hashlib, json, os, shutil, sys

root, base = sys.argv[1], sys.argv[2].rstrip("/")
MODELS = [
    # id, title, purpose, direction, quality
    ("fr-en", "French → English", "Best quality", "fr-en", "best"),
    ("en-fr", "English → French", "Best quality", "en-fr", "best"),
    ("fr-en-fast", "French → English · fast", "Smaller and about twice as fast", "fr-en", "fast"),
    ("en-fr-fast", "English → French · fast", "Smaller and about twice as fast", "en-fr", "fast"),
    ("htr-fr", "Handwriting reader", "Reads handwritten French notes", None, None),
]
FILES = ["encoder_model.int8.onnx", "decoder_model_merged.int8.onnx", "source.pieces", "target.pieces", "vocab.txt", "model.json"]
HTR_FILES = ["encoder_model.int8.onnx", "decoder_model_merged.int8.onnx", "vocab.txt", "htr.json"]


def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


up = os.path.join(root, "upload")
os.makedirs(up, exist_ok=True)
cat = {"version": 1, "models": []}
for mid, title, purpose, direction, quality in MODELS:
    d = os.path.join(root, mid)
    if not os.path.isdir(d):
        continue
    files = []
    for f in (HTR_FILES if mid == "htr-fr" else FILES):
        p = os.path.join(d, f)
        files.append({"name": f, "url": f"{base}/{mid}__{f}", "size": os.path.getsize(p), "sha256": sha(p)})
        shutil.copy(p, os.path.join(up, f"{mid}__{f}"))
    cat["models"].append({"id": mid, "title": title, "purpose": purpose, "direction": direction,
                          "quality": quality, "recommended": quality == "best" or mid == "htr-fr", "files": files})
json.dump(cat, open(os.path.join(up, "models.json"), "w", encoding="utf-8"), indent=1, ensure_ascii=False)
print(json.dumps({m["id"]: sum(x["size"] for x in m["files"]) // 1_000_000 for m in cat["models"]}))
